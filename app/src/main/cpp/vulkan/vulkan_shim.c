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
 *   vkCreateInstance                        strips VK_KHR_xlib_surface (and the
 *                                           xcb spelling) from
 *                                           ppEnabledExtensionNames before
 *                                           forwarding to the real loader
 *
 * vkGetInstanceProcAddr hands out the shim's versions of both so the wrap holds
 * whether Wine reaches them by dlsym or by procaddr. Every other Vulkan
 * function is reached through the forwarded vkGetInstanceProcAddr /
 * vkGetDeviceProcAddr, so nothing else is wrapped.
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
    PFN_vkCreateXlibSurfaceKHR fn = real.vkCreateXlibSurfaceKHR;
    if (!fn) fn = (PFN_vkCreateXlibSurfaceKHR)real_instance_proc(instance, "vkCreateXlibSurfaceKHR");
    if (!fn) {
        LOGE("vkCreateXlibSurfaceKHR: the Vulkan loader has no VK_KHR_xlib_surface");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    return fn(instance, pCreateInfo, pAllocator, pSurface);
}

/* --- Instance extension masquerade -------------------------------------------------------- */

/* Extension names winevulkan's X11 driver asks the host for and Android's loader lacks. The
 * shim advertises the first (the one winex11 reports as its host surface extension) and
 * swallows both at instance creation. */
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

/* Forwards instance creation without the X11 surface extensions the real loader can't
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
        filtered = calloc(requested - stripped ? requested - stripped : 1, sizeof(*filtered));
        if (!filtered) return VK_ERROR_OUT_OF_HOST_MEMORY;
        uint32_t n = 0;
        for (uint32_t i = 0; i < requested; i++) {
            if (is_x11_surface_extension(names[i])) {
                LOGI("vkCreateInstance: dropping %s (not an Android loader extension; the shim provides it)", names[i]);
                continue;
            }
            filtered[n++] = names[i];
        }
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
}

VK_SHIM_EXPORT VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
    ensure_init();
    if (!real.vkQueuePresentKHR) {
        LOGE("vkQueuePresentKHR: not available in the Vulkan loader");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return real.vkQueuePresentKHR(queue, pPresentInfo);
}
