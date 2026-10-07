// SPDX-License-Identifier: MIT
/*
 * Winlator/adrenotools-style Vulkan HAL injection. See vulkan_hal_inject.h for the contract.
 *
 * Why this and not "open the ICD directly": RADV Xclipse is built with -Dplatforms=android, i.e.
 * as an Android Vulkan HAL. It exports HMI (hwvulkan_module_t) and VK_ANDROID_native_buffer and
 * leaves VK_KHR_surface / VK_KHR_android_surface / VK_KHR_swapchain to Android's platform
 * loader. Opened directly it has no WSI, so DXVK can't present. Mixing handles instead (an
 * instance from the system loader, devices from RADV's own vkGetInstanceProcAddr) cannot work
 * either: dispatchable handles belong to whoever created them, so RADV would dereference
 * Samsung-driver objects. The only consistent split is the one adrenotools makes: the system
 * loader loads RADV *as its HAL*. The loader then owns the instance and WSI, RADV owns every
 * physical device and device, and the loader's swapchain drives RADV via VK_ANDROID_native_buffer.
 *
 * How the loader is made to load RADV: Android's libvulkan.so picks its HAL in Hal::Open() with
 *   android_load_sphal_library("vulkan.<ro.hardware.vulkan | ro.board.platform>.so")  (built-in)
 *   android_dlopen_ext("vulkan.<...>.so", ns)                     (updatable / APEX drivers)
 * and then dlsym(handle, "HMI"). adrenotools interposes those calls with a hook library in a
 * private linker namespace. Here the same effect is achieved without a namespace or a hook .so:
 * a fresh, private instance of libvulkan.so is mapped (ANDROID_DLEXT_FORCE_LOAD, so the
 * untouched system loader can still be used for the fallback) and the GOT slots for those two
 * imports — in that instance only — are pointed at hooks that return the RADV handle for any
 * "vulkan.*.so" request and forward everything else. Hal::Open() runs lazily on the first Vulkan
 * call, so the redirect is in place before the loader picks a driver.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "vulkan_hal_inject.h"
#include "vulkan_shim_log.h"

#include <android/dlext.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__aarch64__)
#define R_JUMP_SLOT R_AARCH64_JUMP_SLOT
#define R_GLOB_DAT R_AARCH64_GLOB_DAT
#define R_ABS64 R_AARCH64_ABS64
#elif defined(__x86_64__)
#define R_JUMP_SLOT R_X86_64_JUMP_SLOT
#define R_GLOB_DAT R_X86_64_GLOB_DAT
#define R_ABS64 R_X86_64_64
#else
#error "vulkan_hal_inject: unsupported architecture (64-bit only)"
#endif

typedef void *(*sphal_fn)(const char *name, int flags);
typedef void *(*dlopen_ext_fn)(const char *name, int flags, const android_dlextinfo *info);

/* Hook state. Written before the private loader runs any code, read by the hooks afterwards. */
static sphal_fn orig_load_sphal;
static dlopen_ext_fn orig_dlopen_ext;
static char inject_driver_path[PATH_MAX];
static void *inject_driver_handle;
static unsigned inject_hits;
static char inject_requested[256];
static int inject_loader_loaded;

unsigned fable_hal_inject_hits(void) { return __atomic_load_n(&inject_hits, __ATOMIC_ACQUIRE); }
const char *fable_hal_inject_requested(void) { return inject_requested; }
int fable_hal_inject_loader_loaded(void) { return __atomic_load_n(&inject_loader_loaded, __ATOMIC_ACQUIRE); }

static void set_err(char *err, size_t len, const char *fmt, ...) {
    if (!err || !len) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(err, len, fmt, ap);
    va_end(ap);
}

/* "vulkan.<anything>.so", with or without a directory: the names Android's loader asks for. */
static int is_vulkan_hal_name(const char *name) {
    if (!name) return 0;
    const char *base = strrchr(name, '/');
    base = base ? base + 1 : name;
    size_t len = strlen(base);
    return len > strlen("vulkan..so") && strncmp(base, "vulkan.", 7) == 0 && strcmp(base + len - 3, ".so") == 0;
}

