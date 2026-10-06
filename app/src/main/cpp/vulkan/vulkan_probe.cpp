// SPDX-License-Identifier: MIT
// Vulkan extension probe. See vulkan_probe.h for the document layout.
//
// No link-time dependency on libvulkan: every entry point is resolved through
// vkGetInstanceProcAddr, which is how the probe can talk to an installed ICD
// that the system loader knows nothing about.

#include "vulkan_probe.h"

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include <adrenotools/driver.h>

#include "../common/fable_log.h"

#include <dlfcn.h>
#include <sys/stat.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

using gpa_fn = PFN_vkVoidFunction (*)(VkInstance, const char*);

// --- Minimal JSON writer ------------------------------------------------------------------------

std::string json_escape(const std::string& text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    return out;
}

std::string quoted(const std::string& text) { return "\"" + json_escape(text) + "\""; }

std::string version_string(uint32_t version) {
    char buf[48];
    std::snprintf(buf, sizeof(buf), "%u.%u.%u",
                  VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));
    return buf;
}

const char* device_type_name(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

const char* result_name(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        default: return "VkResult";
    }
}

std::string result_message(const char* what, VkResult result) {
    return std::string(what) + " failed: " + result_name(result) + " (" + std::to_string(static_cast<int>(result)) + ")";
}

std::string extensions_json(const std::vector<VkExtensionProperties>& extensions) {
    std::string out = "[";
    for (size_t i = 0; i < extensions.size(); ++i) {
        if (i) out += ",";
        // extensionName is a fixed buffer; make sure it is terminated before reading it.
        char name[VK_MAX_EXTENSION_NAME_SIZE + 1];
        std::memcpy(name, extensions[i].extensionName, VK_MAX_EXTENSION_NAME_SIZE);
        name[VK_MAX_EXTENSION_NAME_SIZE] = '\0';
        out += "{\"name\":" + quoted(name) + ",\"specVersion\":" + std::to_string(extensions[i].specVersion) + "}";
    }
    out += "]";
    return out;
}

// --- Loading the entry point ---------------------------------------------------------------------

// One probe at a time: ICDs are not expected to be opened concurrently from one process.
std::mutex g_probe_mu;

// Driver sessions are kept for the life of the process. Unloading a Mesa ICD right after use is
// a known way to crash (worker threads and static state outlive vkDestroyInstance), and a
// session costs a few megabytes at most. The key includes inode and mtime so a reinstalled
// package at the same path gets a fresh load.
std::mutex g_sessions_mu;
std::map<std::string, void*> g_sessions;

void* driver_session(const std::string& path, std::string* error) {
    std::string key = path;
    struct stat st{};
    if (stat(path.c_str(), &st) == 0) {
        key += ":" + std::to_string(static_cast<long long>(st.st_ino)) + ":" +
               std::to_string(static_cast<long long>(st.st_mtime));
    }
    std::lock_guard<std::mutex> lock(g_sessions_mu);
    auto it = g_sessions.find(key);
    if (it != g_sessions.end()) return it->second;
    void* session = adrenotools_load_driver(path.c_str(), nullptr);
    if (!session) {
        const char* err = adrenotools_last_error();
        *error = (err && *err) ? err : "Couldn't load the installed driver";
        return nullptr;
    }
    g_sessions.emplace(key, session);
    return session;
}

struct Entry {
    void* dl = nullptr;  // dlopen handle (system libvulkan); the loader is reference counted
    gpa_fn gpa = nullptr;
    std::string library;
    std::string error;

    Entry() = default;
    Entry(const Entry&) = delete;
    Entry& operator=(const Entry&) = delete;
    ~Entry() {
        if (dl) dlclose(dl);
    }
};

void open_entry(const std::string& library_path, Entry* entry) {
    if (library_path.empty()) {
        entry->library = "libvulkan.so";
        entry->dl = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!entry->dl) {
            const char* err = dlerror();
            entry->error = std::string("Couldn't open the system libvulkan.so") + (err ? std::string(": ") + err : "");
            return;
        }
        entry->gpa = reinterpret_cast<gpa_fn>(dlsym(entry->dl, "vkGetInstanceProcAddr"));
        if (!entry->gpa) entry->error = "The system libvulkan.so does not export vkGetInstanceProcAddr";
        return;
    }
    entry->library = library_path;
    void* session = driver_session(library_path, &entry->error);
    if (!session) return;
    entry->gpa = reinterpret_cast<gpa_fn>(adrenotools_get_instance_proc_addr(session));
    if (!entry->gpa) entry->error = "The installed driver does not export vkGetInstanceProcAddr";
}

template <typename Fn>
Fn load(gpa_fn gpa, VkInstance instance, const char* name) {
    return reinterpret_cast<Fn>(gpa(instance, name));
}

