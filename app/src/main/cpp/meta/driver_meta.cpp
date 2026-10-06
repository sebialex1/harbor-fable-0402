// SPDX-License-Identifier: MIT

#include "driver_meta.h"

#include "../common/fable_log.h"
#include "../json/mini_json.h"
#include "../zip/zip_archive.h"

#include <elf.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <sys/stat.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

namespace fable {
namespace {

int g_sdk_override = 0;

bool is_safe_component(const std::string& name) {
    if (name.empty() || name == "." || name == "..") return false;
    if (name.find('/') != std::string::npos) return false;
    if (name.find('\\') != std::string::npos) return false;
    if (name.find(':') != std::string::npos) return false;
    return true;
}

bool safe_zip_relpath(const std::string& name, std::string* error) {
    if (name.empty()) {
        if (error) *error = "Zip entry has an empty name";
        return false;
    }
    if (name[0] == '/' || name[0] == '\\') {
        if (error) *error = "Zip entry has an absolute path: " + name;
        return false;
    }
    if (name.find('\\') != std::string::npos || name.find(':') != std::string::npos) {
        if (error) *error = "Zip entry has an illegal path: " + name;
        return false;
    }
    size_t start = 0;
    while (start < name.size()) {
        auto slash = name.find('/', start);
        const std::string part = name.substr(start, slash == std::string::npos ? std::string::npos : slash - start);
        if (!part.empty() && !is_safe_component(part)) {
            if (error) *error = "Zip entry escapes the destination: " + name;
            return false;
        }
        if (slash == std::string::npos) break;
        start = slash + 1;
    }
    return true;
}

bool valid_library_name(const std::string& name, std::string* error) {
    if (name.size() < 4 || name.size() > 128) {
        if (error) *error = "libraryName must be a single .so filename";
        return false;
    }
    if (!is_safe_component(name)) {
        if (error) *error = "libraryName must be a single .so filename without directories";
        return false;
    }
    for (unsigned char c : name) {
        if (std::isspace(c) || c < 0x20) {
            if (error) *error = "libraryName contains illegal characters";
            return false;
        }
    }
    if (name.compare(name.size() - 3, 3, ".so") != 0) {
        if (error) *error = "libraryName must end with .so";
        return false;
    }
    return true;
}

bool parse_vulkan_version(const std::string& text, DriverMeta* meta, std::string* error) {
    int major = 0, minor = 0, patch = 0;
    size_t i = 0;
    auto read_num = [&](int* dst) -> bool {
        if (i >= text.size() || text[i] < '0' || text[i] > '9') return false;
        int v = 0;
        int digits = 0;
        while (i < text.size() && text[i] >= '0' && text[i] <= '9') {
            v = v * 10 + (text[i] - '0');
            ++i;
            ++digits;
            if (digits > 5 || v > 99999) return false;
        }
        *dst = v;
        return digits > 0;
    };
    if (!read_num(&major) || i >= text.size() || text[i++] != '.') {
        if (error) *error = "meta.json vulkan version is missing or invalid";
        return false;
    }
    if (!read_num(&minor) || i >= text.size() || text[i++] != '.') {
        if (error) *error = "meta.json vulkan version is missing or invalid";
        return false;
    }
    if (!read_num(&patch) || i != text.size()) {
        if (error) *error = "meta.json vulkan version is missing or invalid";
        return false;
    }
    if (major < 1 || major > 9) {
        if (error) *error = "meta.json vulkan version has an unsupported major number";
        return false;
    }
    meta->vulkan = text;
    meta->vulkan_major = major;
    meta->vulkan_minor = minor;
    meta->vulkan_patch = patch;
    return true;
}

std::string optional_string(const Json& obj, const char* key) {
    auto v = obj.string_field(key);
    return v ? *v : std::string();
}

// First "major.minor.patch" run of digits in text ("Vulkan 1.4.358" -> "1.4.358"), or "".
std::string extract_version_triplet(const std::string& text) {
    size_t i = 0;
    while (i < text.size()) {
        if (text[i] < '0' || text[i] > '9') {
            ++i;
            continue;
        }
        size_t j = i;
        int dots = 0;
        while (j < text.size() && ((text[j] >= '0' && text[j] <= '9') || text[j] == '.')) {
            if (text[j] == '.') ++dots;
            ++j;
        }
        std::string candidate = text.substr(i, j - i);
        while (!candidate.empty() && candidate.back() == '.') candidate.pop_back();
        if (dots >= 2 && candidate.find("..") == std::string::npos) return candidate;
        i = j;
    }
    return {};
}

bool is_arm64_elf(const std::vector<uint8_t>& bytes, std::string* error) {
    if (bytes.size() < 20) {
        if (error) *error = "Driver library is too small to be an ELF object";
        return false;
    }
    if (bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') {
        if (error) *error = "Driver library is not an ELF object";
        return false;
    }
    if (bytes[4] != ELFCLASS64) {
        if (error) *error = "Driver library is not a 64-bit ELF object";
        return false;
    }
    if (bytes[5] != ELFDATA2LSB) {
        if (error) *error = "Driver library is not little-endian ELF";
        return false;
    }
    const uint16_t type = static_cast<uint16_t>(bytes[16] | (bytes[17] << 8));
    const uint16_t machine = static_cast<uint16_t>(bytes[18] | (bytes[19] << 8));
    if (type != ET_DYN) {
        if (error) *error = "Driver library is not a shared object";
        return false;
    }
    if (machine != EM_AARCH64) {
        if (error) *error = "Driver library is not an arm64 ELF (expected EM_AARCH64)";
        return false;
    }
    return true;
}

const ZipEntry* locate_meta(const ZipArchive& zip) {
    const ZipEntry* best = nullptr;
    size_t best_depth = 0;
    for (const auto& e : zip.entries()) {
        if (e.is_dir) continue;
        if (e.name != "meta.json" &&
            (e.name.size() < 10 || e.name.compare(e.name.size() - 10, 10, "/meta.json") != 0)) {
            continue;
        }
        size_t depth = static_cast<size_t>(std::count(e.name.begin(), e.name.end(), '/'));
        if (!best || depth < best_depth) {
            best = &e;
            best_depth = depth;
        }
    }
    return best;
}

bool load_meta_from_zip(const ZipArchive& zip, DriverMeta* meta, std::string* error) {
    const ZipEntry* meta_entry = locate_meta(zip);
    if (!meta_entry) {
        if (error) *error = "Missing meta.json in driver package";
        return false;
    }
    if (meta_entry->uncompressed_size > 1024ull * 1024ull) {
        if (error) *error = "meta.json is unreasonably large";
        return false;
    }
    std::string path_error;
    if (!safe_zip_relpath(meta_entry->name, &path_error)) {
        if (error) *error = path_error;
        return false;
    }
    std::vector<uint8_t> bytes;
    if (!zip.read(*meta_entry, &bytes, error)) return false;
    std::string text(bytes.begin(), bytes.end());
    if (!parse_driver_meta_json(text, meta, error)) return false;

    auto slash = meta_entry->name.rfind('/');
    meta->meta_dir = slash == std::string::npos ? std::string() : meta_entry->name.substr(0, slash + 1);

    const std::string expected = meta->meta_dir + meta->library_name;
    const ZipEntry* lib = zip.find(expected);
    if (!lib) {
        lib = zip.find_basename(meta->library_name);
    }
    if (!lib) {
        if (error) *error = "Driver library '" + meta->library_name + "' not found in zip";
        return false;
    }
    if (!safe_zip_relpath(lib->name, error)) return false;
    meta->library_entry = lib->name;

    std::vector<uint8_t> elf;
    // Only the ELF header is required for the ABI check, but reading the whole
    // object also verifies CRC before we claim the package is valid.
    if (!zip.read(*lib, &elf, error)) return false;
    if (!is_arm64_elf(elf, error)) return false;
    return true;
}

bool mkdir_p(const std::string& path, std::string* error) {
    if (path.empty() || path == "/") return true;
    std::string cur;
    size_t i = 0;
    if (path[0] == '/') {
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
            if (mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                if (error) *error = std::string("Failed to create directory ") + cur + ": " + std::strerror(errno);
                return false;
            }
            struct stat st{};
            if (stat(cur.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
                if (error) *error = "Path is not a directory: " + cur;
                return false;
            }
            chmod(cur.c_str(), 0755);
        }
        if (slash == std::string::npos) break;
        i = slash + 1;
    }
    return true;
}

bool write_file(const std::string& path, const std::vector<uint8_t>& data, mode_t mode, std::string* error) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, mode);
    if (fd < 0) {
        if (error) *error = std::string("Failed to write ") + path + ": " + std::strerror(errno);
        return false;
    }
    size_t off = 0;
    while (off < data.size()) {
        const ssize_t n = write(fd, data.data() + off, data.size() - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (error) *error = std::string("Failed to write ") + path + ": " + std::strerror(errno);
            close(fd);
            unlink(path.c_str());
            return false;
        }
        off += static_cast<size_t>(n);
    }
    if (fchmod(fd, mode) != 0) {
        if (error) *error = std::string("Failed to chmod ") + path + ": " + std::strerror(errno);
        close(fd);
        unlink(path.c_str());
        return false;
    }
    fsync(fd);
    close(fd);
    return true;
}