static void *redirect_to_driver(const char *requested, const char *via) {
    unsigned hit = __atomic_add_fetch(&inject_hits, 1, __ATOMIC_ACQ_REL);
    if (hit == 1) snprintf(inject_requested, sizeof(inject_requested), "%s", requested);
    /* A real dlopen keeps the loader's reference counting honest (it may dlclose on failure);
     * the driver is already mapped, so this only bumps the count. */
    void *handle = dlopen(inject_driver_path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) handle = inject_driver_handle;
    LOGI("HAL injection: system loader %s(\"%s\") -> %s (handle %p)", via, requested, inject_driver_path, handle);
    return handle;
}

static void *hook_android_load_sphal_library(const char *name, int flags) {
    if (inject_driver_path[0] && is_vulkan_hal_name(name)) {
        return redirect_to_driver(name, "android_load_sphal_library");
    }
    return orig_load_sphal ? orig_load_sphal(name, flags) : NULL;
}

static void *hook_android_dlopen_ext(const char *name, int flags, const android_dlextinfo *info) {
    if (inject_driver_path[0] && is_vulkan_hal_name(name)) {
        return redirect_to_driver(name, "android_dlopen_ext");
    }
    return orig_dlopen_ext ? orig_dlopen_ext(name, flags, info) : NULL;
}

/* --- Loading a private loader instance ---------------------------------------------------- */

void *fable_dlopen_fresh(const char *path, int flags) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    android_dlextinfo ext;
    memset(&ext, 0, sizeof(ext));
    ext.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
    ext.library_fd = fd;
    void *handle = android_dlopen_ext(path, flags, &ext);
    close(fd);
    return handle;
}

/* Second way to get a private instance: a byte copy under a temporary name, unlinked once
 * mapped. Only used if the linker refuses the fd load. */
static void *dlopen_copy(const char *path, int flags, char *used, size_t used_len) {
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) return NULL;
    struct stat st;
    if (fstat(in, &st) != 0 || st.st_size <= 0) { close(in); return NULL; }
    char *buf = malloc((size_t)st.st_size);
    if (!buf) { close(in); return NULL; }
    size_t got = 0;
    while (got < (size_t)st.st_size) {
        ssize_t n = read(in, buf + got, (size_t)st.st_size - got);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        got += (size_t)n;
    }
    close(in);
    if (got != (size_t)st.st_size) { free(buf); return NULL; }

    char driver_dir[PATH_MAX];
    snprintf(driver_dir, sizeof(driver_dir), "%s", inject_driver_path);
    char *slash = strrchr(driver_dir, '/');
    if (slash) *slash = '\0'; else driver_dir[0] = '\0';
    const char *dirs[] = { getenv("FABLE_VULKAN_TMPDIR"), getenv("TMPDIR"), driver_dir };

    void *handle = NULL;
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]) && !handle; i++) {
        if (!dirs[i] || !*dirs[i]) continue;
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s/libvulkan-fable-hal-%d.so", dirs[i], (int)getpid());
        int out = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0700);
        if (out < 0) continue;
        size_t put = 0;
        while (put < got) {
            ssize_t n = write(out, buf + put, got - put);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            put += (size_t)n;
        }
        close(out);
        if (put == got) {
            handle = dlopen(tmp, flags);
            if (!handle) LOGW("HAL injection: dlopen(%s) failed: %s", tmp, dlerror());
            else if (used) snprintf(used, used_len, "%s", tmp);
        }
        unlink(tmp);
    }
    free(buf);
    return handle;
}

/* --- GOT patching ------------------------------------------------------------------------- */

struct loaded_object {
    uintptr_t addr; /* any address inside the object */
    uintptr_t bias;
    const ElfW(Phdr) *phdr;
    ElfW(Half) phnum;
    int found;
};

static int find_object_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct loaded_object *obj = data;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t start = info->dlpi_addr + ph->p_vaddr;
        if (obj->addr >= start && obj->addr < start + ph->p_memsz) {
            obj->bias = info->dlpi_addr;
            obj->phdr = info->dlpi_phdr;
            obj->phnum = info->dlpi_phnum;
            obj->found = 1;
            return 1;
        }
    }
    return 0;
}

