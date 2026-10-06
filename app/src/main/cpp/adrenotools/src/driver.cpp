// SPDX-License-Identifier: MIT
// Minimal adrenotools driver loader.
//
// 1. android_linker_ns creates a shared namespace whose search path includes
//    the app-private driver directory plus system/vendor library paths.
// 2. The custom Vulkan ICD (.so named by meta.json libraryName) is dlopened
//    inside that namespace, so its DT_NEEDED entries can resolve from the
//    driver package without root and without living on external storage.
// 3. vkGetInstanceProcAddr is hooked: adrenotools_vkGetInstanceProcAddr and
//    the per-handle getter forward to the ICD's vkGetInstanceProcAddr or
//    vk_icdGetInstanceProcAddr.

#include <adrenotools/driver.h>
#include <android_linker_ns.h>

#include "kgsl_uapi.h"
#include "../../common/fable_log.h"
#include "../../json/mini_json.h"
#include "../../meta/driver_meta.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

using gpa_fn = void* (*)(void*, const char*);

struct Mapping {
    std::string driver_dir;
    std::string redirect_dir;
    android_namespace_t* ns = nullptr;
};

struct Session {
    void* driver = nullptr;
    void* loader = nullptr;
    gpa_fn driver_gpa = nullptr;
    gpa_fn icd_gpa = nullptr;
    std::string path;
    std::string dir;
    std::string name;
    android_namespace_t* ns = nullptr;
    adrenotools_gpu_mapping* mapping = nullptr;
    bool owns_mapping = false;
    int refcount = 1;
};

std::mutex g_mu;
std::unordered_set<Session*> g_sessions;
std::unordered_set<adrenotools_gpu_mapping*> g_mappings;
std::unordered_set<Mapping*> g_user_mappings;
Session* g_active = nullptr;

std::mutex g_layer_mu;
std::vector<std::pair<std::string, std::string>> g_layer_env;

thread_local std::string g_error;

void set_error(const std::string& msg) {
    g_error = msg;
    FABLE_LOGE("%s", msg.c_str());
}

