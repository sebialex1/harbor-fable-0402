// SPDX-License-Identifier: MIT
// Host test of the shim's driver selection (split / fallback), with every loader and driver faked.
// Build like vulkan_shim_test.c:
//   cc -D_GNU_SOURCE -Itests/include -I<dir with vulkan/ + vk_video/> tests/vulkan_shim_split_test.c -lpthread
// Each scenario runs in a forked child (driver selection happens once per process).
#include <assert.h>
#include <stdio.h>
#include <sys/wait.h>
#define dlopen test_dlopen
#define dlsym test_dlsym
#include "../app/src/main/cpp/vulkan/vulkan_shim.c"

int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void)priority; (void)tag; (void)format;
    return 0;
}
int __android_log_write(int priority, const char *tag, const char *text) {
    (void)priority; (void)tag; (void)text;
    return 0;
}

/* Surface bridge: unused here. */
struct AndroidSurfaceBridge { int unused; };
struct AndroidSurfaceBridge *android_bridge_create(uint32_t window) { (void)window; return NULL; }
ANativeWindow *android_bridge_window(struct AndroidSurfaceBridge *b) { (void)b; return NULL; }
void android_bridge_attach(struct AndroidSurfaceBridge *b, VkSurfaceKHR surface) { (void)b; (void)surface; }
void android_bridge_delete(struct AndroidSurfaceBridge *b) { (void)b; }
int android_bridge_contains(VkSurfaceKHR surface) { (void)surface; return 0; }
int android_bridge_refresh(VkSurfaceKHR surface) { (void)surface; return 1; }
void android_bridge_destroy_surface(VkSurfaceKHR surface) { (void)surface; }

#define H_DRIVER ((void *)(uintptr_t)0x10)
#define H_SPLIT ((void *)(uintptr_t)0x20)
#define H_SYSTEM ((void *)(uintptr_t)0x30)

static const char *driver_path; /* a real file, so stat() passes */
static int scenario_has_hmi = 1, scenario_hook_hit = 1, scenario_radv_devices = 1;
static unsigned hits;
static int split_instances, system_instances, inject_opened;

/* --- Fake HAL injection -------------------------------------------------------------------- */
int fable_hal_inject_open(const char *loader_path, void *driver, const char *path,
                          struct fable_hal_inject *out, char *err, size_t err_len) {
    (void)err; (void)err_len;
    assert(strcmp(loader_path, "/system/lib64/libvulkan.so") == 0);
    assert(driver == H_DRIVER && strcmp(path, driver_path) == 0);
    memset(out, 0, sizeof(*out));
    out->loader = H_SPLIT;
    out->driver = driver;
    out->loader_path = loader_path;
    out->driver_path = path;
    out->how = "fake";
    inject_opened = 1;
    return 1;
}
unsigned fable_hal_inject_hits(void) { return hits; }
const char *fable_hal_inject_requested(void) { return hits ? "vulkan.samsung.so" : ""; }
int fable_hal_inject_loader_loaded(void) { return inject_opened; }
void *fable_dlopen_fresh(const char *path, int flags) {
    (void)flags;
    return strcmp(path, "/system/lib64/libvulkan.so") == 0 ? H_SYSTEM : NULL;
}

