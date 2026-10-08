// SPDX-License-Identifier: MIT
// Host regression test for the driver package export check (driver_meta.cpp).
//
//   c++ -std=c++17 -Iapp/src/main/cpp -I<zlib include> tests/driver_meta_exports_test.cpp \
//       app/src/main/cpp/meta/driver_meta.cpp app/src/main/cpp/zip/zip_archive.cpp \
//       app/src/main/cpp/json/mini_json.cpp -lz -o /tmp/driver_meta_exports_test
//   /tmp/driver_meta_exports_test [driver.zip ...]
//
// Builds two tiny host shared objects (an Android-HAL-style one exporting only HMI, as the Turnip
// packages do, and an ICD-style one exporting vk_icdGetInstanceProcAddr, as RADV Xclipse does)
// and checks read_vulkan_exports() tells them apart. Any zip paths given are validated too, so a
// real package can be checked: Turnip zips must come out as "hal", RADV Xclipse as "icd".
#include "meta/driver_meta.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

std::vector<uint8_t> build_so(const std::string& name, const std::string& source) {
    const std::string c = "/tmp/" + name + ".c";
    const std::string so = "/tmp/" + name + ".so";
    std::ofstream(c) << source;
    const std::string cmd = "cc -shared -fPIC -o " + so + " " + c;
    if (std::system(cmd.c_str()) != 0) {
        std::fprintf(stderr, "skipping: couldn't run '%s'\n", cmd.c_str());
        std::exit(0);
    }
    std::ifstream in(so, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

}  // namespace

int main(int argc, char** argv) {
    const auto hal = fable::read_vulkan_exports(build_so("fable_hal_only", "int HMI[64] = {1};\n"));
    assert(hal.readable && hal.hmi && !hal.icd_gpa && !hal.gpa);

    const auto icd = fable::read_vulkan_exports(build_so(
        "fable_icd", "int HMI[64] = {1};\nvoid* vk_icdGetInstanceProcAddr(void* i, const char* n) { (void)i; (void)n; return 0; }\n"));
    assert(icd.readable && icd.hmi && icd.icd_gpa && !icd.gpa);

    const auto none = fable::read_vulkan_exports(build_so("fable_none", "int something_else = 1;\n"));
    assert(none.readable && !none.hmi && !none.icd_gpa && !none.gpa);

    assert(!fable::read_vulkan_exports(std::vector<uint8_t>(16, 0)).readable);

    for (int i = 1; i < argc; ++i) {
        fable::DriverMeta meta;
        const std::string error = fable::validate_driver_zip(argv[i], &meta);
        std::printf("%s: %s (library %s, entry %s)\n", argv[i], error.empty() ? "ok" : error.c_str(),
                    meta.library_entry.c_str(), meta.entry_point.c_str());
        assert(error.empty());
    }
    std::printf("driver_meta_exports_test: ok\n");
    return 0;
}