// Count-then-fill with the VK_INCOMPLETE retry the spec allows.
template <typename Enumerate>
VkResult enumerate_extensions(Enumerate enumerate, std::vector<VkExtensionProperties>* out) {
    for (int attempt = 0; attempt < 4; ++attempt) {
        uint32_t count = 0;
        VkResult result = enumerate(&count, nullptr);
        if (result != VK_SUCCESS) return result;
        out->assign(count, VkExtensionProperties{});
        if (count == 0) return VK_SUCCESS;
        result = enumerate(&count, out->data());
        if (result == VK_INCOMPLETE) continue;
        out->resize(count);
        return result;
    }
    return VK_INCOMPLETE;
}

std::string failure(const std::string& source, const std::string& library, const std::string& error) {
    FABLE_LOGW("Vulkan probe (%s) failed: %s", source.c_str(), error.c_str());
    return "{\"ok\":false,\"source\":" + quoted(source) + ",\"library\":" + quoted(library) +
           ",\"error\":" + quoted(error) + ",\"instanceVersion\":null,\"instanceExtensions\":[],\"devices\":[]}";
}

}  // namespace

namespace fable {

std::string probe_vulkan_extensions(const std::string& library_path) {
    std::lock_guard<std::mutex> probe_lock(g_probe_mu);
    const std::string source = library_path.empty() ? "system" : "driver";
    Entry entry;
    open_entry(library_path, &entry);
    if (!entry.gpa) return failure(source, entry.library, entry.error);
    const gpa_fn gpa = entry.gpa;

    // Global commands: resolved with a null instance.
    auto vkEnumerateInstanceVersion = load<PFN_vkEnumerateInstanceVersion>(gpa, nullptr, "vkEnumerateInstanceVersion");
    auto vkEnumerateInstanceExtensionProperties =
        load<PFN_vkEnumerateInstanceExtensionProperties>(gpa, nullptr, "vkEnumerateInstanceExtensionProperties");
    auto vkCreateInstance = load<PFN_vkCreateInstance>(gpa, nullptr, "vkCreateInstance");
    if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties) {
        return failure(source, entry.library, "The driver does not expose vkCreateInstance");
    }

