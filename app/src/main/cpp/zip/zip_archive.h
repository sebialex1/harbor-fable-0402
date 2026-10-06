// SPDX-License-Identifier: MIT
// Minimal ZIP reader. The NDK does not ship libzip; this reader uses the
// NDK zlib (inflate) and the documented ZIP local/central directory format.
// Enough for adrenotools driver packages: stored and deflate entries, no
// encryption, zip-slip checks left to the caller.

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fable {

struct ZipEntry {
    std::string name;
    uint32_t crc32 = 0;
    uint16_t method = 0;
    uint16_t flags = 0;
    uint64_t compressed_size = 0;
    uint64_t uncompressed_size = 0;
    uint64_t local_header_offset = 0;
    bool is_dir = false;
};

class ZipArchive {
public:
    static std::unique_ptr<ZipArchive> open(const std::string& path, std::string* error);

    const std::vector<ZipEntry>& entries() const { return entries_; }
    const ZipEntry* find(const std::string& name) const;
    // First entry whose name is exactly `name` or ends with "/" + name.
    const ZipEntry* find_basename(const std::string& name) const;

    bool read(const ZipEntry& entry, std::vector<uint8_t>* out, std::string* error) const;

    // Hard limits applied while opening. Driver packages are small.
    static constexpr uint64_t kMaxEntries = 512;
    static constexpr uint64_t kMaxEntryBytes = 64ull * 1024ull * 1024ull;
    static constexpr uint64_t kMaxTotalBytes = 256ull * 1024ull * 1024ull;

private:
    ZipArchive() = default;
    bool load(const std::string& path, std::string* error);

    std::string path_;
    std::vector<ZipEntry> entries_;
};

}  // namespace fable
