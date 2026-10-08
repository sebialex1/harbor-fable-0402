// SPDX-License-Identifier: MIT
/*
 * libvulkan.so.1 shim for Android (built as libvulkan_shim.so, installed by
 * NativeLibResolver as <filesDir>/x11/lib/libvulkan.so.1).
 *
 * Wine's Unix side (win32u, through Box64) does dlopen("libvulkan.so.1") — the
 * SONAME every Linux distribution ships — and dlsym's a handful of entry points
 * directly instead of going through vkGetInstanceProcAddr:
 *
 *   win32u/vulkan.c      vkGetInstanceProcAddr, vkGetDeviceProcAddr,
 *                        vkDestroySurfaceKHR, vkQueuePresentKHR
 *   winex11.drv/vulkan.c vkCreateXlibSurfaceKHR,
 *                        vkGetPhysicalDeviceXlibPresentationSupportKHR
 *
 * Android's own loader (/system/lib64/libvulkan.so) has no Xlib: it does not
 * export vkGetPhysicalDeviceXlibPresentationSupportKHR, so winex11's VulkanInit
 * returns STATUS_PROCEDURE_NOT_FOUND (0xc000007a), winevulkan's loader asserts
 * (`!status`, loader.c) and DXVK never gets a Vulkan instance:
 *
 *   Call to dlsym(libvulkan.so.1, "vkGetPhysicalDeviceXlibPresentationSupportKHR") Symbol not found
 *   err:vulkan:vulkan_driver_init Failed to initialize the driver vulkan functions, status 0xc000007a
 *   err:msvcrt:_wassert (L"!status", L"../dlls/winevulkan/loader.c", 372)
 *
 * This library sits first on the Wine process's LD_LIBRARY_PATH under the name
 * libvulkan.so.1. It dlopen's the real system loader lazily (constructor +
 * pthread_once, so a dlsym-before-constructor or a concurrent first call are
 * both fine), forwards the entry points above, and stubs the Xlib query Android
 * lacks: vkGetPhysicalDeviceXlibPresentationSupportKHR always answers VK_TRUE.
 *
 * Function pointers alone are not enough, though. winevulkan asks the host for
 * VK_KHR_xlib_surface as an *instance extension*: it translates the
 * VK_KHR_win32_surface DXVK enables into the X11 driver's host surface
 * extension and passes that to the host vkCreateInstance. Android's loader has
 * never heard of VK_KHR_xlib_surface, so instance creation dies with
 *
 *   wine_vkCreateInstance Failed to create instance, res=-7   (VK_ERROR_EXTENSION_NOT_PRESENT)
 *
 * and every D3D9/10/11 title falls over before it draws a frame. The shim
 * therefore also wraps the two instance-level entry points involved:
 *
 *   vkEnumerateInstanceExtensionProperties  appends VK_KHR_xlib_surface to the
 *                                           real loader's list so winevulkan
 *                                           advertises VK_KHR_win32_surface
 *   vkCreateInstance                        replaces X11 WSI with Android WSI
 *   vkCreateXlibSurfaceKHR                   creates an ImageReader ANativeWindow
 *                                           in the Wine process and a real
 *                                           VK_KHR_android_surface; presents
 *                                           its frames to the app's X drawable
 *
 * vkGetInstanceProcAddr hands out the shim's versions of both so the wrap holds
 * whether Wine reaches them by dlsym or by procaddr. Every other Vulkan
 * function is reached through the forwarded vkGetInstanceProcAddr /
 * vkGetDeviceProcAddr. Surface queries and destruction also stay behind the shim.
 *
 * Which Vulkan implementation sits behind the shim (real.direct_icd, an enum shim_mode):
 *
 *   SHIM_MODE_SPLIT (2)      FABLE_VULKAN_DRIVER set (RADV Xclipse vulkan.radeon.so, or Turnip
 *                            vulkan.ad07xx.so / libvulkan_freedreno.so on Adreno) — the default.
 *                            Winlator/adrenotools-style injection (vulkan_hal_inject.c): a private
 *                            instance of Android's system loader is opened and its HAL lookup is
 *                            redirected to the custom driver. The system loader provides instance
 *                            creation and all WSI (VK_KHR_surface, VK_KHR_android_surface,
 *                            VK_KHR_swapchain, implemented over the driver's
 *                            VK_ANDROID_native_buffer); the custom driver provides every physical
 *                            device, VkDevice, queue, memory allocation and command. RADV is built
 *                            with -Dplatforms=android, i.e. as exactly such a HAL: it exports HMI and
 *                            has no WSI of its own, so this is the only way DXVK gets both a
 *                            presentable instance and RADV's real RDNA2 features
 *                            (textureCompressionBC & co).
 *   SHIM_MODE_DIRECT_ICD (1) the ICD .so is dlopen'ed directly and the shim acts as the loader. Only
 *                            usable for an ICD with its own VK_KHR_surface + VK_KHR_android_surface
 *                            (not RADV Xclipse); tried when split mode is unavailable (driver is not
 *                            an Android HAL) or FABLE_VULKAN_DRIVER_MODE=direct.
 *   SHIM_MODE_SYSTEM (0)     Android's system loader alone (the vendor driver, Samsung Xclipse), with
 *                            textureCompressionBC forced on. No custom driver, or every custom path
 *                            failed.
 *
 * VK_ICD_FILENAMES / VK_DRIVER_FILES are a secondary source for the driver (library_path from the
 * JSON manifest) with the same handling. FABLE_VULKAN_DRIVER_MODE=split|direct|system overrides
 * the choice; FABLE_VULKAN_SPLIT_PROBE=0 skips split mode's start-up device probe.
 *
 * The log says which: "Using split Vulkan: ...", "Using Fable Vulkan driver: <path>" (direct) or
 * "Using system Vulkan loader: <path>". Those lines (and every other shim diagnostic) go to
 * logcat, to stderr — i.e. the Wine process log — prefixed "[vulkan_shim]", and to
 * $FABLE_VULKAN_SHIM_LOG.
 *
 * Driver selection runs on the first Vulkan call (pthread_once), not in the library constructor:
 * split mode's probe creates a Vulkan instance, which must not happen inside the dynamic linker's
 * dlopen of this shim. If an instance still cannot be created on the selected custom path (and no
 * instance exists yet), vkCreateInstance switches to the system loader and retries.
 *
 * Only vk* symbols are exported (-fvisibility=hidden + VK_SHIM_EXPORT).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* dladdr; bionic always declares it, glibc only with this */
#endif
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>
#include <android/native_window.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_android.h>

#include "android_surface_bridge.h"
#include "vulkan_hal_inject.h"
#include "vulkan_shim_log.h"

#define VK_SHIM_EXPORT __attribute__((visibility("default")))

#define LOG_TAG "vulkan_shim"

/* --- Diagnostic logging -------------------------------------------------------------------
 *
 * logcat alone is not enough: the user only sees the Wine process log, which is the process's
 * stdout/stderr (WineProcessLauncher uses redirectErrorStream). Every shim line therefore goes to
 *   - logcat (tag vulkan_shim),
 *   - stderr, prefixed "[vulkan_shim]", so it lands in the Wine process log next to winevulkan's
 *     own err:vulkan lines,
 *   - a log file: $FABLE_VULKAN_SHIM_LOG (WineProcessLauncher points it into the container), else
 *     /data/user/0/io.harbor.fable/files/vulkan_shim.log.
 * If no "[vulkan_shim]" line shows up in the Wine log at all, Wine did not load this shim. */
#define SHIM_LOG_ENV "FABLE_VULKAN_SHIM_LOG"
#define SHIM_LOG_DEFAULT "/data/user/0/io.harbor.fable/files/vulkan_shim.log"

static pthread_mutex_t shim_log_mutex = PTHREAD_MUTEX_INITIALIZER;
static FILE *shim_log_file;
static int shim_log_file_tried;

void shim_log(int prio, const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    __android_log_write(prio, LOG_TAG, msg);

    const char *level = prio >= ANDROID_LOG_ERROR ? "err" : prio >= ANDROID_LOG_WARN ? "warn" : "info";
    pthread_mutex_lock(&shim_log_mutex);
    dprintf(STDERR_FILENO, "[vulkan_shim] %s: %s\n", level, msg);
    if (!shim_log_file_tried) {
        shim_log_file_tried = 1;
        const char *path = getenv(SHIM_LOG_ENV);
        if (!path || !*path) path = SHIM_LOG_DEFAULT;
        shim_log_file = fopen(path, "a");
        if (shim_log_file) {
            setvbuf(shim_log_file, NULL, _IOLBF, 0);
            fprintf(shim_log_file, "---- vulkan_shim pid %d ----\n", (int)getpid());
        }
    }
    if (shim_log_file) fprintf(shim_log_file, "%s: %s\n", level, msg);
    pthread_mutex_unlock(&shim_log_mutex);
}

/* Where Android keeps its Vulkan loader; tried first, then the plain SONAME (which the
 * linker resolves through LD_LIBRARY_PATH / the default system directories). Only used when
 * no Fable driver is configured, or when it cannot be opened. */
static const char *const REAL_LOADER_PATHS[] = {
    "/system/lib64/libvulkan.so",
    "libvulkan.so",
};

/* Set by WineProcessLauncher when a custom driver (RADV Xclipse) is active: the path of the ICD
 * .so itself. Android's loader cannot be pointed at it (it ignores VK_ICD_FILENAMES and always
 * picks the vendor driver), so the shim opens the ICD directly and acts as the loader. */
#define FABLE_DRIVER_ENV "FABLE_VULKAN_DRIVER"
/* "1" keeps a directly opened ICD even when it lacks VK_KHR_surface / VK_KHR_android_surface. */
#define FABLE_DRIVER_FORCE_ENV "FABLE_VULKAN_DRIVER_FORCE"
/* "split" (default), "direct" or "system": which way the custom driver is used, see above. */
#define FABLE_DRIVER_MODE_ENV "FABLE_VULKAN_DRIVER_MODE"
/* "0" skips split mode's start-up probe (instance + physical-device enumeration on RADV). */
#define FABLE_SPLIT_PROBE_ENV "FABLE_VULKAN_SPLIT_PROBE"