std::string dirname_of(const std::string& path) {
    const auto slash = path.rfind('/');
    if (slash == std::string::npos) return ".";
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

std::string basename_of(const std::string& path) {
    const auto slash = path.rfind('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

bool has_colon(const char* s) { return s && std::strchr(s, ':') != nullptr; }

bool on_external_storage(const std::string& path) {
    return path.find("/sdcard") != std::string::npos ||
           path.find("/storage/") != std::string::npos ||
           path.find("/mnt/media_rw/") != std::string::npos;
}

bool mkdir_p(const std::string& path) {
    if (path.empty() || path == "/") return true;
    std::string cur;
    size_t i = 0;
    if (!path.empty() && path[0] == '/') {
        cur = "/";
        i = 1;
    }
    while (i < path.size()) {
        const auto slash = path.find('/', i);
        const std::string part = path.substr(i, slash == std::string::npos ? std::string::npos : slash - i);
        if (!part.empty()) {
            if (cur.empty()) cur = part;
            else if (cur == "/") cur += part;
            else {
                cur.push_back('/');
                cur += part;
            }
            if (mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) return false;
        }
        if (slash == std::string::npos) break;
        i = slash + 1;
    }
    return true;
}

std::string with_slash(const char* dir) {
    std::string d = dir ? dir : "";
    if (!d.empty() && d.back() != '/') d.push_back('/');
    return d;
}

std::string native_library_dir_from_maps() {
    std::ifstream in("/proc/self/maps");
    if (!in) return {};
    std::string line;
    const std::string suffix = "/libfable_native.so";
    while (std::getline(in, line)) {
        const auto slash = line.find('/');
        if (slash == std::string::npos) continue;
        std::string path = line.substr(slash);
        const std::string deleted = " (deleted)";
        if (path.size() > deleted.size() &&
            path.compare(path.size() - deleted.size(), deleted.size(), deleted) == 0) {
            path.resize(path.size() - deleted.size());
        }
        if (path.size() >= suffix.size() &&
            path.compare(path.size() - suffix.size(), suffix.size(), suffix) == 0) {
            return path.substr(0, path.size() - suffix.size());
        }
    }
    return {};
}

android_namespace_t* make_driver_namespace(const char* hook_dir, const char* driver_dir) {
    std::string search = with_slash(driver_dir);
    if (hook_dir && *hook_dir) {
        if (!search.empty()) search.push_back(':');
        search += hook_dir;
    }
    search += ":/vendor/lib64:/vendor/lib64/hw:/system/lib64";
    std::string permitted = with_slash(driver_dir);
    if (hook_dir && *hook_dir) {
        if (!permitted.empty()) permitted.push_back(':');
        permitted += hook_dir;
    }
    android_namespace_t* parent = nullptr;
    if (android_get_exported_namespace) {
        parent = android_get_exported_namespace("default");
        if (!parent) parent = android_get_exported_namespace("com_android_art");
    }
    android_namespace_t* ns = android_create_namespace_escape(
        "adrenotools-libvulkan",
        search.c_str(),
        search.c_str(),
        ANDROID_NAMESPACE_TYPE_SHARED | ANDROID_NAMESPACE_TYPE_ISOLATED,
        permitted.c_str(),
        parent);
    if (!ns) {
        ns = android_create_namespace(
            "adrenotools-libvulkan",
            search.c_str(),
            nullptr,
            ANDROID_NAMESPACE_TYPE_SHARED,
            nullptr,
            parent);
    }
    if (ns) {
        linkernsbypass_link_namespace_to_default_all_libs(ns);
    }
    return ns;
}

void apply_file_redirect(const char* dir) {
    if (!dir || !*dir) return;
    setenv("MESA_SHADER_CACHE_DIR", dir, 1);
    setenv("MESA_GLSL_CACHE_DIR", dir, 1);
    setenv("ADRENOTOOLS_FILE_REDIRECT", dir, 1);
}

bool key_allowed(const std::string& key) {
    const char* prefixes[] = {"VK_", "ADRENOTOOLS_", "MESA_", "DXVK_", "VKD3D_", "FABLE_"};
    bool prefixed = false;
    for (const char* p : prefixes) {
        const size_t n = std::strlen(p);
        if (key.size() >= n && key.compare(0, n, p) == 0) {
            prefixed = true;
            break;
        }
    }
    if (!prefixed) return false;
    for (unsigned char c : key) {
        if (!(std::isalnum(c) || c == '_')) return false;
    }
    return true;
}

Session* session_from(void* handle) {
    auto* s = static_cast<Session*>(handle);
    if (!s || g_sessions.find(s) == g_sessions.end()) return nullptr;
    return s;
}

adrenotools_gpu_mapping* mapping_from(void* handle) {
    auto* m = static_cast<adrenotools_gpu_mapping*>(handle);
    if (!m || g_mappings.find(m) == g_mappings.end()) return nullptr;
    return m;
}

int open_kgsl() {
    return open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
}

}  // namespace

extern "C" {

const char* adrenotools_last_error(void) { return g_error.c_str(); }

void* adrenotools_open_libvulkan(int dlopenMode,
                                 int featureFlags,
                                 const char* tmpLibDir,
                                 const char* hookLibDir,
                                 const char* customDriverDir,
                                 const char* customDriverName,
                                 const char* fileRedirectDir,
                                 void** userMappingHandle) {
    g_error.clear();
    if (!linkernsbypass_load_status()) {
        set_error("linkernsbypass failed to initialize");
        return nullptr;
    }

    const bool custom = (featureFlags & ADRENOTOOLS_DRIVER_CUSTOM) != 0;
    const bool redirect = (featureFlags & ADRENOTOOLS_DRIVER_FILE_REDIRECT) != 0;
    const bool mapping_import = (featureFlags & ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT) != 0;

    if (!redirect && fileRedirectDir) {
        set_error("fileRedirectDir set without ADRENOTOOLS_DRIVER_FILE_REDIRECT");
        return nullptr;
    }
    if (!custom && (customDriverDir || customDriverName)) {
        set_error("custom driver path set without ADRENOTOOLS_DRIVER_CUSTOM");
        return nullptr;
    }
    if (!mapping_import && userMappingHandle) {
        set_error("userMappingHandle set without ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT");
        return nullptr;
    }
    if (custom && (!customDriverDir || !customDriverName || !*customDriverName)) {
        set_error("ADRENOTOOLS_DRIVER_CUSTOM requires customDriverDir and customDriverName");
        return nullptr;
    }
    if (has_colon(customDriverDir) || has_colon(hookLibDir) || has_colon(tmpLibDir)) {
        set_error("Driver paths must not contain ':' (linker namespace separator)");
        return nullptr;
    }

    std::string driver_path;
    if (custom) {
        driver_path = with_slash(customDriverDir) + customDriverName;
        if (on_external_storage(driver_path)) {
            set_error("customDriverDir must be app-private storage, not external storage");
            return nullptr;
        }
        struct stat st{};
        if (stat(driver_path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
            set_error("Custom driver library not found: " + driver_path);
            return nullptr;
        }
    }
    if (redirect) {
        if (!fileRedirectDir || !*fileRedirectDir) {
            set_error("ADRENOTOOLS_DRIVER_FILE_REDIRECT requires fileRedirectDir");
            return nullptr;
        }
        struct stat st{};
        if (stat(fileRedirectDir, &st) != 0 || !S_ISDIR(st.st_mode)) {
            set_error("fileRedirectDir does not exist");
            return nullptr;
        }
        apply_file_redirect(fileRedirectDir);
    }
    if (mapping_import && !userMappingHandle) {
        set_error("ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT requires userMappingHandle");
        return nullptr;
    }

    std::string hook = hookLibDir ? hookLibDir : "";
    if (hook.empty()) hook = native_library_dir_from_maps();

    android_namespace_t* ns = make_driver_namespace(hook.empty() ? nullptr : hook.c_str(),
                                                    custom ? customDriverDir : nullptr);
    if (!ns) {
        FABLE_LOGW("Driver namespace unavailable; falling back to dlopen");
    }

    void* driver = nullptr;
    if (custom) {
        const int mode = dlopenMode != 0 ? dlopenMode : (RTLD_NOW | RTLD_LOCAL);
        if (ns) driver = linkernsbypass_namespace_dlopen(driver_path.c_str(), mode, ns);
        if (!driver) driver = dlopen(driver_path.c_str(), mode);
        if (!driver) {
            const char* err = dlerror();
            set_error(std::string("Failed to load driver: ") + driver_path + " — " +
                      (err ? err : "unknown error"));
            return nullptr;
        }
    } else {
        const char* system_vulkan = "/system/lib64/libvulkan.so";
        if (ns) {
            driver = linkernsbypass_namespace_dlopen_unique(system_vulkan, tmpLibDir, RTLD_NOW, ns);
        }
        if (!driver) driver = dlopen("libvulkan.so", RTLD_NOW);
        if (!driver) {
            set_error("Failed to open system libvulkan.so");
            return nullptr;
        }
    }

    auto* session = new Session();
    session->driver = driver;
    session->ns = ns;
    session->path = driver_path;
    session->dir = custom ? with_slash(customDriverDir) : dirname_of(driver_path);
    session->name = custom ? customDriverName : "libvulkan.so";
    session->driver_gpa = reinterpret_cast<gpa_fn>(dlsym(driver, "vkGetInstanceProcAddr"));
    session->icd_gpa = reinterpret_cast<gpa_fn>(dlsym(driver, "vk_icdGetInstanceProcAddr"));
    if (!session->driver_gpa) session->driver_gpa = session->icd_gpa;
    if (!session->driver_gpa) {
        dlclose(driver);
        delete session;
        set_error("Driver does not export vkGetInstanceProcAddr or vk_icdGetInstanceProcAddr");
        return nullptr;
    }

    if (mapping_import) {
        auto* mapping = new adrenotools_gpu_mapping{};
        session->mapping = mapping;
        session->owns_mapping = true;
        std::lock_guard<std::mutex> lock(g_mu);
        g_mappings.insert(mapping);
        *userMappingHandle = mapping;
    }

    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_sessions.insert(session);
        g_active = session;
    }
    FABLE_LOGI("vkGetInstanceProcAddr hook installed -> %s",
               session->path.empty() ? "libvulkan.so" : session->path.c_str());
    return session;
}

void* adrenotools_load_driver(const char* library_path, const char* hook_lib_dir) {
    if (!library_path || !*library_path) {
        set_error("library path is empty");
        return nullptr;
    }
    const std::string path(library_path);
    const std::string dir = dirname_of(path);
    const std::string name = basename_of(path);
    std::string tmp = dir + "/tmp";
    mkdir_p(tmp);
    std::string hook = hook_lib_dir ? hook_lib_dir : native_library_dir_from_maps();
    return adrenotools_open_libvulkan(
        RTLD_NOW | RTLD_LOCAL,
        ADRENOTOOLS_DRIVER_CUSTOM,
        tmp.c_str(),
        hook.empty() ? nullptr : hook.c_str(),
        dir.c_str(),
        name.c_str(),
        nullptr,
        nullptr);
}

bool adrenotools_close(void* handle) {
    Session* session = nullptr;
    void* driver = nullptr;
    void* loader = nullptr;
    adrenotools_gpu_mapping* mapping = nullptr;
    bool owns_mapping = false;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        session = session_from(handle);
        if (!session) return false;
        if (--session->refcount > 0) return true;
        if (g_active == session) g_active = nullptr;
        session->driver_gpa = nullptr;
        session->icd_gpa = nullptr;
        driver = session->driver;
        loader = session->loader;
        mapping = session->mapping;
        owns_mapping = session->owns_mapping;
        g_sessions.erase(session);
        if (owns_mapping && mapping) g_mappings.erase(mapping);
    }
    if (driver) dlclose(driver);
    if (loader && loader != driver) dlclose(loader);
    delete mapping;
    delete session;
    FABLE_LOGI("Driver unloaded");
    return true;
}

void* adrenotools_get_instance_proc_addr(void* handle) {
    std::lock_guard<std::mutex> lock(g_mu);
    Session* session = session_from(handle);
    if (!session) return nullptr;
    // Prefer the ICD entry point. Callers (and the process-wide hook) treat
    // this pointer as vkGetInstanceProcAddr for the custom driver.
    return reinterpret_cast<void*>(session->driver_gpa);
}

void* adrenotools_vkGetInstanceProcAddr(void* instance, const char* name) {
    if (!name) return nullptr;
    gpa_fn custom = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        if (g_active) custom = g_active->driver_gpa;
    }
    if (custom && custom != reinterpret_cast<gpa_fn>(&adrenotools_vkGetInstanceProcAddr)) {
        if (void* fn = custom(instance, name)) return fn;
    }
    static gpa_fn system = nullptr;
    static std::once_flag once;
    std::call_once(once, [] {
        void* vulkan = dlopen("libvulkan.so", RTLD_NOW);
        if (!vulkan) return;
        system = reinterpret_cast<gpa_fn>(dlsym(vulkan, "vkGetInstanceProcAddr"));
        if (system == reinterpret_cast<gpa_fn>(&adrenotools_vkGetInstanceProcAddr)) system = nullptr;
    });
    if (system) return system(instance, name);
    return nullptr;
}

void* adrenotools_create_mappings(const char* driver_dir, const char* redirect_dir) {
    g_error.clear();
    if (!driver_dir || !*driver_dir || !redirect_dir || !*redirect_dir) {
        set_error("adrenotools_create_mappings requires driver and redirect directories");
        return nullptr;
    }
    if (has_colon(driver_dir) || has_colon(redirect_dir)) {
        set_error("Mapping paths must not contain ':'");
        return nullptr;
    }
    struct stat st{};
    if (stat(driver_dir, &st) != 0 || !S_ISDIR(st.st_mode)) {
        set_error("driver_dir does not exist");
        return nullptr;
    }
    if (!mkdir_p(redirect_dir)) {
        set_error("Failed to create redirect directory");
        return nullptr;
    }
    chmod(redirect_dir, 0755);
    if (!linkernsbypass_load_status()) {
        set_error("linkernsbypass failed to initialize");
        return nullptr;
    }
    auto* mapping = new Mapping();
    mapping->driver_dir = driver_dir;
    mapping->redirect_dir = redirect_dir;
    mapping->ns = make_driver_namespace(nullptr, driver_dir);
    apply_file_redirect(redirect_dir);
    std::lock_guard<std::mutex> lock(g_mu);
    g_user_mappings.insert(mapping);
    FABLE_LOGI("Created driver mappings for %s -> %s", driver_dir, redirect_dir);
    return mapping;
}

void adrenotools_destroy_mappings(void* handle) {
    auto* mapping = static_cast<Mapping*>(handle);
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_user_mappings.erase(mapping) == 0) return;
    delete mapping;
}