    uint32_t instance_version = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion && vkEnumerateInstanceVersion(&instance_version) != VK_SUCCESS) {
        instance_version = VK_API_VERSION_1_0;
    }

    std::vector<VkExtensionProperties> instance_extensions;
    {
        const VkResult result = enumerate_extensions(
            [&](uint32_t* count, VkExtensionProperties* props) {
                return vkEnumerateInstanceExtensionProperties(nullptr, count, props);
            },
            &instance_extensions);
        if (result != VK_SUCCESS) {
            return failure(source, entry.library, result_message("vkEnumerateInstanceExtensionProperties", result));
        }
    }

    // An instance with no layers and no extensions is enough to enumerate devices. Ask for the
    // version the implementation advertises; a 1.0 driver rejects anything newer, so fall back.
    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Fable Vulkan probe";
    app_info.applicationVersion = 1;
    app_info.pEngineName = "Fable";
    app_info.engineVersion = 1;
    app_info.apiVersion = instance_version;
    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult created = vkCreateInstance(&create_info, nullptr, &instance);
    if (created == VK_ERROR_INCOMPATIBLE_DRIVER && instance_version != VK_API_VERSION_1_0) {
        app_info.apiVersion = VK_API_VERSION_1_0;
        instance_version = VK_API_VERSION_1_0;
        created = vkCreateInstance(&create_info, nullptr, &instance);
    }
    if (created != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        return failure(source, entry.library, result_message("vkCreateInstance", created));
    }

    auto vkDestroyInstance = load<PFN_vkDestroyInstance>(gpa, instance, "vkDestroyInstance");
    auto vkEnumeratePhysicalDevices = load<PFN_vkEnumeratePhysicalDevices>(gpa, instance, "vkEnumeratePhysicalDevices");
    auto vkGetPhysicalDeviceProperties =
        load<PFN_vkGetPhysicalDeviceProperties>(gpa, instance, "vkGetPhysicalDeviceProperties");
    auto vkEnumerateDeviceExtensionProperties =
        load<PFN_vkEnumerateDeviceExtensionProperties>(gpa, instance, "vkEnumerateDeviceExtensionProperties");
    // Core in 1.1; without it driver name/info stay null (there is no instance extension enabled).
    PFN_vkGetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2 = nullptr;
    if (instance_version >= VK_API_VERSION_1_1) {
        vkGetPhysicalDeviceProperties2 =
            load<PFN_vkGetPhysicalDeviceProperties2>(gpa, instance, "vkGetPhysicalDeviceProperties2");
    }

    std::string out = "{\"ok\":true,\"source\":" + quoted(source) + ",\"library\":" + quoted(entry.library) +
                      ",\"error\":null,\"instanceVersion\":" + quoted(version_string(instance_version)) +
                      ",\"instanceExtensions\":" + extensions_json(instance_extensions) + ",\"devices\":[";

    std::string device_error;
    std::vector<VkPhysicalDevice> devices;
    if (!vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties || !vkEnumerateDeviceExtensionProperties) {
        device_error = "The driver does not expose the physical-device entry points";
    } else {
        uint32_t count = 0;
        VkResult result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (result == VK_SUCCESS && count > 0) {
            devices.assign(count, VK_NULL_HANDLE);
            result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
            devices.resize(count);
        }
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            device_error = result_message("vkEnumeratePhysicalDevices", result);
            devices.clear();
        }
    }

    for (size_t i = 0; i < devices.size(); ++i) {
        VkPhysicalDevice device = devices[i];
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(device, &props);

        std::vector<VkExtensionProperties> extensions;
        const VkResult ext_result = enumerate_extensions(
            [&](uint32_t* count, VkExtensionProperties* out_props) {
                return vkEnumerateDeviceExtensionProperties(device, nullptr, count, out_props);
            },
            &extensions);
        if (ext_result != VK_SUCCESS && device_error.empty()) {
            device_error = result_message("vkEnumerateDeviceExtensionProperties", ext_result);
        }

        bool has_driver_properties = props.apiVersion >= VK_API_VERSION_1_2;
        for (const VkExtensionProperties& ext : extensions) {
            if (std::strncmp(ext.extensionName, "VK_KHR_driver_properties", VK_MAX_EXTENSION_NAME_SIZE) == 0) {
                has_driver_properties = true;
                break;
            }
        }
        std::string driver_name;
        std::string driver_info;
        std::string conformance;
        if (has_driver_properties && vkGetPhysicalDeviceProperties2) {
            VkPhysicalDeviceDriverProperties driver{};
            driver.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
            VkPhysicalDeviceProperties2 props2{};
            props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            props2.pNext = &driver;
            vkGetPhysicalDeviceProperties2(device, &props2);
            char name[VK_MAX_DRIVER_NAME_SIZE + 1];
            std::memcpy(name, driver.driverName, VK_MAX_DRIVER_NAME_SIZE);
            name[VK_MAX_DRIVER_NAME_SIZE] = '\0';
            char info[VK_MAX_DRIVER_INFO_SIZE + 1];
            std::memcpy(info, driver.driverInfo, VK_MAX_DRIVER_INFO_SIZE);
            info[VK_MAX_DRIVER_INFO_SIZE] = '\0';
            driver_name = name;
            driver_info = info;
            char conf[48];
            std::snprintf(conf, sizeof(conf), "%u.%u.%u.%u", driver.conformanceVersion.major,
                          driver.conformanceVersion.minor, driver.conformanceVersion.subminor,
                          driver.conformanceVersion.patch);
            conformance = conf;
        }

        char device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE + 1];
        std::memcpy(device_name, props.deviceName, VK_MAX_PHYSICAL_DEVICE_NAME_SIZE);
        device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = '\0';

        if (i) out += ",";
        out += "{\"name\":" + quoted(device_name) +
               ",\"apiVersion\":" + quoted(version_string(props.apiVersion)) +
               ",\"apiVersionRaw\":" + std::to_string(props.apiVersion) +
               ",\"driverVersion\":" + quoted(version_string(props.driverVersion)) +
               ",\"driverVersionRaw\":" + std::to_string(props.driverVersion) +
               ",\"vendorId\":" + std::to_string(props.vendorID) +
               ",\"deviceId\":" + std::to_string(props.deviceID) +
               ",\"deviceType\":" + quoted(device_type_name(props.deviceType)) +
               ",\"driverName\":" + (driver_name.empty() ? "null" : quoted(driver_name)) +
               ",\"driverInfo\":" + (driver_info.empty() ? "null" : quoted(driver_info)) +
               ",\"conformanceVersion\":" + (conformance.empty() ? "null" : quoted(conformance)) +
               ",\"extensions\":" + extensions_json(extensions) + "}";
        FABLE_LOGI("Vulkan probe (%s): %s, API %s, %zu device extensions", source.c_str(), device_name,
                   version_string(props.apiVersion).c_str(), extensions.size());
    }
    out += "]";
    if (devices.empty() && device_error.empty()) device_error = "The driver reports no physical devices";
    // A device-level problem is still a usable result: the instance half is real.
    out += ",\"deviceError\":" + (device_error.empty() ? std::string("null") : quoted(device_error)) + "}";

    if (vkDestroyInstance) vkDestroyInstance(instance, nullptr);
    return out;
}

}  // namespace fable