/* The system loader split mode injects the custom driver into. */
#define SYSTEM_LOADER_PATH "/system/lib64/libvulkan.so"

enum shim_mode {
    SHIM_MODE_SYSTEM = 0,     /* Android's system loader + vendor driver */
    SHIM_MODE_DIRECT_ICD = 1, /* custom ICD opened directly, the shim is the loader */
    SHIM_MODE_SPLIT = 2,      /* system loader (instance + WSI) with the custom driver as its HAL (devices) */
};

/* Highest loader <-> ICD interface version the shim speaks (Vulkan loader interface v5). */
#define SHIM_ICD_INTERFACE_VERSION 5u

typedef VkResult(VKAPI_PTR *PFN_shim_NegotiateLoaderICDInterfaceVersion)(uint32_t *pSupportedVersion);

/* VK_KHR_xlib_surface types, spelled out so no X11 headers are needed: Xlib's Display is
 * opaque here, Window and VisualID are XIDs (unsigned long). */
typedef void XlibDisplay;
typedef unsigned long XlibWindow;
typedef unsigned long XlibVisualID;
typedef VkFlags VkXlibSurfaceCreateFlagsKHR;

typedef struct VkXlibSurfaceCreateInfoKHR {
    VkStructureType sType;
    const void *pNext;
    VkXlibSurfaceCreateFlagsKHR flags;
    XlibDisplay *dpy;
    XlibWindow window;
} VkXlibSurfaceCreateInfoKHR;

typedef VkResult(VKAPI_PTR *PFN_vkCreateXlibSurfaceKHR)(VkInstance instance,
                                                        const VkXlibSurfaceCreateInfoKHR *pCreateInfo,
                                                        const VkAllocationCallbacks *pAllocator,
                                                        VkSurfaceKHR *pSurface);
typedef VkBool32(VKAPI_PTR *PFN_vkGetPhysicalDeviceXlibPresentationSupportKHR)(VkPhysicalDevice physicalDevice,
                                                                               uint32_t queueFamilyIndex,
                                                                               XlibDisplay *dpy,
                                                                               XlibVisualID visualID);

/* The real Vulkan implementation and the entry points forwarded to it. Written once by
 * shim_init() under pthread_once and read-only afterwards, which is what makes the forwarders
 * thread-safe.
 *
 * Three kinds of implementation are supported (enum shim_mode):
 *   - the Android system loader (libvulkan.so), SHIM_MODE_SYSTEM: exports every core entry
 *     point, so plain dlsym works for all of them;
 *   - split, SHIM_MODE_SPLIT: a private instance of the same system loader whose HAL is the
 *     custom driver (handle = that loader instance, driver_path = the driver). Also a full
 *     loader, so plain dlsym works too; the loader routes device-level work to the driver;
 *   - a Vulkan ICD opened directly, SHIM_MODE_DIRECT_ICD: ICDs only promise
 *     vk_icdGetInstanceProcAddr, so every other entry point is resolved through it (globals with
 *     a NULL instance, the rest once an instance exists, see cache_instance_procs()). */
static struct {
    void *handle;
    const char *path;
    int direct_icd;          /* enum shim_mode (historical name) */
    const char *driver_path; /* SHIM_MODE_SPLIT: the injected driver */
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr vkGetDeviceProcAddr;
    PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance vkCreateInstance;
    PFN_vkDestroySurfaceKHR vkDestroySurfaceKHR;
    PFN_vkQueuePresentKHR vkQueuePresentKHR;
    PFN_vkCreateXlibSurfaceKHR vkCreateXlibSurfaceKHR;
    PFN_vkGetPhysicalDeviceXlibPresentationSupportKHR vkGetPhysicalDeviceXlibPresentationSupportKHR;
} real;

static pthread_once_t shim_once = PTHREAD_ONCE_INIT;

/* Entry points the shim calls itself (rather than handing out). With the system loader they
 * are plain exports; with a direct ICD they only exist behind vkGetInstanceProcAddr(instance),
 * so they are cached from the first instance created (the ICD's dispatch functions do not
 * depend on which instance they were looked up with). Each slot is written at most once. */
enum real_proc_id {
    RP_vkGetDeviceProcAddr,
    RP_vkGetPhysicalDeviceFeatures,
    RP_vkGetPhysicalDeviceFeatures2,
    RP_vkGetPhysicalDeviceFeatures2KHR,
    RP_vkGetPhysicalDeviceSurfaceCapabilitiesKHR,
    RP_vkGetPhysicalDeviceSurfaceFormatsKHR,
    RP_vkDestroySurfaceKHR,
    RP_vkQueuePresentKHR,
    RP_vkCreateDevice,
    RP_vkGetPhysicalDeviceProperties,
    RP_COUNT
};

static const char *const REAL_PROC_NAMES[RP_COUNT] = {
    [RP_vkGetDeviceProcAddr] = "vkGetDeviceProcAddr",
    [RP_vkGetPhysicalDeviceFeatures] = "vkGetPhysicalDeviceFeatures",
    [RP_vkGetPhysicalDeviceFeatures2] = "vkGetPhysicalDeviceFeatures2",
    [RP_vkGetPhysicalDeviceFeatures2KHR] = "vkGetPhysicalDeviceFeatures2KHR",
    [RP_vkGetPhysicalDeviceSurfaceCapabilitiesKHR] = "vkGetPhysicalDeviceSurfaceCapabilitiesKHR",
    [RP_vkGetPhysicalDeviceSurfaceFormatsKHR] = "vkGetPhysicalDeviceSurfaceFormatsKHR",
    [RP_vkDestroySurfaceKHR] = "vkDestroySurfaceKHR",
    [RP_vkQueuePresentKHR] = "vkQueuePresentKHR",
    [RP_vkCreateDevice] = "vkCreateDevice",
    [RP_vkGetPhysicalDeviceProperties] = "vkGetPhysicalDeviceProperties",
};

static void *real_procs[RP_COUNT];