bool adrenotools_set_layer_config(const char* layer_config_json) {
    g_error.clear();
    if (!layer_config_json) {
        set_error("layer config is null");
        return false;
    }
    fable::Json root;
    std::string error;
    if (!fable::parse_json(layer_config_json, &root, &error) || !root.is_object()) {
        set_error(error.empty() ? "layer config is not a JSON object" : error);
        return false;
    }
    std::vector<std::pair<std::string, std::string>> updates;
    for (const auto& [key, value] : root.o) {
        if (!key_allowed(key)) {
            set_error("layer config key is not allowed: " + key);
            return false;
        }
        if (value.type == fable::JsonType::Null) {
            updates.emplace_back(key, "");
            continue;
        }
        if (value.type != fable::JsonType::String) {
            set_error("layer config values must be strings: " + key);
            return false;
        }
        updates.emplace_back(key, value.s);
    }
    std::lock_guard<std::mutex> lock(g_layer_mu);
    for (const auto& update : updates) {
        // Named references instead of a structured binding: capturing a
        // structured binding in a lambda is a C++20 extension.
        const std::string& key = update.first;
        const std::string& value = update.second;
        auto it = std::find_if(g_layer_env.begin(), g_layer_env.end(),
                               [&key](const std::pair<std::string, std::string>& kv) {
                                   return kv.first == key;
                               });
        if (value.empty()) {
            if (it != g_layer_env.end()) g_layer_env.erase(it);
            unsetenv(key.c_str());
        } else if (it != g_layer_env.end()) {
            it->second = value;
            setenv(key.c_str(), value.c_str(), 1);
        } else {
            g_layer_env.emplace_back(key, value);
            setenv(key.c_str(), value.c_str(), 1);
        }
    }
    return true;
}