/* Current protection of the mapping containing `addr`, from /proc/self/maps. */
static int prot_of(uintptr_t addr) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return -1;
    char line[512];
    int prot = -1;
    while (fgets(line, sizeof(line), f)) {
        unsigned long start, end;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (addr < start || addr >= end) continue;
        prot = 0;
        if (perms[0] == 'r') prot |= PROT_READ;
        if (perms[1] == 'w') prot |= PROT_WRITE;
        if (perms[2] == 'x') prot |= PROT_EXEC;
        break;
    }
    fclose(f);
    return prot;
}

static int write_slot(void **slot, void *value) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t page = (uintptr_t)slot & ~((uintptr_t)page_size - 1);
    int prot = prot_of((uintptr_t)slot);
    if (prot < 0) prot = PROT_READ; /* RELRO after full relocation */
    if (!(prot & PROT_WRITE) && mprotect((void *)page, (size_t)page_size, prot | PROT_READ | PROT_WRITE) != 0) {
        LOGE("HAL injection: mprotect(%p) failed: %s", (void *)page, strerror(errno));
        return 0;
    }
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    if (!(prot & PROT_WRITE)) mprotect((void *)page, (size_t)page_size, prot);
    return 1;
}

/* Dynamic-section pointers are link-time addresses on bionic (the linker does not rewrite
 * .dynamic); glibc-style already-relocated values are left alone. */
static uintptr_t dyn_ptr(const struct loaded_object *obj, uintptr_t value) {
    return value < obj->bias ? obj->bias + value : value;
}

static int patch_rela_table(const struct loaded_object *obj, uintptr_t table, size_t size, const ElfW(Sym) *symtab,
                            const char *strtab, size_t strsz, const char *symbol, void *replacement,
                            void **original) {
    if (!table || !size) return 0;
    const ElfW(Rela) *rel = (const ElfW(Rela) *)table;
    size_t count = size / sizeof(ElfW(Rela));
    int patched = 0;
    for (size_t i = 0; i < count; i++) {
        unsigned long type = ELF64_R_TYPE(rel[i].r_info);
        unsigned long sym = ELF64_R_SYM(rel[i].r_info);
        if (sym == 0) continue;
        if (type != R_JUMP_SLOT && type != R_GLOB_DAT && type != R_ABS64) continue;
        if (type == R_ABS64 && rel[i].r_addend != 0) continue;
        ElfW(Word) name_off = symtab[sym].st_name;
        if (strsz && name_off >= strsz) continue;
        if (strcmp(strtab + name_off, symbol) != 0) continue;
        void **slot = (void **)(obj->bias + rel[i].r_offset);
        void *current = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
        if (current == replacement) continue;
        if (original && !*original) *original = current;
        if (write_slot(slot, replacement)) patched++;
    }
    return patched;
}

static int patch_import(const struct loaded_object *obj, const char *symbol, void *replacement, void **original) {
    const ElfW(Dyn) *dyn = NULL;
    for (ElfW(Half) i = 0; i < obj->phnum; i++) {
        if (obj->phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn) *)(obj->bias + obj->phdr[i].p_vaddr);
    }
    if (!dyn) return 0;

    uintptr_t symtab = 0, strtab = 0, jmprel = 0, rela = 0;
    size_t strsz = 0, pltrelsz = 0, relasz = 0;
    long pltrel = DT_RELA;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
        case DT_SYMTAB: symtab = dyn_ptr(obj, dyn->d_un.d_ptr); break;
        case DT_STRTAB: strtab = dyn_ptr(obj, dyn->d_un.d_ptr); break;
        case DT_STRSZ: strsz = dyn->d_un.d_val; break;
        case DT_JMPREL: jmprel = dyn_ptr(obj, dyn->d_un.d_ptr); break;
        case DT_PLTRELSZ: pltrelsz = dyn->d_un.d_val; break;
        case DT_PLTREL: pltrel = (long)dyn->d_un.d_val; break;
        case DT_RELA: rela = dyn_ptr(obj, dyn->d_un.d_ptr); break;
        case DT_RELASZ: relasz = dyn->d_un.d_val; break;
        default: break;
        }
    }
    if (!symtab || !strtab) return 0;
    const ElfW(Sym) *syms = (const ElfW(Sym) *)symtab;
    const char *strs = (const char *)strtab;
    int patched = 0;
    /* Calls go through the PLT (.rela.plt, always plain RELA). Address-taken imports live in
     * .rela.dyn, which Android may pack (DT_ANDROID_RELA) — those are not needed here. */
    if (pltrel == DT_RELA) patched += patch_rela_table(obj, jmprel, pltrelsz, syms, strs, strsz, symbol, replacement, original);
    patched += patch_rela_table(obj, rela, relasz, syms, strs, strsz, symbol, replacement, original);
    return patched;
}