static void store_real_proc(enum real_proc_id id, void *fn) {
    void *expected = NULL;
    if (fn) __atomic_compare_exchange_n(&real_procs[id], &expected, fn, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

/* The implementation's `id` entry point, or NULL if it has none (yet). */
static void *real_proc(enum real_proc_id id) {
    void *fn = __atomic_load_n(&real_procs[id], __ATOMIC_ACQUIRE);
    if (fn || !real.handle) return fn;
    if (real.direct_icd != SHIM_MODE_DIRECT_ICD) { /* system loader, or split mode's loader instance */
        fn = dlsym(real.handle, REAL_PROC_NAMES[id]);
        store_real_proc(id, fn);
        return __atomic_load_n(&real_procs[id], __ATOMIC_ACQUIRE);
    }
    return NULL; /* direct ICD: only known once an instance exists */
}

/* Called after every successful vkCreateInstance: fills the slots that plain dlsym could not. */
static void cache_instance_procs(VkInstance instance) {
    if (!real.vkGetInstanceProcAddr || instance == VK_NULL_HANDLE) return;
    for (int id = 0; id < RP_COUNT; id++) {
        if (real_proc((enum real_proc_id)id)) continue;
        store_real_proc((enum real_proc_id)id,
                        (void *)real.vkGetInstanceProcAddr(instance, REAL_PROC_NAMES[id]));
    }
}

/* Resolves an entry point by symbol name. The Android system loader exports every Vulkan
 * function as a plain dlsym symbol; a direct ICD (RADV Xclipse) does not — it exposes
 * vk_icdGetInstanceProcAddr (or, after negotiation, vkGetInstanceProcAddr) and expects the loader
 * to resolve everything else through that. In ICD mode, a dlsym miss is therefore normal, not an
 * error: the function is reached through the instance-level cache populated by
 * cache_instance_procs(). */
static void *load_real(const char *name, int required) {
    void *fn = dlsym(real.handle, name);
    if (!fn && real.direct_icd == SHIM_MODE_DIRECT_ICD) {
        for (int id = 0; id < RP_COUNT; id++) {
            if (strcmp(name, REAL_PROC_NAMES[id]) == 0) {
                fn = real_proc((enum real_proc_id)id);
                break;
            }
        }
    }
    if (!fn) {
        if (required) LOGE("%s does not export %s", real.path, name);
        else LOGI("%s does not export %s (forwarded through vkGetInstanceProcAddr, or stubbed)", real.path, name);
    }
    return fn;
}

/* --- Driver selection ------------------------------------------------------------------- */

/* Extracts "library_path" from an ICD JSON manifest (VK_ICD_FILENAMES / VK_DRIVER_FILES; a
 * list separated by ':' — the first entry that yields a path wins). Relative paths are taken
 * relative to the manifest, as the Khronos loader does; a bare file name is left to dlopen's
 * search path. Returns a malloc'd string or NULL. Deliberately minimal: the manifests in
 * question are the one WineProcessLauncher writes. */
static char *icd_library_from_manifest(const char *manifest) {
    FILE *f = fopen(manifest, "rb");
    if (!f) {
        LOGW("ICD manifest %s: cannot open", manifest);
        return NULL;
    }
    char buf[8192];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = '\0';

    const char *key = strstr(buf, "\"library_path\"");
    if (!key) {
        LOGW("ICD manifest %s: no library_path", manifest);
        return NULL;
    }
    const char *p = key + strlen("\"library_path\"");
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    if (*p++ != ':') return NULL;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    if (*p++ != '"') return NULL;

    char value[PATH_MAX];
    size_t len = 0;
    for (; *p && *p != '"' && len < sizeof(value) - 1; p++) {
        if (*p == '\\' && p[1]) p++; /* \/ and \\ are the only escapes a path needs */
        value[len++] = *p;
    }
    if (*p != '"' || len == 0) return NULL;
    value[len] = '\0';

    if (value[0] == '/' || !strchr(value, '/')) return strdup(value);
    const char *slash = strrchr(manifest, '/');
    if (!slash) return strdup(value);
    size_t dir = (size_t)(slash - manifest) + 1;
    char *full = malloc(dir + len + 1);
    if (!full) return NULL;
    memcpy(full, manifest, dir);
    memcpy(full + dir, value, len + 1);
    return full;
}

/* Opens a Vulkan ICD .so directly and makes it the implementation the shim forwards to.
 * `origin` only labels the log lines. Returns 1 on success; on failure nothing is kept. */
static int open_direct_icd(const char *path, const char *origin) {
    /* Say plainly when the file is simply not there: a stale active.json or a half-extracted
     * driver otherwise only shows up as an opaque dlopen error. */
    if (strchr(path, '/')) {
        struct stat st;
        if (stat(path, &st) != 0) {
            LOGE("Fable Vulkan driver %s [%s] does not exist; falling back to the system Vulkan loader", path, origin);
            return 0;
        }
        if (!S_ISREG(st.st_mode)) {
            LOGE("Fable Vulkan driver %s [%s] is not a regular file; falling back to the system Vulkan loader", path, origin);
            return 0;
        }
        if (access(path, R_OK) != 0) {
            LOGE("Fable Vulkan driver %s [%s] is not readable; falling back to the system Vulkan loader", path, origin);
            return 0;
        }
    }

    /* RTLD_NOW: an ICD with unresolvable dependencies must fail here, where the shim can still
     * fall back to the system loader, not on first call. */
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("dlopen(%s) [%s] failed: %s; falling back to the system Vulkan loader", path, origin, dlerror());
        return 0;
    }

    /* Loader-style ICDs (Mesa's RADV among them) export vk_icdGetInstanceProcAddr only; some
     * also export the plain name. Prefer the plain export when there is one. */
    const char *gipa_name = "vkGetInstanceProcAddr";
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(handle, gipa_name);
    if (!gipa) {
        gipa_name = "vk_icdGetInstanceProcAddr";
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(handle, gipa_name);
    }
    if (!gipa) {
        LOGE("%s [%s] exports neither vkGetInstanceProcAddr nor vk_icdGetInstanceProcAddr", path, origin);
        dlclose(handle);
        return 0;
    }

    /* The loader always negotiates before anything else; ICDs may depend on it. */
    PFN_shim_NegotiateLoaderICDInterfaceVersion negotiate =
        (PFN_shim_NegotiateLoaderICDInterfaceVersion)dlsym(handle, "vk_icdNegotiateLoaderICDInterfaceVersion");
    uint32_t interface_version = SHIM_ICD_INTERFACE_VERSION;
    if (negotiate && negotiate(&interface_version) != VK_SUCCESS) {
        LOGE("%s [%s] rejected loader interface negotiation", path, origin);
        dlclose(handle);
        return 0;
    }

    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    PFN_vkEnumerateInstanceExtensionProperties enumerate =
        (PFN_vkEnumerateInstanceExtensionProperties)gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
    if (!create || !enumerate) {
        LOGE("%s [%s]: %s(NULL) does not provide vkCreateInstance / vkEnumerateInstanceExtensionProperties",
             path, origin, gipa_name);
        dlclose(handle);
        return 0;
    }

    /* WSI probe. An ICD built for Android (Mesa -Dplatforms=android, which is how RADV Xclipse is
     * built) is a Vulkan *HAL*: it implements VK_ANDROID_native_buffer and leaves VK_KHR_surface,
     * VK_KHR_android_surface and VK_KHR_swapchain to Android's platform loader. Opened directly,
     * it has no window-system integration at all, so the vkCreateInstance below (which needs
     * VK_KHR_surface + VK_KHR_android_surface in place of winevulkan's VK_KHR_xlib_surface)
     * would fail with VK_ERROR_EXTENSION_NOT_PRESENT (-7) and DXVK would never get a device.
     * Such a driver is only kept when FABLE_VULKAN_DRIVER_FORCE=1 (headless experiments). */
    int has_surface = 0, has_android_surface = 0;
    uint32_t ext_count = 0;
    VkResult probe = enumerate(NULL, &ext_count, NULL);
    VkExtensionProperties *exts = NULL;
    if (probe == VK_SUCCESS && ext_count) {
        exts = calloc(ext_count, sizeof(*exts));
        if (exts) probe = enumerate(NULL, &ext_count, exts);
    }
    if (exts && (probe == VK_SUCCESS || probe == VK_INCOMPLETE)) {
        char list[768];
        size_t used = 0;
        list[0] = '\0';
        for (uint32_t i = 0; i < ext_count; i++) {
            const char *name = exts[i].extensionName;
            if (strcmp(name, VK_KHR_SURFACE_EXTENSION_NAME) == 0) has_surface = 1;
            if (strcmp(name, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME) == 0) has_android_surface = 1;
            int w = snprintf(list + used, sizeof(list) - used, "%s%s", used ? " " : "", name);
            if (w > 0 && (size_t)w < sizeof(list) - used) used += (size_t)w;
        }
        LOGI("%s [%s] instance extensions (%u): %s", path, origin, ext_count, list);
    } else {
        LOGW("%s [%s]: vkEnumerateInstanceExtensionProperties failed (res=%d)", path, origin, (int)probe);
    }
    free(exts);

    if (!has_surface || !has_android_surface) {
        const char *force = getenv(FABLE_DRIVER_FORCE_ENV);
        LOGE("%s [%s] has no window-system integration (%s%s%s missing): it is an Android Vulkan HAL "
             "driver that relies on the platform loader for surfaces/swapchains, so DXVK cannot create "
             "a presentable instance on it directly",
             path, origin,
             has_surface ? "" : VK_KHR_SURFACE_EXTENSION_NAME,
             (!has_surface && !has_android_surface) ? ", " : "",
             has_android_surface ? "" : VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
        if (!(force && strcmp(force, "1") == 0)) {
            LOGE("Not using %s; falling back to the system Vulkan loader (textureCompressionBC is forced on "
                 "there). Set %s=1 to keep the driver anyway", path, FABLE_DRIVER_FORCE_ENV);
            /* Not dlclose'd: the driver has already run code (static init, debug options) and
             * unloading Mesa mid-process is not something it is written to survive. */
            return 0;
        }
        LOGW("%s=1: keeping %s despite the missing WSI extensions", FABLE_DRIVER_FORCE_ENV, path);
    }

    real.handle = handle;
    real.path = strdup(path);
    if (!real.path) real.path = "(fable vulkan driver)";
    real.direct_icd = SHIM_MODE_DIRECT_ICD;
    real.driver_path = NULL;
    real.vkGetInstanceProcAddr = gipa;
    real.vkCreateInstance = create;
    real.vkEnumerateInstanceExtensionProperties = enumerate;
    /* Device-level and instance-level entry points come from vkGetInstanceProcAddr; an ICD may
     * still export some of them, which is a harmless head start. */
    real.vkGetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)dlsym(handle, "vkGetDeviceProcAddr");
    store_real_proc(RP_vkGetDeviceProcAddr, (void *)real.vkGetDeviceProcAddr);
    real.vkDestroySurfaceKHR = NULL;
    real.vkQueuePresentKHR = NULL;
    real.vkCreateXlibSurfaceKHR = NULL;
    real.vkGetPhysicalDeviceXlibPresentationSupportKHR = NULL;

    LOGI("Using Fable Vulkan driver: %s (from %s; %s, ICD interface v%u%s)", real.path, origin, gipa_name,
         interface_version, negotiate ? "" : ", not negotiated");
    return 1;
}

/* Binds the shim's directly used entry points to a full loader at real.handle (the system
 * loader, or split mode's private instance of it). Plain dlsym works for all of them. */
static void bind_loader_exports(void) {
    real.vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)load_real("vkGetInstanceProcAddr", 1);
    real.vkGetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)load_real("vkGetDeviceProcAddr", 1);
    store_real_proc(RP_vkGetDeviceProcAddr, (void *)real.vkGetDeviceProcAddr);
    real.vkEnumerateInstanceExtensionProperties =
        (PFN_vkEnumerateInstanceExtensionProperties)load_real("vkEnumerateInstanceExtensionProperties", 1);
    real.vkCreateInstance = (PFN_vkCreateInstance)load_real("vkCreateInstance", 1);
    real.vkDestroySurfaceKHR = (PFN_vkDestroySurfaceKHR)load_real("vkDestroySurfaceKHR", 0);
    real.vkQueuePresentKHR = (PFN_vkQueuePresentKHR)load_real("vkQueuePresentKHR", 0);
    real.vkCreateXlibSurfaceKHR = (PFN_vkCreateXlibSurfaceKHR)load_real("vkCreateXlibSurfaceKHR", 0);
    real.vkGetPhysicalDeviceXlibPresentationSupportKHR =
        (PFN_vkGetPhysicalDeviceXlibPresentationSupportKHR)load_real("vkGetPhysicalDeviceXlibPresentationSupportKHR", 0);
}

/* --- Split mode: system loader for instance + WSI, custom HAL driver for devices ------------ */

/* The leading fields of Android's hw_module_t (hardware/hardware.h), enough to sanity-check the
 * HMI export of a Vulkan HAL without the platform headers. */
struct shim_hw_module_head {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
};
#define SHIM_HARDWARE_MODULE_TAG (((uint32_t)'H' << 24) | ((uint32_t)'W' << 16) | ((uint32_t)'M' << 8) | (uint32_t)'T')

static const char *driver_id_name(VkDriverId id) {
    switch (id) {
    case VK_DRIVER_ID_MESA_RADV: return "MESA_RADV";
    case VK_DRIVER_ID_AMD_PROPRIETARY: return "AMD_PROPRIETARY";
    case VK_DRIVER_ID_SAMSUNG_PROPRIETARY: return "SAMSUNG_PROPRIETARY";
    case VK_DRIVER_ID_ARM_PROPRIETARY: return "ARM_PROPRIETARY";
    case VK_DRIVER_ID_QUALCOMM_PROPRIETARY: return "QUALCOMM_PROPRIETARY";
    case VK_DRIVER_ID_MESA_TURNIP: return "MESA_TURNIP";
    default: return "other";
    }
}

