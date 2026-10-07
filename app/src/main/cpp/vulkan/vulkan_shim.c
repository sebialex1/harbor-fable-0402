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
 * Only vk* symbols are exported (-fvisibility=hidden + VK_SHIM_EXPORT).
 */
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
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
    PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance vkCreateInstance;
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
    real.vkEnumerateInstanceExtensionProperties =
        (PFN_vkEnumerateInstanceExtensionProperties)load_real("vkEnumerateInstanceExtensionProperties", 1);
    real.vkCreateInstance = (PFN_vkCreateInstance)load_real("vkCreateInstance", 1);
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
        (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)load_real("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", 1);
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
        (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)load_real("vkGetPhysicalDeviceSurfaceFormatsKHR", 1);
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
        LOGI("vkCreateInstance: instance created (%u extensions requested, %u forwarded)",
             requested, info.enabledExtensionCount);
    }

    free(filtered);
    return res;
}

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
    android_bridge_destroy_surface(surface);
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
    ensure_init();
    if (!real.vkQueuePresentKHR) {
        LOGE("vkQueuePresentKHR: not available in the Vulkan loader");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return real.vkQueuePresentKHR(queue, pPresentInfo);
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
        (PFN_vkGetPhysicalDeviceFeatures)load_real("vkGetPhysicalDeviceFeatures", 1);
    if (!fn || !pFeatures) return;
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc(pFeatures);
}

VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice,
                                                            VkPhysicalDeviceFeatures2 *pFeatures) {
    ensure_init();
    PFN_vkGetPhysicalDeviceFeatures2 fn =
        (PFN_vkGetPhysicalDeviceFeatures2)load_real("vkGetPhysicalDeviceFeatures2", 1);
    if (!fn || !pFeatures) return;
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc2(pFeatures);
}

VK_SHIM_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2KHR(VkPhysicalDevice physicalDevice,
                                                               VkPhysicalDeviceFeatures2 *pFeatures) {
    ensure_init();
    PFN_vkGetPhysicalDeviceFeatures2KHR fn =
        (PFN_vkGetPhysicalDeviceFeatures2KHR)real_instance_proc(VK_NULL_HANDLE, "vkGetPhysicalDeviceFeatures2KHR");
    if (!fn || !pFeatures) {
        /* Fall back to the core entry point (Vulkan 1.1 loaders expose both names). */
        vkGetPhysicalDeviceFeatures2(physicalDevice, pFeatures);
        return;
    }
    fn(physicalDevice, pFeatures);
    force_texture_compression_bc2(pFeatures);
}