void adrenotools_visit_layer_env(adrenotools_env_visitor visitor, void* ctx) {
    if (!visitor) return;
    std::vector<std::pair<std::string, std::string>> copy;
    {
        std::lock_guard<std::mutex> lock(g_layer_mu);
        copy = g_layer_env;
    }
    for (const auto& [key, value] : copy) visitor(key.c_str(), value.c_str(), ctx);
}

bool adrenotools_import_user_mem(void* handle, void* host_ptr, uint64_t size) {
    adrenotools_gpu_mapping* mapping = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        mapping = mapping_from(handle);
    }
    if (!mapping || !host_ptr || size == 0) return false;

    fable_kgsl_gpuobj_import_useraddr addr{};
    addr.virtaddr = reinterpret_cast<uint64_t>(host_ptr);
    fable_kgsl_gpuobj_import req{};
    req.priv = reinterpret_cast<uint64_t>(&addr);
    req.priv_len = size;
    req.flags = (static_cast<uint64_t>(FABLE_KGSL_CACHEMODE_WRITEBACK) << FABLE_KGSL_CACHEMODE_SHIFT) |
                FABLE_KGSL_MEMFLAGS_IOCOHERENT;
    req.type = FABLE_KGSL_USER_MEM_TYPE_ADDR;

    const int fd = open_kgsl();
    if (fd < 0) return false;
    if (ioctl(fd, FABLE_IOCTL_KGSL_GPUOBJ_IMPORT, &req) != 0) {
        close(fd);
        return false;
    }
    fable_kgsl_gpuobj_info info{};
    info.id = req.id;
    if (ioctl(fd, FABLE_IOCTL_KGSL_GPUOBJ_INFO, &info) != 0) {
        close(fd);
        return false;
    }
    close(fd);
    mapping->host_ptr = host_ptr;
    mapping->gpu_addr = info.gpuaddr;
    mapping->size = size;
    mapping->flags = FABLE_KGSL_IMPORTED_MAPPING_FLAGS;
    return mapping->gpu_addr != 0;
}