/* Lists the instance extensions the split loader offers (it adds WSI on top of the driver's) and
 * checks the two DXVK/winevulkan need. Also the call that makes the loader open its HAL. */
static int split_check_instance_extensions(PFN_vkEnumerateInstanceExtensionProperties enumerate, VkResult *res_out) {
    uint32_t count = 0;
    VkResult res = enumerate(NULL, &count, NULL);
    VkExtensionProperties *exts = NULL;
    if (res == VK_SUCCESS && count) {
        exts = calloc(count, sizeof(*exts));
        if (!exts) return 0;
        res = enumerate(NULL, &count, exts);
    }
    *res_out = res;
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) {
        free(exts);
        return 0;
    }
    int has_surface = 0, has_android_surface = 0;
    char list[900];
    size_t used = 0;
    list[0] = '\0';
    for (uint32_t i = 0; exts && i < count; i++) {
        const char *name = exts[i].extensionName;
        if (strcmp(name, VK_KHR_SURFACE_EXTENSION_NAME) == 0) has_surface = 1;
        if (strcmp(name, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME) == 0) has_android_surface = 1;
        int w = snprintf(list + used, sizeof(list) - used, "%s%s", used ? " " : "", name);
        if (w > 0 && (size_t)w < sizeof(list) - used) used += (size_t)w;
    }
    free(exts);
    LOGI("split: system loader instance extensions with the injected HAL (%u): %s", count, list);
    if (!has_surface || !has_android_surface) {
        LOGE("split: the system loader does not offer %s%s%s", has_surface ? "" : VK_KHR_SURFACE_EXTENSION_NAME,
             (!has_surface && !has_android_surface) ? ", " : "",
             has_android_surface ? "" : VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
        return 0;
    }
    return 1;
}

/* True when a physical device belongs to one of the Mesa drivers Fable injects: RADV (Samsung
 * Xclipse) or Turnip (Qualcomm Adreno). Decided by VkPhysicalDeviceDriverProperties.driverID when
 * the device reports it, else by name ("AMD Radeon ... (RADV ...)", "Turnip Adreno (TM) 740"). */
static int is_injected_mesa_device(const VkPhysicalDeviceDriverProperties *driver_props, const char *device_name) {
    if (driver_props->driverID == VK_DRIVER_ID_MESA_RADV || driver_props->driverID == VK_DRIVER_ID_MESA_TURNIP) return 1;
    if (driver_props->driverID == VK_DRIVER_ID_SAMSUNG_PROPRIETARY ||
        driver_props->driverID == VK_DRIVER_ID_QUALCOMM_PROPRIETARY ||
        driver_props->driverID == VK_DRIVER_ID_ARM_PROPRIETARY) {
        return 0;
    }
    if (!device_name) return 0;
    return strstr(device_name, "RADV") != NULL || strstr(device_name, "Turnip") != NULL ||
           strstr(device_name, "turnip") != NULL;
}

/* Start-up probe: a throw-away instance on the split loader, then every physical device it
 * reports. Split mode is only kept if at least one device really is the injected driver (RADV/Turnip)
 * and the loader exposes VK_KHR_swapchain on it (i.e. the driver's VK_ANDROID_native_buffer was
 * accepted). Logs what DXVK is going to see. */
static int split_probe_devices(PFN_vkGetInstanceProcAddr gipa, const char *driver_path) {
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion instance_version =
        (PFN_vkEnumerateInstanceVersion)gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (!create) {
        LOGE("split probe: no vkCreateInstance");
        return 0;
    }
    uint32_t api = VK_API_VERSION_1_0;
    if (instance_version && instance_version(&api) != VK_SUCCESS) api = VK_API_VERSION_1_0;
    const VkApplicationInfo app = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "fable-vulkan-shim-probe",
        .apiVersion = api >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0,
    };
    const VkInstanceCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &app,
    };
    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = create(&info, NULL, &instance);
    if (res != VK_SUCCESS) {
        LOGE("split probe: vkCreateInstance on the system loader with %s as HAL failed, res=%d", driver_path, (int)res);
        return 0;
    }

    PFN_vkDestroyInstance destroy = (PFN_vkDestroyInstance)gipa(instance, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumerate_devices =
        (PFN_vkEnumeratePhysicalDevices)gipa(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties get_props =
        (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceProperties2 get_props2 = app.apiVersion >= VK_API_VERSION_1_1
        ? (PFN_vkGetPhysicalDeviceProperties2)gipa(instance, "vkGetPhysicalDeviceProperties2") : NULL;
    PFN_vkGetPhysicalDeviceFeatures get_features =
        (PFN_vkGetPhysicalDeviceFeatures)gipa(instance, "vkGetPhysicalDeviceFeatures");
    PFN_vkEnumerateDeviceExtensionProperties device_exts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");

    int usable = 0, foreign = 0, no_swapchain = 0;
    uint32_t count = 0;
    VkPhysicalDevice devices[8];
    if (!enumerate_devices || !get_props || !get_features || !device_exts) {
        LOGE("split probe: the system loader is missing core instance entry points");
    } else {
        res = enumerate_devices(instance, &count, NULL);
        if (res == VK_SUCCESS && count > 8) count = 8;
        if (res == VK_SUCCESS && count) {
            res = enumerate_devices(instance, &count, devices);
            if (res == VK_INCOMPLETE) res = VK_SUCCESS;
        }
        if (res != VK_SUCCESS) {
            LOGE("split probe: vkEnumeratePhysicalDevices failed, res=%d", (int)res);
            count = 0;
        } else if (!count) {
            LOGE("split probe: %s reports no physical devices through the system loader", driver_path);
        }
    }

    for (uint32_t i = 0; i < count; i++) {
        VkPhysicalDeviceProperties props;
        memset(&props, 0, sizeof(props));
        get_props(devices[i], &props);
        VkPhysicalDeviceDriverProperties driver_props;
        memset(&driver_props, 0, sizeof(driver_props));
        driver_props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        if (get_props2 && props.apiVersion >= VK_API_VERSION_1_2) {
            VkPhysicalDeviceProperties2 props2 = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
                .pNext = &driver_props,
            };
            get_props2(devices[i], &props2);
        }
        VkPhysicalDeviceFeatures features;
        memset(&features, 0, sizeof(features));
        get_features(devices[i], &features);

        int has_swapchain = 0;
        uint32_t ext_count = 0;
        if (device_exts(devices[i], NULL, &ext_count, NULL) == VK_SUCCESS && ext_count) {
            VkExtensionProperties *exts = calloc(ext_count, sizeof(*exts));
            if (exts) {
                VkResult r = device_exts(devices[i], NULL, &ext_count, exts);
                if (r == VK_SUCCESS || r == VK_INCOMPLETE) {
                    for (uint32_t e = 0; e < ext_count; e++) {
                        if (strcmp(exts[e].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0) has_swapchain = 1;
                    }
                }
                free(exts);
            }
        }
        /* The injected HAL is a Mesa driver: RADV Xclipse (Samsung Xclipse) or Turnip (Adreno).
         * The vendor's own driver showing up instead (SAMSUNG_PROPRIETARY, QUALCOMM_PROPRIETARY)
         * means the redirect did not take. */
        const int is_injected = is_injected_mesa_device(&driver_props, props.deviceName);
        LOGI("split probe: device %u \"%s\" vendor 0x%04x device 0x%04x api %u.%u.%u driverID %s (%s %s) "
             "textureCompressionBC=%d %s=%s -> %s",
             i, props.deviceName, props.vendorID, props.deviceID, VK_API_VERSION_MAJOR(props.apiVersion),
             VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion),
             driver_props.driverID ? driver_id_name(driver_props.driverID) : "(unknown)",
             driver_props.driverName[0] ? driver_props.driverName : "?",
             driver_props.driverInfo[0] ? driver_props.driverInfo : "",
             (int)features.textureCompressionBC, VK_KHR_SWAPCHAIN_EXTENSION_NAME, has_swapchain ? "yes" : "NO",
             !is_injected ? "not the injected driver" : has_swapchain ? "usable" : "no presentation");
        if (!is_injected) foreign++;
        else if (!has_swapchain) no_swapchain++;
        else usable++;
    }
    if (destroy) destroy(instance, NULL);

    if (!usable) {
        if (foreign) {
            LOGE("split probe: the system loader's devices are not %s (the HAL redirect did not take effect)", driver_path);
        }
        if (no_swapchain) {
            LOGE("split probe: the system loader exposes no %s on %s (its VK_ANDROID_native_buffer was not "
                 "accepted), so nothing could be presented", VK_KHR_SWAPCHAIN_EXTENSION_NAME, driver_path);
        }
        return 0;
    }
    return 1;
}

/* Split mode for the driver at `path`. Returns 1 when it is in use; 0 when split mode is not
 * possible for this driver (the caller may still try it as a direct ICD); -1 when the file
 * itself is unusable. */
static int open_split_driver(const char *path, const char *origin) {
    if (strchr(path, '/')) {
        struct stat st;
        if (stat(path, &st) != 0 || !S_ISREG(st.st_mode) || access(path, R_OK) != 0) {
            LOGE("Fable Vulkan driver %s [%s] does not exist or is not a readable regular file", path, origin);
            return -1;
        }
    }
    /* Same RTLD_NOW reasoning as open_direct_icd: unresolvable dependencies fail here. */
    void *driver = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!driver) {
        LOGE("dlopen(%s) [%s] failed: %s", path, origin, dlerror());
        return -1;
    }
    const struct shim_hw_module_head *hmi = (const struct shim_hw_module_head *)dlsym(driver, "HMI");
    if (!hmi) {
        LOGW("split: %s [%s] does not export HMI, so it is not an Android Vulkan HAL the system loader can "
             "load; split mode unavailable", path, origin);
        return 0;
    }
    if (hmi->tag != SHIM_HARDWARE_MODULE_TAG || !hmi->id || strcmp(hmi->id, "vulkan") != 0) {
        LOGW("split: %s [%s] exports HMI but it is not a Vulkan hw_module_t (tag 0x%08x, id %s); split mode "
             "unavailable", path, origin, hmi->tag, hmi->id ? hmi->id : "(null)");
        return 0;
    }
    LOGI("split: %s [%s] is an Android Vulkan HAL (HMI \"%s\" by %s, module API 0x%04x)", path, origin,
         hmi->name ? hmi->name : "?", hmi->author ? hmi->author : "?", hmi->module_api_version);

    struct fable_hal_inject inject;
    char err[512] = "";
    if (!fable_hal_inject_open(SYSTEM_LOADER_PATH, driver, path, &inject, err, sizeof(err))) {
        LOGE("split: HAL injection into %s failed: %s", SYSTEM_LOADER_PATH, err);
        return 0;
    }

    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(inject.loader, "vkGetInstanceProcAddr");
    PFN_vkEnumerateInstanceExtensionProperties enumerate =
        (PFN_vkEnumerateInstanceExtensionProperties)dlsym(inject.loader, "vkEnumerateInstanceExtensionProperties");
    if (!gipa || !enumerate) {
        LOGE("split: the private %s lacks vkGetInstanceProcAddr / vkEnumerateInstanceExtensionProperties",
             SYSTEM_LOADER_PATH);
        return 0;
    }

    /* First call into the loader: this is where it opens its HAL — through the hooks. */
    VkResult res = VK_SUCCESS;
    int wsi_ok = split_check_instance_extensions(enumerate, &res);
    if (fable_hal_inject_hits() == 0) {
        LOGE("split: the system loader never asked for a vulkan.*.so HAL through the redirected imports "
             "(res=%d); it is not using %s", (int)res, path);
        return 0;
    }
    LOGI("split: the system loader asked for \"%s\" and got %s", fable_hal_inject_requested(), path);
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) {
        LOGE("split: vkEnumerateInstanceExtensionProperties on the system loader with %s as HAL failed, res=%d "
             "(the loader could not open the HAL)", path, (int)res);
        return 0;
    }
    if (!wsi_ok) return 0;

    const char *probe = getenv(FABLE_SPLIT_PROBE_ENV);
    if (probe && strcmp(probe, "0") == 0) {
        LOGW("split: %s=0, skipping the device probe", FABLE_SPLIT_PROBE_ENV);
    } else if (!split_probe_devices(gipa, path)) {
        return 0;
    }

    real.handle = inject.loader;
    real.path = SYSTEM_LOADER_PATH;
    real.direct_icd = SHIM_MODE_SPLIT;
    real.driver_path = strdup(path);
    if (!real.driver_path) real.driver_path = "(fable vulkan driver)";
    bind_loader_exports();
    if (!real.vkGetInstanceProcAddr || !real.vkCreateInstance || !real.vkEnumerateInstanceExtensionProperties) {
        LOGE("split: the private system loader lacks core exports");
        memset(&real, 0, sizeof(real));
        for (int id = 0; id < RP_COUNT; id++) __atomic_store_n(&real_procs[id], NULL, __ATOMIC_RELEASE);
        return 0;
    }

    LOGI("Using split Vulkan (Winlator-style driver injection, from %s):", origin);
    LOGI("  system loader %s (%s): vkCreateInstance, instance extensions, VK_KHR_surface, "
         "VK_KHR_android_surface (Xlib surfaces are translated to it by this shim), surface queries, "
         "VK_KHR_swapchain, vkQueuePresentKHR", SYSTEM_LOADER_PATH, inject.how);
    LOGI("  %s (loaded as the loader's HAL \"%s\"): physical devices, vkCreateDevice, queues, memory, "
         "vkQueueSubmit and all rendering; swapchain images via VK_ANDROID_native_buffer",
         real.driver_path, fable_hal_inject_requested());
    return 1;
}

