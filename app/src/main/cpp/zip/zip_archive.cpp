// SPDX-License-Identifier: MIT

#include "zip_archive.h"

#include "../common/fable_log.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <zlib.h>

namespace fable {
namespace {

constexpr uint32_t kEocdSig = 0x06054b50u;
constexpr uint32_t kCdSig = 0x02014b50u;
constexpr uint32_t kLocalSig = 0x04034b50u;
constexpr uint16_t kFlagEncrypted = 0x1;

uint16_t read_le16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t read_le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

bool read_fully(std::ifstream& in, uint64_t offset, void* dst, size_t n, std::string* error) {
    in.clear();
    in.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    if (!in) {
        if (error) *error = "Failed to seek in zip";
        return false;
    }
    in.read(static_cast<char*>(dst), static_cast<std::streamsize>(n));
    if (in.gcount() != static_cast<std::streamsize>(n)) {
        if (error) *error = "Unexpected end of zip";
        return false;
    }
    return true;
}

bool inflate_raw(const uint8_t* src, size_t src_len, std::vector<uint8_t>* dst, std::string* error) {
    z_stream strm{};
    strm.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(src));
    strm.avail_in = static_cast<uInt>(src_len);
    if (inflateInit2(&strm, -MAX_WBITS) != Z_OK) {
        if (error) *error = "inflateInit2 failed";
        return false;
    }
    int rc = Z_OK;
    while (rc != Z_STREAM_END) {
        if (dst->size() == dst->capacity()) {
            dst->reserve(dst->empty() ? 4096 : dst->capacity() * 2);
        }
        const size_t old = dst->size();
        const size_t room = dst->capacity() - old;
        dst->resize(old + room);
        strm.next_out = reinterpret_cast<Bytef*>(dst->data() + old);
        strm.avail_out = static_cast<uInt>(room);
        rc = inflate(&strm, Z_NO_FLUSH);
        dst->resize(old + (room - strm.avail_out));
        if (rc == Z_STREAM_END) break;
        if (rc != Z_OK) {
            inflateEnd(&strm);
            if (error) *error = "Deflate stream is corrupt";
            return false;
        }
        if (dst->size() > ZipArchive::kMaxEntryBytes) {
            inflateEnd(&strm);
            if (error) *error = "Zip entry exceeds size limit";
            return false;
        }
    }
    inflateEnd(&strm);
    return true;
}

}  // namespace

std::unique_ptr<ZipArchive> ZipArchive::open(const std::string& path, std::string* error) {
    auto zip = std::unique_ptr<ZipArchive>(new ZipArchive());
    if (!zip->load(path, error)) return nullptr;
    return zip;
}

const ZipEntry* ZipArchive::find(const std::string& name) const {
    for (const auto& e : entries_) {
        if (e.name == name) return &e;
    }
    return nullptr;
}

const ZipEntry* ZipArchive::find_basename(const std::string& name) const {
    const ZipEntry* found = nullptr;
    for (const auto& e : entries_) {
        if (e.is_dir) continue;
        if (e.name == name) return &e;
        const auto slash = e.name.rfind('/');
        const std::string base = slash == std::string::npos ? e.name : e.name.substr(slash + 1);
        if (base == name) {
            if (found) return nullptr;  // ambiguous
            found = &e;
        }
    }
    return found;
}