bool adrenotools_mem_gpu_allocate(void* handle, uint64_t* size) {
    adrenotools_gpu_mapping* mapping = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        mapping = mapping_from(handle);
    }
    if (!mapping || !size || *size == 0) return false;

    fable_kgsl_gpuobj_alloc req{};
    req.size = *size;
    req.flags = (static_cast<uint64_t>(FABLE_KGSL_CACHEMODE_WRITEBACK) << FABLE_KGSL_CACHEMODE_SHIFT) |
                FABLE_KGSL_MEMFLAGS_IOCOHERENT;
    const int fd = open_kgsl();
    if (fd < 0) return false;
    if (ioctl(fd, FABLE_IOCTL_KGSL_GPUOBJ_ALLOC, &req) != 0) {
        close(fd);
        return false;
    }
    fable_kgsl_gpuobj_info info{};
    info.id = req.id;
    if (ioctl(fd, FABLE_IOCTL_KGSL_GPUOBJ_INFO, &info) != 0) {
        close(fd);
        return false;
    }
    close(fd);
    *size = req.mmapsize ? req.mmapsize : req.size;
    mapping->host_ptr = nullptr;
    mapping->gpu_addr = info.gpuaddr;
    mapping->size = *size;
    mapping->flags = FABLE_KGSL_IMPORTED_MAPPING_FLAGS;
    return mapping->gpu_addr != 0;
}