/* Which custom-driver path FABLE_VULKAN_DRIVER_MODE asks for. */
enum driver_preference { PREFER_SPLIT, PREFER_DIRECT, PREFER_SYSTEM };

static enum driver_preference driver_preference(void) {
    const char *mode = getenv(FABLE_DRIVER_MODE_ENV);
    if (!mode || !*mode || strcmp(mode, "split") == 0 || strcmp(mode, "inject") == 0) return PREFER_SPLIT;
    if (strcmp(mode, "direct") == 0 || strcmp(mode, "icd") == 0) return PREFER_DIRECT;
    if (strcmp(mode, "system") == 0) return PREFER_SYSTEM;
    LOGW("%s=%s is not one of split, direct, system; using split", FABLE_DRIVER_MODE_ENV, mode);
    return PREFER_SPLIT;
}

/* Split mode first (system WSI + custom device), then the driver as a direct ICD (only works
 * for an ICD with its own WSI). Returns 1 when one of them is in use. */
static int open_custom_driver(const char *path, const char *origin, enum driver_preference pref) {
    if (pref == PREFER_SPLIT) {
        int r = open_split_driver(path, origin);
        if (r > 0) return 1;
        if (r < 0) return 0;
        LOGW("split mode unavailable for %s; trying it as a directly opened ICD", path);
    }
    return open_direct_icd(path, origin);
}

/* The configured Fable driver, if any: FABLE_VULKAN_DRIVER (the .so itself) first, then the
 * library_path of the VK_ICD_FILENAMES / VK_DRIVER_FILES manifest. */
static int open_fable_driver(void) {
    const char *driver = getenv(FABLE_DRIVER_ENV);
    const char *icd_files = getenv("VK_ICD_FILENAMES");
    const char *driver_files = getenv("VK_DRIVER_FILES");
    const char *mode = getenv(FABLE_DRIVER_MODE_ENV);
    LOGI("env: %s=%s VK_ICD_FILENAMES=%s VK_DRIVER_FILES=%s %s=%s", FABLE_DRIVER_ENV,
         driver ? driver : "(unset)", icd_files ? icd_files : "(unset)", driver_files ? driver_files : "(unset)",
         FABLE_DRIVER_MODE_ENV, mode ? mode : "(unset: split)");
    const int configured = (driver && *driver) || (icd_files && *icd_files) || (driver_files && *driver_files);
    const enum driver_preference pref = driver_preference();
    if (configured && pref == PREFER_SYSTEM) {
        LOGW("%s=system: ignoring the configured Fable Vulkan driver", FABLE_DRIVER_MODE_ENV);
        return 0;
    }
    if (driver && *driver) {
        if (open_custom_driver(driver, FABLE_DRIVER_ENV, pref)) return 1;
    } else {
        LOGI("%s is not set: no custom Vulkan driver is active in Fable", FABLE_DRIVER_ENV);
    }

    static const char *const manifest_vars[] = { "VK_ICD_FILENAMES", "VK_DRIVER_FILES" };
    for (size_t v = 0; v < sizeof(manifest_vars) / sizeof(manifest_vars[0]); v++) {
        const char *list = getenv(manifest_vars[v]);
        if (!list || !*list) continue;
        char *copy = strdup(list);
        if (!copy) continue;
        char *save = NULL;
        for (char *manifest = strtok_r(copy, ":", &save); manifest; manifest = strtok_r(NULL, ":", &save)) {
            char *library = icd_library_from_manifest(manifest);
            if (!library) continue;
            /* Skip the FABLE_VULKAN_DRIVER path that already failed above. */
            int same = driver && strcmp(library, driver) == 0;
            int ok = !same && open_custom_driver(library, manifest_vars[v], pref);
            free(library);
            if (ok) {
                free(copy);
                return 1;
            }
        }
        free(copy);
    }
    if (configured) {
        LOGW("Fable Vulkan driver configured but not usable; falling back to the system Vulkan loader "
             "(vendor driver, textureCompressionBC forced on)");
    }
    return 0;
}

static int open_system_loader(void) {
    /* Once split mode has mapped its private loader instance, a plain dlopen of the same file
     * would return *that* instance (bionic matches loaded libraries by inode, and the bare
     * SONAME by name), i.e. the one whose HAL is the custom driver. Map a fresh one instead. */
    const int fresh = fable_hal_inject_loader_loaded();
    for (size_t i = 0; i < sizeof(REAL_LOADER_PATHS) / sizeof(REAL_LOADER_PATHS[0]); i++) {
        if (fresh && REAL_LOADER_PATHS[i][0] != '/') continue;
        /* RTLD_LAZY: the loader's own unresolved references must not take the whole process
         * down at load time; RTLD_LOCAL: its symbols stay behind this shim. */
        real.handle = fresh ? fable_dlopen_fresh(REAL_LOADER_PATHS[i], RTLD_NOW | RTLD_LOCAL)
                            : dlopen(REAL_LOADER_PATHS[i], RTLD_LAZY | RTLD_LOCAL);
        if (real.handle) {
            real.path = REAL_LOADER_PATHS[i];
            break;
        }
        LOGW("dlopen(%s)%s failed: %s", REAL_LOADER_PATHS[i], fresh ? " (fresh instance)" : "", dlerror());
    }
    if (!real.handle) {
        LOGE("Could not open the system Vulkan loader (tried /system/lib64/libvulkan.so, libvulkan.so)");
        return 0;
    }
    real.direct_icd = SHIM_MODE_SYSTEM;
    real.driver_path = NULL;
    bind_loader_exports();

    LOGI("Using system Vulkan loader: %s%s (vendor driver; textureCompressionBC forced on)", real.path,
         fresh ? " (fresh instance, separate from split mode's)" : "");
    return 1;
}

static void log_shim_loaded(void) {
    Dl_info self;
    const char *ld_path = getenv("LD_LIBRARY_PATH");
    if (dladdr((void *)&log_shim_loaded, &self) && self.dli_fname) {
        LOGI("libvulkan.so.1 shim loaded from %s (pid %d)", self.dli_fname, (int)getpid());
    } else {
        LOGI("libvulkan.so.1 shim loaded (pid %d)", (int)getpid());
    }
    LOGI("env: LD_LIBRARY_PATH=%s", ld_path ? ld_path : "(unset)");
}

