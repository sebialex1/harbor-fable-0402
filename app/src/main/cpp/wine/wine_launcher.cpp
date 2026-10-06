// SPDX-License-Identifier: MIT

#include "wine_launcher.h"

#include "../common/fable_log.h"
#include <adrenotools/driver.h>

#include <cctype>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <map>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern char** environ;

namespace fable {
namespace {

bool is_regular(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool is_dir(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool looks_like_windows_path(const std::string& path) {
    if (path.size() >= 2 && std::isalpha(static_cast<unsigned char>(path[0])) && path[1] == ':') {
        return true;
    }
    return path.find('\\') != std::string::npos;
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

bool mkdir_one(const std::string& path) {
    if (path.empty()) return false;
    if (mkdir(path.c_str(), 0755) == 0 || errno == EEXIST) return is_dir(path);
    return false;
}

std::string find_wine(const std::string& container) {
    const char* rels[] = {"/bin/wine", "/bin/wine64", "/wine", "/wine64", "/usr/bin/wine", nullptr};
    for (int i = 0; rels[i]; ++i) {
        const std::string candidate = container + rels[i];
        if (is_regular(candidate) && access(candidate.c_str(), X_OK) == 0) return candidate;
    }
    return {};
}

std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (unsigned char c : in) {
        switch (c) {
            case '\\':
                out += "\\\\";
                break;
            case '"':
                out += "\\\"";
                break;
            default:
                if (c < 0x20) continue;
                out.push_back(static_cast<char>(c));
                break;
        }
    }
    return out;
}

bool write_icd(const std::string& driver_path, std::string* icd_path, std::string* error) {
    const std::string path = dirname_of(driver_path) + "/fable_icd.json";
    const std::string body =
        std::string("{\n") +
        "  \"file_format_version\": \"1.0.0\",\n" +
        "  \"ICD\": {\n" +
        "    \"library_path\": \"" + json_escape(driver_path) + "\",\n" +
        "    \"api_version\": \"1.3.0\"\n" +
        "  }\n" +
        "}\n";
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        if (error) *error = std::string("Failed to write ICD manifest: ") + std::strerror(errno);
        return false;
    }
    const ssize_t n = write(fd, body.data(), body.size());
    close(fd);
    if (n != static_cast<ssize_t>(body.size())) {
        if (error) *error = "Short write of ICD manifest";
        return false;
    }
    *icd_path = path;
    return true;
}

void add_env(std::map<std::string, std::string>* env, const std::string& key, const std::string& value) {
    (*env)[key] = value;
}

void prepend_path(std::map<std::string, std::string>* env, const char* key, const std::string& prefix) {
    if (prefix.empty()) return;
    auto it = env->find(key);
    if (it == env->end() || it->second.empty()) {
        (*env)[key] = prefix;
        return;
    }
    if (it->second == prefix || it->second.rfind(prefix + ":", 0) == 0) return;
    it->second = prefix + ":" + it->second;
}

struct LayerCopy {
    std::map<std::string, std::string>* env;
};

void layer_cb(const char* key, const char* value, void* ctx) {
    auto* copy = static_cast<LayerCopy*>(ctx);
    if (key && value) (*copy->env)[key] = value;
}

}  // namespace

bool wine_binary_available(const std::string& wine_path) {
    if (wine_path.empty()) return false;
    return is_regular(wine_path) && access(wine_path.c_str(), X_OK) == 0;
}

int launch_wine_container(const WineLaunchRequest& request, std::string* error) {
    auto fail = [&](const std::string& msg) {
        if (error) *error = msg;
        FABLE_LOGE("wine launch: %s", msg.c_str());
        return -1;
    };
    if (request.container_path.empty()) return fail("Container path is empty");
    if (request.exe_path.empty()) return fail("Executable path is empty");
    if (!is_dir(request.container_path)) return fail("Container path is not a directory");
    if (!looks_like_windows_path(request.exe_path) && !is_regular(request.exe_path)) {
        return fail("Executable not found: " + request.exe_path);
    }

    const std::string wine = find_wine(request.container_path);
    if (wine.empty()) return fail("Wine binary not found under " + request.container_path);

    mkdir_one(request.container_path + "/tmp");
    mkdir_one(request.container_path + "/cache");

    std::map<std::string, std::string> env;
    for (char** e = environ; e && *e; ++e) {
        std::string item(*e);
        const auto eq = item.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        env.emplace(item.substr(0, eq), item.substr(eq + 1));
    }

    add_env(&env, "WINEPREFIX", request.container_path);
    add_env(&env, "HOME", request.container_path);
    add_env(&env, "TMPDIR", request.container_path + "/tmp");
    add_env(&env, "XDG_CACHE_HOME", request.container_path + "/cache");
    prepend_path(&env, "PATH", request.container_path + "/bin");

    LayerCopy layer{&env};
    adrenotools_visit_layer_env(layer_cb, &layer);

    if (!request.driver_path.empty()) {
        if (!is_regular(request.driver_path)) return fail("Driver library not found: " + request.driver_path);
        std::string icd;
        std::string icd_error;
        if (!write_icd(request.driver_path, &icd, &icd_error)) return fail(icd_error);
        const std::string driver_dir = dirname_of(request.driver_path);
        add_env(&env, "ADRENOTOOLS_DRIVER_PATH", request.driver_path);
        add_env(&env, "ADRENOTOOLS_DRIVER_NAME", basename_of(request.driver_path));
        add_env(&env, "FABLE_VULKAN_DRIVER", request.driver_path);
        add_env(&env, "VK_ICD_FILENAMES", icd);
        add_env(&env, "VK_DRIVER_FILES", icd);
        prepend_path(&env, "LD_LIBRARY_PATH", driver_dir);
        // Preload the ICD into the Wine process. A later user env var can
        // replace LD_PRELOAD if a particular driver cannot be preloaded.
        auto preload = env.find("LD_PRELOAD");
        if (preload == env.end() || preload->second.empty()) {
            add_env(&env, "LD_PRELOAD", request.driver_path);
        } else if (preload->second.find(request.driver_path) == std::string::npos) {
            preload->second = request.driver_path + ":" + preload->second;
        }
    }

    for (const auto& item : request.env) {
        const auto eq = item.find('=');
        if (eq == std::string::npos || eq == 0) return fail("Malformed environment variable: " + item);
        const std::string key = item.substr(0, eq);
        for (unsigned char c : key) {
            if (!(std::isalnum(c) || c == '_')) return fail("Illegal environment variable name: " + key);
        }
        env[key] = item.substr(eq + 1);
    }

    std::vector<std::string> storage;
    storage.reserve(env.size());
    std::vector<char*> envp;
    envp.reserve(env.size() + 1);
    for (const auto& [key, value] : env) {
        storage.push_back(key + "=" + value);
    }
    for (auto& item : storage) envp.push_back(item.data());
    envp.push_back(nullptr);

    std::string argv0 = wine;
    std::string argv1 = request.exe_path;
    char* argv[] = {argv0.data(), argv1.data(), nullptr};

    FABLE_LOGI("exec wine %s %s (driver=%s)", wine.c_str(), request.exe_path.c_str(),
               request.driver_path.empty() ? "none" : request.driver_path.c_str());

    const pid_t pid = fork();
    if (pid < 0) return fail(std::string("fork failed: ") + std::strerror(errno));
    if (pid == 0) {
        // Only async-signal-safe calls are legal here: the parent is multithreaded.
        execve(wine.c_str(), argv, envp.data());
        _exit(127);
    }
    return static_cast<int>(pid);
}

}  // namespace fable