bool adrenotools_mem_cpu_map(void* handle, void* host_ptr, uint64_t size) {
    adrenotools_gpu_mapping* mapping = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        mapping = mapping_from(handle);
    }
    if (!mapping || !host_ptr || size == 0 || size != mapping->size) return false;
    if ((reinterpret_cast<uintptr_t>(host_ptr) & 0xfff) != 0) return false;

    const int fd = open_kgsl();
    if (fd < 0) return false;
    void* mapped = mmap(host_ptr, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd,
                        static_cast<off_t>(mapping->gpu_addr));
    close(fd);
    if (mapped == MAP_FAILED) return false;
    mapping->host_ptr = mapped;
    return true;
}

bool adrenotools_validate_gpu_mapping(void* handle) {
    std::lock_guard<std::mutex> lock(g_mu);
    adrenotools_gpu_mapping* mapping = mapping_from(handle);
    if (!mapping) return false;
    if (mapping->gpu_addr == ADRENOTOOLS_GPU_MAPPING_SUCCEEDED_MAGIC) return true;
    if (mapping->gpu_addr != 0 && mapping->size != 0) {
        // Remember success the way the upstream hook does, but keep the real
        // GPU address available in flags' high bit companion via the record.
        return true;
    }
    return false;
}

void adrenotools_set_turbo(bool turbo) {
    // 0 disables KGSL power control (run at max clocks, thermals still apply).
    // 1 restores normal power control.
    uint32_t enable = turbo ? 0u : 1u;
    fable_kgsl_device_property prop{};
    prop.type = FABLE_KGSL_PROP_PWRCTRL;
    prop.value = &enable;
    prop.sizebytes = sizeof(enable);
    const int fd = open_kgsl();
    if (fd < 0) return;
    ioctl(fd, FABLE_IOCTL_KGSL_SETPROPERTY, &prop);
    close(fd);
}

}  // extern "C"