static void shim_init(void) {
    LOGI("selecting the Vulkan implementation (first Vulkan call, pid %d)", (int)getpid());
    if (!open_fable_driver() && !open_system_loader()) {
        LOGE("No Vulkan loader found; every forwarded call will fail");
        return;
    }

    LOGI("Wrapping %s%s%s (mode %s, vkGetInstanceProcAddr %p, vkCreateXlibSurfaceKHR %s, "
         "vkGetPhysicalDeviceXlibPresentationSupportKHR %s)",
         real.path, real.driver_path ? " + " : "", real.driver_path ? real.driver_path : "",
         real.direct_icd == SHIM_MODE_SPLIT ? "split" : real.direct_icd == SHIM_MODE_DIRECT_ICD ? "direct ICD" : "system",
         (void *)real.vkGetInstanceProcAddr,
         real.vkCreateXlibSurfaceKHR ? "forwarded" : "via vkGetInstanceProcAddr",
         real.vkGetPhysicalDeviceXlibPresentationSupportKHR ? "forwarded" : "stubbed (VK_TRUE)");
}

static inline void ensure_init(void) {
    pthread_once(&shim_once, shim_init);
}

/* Only announces the shim: driver selection (which may create a probe instance) waits for the
 * first Vulkan call, outside the dynamic linker's dlopen of this library. */
__attribute__((constructor))
static void vulkan_shim_constructor(void) {
    log_shim_loaded();
}

/* Instance-level lookup in the real implementation for an entry point it doesn't export as a
 * plain symbol (extension functions on some loaders; everything on a direct ICD). NULL when
 * there is no way to get it. */
static PFN_vkVoidFunction real_instance_proc(VkInstance instance, const char *name) {
    if (!real.vkGetInstanceProcAddr) return NULL;
    return real.vkGetInstanceProcAddr(instance, name);
}

/* --- Exported entry points ---------------------------------------------------------------- */