/* --- Fake split loader (system loader with RADV as HAL) ------------------------------------ */
static VkResult split_enum_ext(const char *layer, uint32_t *count, VkExtensionProperties *props) {
    (void)layer;
    if (scenario_hook_hit) hits = 1; /* the loader opens its HAL on the first call */
    static const char *names[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
                                   "VK_KHR_get_physical_device_properties2" };
    if (!props) { *count = 3; return VK_SUCCESS; }
    uint32_t n = *count < 3 ? *count : 3;
    for (uint32_t i = 0; i < n; i++) fill_extension(&props[i], names[i], 1);
    *count = n;
    return n < 3 ? VK_INCOMPLETE : VK_SUCCESS;
}
static VkResult split_create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *a, VkInstance *out) {
    (void)info; (void)a;
    split_instances++;
    *out = (VkInstance)(uintptr_t)0x100;
    return VK_SUCCESS;
}
static void split_destroy_instance(VkInstance i, const VkAllocationCallbacks *a) { (void)i; (void)a; }
static VkResult split_enum_devices(VkInstance i, uint32_t *count, VkPhysicalDevice *devs) {
    (void)i;
    if (!devs) { *count = 1; return VK_SUCCESS; }
    devs[0] = (VkPhysicalDevice)(uintptr_t)0x200;
    *count = 1;
    return VK_SUCCESS;
}
static void split_props(VkPhysicalDevice d, VkPhysicalDeviceProperties *p) {
    (void)d;
    memset(p, 0, sizeof(*p));
    p->apiVersion = VK_API_VERSION_1_3;
    p->vendorID = 0x1002;
    snprintf(p->deviceName, sizeof(p->deviceName), "%s",
             scenario_radv_devices ? "AMD Radeon Xclipse 920 (RADV)" : "Samsung Xclipse 920");
}
static void split_props2(VkPhysicalDevice d, VkPhysicalDeviceProperties2 *p) {
    split_props(d, &p->properties);
    for (VkBaseOutStructure *s = p->pNext; s; s = s->pNext) {
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES) {
            VkPhysicalDeviceDriverProperties *dp = (VkPhysicalDeviceDriverProperties *)s;
            dp->driverID = scenario_radv_devices ? VK_DRIVER_ID_MESA_RADV : VK_DRIVER_ID_SAMSUNG_PROPRIETARY;
        }
    }
}
static int bc_reported = 1;
static void split_features(VkPhysicalDevice d, VkPhysicalDeviceFeatures *f) {
    (void)d;
    memset(f, 0, sizeof(*f));
    f->textureCompressionBC = (VkBool32)bc_reported;
}
static VkResult split_dev_ext(VkPhysicalDevice d, const char *layer, uint32_t *count, VkExtensionProperties *props) {
    (void)d; (void)layer;
    if (!props) { *count = 1; return VK_SUCCESS; }
    fill_extension(&props[0], VK_KHR_SWAPCHAIN_EXTENSION_NAME, 70);
    *count = 1;
    return VK_SUCCESS;
}
static PFN_vkVoidFunction split_gipa(VkInstance instance, const char *name);
static void *split_sym(const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (void *)split_gipa;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) return (void *)split_enum_ext;
    if (!strcmp(name, "vkCreateInstance")) return (void *)split_create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (void *)split_destroy_instance;
    if (!strcmp(name, "vkEnumeratePhysicalDevices")) return (void *)split_enum_devices;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties")) return (void *)split_props;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2")) return (void *)split_props2;
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures")) return (void *)split_features;
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) return (void *)split_dev_ext;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)split_gipa; /* never called */
    return NULL;
}
static PFN_vkVoidFunction split_gipa(VkInstance instance, const char *name) {
    (void)instance;
    return (PFN_vkVoidFunction)split_sym(name);
}

/* --- Fake system loader (vendor driver) ------------------------------------------------------ */
static VkResult system_create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *a, VkInstance *out) {
    (void)info; (void)a;
    system_instances++;
    *out = (VkInstance)(uintptr_t)0x300;
    return VK_SUCCESS;
}
static void system_features(VkPhysicalDevice d, VkPhysicalDeviceFeatures *f) {
    (void)d;
    memset(f, 0, sizeof(*f)); /* Samsung driver hides textureCompressionBC */
}
static PFN_vkVoidFunction system_gipa(VkInstance instance, const char *name);
static void *system_sym(const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (void *)system_gipa;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) return (void *)split_enum_ext;
    if (!strcmp(name, "vkCreateInstance")) return (void *)system_create_instance;
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures")) return (void *)system_features;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)system_gipa;
    return NULL;
}
static PFN_vkVoidFunction system_gipa(VkInstance instance, const char *name) {
    (void)instance;
    return (PFN_vkVoidFunction)system_sym(name);
}

/* --- Fake RADV ICD opened directly: no WSI ------------------------------------------------- */
static VkResult icd_enum_ext(const char *layer, uint32_t *count, VkExtensionProperties *props) {
    (void)layer;
    if (!props) { *count = 1; return VK_SUCCESS; }
    fill_extension(&props[0], "VK_EXT_headless_surface", 1);
    *count = 1;
    return VK_SUCCESS;
}
static PFN_vkVoidFunction icd_gipa(VkInstance instance, const char *name) {
    (void)instance;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) return (PFN_vkVoidFunction)icd_enum_ext;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)split_create_instance;
    return NULL;
}

static const struct shim_hw_module_head fake_hmi = {
    .tag = SHIM_HARDWARE_MODULE_TAG, .module_api_version = 1, .id = "vulkan", .name = "Mesa 3D Vulkan HAL",
    .author = "Mesa 3D",
};

void *test_dlopen(const char *path, int flags) {
    (void)flags;
    if (driver_path && strcmp(path, driver_path) == 0) return H_DRIVER;
    if (strcmp(path, "/system/lib64/libvulkan.so") == 0) return inject_opened ? H_SPLIT : H_SYSTEM;
    return NULL;
}
void *test_dlsym(void *handle, const char *name) {
    if (handle == H_DRIVER) {
        if (!strcmp(name, "HMI")) return scenario_has_hmi ? (void *)&fake_hmi : NULL;
        if (!strcmp(name, "vk_icdGetInstanceProcAddr")) return (void *)icd_gipa;
        return NULL;
    }
    if (handle == H_SPLIT) return split_sym(name);
    if (handle == H_SYSTEM) return system_sym(name);
    return NULL;
}

