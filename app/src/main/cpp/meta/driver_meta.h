// SPDX-License-Identifier: MIT
// adrenotools driver package metadata.
//
// Expected meta.json (schemaVersion 1):
//   {
//     "schemaVersion": 1,
//     "libraryName": "vulkan.radeon.so",
//     "minApi": 34,
//     "vulkan": "1.4.358",
//     "name": "RADV Xclipse",
//     "description": "...",
//     "vendor": "AMD",
//     "author": "...",
//     "driverVersion": "26.3.0-devel"
//   }
//
// libraryName may be aliased as "library". Optional "abi" must be arm64-v8a
// or aarch64 when present. The named library must be an ELF64 AArch64 shared
// object inside the zip.
//
// "vulkan" is optional: packages in the common adrenotools layout only carry
// the API version inside driverVersion ("Vulkan 1.4.358"), which is parsed
// when the explicit field is absent. An unparsable version is left as 0.0.0.

#pragma once

#include <string>

namespace fable {

struct DriverMeta {
    int schema_version = -1;
    std::string library_name;
    int min_api = -1;
    std::string vulkan;
    int vulkan_major = 0;
    int vulkan_minor = 0;
    int vulkan_patch = 0;
    std::string name;
    std::string description;
    std::string author;
    std::string vendor;
    std::string driver_version;
    std::string abi;
    // Directory prefix inside the zip that holds meta.json ("" or "android/").
    std::string meta_dir;
    // Zip entry name of the driver library (meta_dir + library_name, or a
    // unique basename match).
    std::string library_entry;
};

// Parse a meta.json document. Does not check that the library exists.
bool parse_driver_meta_json(const std::string& json_text, DriverMeta* out, std::string* error);

// Open zip_path, locate meta.json, parse it, and validate schema, libraryName,
// minApi, vulkan version, ABI, and that the named .so is an arm64 ELF.
// Returns an empty string on success, otherwise a user-facing error.
std::string validate_driver_zip(const std::string& zip_path, DriverMeta* out = nullptr);

// Extract a validated package into dest_dir (created 0755). .so files are
// chmod 0755, other files 0644. Rejects external-storage destinations and
// zip-slip entries. On success returns the absolute installed library path.
std::string install_driver_zip(const std::string& zip_path,
                               const std::string& dest_dir,
                               std::string* error);

// Test hook. 0 clears the override and uses the device SDK.
void set_device_sdk_override(int sdk);

int device_sdk();

}  // namespace fable