/* --- Public entry point ------------------------------------------------------------------- */

int fable_hal_inject_open(const char *loader_path, void *driver, const char *driver_path,
                          struct fable_hal_inject *out, char *err, size_t err_len) {
    if (!loader_path || !driver || !driver_path || !out) {
        set_err(err, err_len, "invalid arguments");
        return 0;
    }
    memset(out, 0, sizeof(*out));
    if (strlen(driver_path) >= sizeof(inject_driver_path)) {
        set_err(err, err_len, "driver path too long");
        return 0;
    }
    /* The hooks must know the driver before the loader can possibly call them. */
    snprintf(inject_driver_path, sizeof(inject_driver_path), "%s", driver_path);
    inject_driver_handle = driver;

    static char copy_path[PATH_MAX];
    const char *how = "private instance (ANDROID_DLEXT_FORCE_LOAD)";
    void *loader = fable_dlopen_fresh(loader_path, RTLD_NOW | RTLD_LOCAL);
    if (!loader) {
        LOGW("HAL injection: fresh load of %s failed (%s); trying a private copy", loader_path, dlerror());
        loader = dlopen_copy(loader_path, RTLD_NOW | RTLD_LOCAL, copy_path, sizeof(copy_path));
        how = "private copy";
    }
    if (!loader) {
        set_err(err, err_len, "could not map a private instance of %s", loader_path);
        return 0;
    }
    __atomic_store_n(&inject_loader_loaded, 1, __ATOMIC_RELEASE);

    void *anchor = dlsym(loader, "vkGetInstanceProcAddr");
    if (!anchor) {
        set_err(err, err_len, "%s does not export vkGetInstanceProcAddr", loader_path);
        return 0;
    }
    struct loaded_object obj;
    memset(&obj, 0, sizeof(obj));
    obj.addr = (uintptr_t)anchor;
    dl_iterate_phdr(find_object_cb, &obj);
    if (!obj.found) {
        set_err(err, err_len, "could not locate the private %s in the process image", loader_path);
        return 0;
    }

    void *orig = NULL;
    int sphal = patch_import(&obj, "android_load_sphal_library", (void *)hook_android_load_sphal_library, &orig);
    if (orig && !orig_load_sphal) orig_load_sphal = (sphal_fn)orig;
    orig = NULL;
    int dlext = patch_import(&obj, "android_dlopen_ext", (void *)hook_android_dlopen_ext, &orig);
    if (orig && !orig_dlopen_ext) orig_dlopen_ext = (dlopen_ext_fn)orig;
    /* Forwarding must work even if a slot was found without a usable original value. */
    if (!orig_load_sphal) {
        void *vndk = dlopen("libvndksupport.so", RTLD_NOW | RTLD_NOLOAD);
        if (vndk) orig_load_sphal = (sphal_fn)dlsym(vndk, "android_load_sphal_library");
    }
    if (!orig_dlopen_ext) orig_dlopen_ext = android_dlopen_ext;

    if (sphal == 0 && dlext == 0) {
        set_err(err, err_len,
                "%s imports neither android_load_sphal_library nor android_dlopen_ext through its GOT; "
                "cannot redirect its HAL lookup", loader_path);
        return 0;
    }

    out->loader = loader;
    out->driver = driver;
    out->loader_path = loader_path;
    out->driver_path = inject_driver_path;
    out->patched_sphal = sphal;
    out->patched_dlopen_ext = dlext;
    out->how = how;
    LOGI("HAL injection: %s mapped as %s at bias %p; redirected android_load_sphal_library (%d slot%s) and "
         "android_dlopen_ext (%d slot%s) for \"vulkan.*.so\" -> %s",
         loader_path, copy_path[0] ? copy_path : how, (void *)obj.bias, sphal, sphal == 1 ? "" : "s", dlext,
         dlext == 1 ? "" : "s", inject_driver_path);
    return 1;
}