static VkInstance create_dxvk_like_instance(void) {
    const char *names[] = { XLIB_SURFACE_EXTENSION_NAME, VK_KHR_SURFACE_EXTENSION_NAME };
    VkInstanceCreateInfo info = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                  .enabledExtensionCount = 2, .ppEnabledExtensionNames = names };
    VkInstance instance = VK_NULL_HANDLE;
    assert(vkCreateInstance(&info, NULL, &instance) == VK_SUCCESS);
    return instance;
}

static void scenario_split_ok(void) {
    VkInstance instance = create_dxvk_like_instance();
    assert(real.direct_icd == SHIM_MODE_SPLIT);
    assert(strcmp(real.driver_path, driver_path) == 0);
    assert(instance == (VkInstance)(uintptr_t)0x100);
    assert(split_instances == 2 && system_instances == 0); /* probe + DXVK's */
    /* RADV's own feature answer passes through untouched. */
    VkPhysicalDeviceFeatures f;
    bc_reported = 0;
    vkGetPhysicalDeviceFeatures((VkPhysicalDevice)(uintptr_t)0x200, &f);
    assert(f.textureCompressionBC == VK_FALSE);
    bc_reported = 1;
    vkGetPhysicalDeviceFeatures((VkPhysicalDevice)(uintptr_t)0x200, &f);
    assert(f.textureCompressionBC == VK_TRUE);
    /* Instance-level lookups go to the split loader. */
    assert(vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices") == (PFN_vkVoidFunction)split_enum_devices);
    assert(vkGetInstanceProcAddr(instance, "vkCreateXlibSurfaceKHR") == (PFN_vkVoidFunction)vkCreateXlibSurfaceKHR);
}

static void expect_system_fallback(void) {
    VkInstance instance = create_dxvk_like_instance();
    assert(real.direct_icd == SHIM_MODE_SYSTEM);
    assert(instance == (VkInstance)(uintptr_t)0x300);
    assert(system_instances == 1);
    VkPhysicalDeviceFeatures f;
    vkGetPhysicalDeviceFeatures(NULL, &f);
    assert(f.textureCompressionBC == VK_TRUE); /* forced on the vendor driver */
}

static int run(const char *label, void (*fn)(void)) {
    fflush(stdout);
    pid_t pid = fork();
    if (pid == 0) {
        fn();
        _exit(0);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    int ok = WIFEXITED(status) && WEXITSTATUS(status) == 0;
    printf("%s: %s\n", label, ok ? "ok" : "FAILED");
    return ok;
}

static void s_split(void) { scenario_split_ok(); }
static void s_no_hook(void) { scenario_hook_hit = 0; expect_system_fallback(); }
static void s_no_hmi(void) { scenario_has_hmi = 0; expect_system_fallback(); }
static void s_not_radv(void) { scenario_radv_devices = 0; expect_system_fallback(); }
static void s_mode_system(void) { setenv("FABLE_VULKAN_DRIVER_MODE", "system", 1); expect_system_fallback(); assert(!inject_opened); }
static void s_unset(void) { unsetenv("FABLE_VULKAN_DRIVER"); expect_system_fallback(); assert(!inject_opened); }

int main(int argc, char **argv) {
    (void)argc;
    driver_path = argv[0]; /* any existing regular file */
    setenv("FABLE_VULKAN_DRIVER", driver_path, 1);
    unsetenv("VK_ICD_FILENAMES");
    unsetenv("VK_DRIVER_FILES");
    unsetenv("FABLE_VULKAN_DRIVER_MODE");
    setenv("FABLE_VULKAN_SHIM_LOG", "/dev/null", 1);
    int ok = 1;
    ok &= run("split: system loader WSI + RADV devices", s_split);
    ok &= run("fallback: loader never asked for the HAL", s_no_hook);
    ok &= run("fallback: driver is not an Android HAL (no HMI), no WSI as direct ICD", s_no_hmi);
    ok &= run("fallback: devices are not the injected driver", s_not_radv);
    ok &= run("FABLE_VULKAN_DRIVER_MODE=system", s_mode_system);
    ok &= run("no custom driver", s_unset);
    puts(ok ? "Vulkan shim split-mode tests passed" : "Vulkan shim split-mode tests FAILED");
    return ok ? 0 : 1;
}
