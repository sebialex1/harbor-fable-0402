// SPDX-License-Identifier: MIT
// adrenotools driver API for harbor-fable.
//
// The loader builds a linker namespace that can see app-private driver
// storage, dlopens the custom Vulkan ICD inside that namespace, and publishes
// a vkGetInstanceProcAddr hook that redirects to the custom driver.
//
// Calling convention of adrenotools_open_libvulkan matches libadrenotools
// (bylaws). The returned handle is an opaque session pointer; use
// adrenotools_get_instance_proc_addr() rather than dlsym().

#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#include "priv.h"

// Open a Vulkan driver.
//
// dlopenMode          RTLD_* flags used for the ICD.
// featureFlags        ADRENOTOOLS_DRIVER_* bits.
// tmpLibDir           Writable directory used when memfd is unavailable.
// hookLibDir          Directory of this app's native libraries
//                     (ApplicationInfo.nativeLibraryDir). May be null; the
//                     loader will look up libfable_native.so in /proc/self/maps.
// customDriverDir     Directory containing the extracted driver. Must be
//                     app-private storage, not external storage. Required when
//                     ADRENOTOOLS_DRIVER_CUSTOM is set.
// customDriverName    soname, e.g. "vulkan.radeon.so". Required with CUSTOM.
// fileRedirectDir     Shader-cache / file-redirect directory. Required when
//                     ADRENOTOOLS_DRIVER_FILE_REDIRECT is set.
// userMappingHandle   Out-parameter for a GPU mapping handle. Required when
//                     ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT is set.
//
// Returns an opaque non-null handle on success.
void* adrenotools_open_libvulkan(int dlopenMode,
                                 int featureFlags,
                                 const char* tmpLibDir,
                                 const char* hookLibDir,
                                 const char* customDriverDir,
                                 const char* customDriverName,
                                 const char* fileRedirectDir,
                                 void** userMappingHandle);

// High-level load used by the JNI bridge. library_path is the installed .so.
// hook_lib_dir may be null.
void* adrenotools_load_driver(const char* library_path, const char* hook_lib_dir);

// Drop a handle returned by adrenotools_open_libvulkan / adrenotools_load_driver.
bool adrenotools_close(void* handle);

// vkGetInstanceProcAddr for this handle. The pointer is a hook: calls are
// forwarded to the custom driver's vkGetInstanceProcAddr (or
// vk_icdGetInstanceProcAddr). Returns null if the driver did not export one.
void* adrenotools_get_instance_proc_addr(void* handle);

// Process-wide hook. Redirects to the most recently loaded custom driver,
// then to the system libvulkan if no custom driver is active.
void* adrenotools_vkGetInstanceProcAddr(void* instance, const char* name);

// Create directory and linker-namespace mappings for a driver install.
// driver_dir must already exist. redirect_dir is created (0755) if needed.
// Returns an opaque mapping handle, or null on failure.
void* adrenotools_create_mappings(const char* driver_dir, const char* redirect_dir);
void adrenotools_destroy_mappings(void* handle);

// Apply a JSON object of Vulkan loader / layer environment variables.
// Keys must start with VK_, ADRENOTOOLS_, MESA_, DXVK_, VKD3D_, or FABLE_.
// An empty string value clears a previously stored key.
// Remembered values are applied to Wine children.
bool adrenotools_set_layer_config(const char* layer_config_json);

typedef void (*adrenotools_env_visitor)(const char* key, const char* value, void* ctx);
void adrenotools_visit_layer_env(adrenotools_env_visitor visitor, void* ctx);

bool adrenotools_import_user_mem(void* handle, void* host_ptr, uint64_t size);
bool adrenotools_mem_gpu_allocate(void* handle, uint64_t* size);
bool adrenotools_mem_cpu_map(void* handle, void* host_ptr, uint64_t size);
bool adrenotools_validate_gpu_mapping(void* handle);
void adrenotools_set_turbo(bool turbo);

// Last error from the calling thread. Never null; empty if none.
const char* adrenotools_last_error(void);

#ifdef __cplusplus
}
#endif
