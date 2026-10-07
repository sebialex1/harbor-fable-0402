// SPDX-License-Identifier: MIT
// Host regression test: cc -Itests/include -I<NDK>/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include
// Do NOT add the NDK sysroot itself to a host build: pass a directory containing only vulkan/.
#include <assert.h>
#include <stdio.h>
#define dlopen test_dlopen
#define dlsym test_dlsym
#include "../app/src/main/cpp/vulkan/vulkan_shim.c"

struct AndroidSurfaceBridge { int unused; };
static struct AndroidSurfaceBridge bridge;
static int bridge_available = 1, attached = 0, deleted = 0, destroyed = 0;
static VkResult create_result = VK_SUCCESS;
static VkSurfaceKHR test_surface = (VkSurfaceKHR)(uintptr_t)0x1234;
static VkInstance test_instance = (VkInstance)(uintptr_t)1;

int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void)priority; (void)tag; (void)format;
    return 0;
}

static VkResult fake_create_instance(const VkInstanceCreateInfo *info,
                                    const VkAllocationCallbacks *allocator, VkInstance *instance) {
    (void)allocator;
    int android = 0, surface = 0;
    for (uint32_t i = 0; i < info->enabledExtensionCount; ++i) {
        assert(!is_x11_surface_extension(info->ppEnabledExtensionNames[i]));
        android += !strcmp(info->ppEnabledExtensionNames[i], VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
        surface += !strcmp(info->ppEnabledExtensionNames[i], VK_KHR_SURFACE_EXTENSION_NAME);
    }
    assert(android == 1 && surface == 1);
    *instance = test_instance;
    return VK_SUCCESS;
}

static VkResult fake_android_surface(VkInstance instance, const VkAndroidSurfaceCreateInfoKHR *info,
                                    const VkAllocationCallbacks *allocator, VkSurfaceKHR *surface) {
    (void)allocator;
    assert(instance == test_instance);
    assert(info->sType == VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR);
    assert(info->window == (ANativeWindow *)(uintptr_t)2);
    if (create_result == VK_SUCCESS) *surface = test_surface;
    return create_result;
}

static void fake_destroy(VkInstance instance, VkSurfaceKHR surface, const VkAllocationCallbacks *allocator) {
    (void)instance; (void)allocator;
    assert(surface == test_surface);
    ++destroyed;
}

static VkResult fake_formats(VkPhysicalDevice device, VkSurfaceKHR surface, uint32_t *count,
                            VkSurfaceFormatKHR *formats) {
    (void)device; (void)surface;
    if (!formats) { *count = 3; return VK_SUCCESS; }
    assert(*count >= 3);
    formats[0] = (VkSurfaceFormatKHR){ VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    formats[1] = (VkSurfaceFormatKHR){ VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    formats[2] = (VkSurfaceFormatKHR){ VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    *count = 3;
    return VK_SUCCESS;
}

static PFN_vkVoidFunction fake_proc(VkInstance instance, const char *name) {
    (void)instance;
    if (!strcmp(name, "vkCreateAndroidSurfaceKHR")) return (PFN_vkVoidFunction)fake_android_surface;
    if (!strcmp(name, "vkDestroySurfaceKHR")) return (PFN_vkVoidFunction)fake_destroy;
    return NULL; /* Android intentionally has no Xlib function */
}

void *test_dlopen(const char *path, int flags) { (void)path; (void)flags; return (void *)(uintptr_t)1; }
void *test_dlsym(void *handle, const char *name) {
    (void)handle;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (void *)fake_proc;
    if (!strcmp(name, "vkCreateInstance")) return (void *)fake_create_instance;
    if (!strcmp(name, "vkDestroySurfaceKHR")) return (void *)fake_destroy;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceFormatsKHR")) return (void *)fake_formats;
    return NULL;
}
struct AndroidSurfaceBridge *android_bridge_create(uint32_t window) {
    assert(window == 42);
    return bridge_available ? &bridge : NULL;
}
ANativeWindow *android_bridge_window(struct AndroidSurfaceBridge *b) {
    assert(b == &bridge); return (ANativeWindow *)(uintptr_t)2;
}
void android_bridge_attach(struct AndroidSurfaceBridge *b, VkSurfaceKHR surface) {
    assert(b == &bridge && surface == test_surface); ++attached;
}
void android_bridge_delete(struct AndroidSurfaceBridge *b) { assert(b == &bridge); ++deleted; }
int android_bridge_contains(VkSurfaceKHR surface) { return surface == test_surface; }
int android_bridge_refresh(VkSurfaceKHR surface) { (void)surface; return 1; }
void android_bridge_destroy_surface(VkSurfaceKHR surface) { assert(surface == test_surface); ++deleted; }

int main(void) {
    const char *names[] = { XLIB_SURFACE_EXTENSION_NAME, VK_KHR_SURFACE_EXTENSION_NAME };
    VkInstanceCreateInfo info = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                 .enabledExtensionCount = 2, .ppEnabledExtensionNames = names };
    VkInstance instance;
    assert(vkCreateInstance(&info, NULL, &instance) == VK_SUCCESS);
    assert(info.enabledExtensionCount == 2 && names[0] == info.ppEnabledExtensionNames[0]);
    const char *already_enabled[] = { XLIB_SURFACE_EXTENSION_NAME,
                                      VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
                                      VK_KHR_SURFACE_EXTENSION_NAME };
    info.enabledExtensionCount = 3;
    info.ppEnabledExtensionNames = already_enabled;
    assert(vkCreateInstance(&info, NULL, &instance) == VK_SUCCESS); /* no duplicate extensions */
    assert(vkGetInstanceProcAddr(instance, "vkCreateXlibSurfaceKHR") == (PFN_vkVoidFunction)vkCreateXlibSurfaceKHR);
    assert(vkGetInstanceProcAddr(instance, "vkDestroySurfaceKHR") == (PFN_vkVoidFunction)vkDestroySurfaceKHR);
    VkXlibSurfaceCreateInfoKHR xlib = { .window = 42 };
    VkSurfaceKHR surface;
    assert(vkCreateXlibSurfaceKHR(instance, &xlib, NULL, &surface) == VK_SUCCESS);
    assert(surface == test_surface && attached == 1);
    uint32_t count = 0;
    assert(vkGetPhysicalDeviceSurfaceFormatsKHR(NULL, surface, &count, NULL) == VK_SUCCESS && count == 2);
    VkSurfaceFormatKHR format;
    count = 1;
    assert(vkGetPhysicalDeviceSurfaceFormatsKHR(NULL, surface, &count, &format) == VK_INCOMPLETE);
    assert(count == 1 && format.format == VK_FORMAT_R8G8B8A8_UNORM);
    vkDestroySurfaceKHR(instance, surface, NULL);
    assert(destroyed == 1 && deleted == 1);
    bridge_available = 0;
    assert(vkCreateXlibSurfaceKHR(instance, &xlib, NULL, &surface) == VK_ERROR_SURFACE_LOST_KHR);
    assert(surface == VK_NULL_HANDLE);
    bridge_available = 1;
    create_result = VK_ERROR_OUT_OF_HOST_MEMORY;
    assert(vkCreateXlibSurfaceKHR(instance, &xlib, NULL, &surface) == create_result);
    assert(deleted == 2 && attached == 1);
    puts("Vulkan shim regression tests passed");
}