bool ZipArchive::load(const std::string& path, std::string* error) {
    path_ = path;
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (error) *error = std::string("Failed to open zip file: ") + std::strerror(errno);
        return false;
    }
    in.seekg(0, std::ios::end);
    const std::streamoff file_size = in.tellg();
    if (file_size < 22) {
        if (error) *error = "File is too small to be a zip archive";
        return false;
    }

    const uint64_t size = static_cast<uint64_t>(file_size);
    const uint64_t scan = size < (65535ull + 22ull) ? size : (65535ull + 22ull);
    std::vector<uint8_t> tail(static_cast<size_t>(scan));
    if (!read_fully(in, size - scan, tail.data(), tail.size(), error)) return false;

    int64_t eocd_rel = -1;
    for (int64_t i = static_cast<int64_t>(tail.size()) - 22; i >= 0; --i) {
        if (read_le32(tail.data() + i) == kEocdSig) {
            const uint16_t comment_len = read_le16(tail.data() + i + 20);
            if (static_cast<uint64_t>(i) + 22ull + comment_len == tail.size()) {
                eocd_rel = i;
                break;
            }
        }
    }
    if (eocd_rel < 0) {
        if (error) *error = "Zip end-of-central-directory record not found";
        return false;
    }
    const uint8_t* eocd = tail.data() + eocd_rel;
    const uint16_t disk = read_le16(eocd + 4);
    const uint16_t cd_disk = read_le16(eocd + 6);
    const uint16_t entry_count = read_le16(eocd + 10);
    const uint32_t cd_size = read_le32(eocd + 12);
    const uint32_t cd_offset = read_le32(eocd + 16);
    if (disk != 0 || cd_disk != 0) {
        if (error) *error = "Multi-disk zip archives are not supported";
        return false;
    }
    if (cd_offset == 0xFFFFFFFFu || cd_size == 0xFFFFFFFFu || entry_count == 0xFFFFu) {
        if (error) *error = "ZIP64 archives are not supported";
        return false;
    }
    if (entry_count > kMaxEntries) {
        if (error) *error = "Zip has too many entries";
        return false;
    }
    if (static_cast<uint64_t>(cd_offset) + cd_size > size) {
        if (error) *error = "Zip central directory is out of range";
        return false;
    }

    std::vector<uint8_t> cd(cd_size);
    if (cd_size > 0 && !read_fully(in, cd_offset, cd.data(), cd.size(), error)) return false;

    size_t pos = 0;
    uint64_t total_uncomp = 0;
    entries_.reserve(entry_count);
    for (uint16_t n = 0; n < entry_count; ++n) {
        if (pos + 46 > cd.size()) {
            if (error) *error = "Truncated zip central directory";
            return false;
        }
        if (read_le32(cd.data() + pos) != kCdSig) {
            if (error) *error = "Invalid zip central directory signature";
            return false;
        }
        ZipEntry e;
        e.flags = read_le16(cd.data() + pos + 8);
        e.method = read_le16(cd.data() + pos + 10);
        e.crc32 = read_le32(cd.data() + pos + 16);
        e.compressed_size = read_le32(cd.data() + pos + 20);
        e.uncompressed_size = read_le32(cd.data() + pos + 24);
        const uint16_t name_len = read_le16(cd.data() + pos + 28);
        const uint16_t extra_len = read_le16(cd.data() + pos + 30);
        const uint16_t comment_len = read_le16(cd.data() + pos + 32);
        e.local_header_offset = read_le32(cd.data() + pos + 42);
        if (pos + 46ull + name_len + extra_len + comment_len > cd.size()) {
            if (error) *error = "Truncated zip central directory entry";
            return false;
        }
        e.name.assign(reinterpret_cast<const char*>(cd.data() + pos + 46), name_len);
        e.is_dir = !e.name.empty() && e.name.back() == '/';
        if (e.flags & kFlagEncrypted) {
            if (error) *error = "Encrypted zip entries are not supported";
            return false;
        }
        if (!e.is_dir) {
            if (e.method != 0 && e.method != 8) {
                if (error) *error = "Unsupported zip compression method for " + e.name;
                return false;
            }
            if (e.uncompressed_size > kMaxEntryBytes || e.compressed_size > kMaxEntryBytes) {
                if (error) *error = "Zip entry exceeds size limit: " + e.name;
                return false;
            }
            total_uncomp += e.uncompressed_size;
            if (total_uncomp > kMaxTotalBytes) {
                if (error) *error = "Zip uncompressed size exceeds limit";
                return false;
            }
        }
        entries_.push_back(std::move(e));
        pos += 46ull + name_len + extra_len + comment_len;
    }
    return true;
}

bool ZipArchive::read(const ZipEntry& entry, std::vector<uint8_t>* out, std::string* error) const {
    if (!out) return false;
    out->clear();
    if (entry.is_dir) return true;
    std::ifstream in(path_, std::ios::binary);
    if (!in) {
        if (error) *error = "Failed to reopen zip";
        return false;
    }
    uint8_t local[30];
    if (!read_fully(in, entry.local_header_offset, local, sizeof(local), error)) return false;
    if (read_le32(local) != kLocalSig) {
        if (error) *error = "Invalid zip local header for " + entry.name;
        return false;
    }
    const uint16_t name_len = read_le16(local + 26);
    const uint16_t extra_len = read_le16(local + 28);
    const uint64_t data_off = entry.local_header_offset + 30ull + name_len + extra_len;

    std::vector<uint8_t> compressed(static_cast<size_t>(entry.compressed_size));
    if (entry.compressed_size > 0 &&
        !read_fully(in, data_off, compressed.data(), compressed.size(), error)) {
        return false;
    }

    if (entry.method == 0) {
        if (entry.compressed_size != entry.uncompressed_size) {
            if (error) *error = "Stored zip entry has inconsistent sizes: " + entry.name;
            return false;
        }
        *out = std::move(compressed);
    } else if (entry.method == 8) {
        out->reserve(static_cast<size_t>(entry.uncompressed_size));
        if (!inflate_raw(compressed.data(), compressed.size(), out, error)) return false;
        if (out->size() != entry.uncompressed_size) {
            if (error) *error = "Inflated size mismatch for " + entry.name;
            return false;
        }
    } else {
        if (error) *error = "Unsupported compression method";
        return false;
    }

    if (entry.uncompressed_size > 0) {
        const uLong got = crc32(0L, reinterpret_cast<const Bytef*>(out->data()),
                                 static_cast<uInt>(out->size()));
        if (got != entry.crc32) {
            if (error) *error = "CRC mismatch for " + entry.name;
            return false;
        }
    }
    return true;
}

}  // namespace fable
