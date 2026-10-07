// SPDX-License-Identifier: MIT
/*
 * Winlator/adrenotools-style Vulkan driver injection for the libvulkan.so.1 shim.
 *
 * Opens a *private* instance of Android's system Vulkan loader (/system/lib64/libvulkan.so) and
 * redirects the loader's HAL lookup (android_load_sphal_library / android_dlopen_ext of
 * "vulkan.<board>.so") to a custom Vulkan HAL driver such as RADV Xclipse (vulkan.radeon.so).
 *
 * The result is the combination adrenotools_open_libvulkan() gives Winlator:
 *   - the system loader owns VkInstance and all window-system integration: VK_KHR_surface,
 *     VK_KHR_android_surface and VK_KHR_swapchain (which Android's loader implements on top of
 *     the driver's VK_ANDROID_native_buffer);
 *   - the custom driver owns every VkPhysicalDevice, VkDevice, queue, memory and command — the
 *     loader dispatches all of it straight to the driver it loaded, i.e. RADV.
 *
 * The redirect is a GOT patch of the private loader copy only (nothing else in the process is
 * touched), applied before the loader opens its HAL. The real system loader stays available,
 * untouched, for the fallback path (fable_dlopen_fresh()).
 */
#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

struct fable_hal_inject {
    void *loader;            /* dlopen handle of the private system loader instance */
    void *driver;            /* dlopen handle of the injected HAL driver */
    const char *loader_path; /* e.g. /system/lib64/libvulkan.so */
    const char *driver_path; /* e.g. .../vulkan.radeon.so */
    int patched_sphal;       /* GOT slots redirected for android_load_sphal_library */
    int patched_dlopen_ext;  /* GOT slots redirected for android_dlopen_ext */
    const char *how;         /* how the private loader instance was obtained (log label) */
};

/* Loads a fresh, private instance of `loader_path` and redirects its HAL loading to the driver
 * already dlopen'ed as `driver` (path `driver_path`). Nothing in the loader runs before the
 * redirect is in place. Returns 1 on success and fills *out; 0 with a message in err otherwise. */
int fable_hal_inject_open(const char *loader_path, void *driver, const char *driver_path,
                          struct fable_hal_inject *out, char *err, size_t err_len);

/* How many times the private loader asked for a Vulkan HAL and got the injected driver, and the
 * name it asked for first ("vulkan.samsung.so", ...; "" if never). */
unsigned fable_hal_inject_hits(void);
const char *fable_hal_inject_requested(void);

/* 1 once a private loader instance exists in this process. A plain dlopen() of the same path
 * would then return *that* instance (bionic matches already-loaded libraries by inode), so
 * whoever needs the untouched system loader must use fable_dlopen_fresh(). */
int fable_hal_inject_loader_loaded(void);

/* dlopen() that always maps a new instance of `path` (ANDROID_DLEXT_USE_LIBRARY_FD +
 * ANDROID_DLEXT_FORCE_LOAD), even if a library with the same file is already loaded. */
void *fable_dlopen_fresh(const char *path, int flags);

#ifdef __cplusplus
}
#endif
