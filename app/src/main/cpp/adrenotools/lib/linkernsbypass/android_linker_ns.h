// SPDX-License-Identifier: MIT
// Linker-namespace bypass for loading vendor/driver .so files from app-private
// storage. Function names match the liblinkernsbypass surface used by
// adrenotools: create an isolated shared namespace, link it to the default
// namespace, and dlopen into it (including a unique instance of a library
// that is already loaded elsewhere).
//
// Android 9+ (API 28). arm64.

#pragma once

#include <android/dlext.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    ANDROID_NAMESPACE_TYPE_REGULAR = 0,
    ANDROID_NAMESPACE_TYPE_ISOLATED = 1,
    ANDROID_NAMESPACE_TYPE_SHARED = 2,
    ANDROID_NAMESPACE_TYPE_EXEMPT_LIST_ENABLED = 0x08000000,
    ANDROID_NAMESPACE_TYPE_ALSO_USED_AS_ANONYMOUS = 0x10000000,
    ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED =
        ANDROID_NAMESPACE_TYPE_SHARED | ANDROID_NAMESPACE_TYPE_ISOLATED,
};

// True after the bypass has resolved enough linker entry points to be useful.
// Safe to call repeatedly. Must be called before the other functions.
bool linkernsbypass_load_status(void);

// Create a linker namespace. ld_library_path and default_library_path are
// colon-separated absolute directories. permitted_when_isolated_path is the
// list of path prefixes an isolated namespace may still open.
// Returns null if the device linker does not expose namespace creation.
struct android_namespace_t* android_create_namespace(
    const char* name,
    const char* ld_library_path,
    const char* default_library_path,
    uint64_t type,
    const char* permitted_when_isolated_path,
    struct android_namespace_t* parent_namespace);

// Like android_create_namespace, but the permitted path also includes
// /system, /vendor, /apex, and /data so driver dependencies can be resolved.
struct android_namespace_t* android_create_namespace_escape(
    const char* name,
    const char* ld_library_path,
    const char* default_library_path,
    uint64_t type,
    const char* permitted_when_isolated_path,
    struct android_namespace_t* parent_namespace);

typedef struct android_namespace_t* (*android_get_exported_namespace_t)(const char*);
typedef bool (*android_link_namespaces_all_libs_t)(struct android_namespace_t*,
                                                    struct android_namespace_t*);
typedef bool (*android_link_namespaces_t)(struct android_namespace_t*,
                                           struct android_namespace_t*,
                                           const char*);

// Populated by linkernsbypass_load_status(). May be null on older linkers.
extern android_get_exported_namespace_t android_get_exported_namespace;
extern android_link_namespaces_all_libs_t android_link_namespaces_all_libs;
extern android_link_namespaces_t android_link_namespaces;

// Link `to` so it can use libraries already visible to the default namespace.
bool linkernsbypass_link_namespace_to_default_all_libs(struct android_namespace_t* to);

// dlopen `filename` into `ns`. If ns is null, falls back to dlopen().
void* linkernsbypass_namespace_dlopen(const char* filename, int flags,
                                       struct android_namespace_t* ns);

// Load a private instance of `libPath` into `ns`. Uses memfd on API 29+ when
// libTargetDir is null; otherwise copies the library into libTargetDir first.
void* linkernsbypass_namespace_dlopen_unique(const char* libPath,
                                              const char* libTargetDir,
                                              int flags,
                                              struct android_namespace_t* ns);

#ifdef __cplusplus
}
#endif
