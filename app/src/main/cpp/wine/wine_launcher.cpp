// SPDX-License-Identifier: MIT

#include "wine_launcher.h"

#include "../common/fable_log.h"
#include <adrenotools/driver.h>

#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

extern char** environ;

namespace fable {
namespace {

// Wine and translator (Box64/FEX) output goes here, inside the container directory.
constexpr const char* kLaunchLogName = "fable-launch.log";

std::mutex g_error_mutex;
std::string g_last_error;

bool is_regular(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool is_dir(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_executable_file(const std::string& path) {
    return is_regular(path) && access(path.c_str(), X_OK) == 0;
}

// A bare program name ("explorer", "winecfg") that Wine resolves on its own.
bool is_wine_builtin_name(const std::string& path) {
    return path.find('/') == std::string::npos && path.find('\\') == std::string::npos;
}

#if defined(__aarch64__)
// ELF e_machine values.
constexpr uint16_t kMachineX86_64 = 62;
constexpr uint16_t kMachineAarch64 = 183;

// ELF e_machine of path, or 0 when it is not a readable ELF file.
uint16_t elf_machine(const std::string& path) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char header[20];
    const ssize_t n = read(fd, header, sizeof(header));
    close(fd);
    if (n != static_cast<ssize_t>(sizeof(header))) return 0;
    if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return 0;
    return static_cast<uint16_t>(header[18] | (header[19] << 8));
}
#endif

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
        if (is_executable_file(candidate)) return candidate;
    }
    return {};
}

// The translator executable: `requested` when given (empty result if it is not executable),
// otherwise a copy installed inside the container. Empty when there is none.
std::string find_translator(const std::string& container, Translator translator, const std::string& requested) {
    if (!requested.empty()) return is_executable_file(requested) ? requested : std::string();
    const char* box64_rels[] = {"/bin/box64", "/box64", "/usr/bin/box64", nullptr};
    const char* fex_rels[] = {"/bin/FEXInterpreter", "/FEXInterpreter", "/usr/bin/FEXInterpreter", nullptr};
    const char** rels = translator == Translator::kFex ? fex_rels : box64_rels;
    for (int i = 0; rels[i]; ++i) {
        const std::string candidate = container + rels[i];
        if (is_executable_file(candidate)) return candidate;
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

// Owns the native launch log descriptor so every early return closes it.
struct LogFd {
    int fd = -1;
    ~LogFd() {
        if (fd >= 0) close(fd);
    }
    int release() {
        const int out = fd;
        fd = -1;
        return out;
    }
};

// Parent-side breadcrumb in fable-launch.log. Never use this in the forked child.
#define FABLE_CRUMB(log_fd, ...)                       \
    do {                                               \
        if ((log_fd) >= 0) {                           \
            dprintf((log_fd), "[fable] native: ");     \
            dprintf((log_fd), __VA_ARGS__);            \
            dprintf((log_fd), "\n");                   \
        }                                              \
    } while (0)

struct LayerCopy {
    std::map<std::string, std::string>* env;
};

void layer_cb(const char* key, const char* value, void* ctx) {
    auto* copy = static_cast<LayerCopy*>(ctx);
    if (key && value) (*copy->env)[key] = value;
}

}  // namespace

Translator parse_translator(const std::string& name) {
    std::string lower;
    lower.reserve(name.size());
    for (unsigned char c : name) lower.push_back(static_cast<char>(std::tolower(c)));
    if (lower == "fex" || lower == "fex-emu" || lower == "fexinterpreter") return Translator::kFex;
    return Translator::kBox64;
}

const char* translator_display_name(Translator translator) {
    return translator == Translator::kFex ? "FEX" : "Box64";
}

std::string last_launch_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

void set_launch_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
}

bool wine_binary_available(const std::string& wine_path) {
    if (wine_path.empty()) return false;
    return is_regular(wine_path) && access(wine_path.c_str(), X_OK) == 0;
}

int launch_wine_container(const WineLaunchRequest& request, std::string* error) {
    set_launch_error("");
    // Opened as soon as the container path is known (below), so a crash anywhere later leaves a
    // trail of breadcrumbs in fable-launch.log showing the last stage that was reached.
    LogFd launch_log;
    auto fail = [&](const std::string& msg) {
        if (error) *error = msg;
        set_launch_error(msg);
        FABLE_LOGE("wine launch: %s", msg.c_str());
        FABLE_CRUMB(launch_log.fd, "failed: %s", msg.c_str());
        return -1;
    };
    if (request.container_path.empty()) return fail("Container path is empty");
    const std::string log_path = request.container_path + "/" + kLaunchLogName;
    launch_log.fd = open(log_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0644);
    if (launch_log.fd < 0) FABLE_LOGW("wine launch: can't open %s: %s", log_path.c_str(), std::strerror(errno));
    FABLE_CRUMB(launch_log.fd, "launcher: JNI fork/execve, container %s", request.container_path.c_str());
    if (request.exe_path.empty()) return fail("Executable path is empty");
    if (!is_dir(request.container_path)) return fail("Container path is not a directory");
    if (!is_wine_builtin_name(request.exe_path) && !looks_like_windows_path(request.exe_path) &&
        !is_regular(request.exe_path)) {
        return fail("Executable not found: " + request.exe_path);
    }

    const std::string wine = find_wine(request.container_path);
    if (wine.empty()) return fail("Wine isn't installed in this container");

    // Box64 or FEX translates the x86_64 Wine binary on ARM64. An explicit path must be
    // usable; without one, a copy inside the container is used when present.
    const char* translator_name = translator_display_name(request.translator);
    const std::string translator =
        find_translator(request.container_path, request.translator, request.translator_path);
    if (!request.translator_path.empty() && translator.empty()) {
        return fail(std::string(translator_name) + " is not executable: " + request.translator_path);
    }
#if defined(__aarch64__)
    if (translator.empty() && elf_machine(wine) == kMachineX86_64) {
        return fail(std::string("Wine is an x86_64 build and needs ") + translator_name + " on this device");
    }
#endif
    const bool use_box64 = !translator.empty() && request.translator == Translator::kBox64;
    const bool use_fex = !translator.empty() && request.translator == Translator::kFex;

    FABLE_CRUMB(launch_log.fd, "wine=%s translator=%s", wine.c_str(), translator.empty() ? "(none)" : translator.c_str());

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
    // Winlator runs Wine as "xuser"; Android has no USER, and Wine names the per-user profile
    // (drive_c/users/<USER>) from it, which the bionic prefixPack ships as xuser.
    add_env(&env, "USER", "xuser");
    add_env(&env, "TMPDIR", request.container_path + "/tmp");
    add_env(&env, "XDG_CACHE_HOME", request.container_path + "/cache");
    prepend_path(&env, "PATH", request.container_path + "/bin");
    FABLE_CRUMB(launch_log.fd, "env setup done (%zu variables)", env.size());

    LayerCopy layer{&env};
    adrenotools_visit_layer_env(layer_cb, &layer);
    FABLE_CRUMB(launch_log.fd, "layer env done (%zu variables)", env.size());

    if (!request.driver_path.empty()) {
        if (!is_regular(request.driver_path)) return fail("Driver library not found: " + request.driver_path);
        std::string icd;
        std::string icd_error;
        if (!write_icd(request.driver_path, &icd, &icd_error)) return fail(icd_error);
        FABLE_CRUMB(launch_log.fd, "driver ICD written: %s", icd.c_str());
        const std::string driver_dir = dirname_of(request.driver_path);
        add_env(&env, "ADRENOTOOLS_DRIVER_PATH", request.driver_path);
        add_env(&env, "ADRENOTOOLS_DRIVER_NAME", basename_of(request.driver_path));
        add_env(&env, "FABLE_VULKAN_DRIVER", request.driver_path);
        add_env(&env, "VK_ICD_FILENAMES", icd);
        add_env(&env, "VK_DRIVER_FILES", icd);
        // The driver is found through the ICD manifest only. It is deliberately NOT put in
        // LD_PRELOAD: the child is box64 (or FEXInterpreter), and preloading a host Vulkan
        // driver into the translator before main() runs its constructors in a process that
        // never uses it directly; with some drivers that aborts the child at start-up.
        // Winlator does not preload the driver either. driver_dir stays on LD_LIBRARY_PATH so
        // the driver's own dependencies resolve when the loader dlopen()s it.
        prepend_path(&env, "LD_LIBRARY_PATH", driver_dir);
    }

    for (const auto& item : request.env) {
        const auto eq = item.find('=');
        if (eq == std::string::npos || eq == 0) return fail("Malformed environment variable: " + item);
        const std::string key = item.substr(0, eq);
        for (unsigned char c : key) {
            if (!(std::isalnum(c) || c == '_')) return fail("Illegal environment variable name: " + key);
        }
        const std::string value = item.substr(eq + 1);
        if (key == "LD_LIBRARY_PATH" || key == "PATH") {
            // Search paths from the caller (the X11 client libraries) go in front of what the
            // launcher already set (the driver directory), instead of replacing it.
            prepend_path(&env, key.c_str(), value);
        } else {
            env[key] = value;
        }
    }

    if (use_box64) {
        // Where Box64 looks for the emulated program's libraries and helper executables
        // (wineserver). Anything the caller set above wins.
        env.emplace("BOX64_PATH", dirname_of(wine));
        env.emplace("BOX64_LD_LIBRARY_PATH",
                    request.container_path + "/lib/wine/x86_64-unix:" + request.container_path + "/lib:" +
                        request.container_path + "/lib64");
    }
    if (use_fex) {
        // FEXInterpreter starts FEXServer (shipped next to it) on demand and looks it up on
        // PATH. Its config lives under $HOME/.fex-emu, i.e. inside the container. The guest
        // RootFS (FEX_ROOTFS) is passed by the caller in request.env.
        prepend_path(&env, "PATH", dirname_of(translator));
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

    // argv: [box64 | FEXInterpreter] wine exe [args...]
    std::vector<std::string> command;
    if (!translator.empty()) command.push_back(translator);
    command.push_back(wine);
    command.push_back(request.exe_path);
    for (const auto& arg : request.args) command.push_back(arg);
    std::vector<char*> argv;
    argv.reserve(command.size() + 1);
    for (auto& item : command) argv.push_back(item.data());
    argv.push_back(nullptr);
    const std::string program = command.front();
    FABLE_CRUMB(launch_log.fd, "argv built (%zu items), envp built (%zu items)", command.size(), storage.size());

#if defined(__ANDROID__) && defined(__LP64__)
    // Android 10+ refuses execve() on files in an app's data directory for apps targeting
    // API 29 and up. The system linker can still map such an executable, so it is the fallback.
    // That only works for an ARM64 (bionic) ELF: the bionic Box64/FEX builds qualify, an x86_64
    // or glibc binary does not, so the fallback is armed only for an aarch64 program.
    const std::string linker = "/system/bin/linker64";
#if defined(__aarch64__)
    const bool linker_fallback = elf_machine(program) == kMachineAarch64;
#else
    const bool linker_fallback = false;
#endif
    const char kFallbackNote[] = "[fable] execve: EACCES, retrying through /system/bin/linker64\n";
    std::vector<char*> linker_argv;
    linker_argv.push_back(const_cast<char*>(linker.c_str()));
    for (char* item : argv) linker_argv.push_back(item);
#endif

    std::string printable;
    for (const auto& item : command) printable += (printable.empty() ? "" : " ") + item;
    FABLE_LOGI("exec %s (translator=%s, driver=%s)", printable.c_str(),
               translator.empty() ? "none" : translator_name,
               request.driver_path.empty() ? "none" : request.driver_path.c_str());

    const int log_fd = launch_log.fd;
    if (log_fd >= 0) {
        dprintf(log_fd, "[fable] %s\n", printable.c_str());
        for (const auto& item : storage) {
            // The full child environment, so a failed start can be reproduced from the log.
            if (item.rfind("DISPLAY=", 0) == 0 || item.rfind("WINE", 0) == 0 || item.rfind("BOX64", 0) == 0 ||
                item.rfind("LD_", 0) == 0 || item.rfind("PATH=", 0) == 0 || item.rfind("HOME=", 0) == 0 ||
                item.rfind("TMPDIR=", 0) == 0 || item.rfind("VK_", 0) == 0 || item.rfind("FEX", 0) == 0) {
                dprintf(log_fd, "[fable] env %s\n", item.c_str());
            }
        }
    }
    const int null_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);

    // The child reports a failed exec through this pipe; it closes on a successful exec.
    int status_pipe[2];
    if (pipe2(status_pipe, O_CLOEXEC) != 0) {
        const std::string message = std::string("pipe failed: ") + std::strerror(errno);
        if (null_fd >= 0) close(null_fd);
        return fail(message);
    }
    FABLE_CRUMB(log_fd, "status pipe created (%d, %d)", status_pipe[0], status_pipe[1]);

    long open_max = sysconf(_SC_OPEN_MAX);
    if (open_max <= 0 || open_max > 65536) open_max = 4096;
    const int max_fd = static_cast<int>(open_max);

    // Child breadcrumbs: fixed buffers prepared before fork, written with write(2) only.
    static const char kChildForked[] = "[fable] native child: forked, stdio redirected\n";
    static const char kChildFdsClosed[] = "[fable] native child: descriptors closed, calling execve\n";
    static const char kChildExecFailed[] = "[fable] native child: execve returned\n";

    FABLE_CRUMB(log_fd, "before fork");
    const pid_t pid = fork();
    if (pid < 0) {
        const std::string message = std::string("fork failed: ") + std::strerror(errno);
        close(status_pipe[0]);
        close(status_pipe[1]);
        if (null_fd >= 0) close(null_fd);
        return fail(message);
    }
    if (pid == 0) {
        // Only async-signal-safe calls are legal here: the parent is multithreaded. No dprintf,
        // std::string, strerror or allocation; breadcrumbs go out with write(STDERR_FILENO).
        if (null_fd >= 0) dup2(null_fd, STDIN_FILENO);
        if (log_fd >= 0) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            const ssize_t crumb = write(STDERR_FILENO, kChildForked, sizeof(kChildForked) - 1);
            (void)crumb;
        }
        // Don't leak the app's descriptors (the X server's listening socket, binder, ...) into
        // Wine, as Runtime.exec/ProcessBuilder (what Winlator uses) doesn't either. The status
        // pipe is O_CLOEXEC and must stay open until exec.
        for (int fd = 3; fd < max_fd; ++fd) {
            if (fd != status_pipe[1]) close(fd);
        }
        if (log_fd >= 0) {
            const ssize_t crumb = write(STDERR_FILENO, kChildFdsClosed, sizeof(kChildFdsClosed) - 1);
            (void)crumb;
        }
        execve(program.c_str(), argv.data(), envp.data());
        const int exec_errno = errno;
        if (log_fd >= 0) {
            const ssize_t crumb = write(STDERR_FILENO, kChildExecFailed, sizeof(kChildExecFailed) - 1);
            (void)crumb;
        }
#if defined(__ANDROID__) && defined(__LP64__)
        if (exec_errno == EACCES && linker_fallback) {
            if (log_fd >= 0) {
                const ssize_t noted = write(STDERR_FILENO, kFallbackNote, sizeof(kFallbackNote) - 1);
                (void)noted;
            }
            execve(linker.c_str(), linker_argv.data(), envp.data());
        }
#endif
        const ssize_t ignored = write(status_pipe[1], &exec_errno, sizeof(exec_errno));
        (void)ignored;
        _exit(127);
    }