VK_SHIM_EXPORT VkBool32 VKAPI_CALL vkGetPhysicalDeviceXlibPresentationSupportKHR(
    VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex, XlibDisplay *dpy, XlibVisualID visualID) {
    ensure_init();
    if (real.vkGetPhysicalDeviceXlibPresentationSupportKHR) {
        return real.vkGetPhysicalDeviceXlibPresentationSupportKHR(physicalDevice, queueFamilyIndex, dpy, visualID);
    }
    /* Android's loader has no Xlib WSI query. Report support so winex11's VulkanInit
     * succeeds and winevulkan accepts the queue family for presentation. */
    (void)physicalDevice;
    (void)queueFamilyIndex;
    (void)dpy;
    (void)visualID;
    return VK_TRUE;
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkCreateXlibSurfaceKHR(VkInstance instance,
                                                         const VkXlibSurfaceCreateInfoKHR *pCreateInfo,
                                                         const VkAllocationCallbacks *pAllocator,
                                                         VkSurfaceKHR *pSurface) {
    ensure_init();
    if (!pCreateInfo || !pSurface) return VK_ERROR_INITIALIZATION_FAILED;
    *pSurface = VK_NULL_HANDLE;
    PFN_vkCreateAndroidSurfaceKHR fn =
        (PFN_vkCreateAndroidSurfaceKHR)real_instance_proc(instance, "vkCreateAndroidSurfaceKHR");
    if (!fn) return VK_ERROR_EXTENSION_NOT_PRESENT;
    struct AndroidSurfaceBridge *bridge = android_bridge_create((uint32_t)pCreateInfo->window);
    if (!bridge) {
        LOGE("vkCreateXlibSurfaceKHR: cannot connect window %lu to the display bridge",
             pCreateInfo->window);
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    const VkAndroidSurfaceCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = android_bridge_window(bridge),
    };
    VkResult result = fn(instance, &info, pAllocator, pSurface);
    if (result == VK_SUCCESS) android_bridge_attach(bridge, *pSurface);
    else android_bridge_delete(bridge);
    return result;
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
    VkPhysicalDevice device, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR *caps) {
    ensure_init();
    if (!android_bridge_refresh(surface)) return VK_ERROR_SURFACE_LOST_KHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR fn =
        (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)real_proc(RP_vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
    return fn ? fn(device, surface, caps) : VK_ERROR_INITIALIZATION_FAILED;
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceCapabilities2KHR(
    VkPhysicalDevice device, const VkPhysicalDeviceSurfaceInfo2KHR *info, VkSurfaceCapabilities2KHR *caps) {
    ensure_init();
    /* Android WSI has no extra capabilities to append; retain the caller's output chain. */
    if (!info || !caps) return VK_ERROR_INITIALIZATION_FAILED;
    return vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, info->surface, &caps->surfaceCapabilities);
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceFormatsKHR(
    VkPhysicalDevice device, VkSurfaceKHR surface, uint32_t *count, VkSurfaceFormatKHR *formats) {
    ensure_init();
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR fn =
        (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)real_proc(RP_vkGetPhysicalDeviceSurfaceFormatsKHR);
    if (!fn || !count) return VK_ERROR_INITIALIZATION_FAILED;
    if (!android_bridge_contains(surface)) return fn(device, surface, count, formats);
    /* ImageReader's consumer is RGBA_8888. A BGRA swapchain would change the producer's HAL
     * format and ImageReader would reject every buffer, despite a successful present. */
    for (int attempt = 0; attempt < 4; ++attempt) {
        uint32_t n = 0;
        VkResult result = fn(device, surface, &n, NULL);
        if (result != VK_SUCCESS) return result;
        VkSurfaceFormatKHR *all = calloc(n ? n : 1, sizeof(*all));
        if (!all) return VK_ERROR_OUT_OF_HOST_MEMORY;
        result = fn(device, surface, &n, all);
        if (result == VK_INCOMPLETE) { free(all); continue; }
        if (result != VK_SUCCESS) { free(all); return result; }
        uint32_t available = 0, written = 0, capacity = formats ? *count : 0;
        for (uint32_t i = 0; i < n; ++i) {
            if (all[i].format != VK_FORMAT_R8G8B8A8_UNORM &&
                all[i].format != VK_FORMAT_R8G8B8A8_SRGB) continue;
            ++available;
            if (formats && written < capacity) formats[written++] = all[i];
        }
        free(all);
        *count = formats ? written : available;
        if (!available) return VK_ERROR_FORMAT_NOT_SUPPORTED;
        return formats && written < available ? VK_INCOMPLETE : VK_SUCCESS;
    }
    return VK_ERROR_INITIALIZATION_FAILED;
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceFormats2KHR(
    VkPhysicalDevice device, const VkPhysicalDeviceSurfaceInfo2KHR *info, uint32_t *count,
    VkSurfaceFormat2KHR *formats) {
    if (!info || !count) return VK_ERROR_INITIALIZATION_FAILED;
    if (!formats) return vkGetPhysicalDeviceSurfaceFormatsKHR(device, info->surface, count, NULL);
    uint32_t capacity = *count;
    VkSurfaceFormatKHR *plain = calloc(capacity ? capacity : 1, sizeof(*plain));
    if (!plain) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkResult result = vkGetPhysicalDeviceSurfaceFormatsKHR(device, info->surface, count, plain);
    if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
        for (uint32_t i = 0; i < *count; ++i) formats[i].surfaceFormat = plain[i];
    }
    free(plain);
    return result;
}

/* --- Instance extension masquerade -------------------------------------------------------- */

/* Extension names winevulkan's X11 driver asks the host for and Android's loader lacks. The
 * shim advertises the first (the one winex11 reports as its host surface extension) and
 * replaces both with Android WSI at instance creation. */
#define XLIB_SURFACE_EXTENSION_NAME "VK_KHR_xlib_surface"
#define XCB_SURFACE_EXTENSION_NAME "VK_KHR_xcb_surface"
#define XLIB_SURFACE_SPEC_VERSION 6

static int is_x11_surface_extension(const char *name) {
    return name && (strcmp(name, XLIB_SURFACE_EXTENSION_NAME) == 0 || strcmp(name, XCB_SURFACE_EXTENSION_NAME) == 0);
}

/* Copies `name` into a VkExtensionProperties the way the loader does: NUL-terminated,
 * truncated at VK_MAX_EXTENSION_NAME_SIZE - 1. */
static void fill_extension(VkExtensionProperties *out, const char *name, uint32_t spec_version) {
    memset(out, 0, sizeof(*out));
    strncpy(out->extensionName, name, VK_MAX_EXTENSION_NAME_SIZE - 1);
    out->specVersion = spec_version;
}

/* Fetches the real loader's full instance extension list for `layer` into a malloc'd array
 * (*out_props, *out_count). Retries on VK_INCOMPLETE because layers/ICDs can appear between
 * the count and the fill call. Returns VK_SUCCESS with *out_props == NULL when the list is
 * empty. The caller frees *out_props. */
static VkResult fetch_real_extensions(const char *layer, VkExtensionProperties **out_props, uint32_t *out_count) {
    *out_props = NULL;
    *out_count = 0;
    if (!real.vkEnumerateInstanceExtensionProperties) return VK_ERROR_INITIALIZATION_FAILED;

    for (int attempt = 0; attempt < 4; attempt++) {
        uint32_t count = 0;
        VkResult res = real.vkEnumerateInstanceExtensionProperties(layer, &count, NULL);
        if (res != VK_SUCCESS) return res;
        if (count == 0) return VK_SUCCESS;

        VkExtensionProperties *props = calloc(count, sizeof(*props));
        if (!props) return VK_ERROR_OUT_OF_HOST_MEMORY;
        res = real.vkEnumerateInstanceExtensionProperties(layer, &count, props);
        if (res == VK_SUCCESS) {
            *out_props = props;
            *out_count = count;
            return VK_SUCCESS;
        }
        free(props);
        if (res != VK_INCOMPLETE) return res;
    }
    return VK_ERROR_INITIALIZATION_FAILED;
}

static int real_has_extension(const VkExtensionProperties *props, uint32_t count, const char *name) {
    for (uint32_t i = 0; i < count; i++) {
        if (strncmp(props[i].extensionName, name, VK_MAX_EXTENSION_NAME_SIZE) == 0) return 1;
    }
    return 0;
}

/* The real loader's list plus VK_KHR_xlib_surface. Only the unlayered (pLayerName == NULL)
 * list is augmented; a layer's own extension list is forwarded untouched. Follows the Vulkan
 * two-call contract: NULL pProperties returns the count, a short buffer gets VK_INCOMPLETE. */
VK_SHIM_EXPORT VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char *pLayerName,
                                                                         uint32_t *pPropertyCount,
                                                                         VkExtensionProperties *pProperties) {
    ensure_init();
    if (!pPropertyCount) return VK_ERROR_INITIALIZATION_FAILED;
    if (pLayerName) {
        if (!real.vkEnumerateInstanceExtensionProperties) return VK_ERROR_LAYER_NOT_PRESENT;
        return real.vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);
    }

    VkExtensionProperties *props = NULL;
    uint32_t count = 0;
    VkResult res = fetch_real_extensions(NULL, &props, &count);
    if (res != VK_SUCCESS) {
        LOGE("vkEnumerateInstanceExtensionProperties: real loader failed, res=%d", (int)res);
        return res;
    }

    const int add_xlib = !real_has_extension(props, count, XLIB_SURFACE_EXTENSION_NAME);
    const uint32_t total = count + (add_xlib ? 1u : 0u);

    if (!pProperties) {
        *pPropertyCount = total;
        free(props);
        return VK_SUCCESS;
    }

    const uint32_t capacity = *pPropertyCount;
    uint32_t written = 0;
    for (uint32_t i = 0; i < count && written < capacity; i++) pProperties[written++] = props[i];
    if (add_xlib && written < capacity) {
        fill_extension(&pProperties[written++], XLIB_SURFACE_EXTENSION_NAME, XLIB_SURFACE_SPEC_VERSION);
    }
    free(props);

    *pPropertyCount = written;
    return written < total ? VK_INCOMPLETE : VK_SUCCESS;
}

/* Number of instances successfully created; once non-zero the implementation is pinned. */
static int instances_created;

/* Names every requested instance extension the current implementation does not offer. */
static void log_missing_extensions(const VkInstanceCreateInfo *info) {
    VkExtensionProperties *props = NULL;
    uint32_t count = 0;
    if (fetch_real_extensions(NULL, &props, &count) != VK_SUCCESS) return;
    for (uint32_t i = 0; i < info->enabledExtensionCount; i++) {
        const char *name = info->ppEnabledExtensionNames[i];
        if (name && !real_has_extension(props, count, name)) {
            LOGE("vkCreateInstance: %s does not support instance extension %s", real.path, name);
        }
    }
    free(props);
}

static const char *mode_label(void) {
    switch (real.direct_icd) {
    case SHIM_MODE_SPLIT: return "split: system loader instance + WSI, custom driver devices";
    case SHIM_MODE_DIRECT_ICD: return "direct ICD";
    default: return "system loader";
    }
}

/* Drops a direct ICD or split mode's loader instance (both left mapped: the driver has already
 * run code and Mesa is not written to be unloaded mid-process) and re-initialises on Android's
 * system loader. Only valid while no instance exists on them. Returns 1 on success. */
static int switch_to_system_loader(void) {
    memset(&real, 0, sizeof(real));
    for (int id = 0; id < RP_COUNT; id++) __atomic_store_n(&real_procs[id], NULL, __ATOMIC_RELEASE);
    if (!open_system_loader()) {
        LOGE("System Vulkan loader unavailable as well; Vulkan will not work");
        return 0;
    }
    return 1;
}

/* Forwards instance creation with Android WSI in place of the X11 surface extensions.
 * satisfy. If the loader still rejects the request, names the extensions it is missing so the
 * Wine log says *which* one, not just res=-7. */
VK_SHIM_EXPORT VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *pCreateInfo,
                                                   const VkAllocationCallbacks *pAllocator,
                                                   VkInstance *pInstance) {
    ensure_init();
    if (!real.vkCreateInstance) {
        LOGE("vkCreateInstance: no Vulkan loader");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (!pCreateInfo) return real.vkCreateInstance(pCreateInfo, pAllocator, pInstance);

    const uint32_t requested = pCreateInfo->enabledExtensionCount;
    const char *const *names = pCreateInfo->ppEnabledExtensionNames;

    uint32_t stripped = 0;
    for (uint32_t i = 0; i < requested; i++) {
        if (is_x11_surface_extension(names[i])) stripped++;
    }

    VkInstanceCreateInfo info = *pCreateInfo;
    const char **filtered = NULL;
    if (stripped) {
        filtered = calloc(requested + 2, sizeof(*filtered));
        if (!filtered) return VK_ERROR_OUT_OF_HOST_MEMORY;
        uint32_t n = 0;
        int has_android = 0, has_surface = 0;
        for (uint32_t i = 0; i < requested; i++) {
            if (is_x11_surface_extension(names[i])) {
                LOGI("vkCreateInstance: dropping %s (not an Android loader extension; the shim provides it)", names[i]);
                continue;
            }
            if (strcmp(names[i], VK_KHR_ANDROID_SURFACE_EXTENSION_NAME) == 0) has_android = 1;
            if (strcmp(names[i], VK_KHR_SURFACE_EXTENSION_NAME) == 0) has_surface = 1;
            filtered[n++] = names[i];
        }
        if (!has_android) filtered[n++] = VK_KHR_ANDROID_SURFACE_EXTENSION_NAME;
        if (!has_surface) filtered[n++] = VK_KHR_SURFACE_EXTENSION_NAME;
        info.enabledExtensionCount = n;
        info.ppEnabledExtensionNames = filtered;
    }

    {
        char list[1024];
        size_t used = 0;
        list[0] = '\0';
        for (uint32_t i = 0; i < info.enabledExtensionCount; i++) {
            const char *name = info.ppEnabledExtensionNames[i];
            int w = snprintf(list + used, sizeof(list) - used, "%s%s", used ? " " : "", name ? name : "(null)");
            if (w > 0 && (size_t)w < sizeof(list) - used) used += (size_t)w;
        }
        LOGI("vkCreateInstance on %s%s%s (%s): %u extensions requested, forwarding %u: %s", real.path,
             real.driver_path ? " + " : "", real.driver_path ? real.driver_path : "", mode_label(), requested,
             info.enabledExtensionCount, list);
    }

    VkResult res = real.vkCreateInstance(&info, pAllocator, pInstance);

    if (res == VK_ERROR_EXTENSION_NOT_PRESENT) log_missing_extensions(&info);
    /* Last line of defence for a custom path (direct ICD, or split mode) that passed the
     * selection-time probe but still rejects the request: as long as no instance lives on it,
     * swap in the system loader and retry. */
    if ((res == VK_ERROR_EXTENSION_NOT_PRESENT || res == VK_ERROR_INCOMPATIBLE_DRIVER ||
         res == VK_ERROR_INITIALIZATION_FAILED) &&
        real.direct_icd != SHIM_MODE_SYSTEM && __atomic_load_n(&instances_created, __ATOMIC_ACQUIRE) == 0) {
        LOGE("vkCreateInstance: %s%s%s (%s) rejected the instance (res=%d); retrying on the system Vulkan loader",
             real.path, real.driver_path ? " + " : "", real.driver_path ? real.driver_path : "", mode_label(), (int)res);
        if (switch_to_system_loader()) {
            res = real.vkCreateInstance(&info, pAllocator, pInstance);
            if (res == VK_ERROR_EXTENSION_NOT_PRESENT) log_missing_extensions(&info);
        }
    }

    if (res == VK_SUCCESS) {
        __atomic_add_fetch(&instances_created, 1, __ATOMIC_ACQ_REL);
        cache_instance_procs(*pInstance);
        LOGI("vkCreateInstance: instance created on %s (%s; %u extensions requested, %u forwarded)",
             real.path, mode_label(), requested, info.enabledExtensionCount);
        if (real.direct_icd == SHIM_MODE_SPLIT) {
            LOGI("vkCreateInstance: WSI from %s, physical devices / VkDevice from %s", real.path, real.driver_path);
        }
    } else {
        LOGE("vkCreateInstance: failed on %s, res=%d%s", real.path ? real.path : "(no loader)", (int)res,
             res == VK_ERROR_EXTENSION_NOT_PRESENT ? " (VK_ERROR_EXTENSION_NOT_PRESENT)" :
             res == VK_ERROR_INCOMPATIBLE_DRIVER ? " (VK_ERROR_INCOMPATIBLE_DRIVER)" :
             res == VK_ERROR_INITIALIZATION_FAILED ? " (VK_ERROR_INITIALIZATION_FAILED)" :
             res == VK_ERROR_LAYER_NOT_PRESENT ? " (VK_ERROR_LAYER_NOT_PRESENT)" : "");
    }

    free(filtered);
    return res;
}

/* Forward declarations for the physical-device feature wrappers (defined below
 * vkGetInstanceProcAddr). SHIM_PROC references them by name, so they must be
 * declared before that point even though they are defined later. */
VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures(VkPhysicalDevice physicalDevice,
                                                           VkPhysicalDeviceFeatures *pFeatures);
VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice,
                                                            VkPhysicalDeviceFeatures2 *pFeatures);
VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2KHR(VkPhysicalDevice physicalDevice,
                                                               VkPhysicalDeviceFeatures2 *pFeatures);
VK_SHIM_EXPORT VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice physicalDevice,
                                                 const VkDeviceCreateInfo *pCreateInfo,
                                                 const VkAllocationCallbacks *pAllocator, VkDevice *pDevice);