bool on_external_storage(const std::string& path) {
    return path.find("/sdcard") != std::string::npos ||
           path.find("/storage/") != std::string::npos ||
           path.find("/mnt/media_rw/") != std::string::npos;
}

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

std::string parent_dir(const std::string& path) {
    auto slash = path.rfind('/');
    if (slash == std::string::npos) return ".";
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

}  // namespace

void set_device_sdk_override(int sdk) { g_sdk_override = sdk; }

int device_sdk() {
    if (g_sdk_override > 0) return g_sdk_override;
#ifdef __ANDROID__
    char buf[92] = {0};
    __system_property_get("ro.build.version.sdk", buf);
    return std::atoi(buf);
#else
    return 34;
#endif
}

bool parse_driver_meta_json(const std::string& json_text, DriverMeta* out, std::string* error) {
    if (!out) return false;
    Json root;
    std::string parse_error;
    if (!parse_json(json_text, &root, &parse_error) || !root.is_object()) {
        if (error) *error = parse_error.empty() ? "meta.json is not valid JSON" : ("meta.json is not valid JSON: " + parse_error);
        return false;
    }
    auto schema = root.int_field("schemaVersion");
    if (!schema) {
        if (error) *error = "meta.json schemaVersion must be 1";
        return false;
    }
    if (*schema != 1) {
        if (error) *error = "meta.json schemaVersion must be 1";
        return false;
    }
    out->schema_version = 1;

    auto library = root.string_field("libraryName");
    if (!library) library = root.string_field("library");
    if (!library) {
        if (error) *error = "meta.json missing libraryName";
        return false;
    }
    if (!valid_library_name(*library, error)) return false;
    out->library_name = *library;

    auto min_api = root.int_field("minApi");
    if (!min_api) {
        if (error) *error = "meta.json minApi must be an integer";
        return false;
    }
    if (*min_api < 1 || *min_api > 100) {
        if (error) *error = "meta.json minApi is out of range";
        return false;
    }
    out->min_api = static_cast<int>(*min_api);
    const int sdk = device_sdk();
    if (sdk > 0 && sdk < out->min_api) {
        if (error) {
            *error = "Device API " + std::to_string(sdk) + " is below driver minApi " +
                     std::to_string(out->min_api);
        }
        return false;
    }

    out->name = optional_string(root, "name");
    out->description = optional_string(root, "description");
    out->author = optional_string(root, "author");
    out->vendor = optional_string(root, "vendor");
    out->driver_version = optional_string(root, "driverVersion");
    if (out->driver_version.empty()) out->driver_version = optional_string(root, "version");

    // The Vulkan API version is optional. Packages in the common adrenotools layout
    // (RADV Xclipse, Winlator-style zips) only carry it inside driverVersion, e.g.
    // "Vulkan 1.4.358"; an explicit "vulkan" / "vulkanVersion" field wins when present.
    auto vulkan = root.string_field("vulkan");
    if (!vulkan) vulkan = root.string_field("vulkanVersion");
    if (vulkan) {
        if (!parse_vulkan_version(*vulkan, out, error)) return false;
    } else {
        const std::string embedded = extract_version_triplet(out->driver_version);
        if (!embedded.empty()) {
            std::string ignored;
            parse_vulkan_version(embedded, out, &ignored);
        }
    }
    out->abi = optional_string(root, "abi");
    if (!out->abi.empty() && out->abi != "arm64-v8a" && out->abi != "aarch64") {
        if (error) *error = "Driver ABI is not arm64-v8a";
        return false;
    }
    return true;
}

