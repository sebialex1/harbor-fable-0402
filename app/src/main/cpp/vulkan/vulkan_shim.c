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
 * Which Vulkan implementation sits behind the shim:
 *
 *   FABLE_VULKAN_DRIVER set  the ICD .so it names (the adrenotools-installed RADV Xclipse
 *                            vulkan.radeon.so) is dlopen'ed directly and the shim acts as the
 *                            loader. Android's loader can't be used for it: it ignores
 *                            VK_ICD_FILENAMES and always picks the vendor (Samsung) driver,
 *                            which hides textureCompressionBC so DXVK finds no adapter.
 *   VK_ICD_FILENAMES /       secondary: library_path from the JSON manifest, same handling.
 *   VK_DRIVER_FILES
 *   otherwise / on failure   Android's system loader, as before.
 *
 * The log says which: "Using Fable Vulkan driver: <path>" or "Using system Vulkan loader: <path>".
 *
 * Only vk* symbols are exported (-fvisibility=hidden + VK_SHIM_EXPORT).
 */
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#include <android/native_window.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_android.h>

#include "android_surface_bridge.h"

#define VK_SHIM_EXPORT __attribute__((visibility("default")))

#define LOG_TAG "vulkan_shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
 * Two kinds of implementation are supported:
 *   - the Android system loader (libvulkan.so): exports every core entry point, so plain dlsym
 *     works for all of them;
 *   - a Vulkan ICD opened directly (direct_icd = 1), e.g. the RADV Xclipse vulkan.radeon.so
 *     named by FABLE_VULKAN_DRIVER: ICDs only promise vk_icdGetInstanceProcAddr, so every other
 *     entry point is resolved through it (globals with a NULL instance, the rest once an
 *     instance exists, see cache_instance_procs()). */
static struct {
    void *handle;
    const char *path;
    int direct_icd;
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
    if (!real.direct_icd) {
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
    if (!fn && real.direct_icd) {
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
    /* RTLD_NOW: an ICD with unresolvable dependencies must fail here, where the shim can still
     * fall back to the system loader, not on first call. */
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGW("dlopen(%s) [%s] failed: %s", path, origin, dlerror());
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

    real.handle = handle;
    real.path = strdup(path);
    if (!real.path) real.path = "(fable vulkan driver)";
    real.direct_icd = 1;
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

/* The configured Fable driver, if any: FABLE_VULKAN_DRIVER (the .so itself) first, then the
 * library_path of the VK_ICD_FILENAMES / VK_DRIVER_FILES manifest. */
static int open_fable_driver(void) {
    const char *driver = getenv(FABLE_DRIVER_ENV);
    if (driver && *driver) {
        if (open_direct_icd(driver, FABLE_DRIVER_ENV)) return 1;
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
            int ok = !same && open_direct_icd(library, manifest_vars[v]);
            free(library);
            if (ok) {
                free(copy);
                return 1;
            }
        }
        free(copy);
    }
    if ((driver && *driver) || getenv("VK_ICD_FILENAMES") || getenv("VK_DRIVER_FILES")) {
        LOGW("Fable Vulkan driver configured but not usable; falling back to the system Vulkan loader");
    }
    return 0;
}

static int open_system_loader(void) {
    for (size_t i = 0; i < sizeof(REAL_LOADER_PATHS) / sizeof(REAL_LOADER_PATHS[0]); i++) {
        /* RTLD_LAZY: the loader's own unresolved references must not take the whole process
         * down at load time; RTLD_LOCAL: its symbols stay behind this shim. */
        real.handle = dlopen(REAL_LOADER_PATHS[i], RTLD_LAZY | RTLD_LOCAL);
        if (real.handle) {
            real.path = REAL_LOADER_PATHS[i];
            break;
        }
        LOGW("dlopen(%s) failed: %s", REAL_LOADER_PATHS[i], dlerror());
    }
    if (!real.handle) return 0;
    real.direct_icd = 0;

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

    LOGI("Using system Vulkan loader: %s", real.path);
    return 1;
}

static void shim_init(void) {
    if (!open_fable_driver() && !open_system_loader()) {
        LOGE("No Vulkan loader found; every forwarded call will fail");
        return;
    }

    LOGI("Wrapping %s (vkGetInstanceProcAddr %p, vkCreateXlibSurfaceKHR %s, "
         "vkGetPhysicalDeviceXlibPresentationSupportKHR %s)",
         real.path, (void *)real.vkGetInstanceProcAddr,
         real.vkCreateXlibSurfaceKHR ? "forwarded" : "via vkGetInstanceProcAddr",
         real.vkGetPhysicalDeviceXlibPresentationSupportKHR ? "forwarded" : "stubbed (VK_TRUE)");
}

static inline void ensure_init(void) {
    pthread_once(&shim_once, shim_init);
}

__attribute__((constructor))
static void vulkan_shim_constructor(void) {
    ensure_init();
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

    VkResult res = real.vkCreateInstance(&info, pAllocator, pInstance);

    if (res == VK_ERROR_EXTENSION_NOT_PRESENT) {
        VkExtensionProperties *props = NULL;
        uint32_t count = 0;
        if (fetch_real_extensions(NULL, &props, &count) == VK_SUCCESS) {
            for (uint32_t i = 0; i < info.enabledExtensionCount; i++) {
                const char *name = info.ppEnabledExtensionNames[i];
                if (name && !real_has_extension(props, count, name)) {
                    LOGE("vkCreateInstance: %s does not support instance extension %s", real.path, name);
                }
            }
            free(props);
        }
    } else if (res != VK_SUCCESS) {
        LOGE("vkCreateInstance: real loader failed, res=%d", (int)res);
    } else {
        cache_instance_procs(*pInstance);
        LOGI("vkCreateInstance: instance created on %s (%u extensions requested, %u forwarded)",
             real.path, requested, info.enabledExtensionCount);
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
 */

static void force_texture_compression_bc(VkPhysicalDeviceFeatures *features) {
    if (features && !features->textureCompressionBC) {
        features->textureCompressionBC = VK_TRUE;
        LOGI("vkGetPhysicalDeviceFeatures: forcing textureCompressionBC = VK_TRUE (RDNA2 hardware supports it; driver omits it)");
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