VK_SHIM_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    ensure_init();
    if (!pName) return NULL;
    /* Entry points the shim must own even though the real loader exports them too: these are
     * what make VK_KHR_xlib_surface look like an instance extension to winevulkan. */
    if (strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0) {
        return (PFN_vkVoidFunction)vkEnumerateInstanceExtensionProperties;
    }
    if (strcmp(pName, "vkCreateInstance") == 0) {
        return (PFN_vkVoidFunction)vkCreateInstance;
    }
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    }
    /* Always return our WSI functions, including the real loader's otherwise valid surface
     * queries/destructor. Returning its destructor first leaks the ImageReader and socket. */
#define SHIM_PROC(name) if (strcmp(pName, #name) == 0) return (PFN_vkVoidFunction)name
    SHIM_PROC(vkCreateXlibSurfaceKHR);
    SHIM_PROC(vkGetPhysicalDeviceXlibPresentationSupportKHR);
    SHIM_PROC(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
    SHIM_PROC(vkGetPhysicalDeviceSurfaceCapabilities2KHR);
    SHIM_PROC(vkGetPhysicalDeviceSurfaceFormatsKHR);
    SHIM_PROC(vkGetPhysicalDeviceSurfaceFormats2KHR);
    SHIM_PROC(vkGetPhysicalDeviceFeatures);
    SHIM_PROC(vkGetPhysicalDeviceFeatures2);
    SHIM_PROC(vkGetPhysicalDeviceFeatures2KHR);
    SHIM_PROC(vkCreateDevice);
    /* Defined below, also needed by Wine's direct dlsym lookup. */
    extern VK_SHIM_EXPORT void VKAPI_CALL vkDestroySurfaceKHR(VkInstance, VkSurfaceKHR, const VkAllocationCallbacks *);
    SHIM_PROC(vkDestroySurfaceKHR);
#undef SHIM_PROC
    PFN_vkVoidFunction fn = real_instance_proc(instance, pName);
    if (fn) return fn;
    /* The real loader doesn't know the Xlib entry points; hand out this shim's. */
    if (strcmp(pName, "vkGetPhysicalDeviceXlibPresentationSupportKHR") == 0) {
        return (PFN_vkVoidFunction)vkGetPhysicalDeviceXlibPresentationSupportKHR;
    }
    if (strcmp(pName, "vkCreateXlibSurfaceKHR") == 0) {
        return (PFN_vkVoidFunction)vkCreateXlibSurfaceKHR;
    }
    return NULL;
}

VK_SHIM_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *pName) {
    ensure_init();
    PFN_vkGetDeviceProcAddr fn = (PFN_vkGetDeviceProcAddr)real_proc(RP_vkGetDeviceProcAddr);
    if (!fn) return NULL;
    return fn(device, pName);
}

VK_SHIM_EXPORT void VKAPI_CALL vkDestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                                                   const VkAllocationCallbacks *pAllocator) {
    ensure_init();
    PFN_vkDestroySurfaceKHR fn = real.vkDestroySurfaceKHR;
    if (!fn) fn = (PFN_vkDestroySurfaceKHR)real_proc(RP_vkDestroySurfaceKHR);
    if (!fn) fn = (PFN_vkDestroySurfaceKHR)real_instance_proc(instance, "vkDestroySurfaceKHR");
    if (!fn) {
        LOGE("vkDestroySurfaceKHR: not available in the Vulkan loader");
        return;
    }
    fn(instance, surface, pAllocator);
    android_bridge_destroy_surface(surface);
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
    ensure_init();
    PFN_vkQueuePresentKHR fn = real.vkQueuePresentKHR;
    if (!fn) fn = (PFN_vkQueuePresentKHR)real_proc(RP_vkQueuePresentKHR);
    if (!fn) {
        LOGE("vkQueuePresentKHR: not available in the Vulkan loader");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return fn(queue, pPresentInfo);
}

/* --- Device creation (diagnostics only) -----------------------------------------------------
 *
 * Forwards unchanged; logs which device (and therefore which driver) DXVK got, whether it asked
 * for textureCompressionBC, and the result — the line that tells "RADV device" from "Samsung
 * device" in the Wine log. */

static const VkPhysicalDeviceFeatures *requested_features(const VkDeviceCreateInfo *info) {
    if (info->pEnabledFeatures) return info->pEnabledFeatures;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2) {
            return &((const VkPhysicalDeviceFeatures2 *)p)->features;
        }
    }
    return NULL;
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice physicalDevice,
                                                 const VkDeviceCreateInfo *pCreateInfo,
                                                 const VkAllocationCallbacks *pAllocator, VkDevice *pDevice) {
    ensure_init();
    PFN_vkCreateDevice fn = (PFN_vkCreateDevice)real_proc(RP_vkCreateDevice);
    if (!fn) {
        LOGE("vkCreateDevice: not available in %s", real.path ? real.path : "(no loader)");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult res = fn(physicalDevice, pCreateInfo, pAllocator, pDevice);

    VkPhysicalDeviceProperties props;
    memset(&props, 0, sizeof(props));
    PFN_vkGetPhysicalDeviceProperties get_props =
        (PFN_vkGetPhysicalDeviceProperties)real_proc(RP_vkGetPhysicalDeviceProperties);
    if (get_props) get_props(physicalDevice, &props);
    const VkPhysicalDeviceFeatures *features = pCreateInfo ? requested_features(pCreateInfo) : NULL;
    LOGI("vkCreateDevice on \"%s\" (vendor 0x%04x device 0x%04x) via %s%s%s [%s]: %u extensions, "
         "textureCompressionBC %s -> res=%d",
         props.deviceName[0] ? props.deviceName : "?", props.vendorID, props.deviceID,
         real.direct_icd == SHIM_MODE_SPLIT ? real.driver_path : real.path,
         real.direct_icd == SHIM_MODE_SPLIT ? " through " : "",
         real.direct_icd == SHIM_MODE_SPLIT ? real.path : "", mode_label(),
         pCreateInfo ? pCreateInfo->enabledExtensionCount : 0,
         !features ? "not requested" : features->textureCompressionBC ? "requested" : "not requested", (int)res);
    return res;
}

/* --- Physical device feature override -----------------------------------------------------
 *
 * Samsung's proprietary Xclipse (RDNA2) Vulkan driver does not report textureCompressionBC
 * even though the hardware supports BC1–BC7 in silicon. DXVK 2.x+ requires that feature as a
 * hard gate in its device filter, so without it DXVK finds zero usable adapters and every
 * Direct3D 9/10/11 application fails at D3D11CreateDevice.
 *
 * The shim wraps vkGetPhysicalDeviceFeatures / vkGetPhysicalDeviceFeatures2[KHR] and sets
 * textureCompressionBC = VK_TRUE after the real driver answers. Only that one bit is touched;
 * every other feature keeps the driver's own value, so nothing else changes for DXVK or any
 * other Vulkan consumer. Because the hardware genuinely supports block-compressed texture
 * formats, games that rely on them (ULTRAKILL, the d3d11-test tool, most Unity titles) render
 * correctly once the gate is lifted.
 *
 * Only on the system loader (and a direct ICD). In split mode the devices are RADV's, which
 * reports the real value.
 */

static void force_texture_compression_bc(VkPhysicalDeviceFeatures *features) {
    static int logged;
    if (real.direct_icd == SHIM_MODE_SPLIT) {
        /* RADV (RDNA2) and Turnip (Adreno) answer truthfully; report it once, never override it. Forcing it would
         * only move the failure to vkCreateDevice (VK_ERROR_FEATURE_NOT_PRESENT). */
        if (features && !__atomic_exchange_n(&logged, 1, __ATOMIC_ACQ_REL)) {
            LOGI("vkGetPhysicalDeviceFeatures: %s reports textureCompressionBC = %d (split mode, not overridden)",
                 real.driver_path, (int)features->textureCompressionBC);
        }
        return;
    }
    if (features && !features->textureCompressionBC) {
        features->textureCompressionBC = VK_TRUE;
        if (!__atomic_exchange_n(&logged, 1, __ATOMIC_ACQ_REL)) {
            LOGI("vkGetPhysicalDeviceFeatures: forcing textureCompressionBC = VK_TRUE (RDNA2 hardware supports it; driver omits it)");
        }
    }
}

static void force_texture_compression_bc2(VkPhysicalDeviceFeatures2 *features2) {
    if (!features2) return;
    force_texture_compression_bc(&features2->features);
    /* A VkPhysicalDeviceVulkan12Features or similar struct in the pNext chain could also gate
     * DXVK; walk the chain for the one that carries imageCompressionControl or any future
     * feature DXVK might read. Nothing currently required beyond the base struct. */
}

VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures(VkPhysicalDevice physicalDevice,
                                                           VkPhysicalDeviceFeatures *pFeatures) {
    ensure_init();
    PFN_vkGetPhysicalDeviceFeatures fn =
        (PFN_vkGetPhysicalDeviceFeatures)real_proc(RP_vkGetPhysicalDeviceFeatures);
    if (!fn || !pFeatures) return;
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc(pFeatures);
}

VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice,
                                                            VkPhysicalDeviceFeatures2 *pFeatures) {
    ensure_init();
    PFN_vkGetPhysicalDeviceFeatures2 fn =
        (PFN_vkGetPhysicalDeviceFeatures2)real_proc(RP_vkGetPhysicalDeviceFeatures2);
    if (!fn || !pFeatures) return;
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc2(pFeatures);
}

VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2KHR(VkPhysicalDevice physicalDevice,
                                                               VkPhysicalDeviceFeatures2 *pFeatures) {
    ensure_init();
    PFN_vkGetPhysicalDeviceFeatures2KHR fn =
        (PFN_vkGetPhysicalDeviceFeatures2KHR)real_proc(RP_vkGetPhysicalDeviceFeatures2KHR);
    if (!fn || !pFeatures) {
        /* Fall back to the core entry point (Vulkan 1.1 loaders expose both names). */
        vkGetPhysicalDeviceFeatures2(physicalDevice, pFeatures);
        return;
    }
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc2(pFeatures);
}
