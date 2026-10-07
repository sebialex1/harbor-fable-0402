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
 * Every other Vulkan function is reached through the forwarded
 * vkGetInstanceProcAddr / vkGetDeviceProcAddr, so nothing else is wrapped.
 *
 * Only vk* symbols are exported (-fvisibility=hidden + VK_SHIM_EXPORT).
 */
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <android/log.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#define VK_SHIM_EXPORT __attribute__((visibility("default")))

#define LOG_TAG "vulkan_shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Where Android keeps its Vulkan loader; tried first, then the plain SONAME (which the
 * linker resolves through LD_LIBRARY_PATH / the default system directories). */
static const char *const REAL_LOADER_PATHS[] = {
    "/system/lib64/libvulkan.so",
    "libvulkan.so",
};

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

/* The real loader and the entry points forwarded to it. Written once by shim_init() under
 * pthread_once and read-only afterwards, which is what makes the forwarders thread-safe. */
static struct {
    void *handle;
    const char *path;
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr vkGetDeviceProcAddr;
    PFN_vkDestroySurfaceKHR vkDestroySurfaceKHR;
    PFN_vkQueuePresentKHR vkQueuePresentKHR;
    PFN_vkCreateXlibSurfaceKHR vkCreateXlibSurfaceKHR;
    PFN_vkGetPhysicalDeviceXlibPresentationSupportKHR vkGetPhysicalDeviceXlibPresentationSupportKHR;
} real;

static pthread_once_t shim_once = PTHREAD_ONCE_INIT;

static void *load_real(const char *name, int required) {
    void *fn = dlsym(real.handle, name);
    if (!fn) {
        if (required) LOGE("%s does not export %s", real.path, name);
        else LOGI("%s does not export %s (forwarded through vkGetInstanceProcAddr, or stubbed)", real.path, name);
    }
    return fn;
}

static void shim_init(void) {
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
    if (!real.handle) {
        LOGE("No Vulkan loader found; every forwarded call will fail");
        return;
    }

    real.vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)load_real("vkGetInstanceProcAddr", 1);
    real.vkGetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)load_real("vkGetDeviceProcAddr", 1);
    real.vkDestroySurfaceKHR = (PFN_vkDestroySurfaceKHR)load_real("vkDestroySurfaceKHR", 0);
    real.vkQueuePresentKHR = (PFN_vkQueuePresentKHR)load_real("vkQueuePresentKHR", 0);
    real.vkCreateXlibSurfaceKHR = (PFN_vkCreateXlibSurfaceKHR)load_real("vkCreateXlibSurfaceKHR", 0);
    real.vkGetPhysicalDeviceXlibPresentationSupportKHR =
        (PFN_vkGetPhysicalDeviceXlibPresentationSupportKHR)load_real("vkGetPhysicalDeviceXlibPresentationSupportKHR", 0);

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

/* Instance-level lookup in the real loader for an entry point it doesn't export as a plain
 * symbol (extension functions on some loaders). NULL when there is no way to get it. */
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
    PFN_vkCreateXlibSurfaceKHR fn = real.vkCreateXlibSurfaceKHR;
    if (!fn) fn = (PFN_vkCreateXlibSurfaceKHR)real_instance_proc(instance, "vkCreateXlibSurfaceKHR");
    if (!fn) {
        LOGE("vkCreateXlibSurfaceKHR: the Vulkan loader has no VK_KHR_xlib_surface");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    return fn(instance, pCreateInfo, pAllocator, pSurface);
}

VK_SHIM_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    ensure_init();
    PFN_vkVoidFunction fn = real_instance_proc(instance, pName);
    if (fn || !pName) return fn;
    /* The real loader doesn't know the Xlib entry points; hand out this shim's. */
    if (strcmp(pName, "vkGetPhysicalDeviceXlibPresentationSupportKHR") == 0) {
        return (PFN_vkVoidFunction)vkGetPhysicalDeviceXlibPresentationSupportKHR;
    }
    if (strcmp(pName, "vkCreateXlibSurfaceKHR") == 0) {
        return (PFN_vkVoidFunction)vkCreateXlibSurfaceKHR;
    }
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    }
    return NULL;
}

VK_SHIM_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *pName) {
    ensure_init();
    if (!real.vkGetDeviceProcAddr) return NULL;
    return real.vkGetDeviceProcAddr(device, pName);
}

VK_SHIM_EXPORT void VKAPI_CALL vkDestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                                                   const VkAllocationCallbacks *pAllocator) {
    ensure_init();
    PFN_vkDestroySurfaceKHR fn = real.vkDestroySurfaceKHR;
    if (!fn) fn = (PFN_vkDestroySurfaceKHR)real_instance_proc(instance, "vkDestroySurfaceKHR");
    if (!fn) {
        LOGE("vkDestroySurfaceKHR: not available in the Vulkan loader");
        return;
    }
    fn(instance, surface, pAllocator);
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
    ensure_init();
    if (!real.vkQueuePresentKHR) {
        LOGE("vkQueuePresentKHR: not available in the Vulkan loader");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return real.vkQueuePresentKHR(queue, pPresentInfo);
}