std::string validate_driver_zip(const std::string& zip_path, DriverMeta* out) {
    if (zip_path.empty()) return "Failed to open zip file";
    std::string error;
    auto zip = ZipArchive::open(zip_path, &error);
    if (!zip) return error.empty() ? "Failed to open zip file" : error;
    DriverMeta meta;
    if (!load_meta_from_zip(*zip, &meta, &error)) {
        return error.empty() ? "Invalid driver package" : error;
    }
    FABLE_LOGI("Driver zip validated: %s vulkan=%s minApi=%d entry=%s",
               meta.library_name.c_str(), meta.vulkan.c_str(), meta.min_api,
               meta.library_entry.c_str());
    if (out) *out = std::move(meta);
    return {};
}

std::string install_driver_zip(const std::string& zip_path, const std::string& dest_dir, std::string* error) {
    auto fail = [&](const std::string& msg) -> std::string {
        if (error) *error = msg;
        FABLE_LOGE("installDriver: %s", msg.c_str());
        return {};
    };
    if (zip_path.empty() || dest_dir.empty()) return fail("Missing zip path or destination");
    if (on_external_storage(dest_dir)) {
        return fail("Driver must be installed to app-private storage (dlopen rejects external storage)");
    }
    std::string open_error;
    auto zip = ZipArchive::open(zip_path, &open_error);
    if (!zip) return fail(open_error.empty() ? "Failed to open zip file" : open_error);

    DriverMeta meta;
    if (!load_meta_from_zip(*zip, &meta, &open_error)) {
        return fail(open_error.empty() ? "Invalid driver package" : open_error);
    }
    if (!mkdir_p(dest_dir, &open_error)) return fail(open_error);

    std::vector<std::string> written;
    for (const auto& entry : zip->entries()) {
        if (!safe_zip_relpath(entry.name, &open_error)) {
            for (const auto& p : written) unlink(p.c_str());
            return fail(open_error);
        }
        const std::string full = dest_dir + "/" + entry.name;
        if (entry.is_dir) {
            if (!mkdir_p(full, &open_error)) return fail(open_error);
            continue;
        }
        if (!mkdir_p(parent_dir(full), &open_error)) return fail(open_error);
        std::vector<uint8_t> data;
        if (!zip->read(entry, &data, &open_error)) {
            for (const auto& p : written) unlink(p.c_str());
            return fail(open_error.empty() ? "Failed to extract " + entry.name : open_error);
        }
        const bool is_so = entry.name.size() >= 3 &&
                           entry.name.compare(entry.name.size() - 3, 3, ".so") == 0;
        const mode_t mode = is_so ? 0755 : 0644;
        if (!write_file(full, data, mode, &open_error)) {
            for (const auto& p : written) unlink(p.c_str());
            return fail(open_error);
        }
        written.push_back(full);
    }

    const std::string installed = dest_dir + "/" + meta.library_entry;
    struct stat st{};
    if (stat(installed.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
        return fail("Installed driver library is missing: " + installed);
    }
    chmod(installed.c_str(), 0755);

    // Normalized sidecar so the Java layer can read what native accepted.
    const std::string sidecar = dest_dir + "/fable-driver.json";
    std::string json = std::string("{\n") +
                       "  \"schemaVersion\": 1,\n" +
                       "  \"libraryName\": \"" + json_escape(meta.library_name) + "\",\n" +
                       "  \"minApi\": " + std::to_string(meta.min_api) + ",\n" +
                       "  \"vulkan\": \"" + json_escape(meta.vulkan) + "\",\n" +
                       "  \"libraryPath\": \"" + json_escape(installed) + "\"";
    if (!meta.name.empty()) json += ",\n  \"name\": \"" + json_escape(meta.name) + "\"";
    if (!meta.description.empty()) json += ",\n  \"description\": \"" + json_escape(meta.description) + "\"";
    if (!meta.vendor.empty()) json += ",\n  \"vendor\": \"" + json_escape(meta.vendor) + "\"";
    if (!meta.author.empty()) json += ",\n  \"author\": \"" + json_escape(meta.author) + "\"";
    if (!meta.driver_version.empty()) json += ",\n  \"driverVersion\": \"" + json_escape(meta.driver_version) + "\"";
    json += "\n}\n";
    std::vector<uint8_t> side(json.begin(), json.end());
    std::string side_error;
    write_file(sidecar, side, 0644, &side_error);

    FABLE_LOGI("Driver installed to: %s", installed.c_str());
    return installed;
}

}  // namespace fable