    FABLE_CRUMB(log_fd, "after fork (parent), child pid %d", static_cast<int>(pid));
    close(status_pipe[1]);
    if (null_fd >= 0) close(null_fd);

    FABLE_CRUMB(log_fd, "before read of status pipe");
    int child_errno = 0;
    ssize_t got;
    do {
        got = read(status_pipe[0], &child_errno, sizeof(child_errno));
    } while (got < 0 && errno == EINTR);
    close(status_pipe[0]);
    FABLE_CRUMB(log_fd, "after read of status pipe (got %zd)", got);
    if (got == static_cast<ssize_t>(sizeof(child_errno))) {
        int ignored_status = 0;
        waitpid(pid, &ignored_status, 0);
        std::string reason = std::strerror(child_errno);
#if defined(__ANDROID__) && defined(__LP64__)
        if (child_errno == EACCES && !linker_fallback) {
            reason += " (not an ARM64 executable, so the linker64 fallback can't start it)";
        }
#endif
        if (log_fd >= 0) dprintf(log_fd, "[fable] exec failed: %s\n", reason.c_str());
        return fail("Couldn't run " + basename_of(program) + ": " + reason);
    }

    // Reap the child when it exits and note how it ended in the launch log.
    std::thread([pid, log_path]() {
        int status = 0;
        pid_t reaped;
        do {
            reaped = waitpid(pid, &status, 0);
        } while (reaped < 0 && errno == EINTR);
        std::string line;
        if (reaped == pid && WIFEXITED(status)) {
            line = "[fable] exit code " + std::to_string(WEXITSTATUS(status));
        } else if (reaped == pid && WIFSIGNALED(status)) {
            line = "[fable] killed by signal " + std::to_string(WTERMSIG(status));
        } else {
            line = "[fable] exit status unknown";
        }
        FABLE_LOGI("wine process %d: %s", static_cast<int>(pid), line.c_str());
        const int fd = open(log_path.c_str(), O_WRONLY | O_APPEND | O_CLOEXEC);
        if (fd >= 0) {
            line += "\n";
            const ssize_t written = write(fd, line.data(), line.size());
            (void)written;
            close(fd);
        }
    }).detach();

    FABLE_CRUMB(log_fd, "return pid %d", static_cast<int>(pid));
    close(launch_log.release());
    return static_cast<int>(pid);
}

}  // namespace fable
