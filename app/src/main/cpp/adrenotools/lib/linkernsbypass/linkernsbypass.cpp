// SPDX-License-Identifier: MIT
// Resolves Android linker namespace entry points (public libdl symbols when
// present, otherwise the linker's exported __loader_* symbols found by
// walking the process ELF modules) and loads libraries into a namespace that
// is allowed to see app-private driver storage.

#include "android_linker_ns.h"

#include "../../../common/fable_log.h"

#include <dlfcn.h>
#include <elf.h>
#include <link.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

namespace {

using create6_fn = android_namespace_t* (*)(const char*, const char*, const char*, uint64_t,
                                            const char*, android_namespace_t*);
using create7_fn = android_namespace_t* (*)(const char*, const char*, const char*, uint64_t,
                                            const char*, android_namespace_t*, const void*);

create6_fn g_create6 = nullptr;
create7_fn g_create7 = nullptr;
bool g_ready = false;
bool g_namespace_ok = false;
std::once_flag g_once;

struct Module {
    uintptr_t bias = 0;
    const Elf64_Phdr* phdr = nullptr;
    Elf64_Half phnum = 0;
    std::string name;
};

int collect_modules(struct dl_phdr_info* info, size_t, void* data) {
    auto* mods = static_cast<std::vector<Module>*>(data);
    Module m;
    m.bias = info->dlpi_addr;
    m.phdr = info->dlpi_phdr;
    m.phnum = info->dlpi_phnum;
    m.name = info->dlpi_name ? info->dlpi_name : "";
    mods->push_back(std::move(m));
    return 0;
}

uint32_t gnu_hash(const char* name) {
    uint32_t h = 5381;
    for (unsigned char c = static_cast<unsigned char>(*name); c; c = static_cast<unsigned char>(*++name)) {
        h = (h << 5) + h + c;
    }
    return h;
}

uint32_t sysv_hash(const char* name) {
    uint32_t h = 0;
    for (unsigned char c = static_cast<unsigned char>(*name); c; c = static_cast<unsigned char>(*++name)) {
        h = (h << 4) + c;
        const uint32_t g = h & 0xf0000000u;
        if (g) h ^= g >> 24;
        h &= ~g;
    }
    return h;
}

void* symbol_from_module(const Module& mod, const char* symbol) {
    if (!mod.phdr || !symbol) return nullptr;
    const Elf64_Dyn* dyn = nullptr;
    for (Elf64_Half i = 0; i < mod.phnum; ++i) {
        if (mod.phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn*>(mod.bias + mod.phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return nullptr;

    // Loaded modules have relocated Dyn pointers (absolute). An unrelocated
    // vaddr is smaller than the load bias, so add the bias only in that case.
    auto relocated = [&](uintptr_t value) -> uintptr_t {
        if (mod.bias != 0 && value < mod.bias) return value + mod.bias;
        return value;
    };
    const Elf64_Sym* symtab = nullptr;
    const char* strtab = nullptr;
    const uint32_t* sysv = nullptr;
    const uint32_t* gnu = nullptr;
    for (const Elf64_Dyn* d = dyn; d->d_tag != DT_NULL; ++d) {
        const uintptr_t ptr = relocated(static_cast<uintptr_t>(d->d_un.d_ptr));
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf64_Sym*>(ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(ptr);
                break;
            case DT_HASH:
                sysv = reinterpret_cast<const uint32_t*>(ptr);
                break;
            case DT_GNU_HASH:
                gnu = reinterpret_cast<const uint32_t*>(ptr);
                break;
            default:
                break;
        }
    }
    if (!symtab || !strtab) return nullptr;

    auto addr_of = [&](const Elf64_Sym& sym) -> void* {
        if (sym.st_shndx == SHN_UNDEF || sym.st_value == 0) return nullptr;
        return reinterpret_cast<void*>(relocated(static_cast<uintptr_t>(sym.st_value)));
    };

    if (gnu) {
        const uint32_t nbuckets = gnu[0];
        const uint32_t symoffset = gnu[1];
        const uint32_t bloom_size = gnu[2];
        const uint32_t bloom_shift = gnu[3];
        const auto* bloom = reinterpret_cast<const Elf64_Addr*>(gnu + 4);
        const auto* buckets = reinterpret_cast<const uint32_t*>(bloom + bloom_size);
        const auto* chain = buckets + nbuckets;
        if (nbuckets == 0) return nullptr;
        const uint32_t h = gnu_hash(symbol);
        const Elf64_Addr word = bloom[(h / 64) % bloom_size];
        const uint64_t mask = (1ull << (h % 64)) | (1ull << ((h >> bloom_shift) % 64));
        if ((word & mask) == mask) {
            uint32_t idx = buckets[h % nbuckets];
            if (idx >= symoffset) {
                while (true) {
                    const char* name = strtab + symtab[idx].st_name;
                    const uint32_t ch = chain[idx - symoffset];
                    if ((ch | 1u) == (h | 1u) && std::strcmp(name, symbol) == 0) {
                        if (void* p = addr_of(symtab[idx])) return p;
                    }
                    if (ch & 1u) break;
                    ++idx;
                }
            }
        }
    }

    if (sysv) {
        const uint32_t nbucket = sysv[0];
        const uint32_t nchain = sysv[1];
        const uint32_t* buckets = sysv + 2;
        const uint32_t* chains = buckets + nbucket;
        if (nbucket == 0) return nullptr;
        const uint32_t h = sysv_hash(symbol);
        for (uint32_t idx = buckets[h % nbucket]; idx != 0 && idx < nchain; idx = chains[idx]) {
            const char* name = strtab + symtab[idx].st_name;
            if (std::strcmp(name, symbol) == 0) {
                if (void* p = addr_of(symtab[idx])) return p;
            }
        }
    }
    return nullptr;
}

void* find_symbol(const char* name) {
    dlerror();
    if (void* p = dlsym(RTLD_DEFAULT, name)) return p;
    void* libdl = dlopen("libdl.so", RTLD_NOW | RTLD_NOLOAD);
    if (!libdl) libdl = dlopen("libdl.so", RTLD_NOW);
    if (libdl) {
        if (void* p = dlsym(libdl, name)) return p;
    }

    std::vector<Module> mods;
    dl_iterate_phdr(collect_modules, &mods);
    // Prefer the dynamic linker and libdl, then search everything else.
    auto prefer = [](const Module& m) {
        return m.name.find("linker64") != std::string::npos ||
               m.name.find("libdl.so") != std::string::npos ||
               m.name.empty();
    };
    for (int pass = 0; pass < 2; ++pass) {
        for (const auto& m : mods) {
            if (prefer(m) != (pass == 0)) continue;
            if (void* p = symbol_from_module(m, name)) return p;
        }
    }
    return nullptr;
}

int memfd_create_compat(const char* name) {
#if defined(__NR_memfd_create)
    return static_cast<int>(syscall(__NR_memfd_create, name, 1 /* MFD_CLOEXEC */));
#else
    (void)name;
    return -1;
#endif
}

bool write_all(int fd, const void* data, size_t n) {
    const auto* p = static_cast<const uint8_t*>(data);
    size_t off = 0;
    while (off < n) {
        const ssize_t w = write(fd, p + off, n - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        off += static_cast<size_t>(w);
    }
    return true;
}

bool read_file(const char* path, std::vector<uint8_t>* out) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size > 64 * 1024 * 1024) {
        close(fd);
        return false;
    }
    out->resize(static_cast<size_t>(st.st_size));
    size_t off = 0;
    while (off < out->size()) {
        const ssize_t n = read(fd, out->data() + off, out->size() - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return false;
        }
        if (n == 0) break;
        off += static_cast<size_t>(n);
    }
    close(fd);
    out->resize(off);
    return !out->empty();
}

void init_locked() {
    g_create6 = reinterpret_cast<create6_fn>(find_symbol("android_create_namespace"));
    g_create7 = reinterpret_cast<create7_fn>(find_symbol("__loader_android_create_namespace"));
    android_link_namespaces = reinterpret_cast<android_link_namespaces_t>(
        find_symbol("android_link_namespaces"));
    if (!android_link_namespaces) {
        android_link_namespaces = reinterpret_cast<android_link_namespaces_t>(
            find_symbol("__loader_android_link_namespaces"));
    }
    android_link_namespaces_all_libs = reinterpret_cast<android_link_namespaces_all_libs_t>(
        find_symbol("android_link_namespaces_all_libs"));
    if (!android_link_namespaces_all_libs) {
        android_link_namespaces_all_libs = reinterpret_cast<android_link_namespaces_all_libs_t>(
            find_symbol("__loader_android_link_namespaces_all_libs"));
    }
    android_get_exported_namespace = reinterpret_cast<android_get_exported_namespace_t>(
        find_symbol("android_get_exported_namespace"));
    if (!android_get_exported_namespace) {
        android_get_exported_namespace = reinterpret_cast<android_get_exported_namespace_t>(
            find_symbol("__loader_android_get_exported_namespace"));
    }

    // Avoid calling ourselves if the only "android_create_namespace" we found
    // is this translation unit's weak definition.
    if (g_create6 == reinterpret_cast<create6_fn>(&android_create_namespace)) {
        g_create6 = nullptr;
    }

    g_namespace_ok = g_create6 || g_create7;
    g_ready = true;
    FABLE_LOGI("linkernsbypass: namespace=%s link=%s link_all=%s exported=%s",
               g_namespace_ok ? "yes" : "no",
               android_link_namespaces ? "yes" : "no",
               android_link_namespaces_all_libs ? "yes" : "no",
               android_get_exported_namespace ? "yes" : "no");
}

void ensure_init() { std::call_once(g_once, init_locked); }

}  // namespace

android_get_exported_namespace_t android_get_exported_namespace = nullptr;
android_link_namespaces_all_libs_t android_link_namespaces_all_libs = nullptr;
android_link_namespaces_t android_link_namespaces = nullptr;

bool linkernsbypass_load_status() {
    ensure_init();
    // Namespace creation is preferred. Plain dlopen remains a fallback, so the
    // loader is still usable when the linker hides namespace symbols.
    return g_ready;
}

__attribute__((weak))
android_namespace_t* android_create_namespace(const char* name,
                                              const char* ld_library_path,
                                              const char* default_library_path,
                                              uint64_t type,
                                              const char* permitted_when_isolated_path,
                                              android_namespace_t* parent_namespace) {
    ensure_init();
    if (g_create6 && g_create6 != reinterpret_cast<create6_fn>(&android_create_namespace)) {
        return g_create6(name, ld_library_path, default_library_path, type,
                         permitted_when_isolated_path, parent_namespace);
    }
    if (g_create7) {
        return g_create7(name, ld_library_path, default_library_path, type,
                         permitted_when_isolated_path, parent_namespace,
                         __builtin_return_address(0));
    }
    FABLE_LOGW("android_create_namespace is not available on this device");
    return nullptr;
}

android_namespace_t* android_create_namespace_escape(const char* name,
                                                     const char* ld_library_path,
                                                     const char* default_library_path,
                                                     uint64_t type,
                                                     const char* permitted_when_isolated_path,
                                                     android_namespace_t* parent_namespace) {
    std::string permitted = permitted_when_isolated_path ? permitted_when_isolated_path : "";
    const char* extra = "/system:/vendor:/apex:/data:/odm:/product";
    if (permitted.empty()) {
        permitted = extra;
    } else {
        permitted.push_back(':');
        permitted += extra;
    }
    return android_create_namespace(name, ld_library_path, default_library_path, type,
                                    permitted.c_str(), parent_namespace);
}

bool linkernsbypass_link_namespace_to_default_all_libs(android_namespace_t* to) {
    ensure_init();
    if (!to) return false;
    android_namespace_t* from = nullptr;
    if (android_get_exported_namespace) {
        const char* names[] = {"default", "com_android_art", "sphal", "vndk", nullptr};
        for (int i = 0; names[i]; ++i) {
            from = android_get_exported_namespace(names[i]);
            if (from) break;
        }
    }
    if (android_link_namespaces_all_libs && from) {
        if (android_link_namespaces_all_libs(from, to)) return true;
    }
    if (android_link_namespaces && from) {
        const char* libs =
            "libc.so:libm.so:libdl.so:liblog.so:libz.so:libandroid.so:"
            "libnativewindow.so:libsync.so:libvndksupport.so:libbase.so";
        if (android_link_namespaces(from, to, libs)) return true;
    }
    // No exported default namespace. Linking is best-effort; the caller may
    // still dlopen absolute paths inside the new namespace.
    FABLE_LOGW("Could not link driver namespace to the default namespace");
    return from == nullptr;
}

void* linkernsbypass_namespace_dlopen(const char* filename, int flags, android_namespace_t* ns) {
    if (!filename) return nullptr;
    ensure_init();
    if (!ns) return dlopen(filename, flags);
    android_dlextinfo ext{};
    ext.flags = ANDROID_DLEXT_USE_NAMESPACE;
    ext.library_namespace = ns;
    void* handle = android_dlopen_ext(filename, flags, &ext);
    if (!handle) {
        FABLE_LOGW("namespace dlopen failed for %s: %s", filename, dlerror());
    }
    return handle;
}

void* linkernsbypass_namespace_dlopen_unique(const char* libPath, const char* libTargetDir,
                                              int flags, android_namespace_t* ns) {
    if (!libPath) return nullptr;
    ensure_init();
    std::vector<uint8_t> image;
    if (!read_file(libPath, &image)) {
        FABLE_LOGE("unique dlopen: cannot read %s", libPath);
        return nullptr;
    }

    int fd = memfd_create_compat("fable-libvulkan");
    std::string tmp_path;
    bool using_memfd = fd >= 0;
    if (!using_memfd) {
        if (!libTargetDir || !*libTargetDir) {
            FABLE_LOGE("unique dlopen: memfd unavailable and no tmp directory");
            return nullptr;
        }
        tmp_path = std::string(libTargetDir) + "/libvulkan-fable-" + std::to_string(getpid()) + ".so";
        fd = open(tmp_path.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
        if (fd < 0) {
            FABLE_LOGE("unique dlopen: cannot create %s: %s", tmp_path.c_str(), std::strerror(errno));
            return nullptr;
        }
    }
    if (!write_all(fd, image.data(), image.size())) {
        FABLE_LOGE("unique dlopen: short write");
        close(fd);
        if (!tmp_path.empty()) unlink(tmp_path.c_str());
        return nullptr;
    }
    if (lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        if (!tmp_path.empty()) unlink(tmp_path.c_str());
        return nullptr;
    }

    android_dlextinfo ext{};
    ext.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
    ext.library_fd = fd;
    if (ns) {
        ext.flags |= ANDROID_DLEXT_USE_NAMESPACE;
        ext.library_namespace = ns;
    }
    // The filename still identifies the library to the linker. Use a distinct
    // name so this instance is not confused with the already-loaded system copy.
    const char* ident = using_memfd ? "libvulkan-fable.so" : tmp_path.c_str();
    void* handle = android_dlopen_ext(ident, flags, &ext);
    close(fd);
    if (!tmp_path.empty()) unlink(tmp_path.c_str());
    if (!handle) {
        FABLE_LOGW("unique dlopen failed (%s), falling back: %s", libPath, dlerror());
        handle = linkernsbypass_namespace_dlopen(libPath, flags, ns);
    }
    return handle;
}
