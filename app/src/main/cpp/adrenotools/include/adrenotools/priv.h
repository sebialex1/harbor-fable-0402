// SPDX-License-Identifier: MIT
// Feature flags and GPU-mapping record shared with the public driver API.
// Flag values match libadrenotools so callers can pass the same constants.

#pragma once

#include <stdint.h>

enum {
    ADRENOTOOLS_DRIVER_CUSTOM = 1 << 0,
    ADRENOTOOLS_DRIVER_FILE_REDIRECT = 1 << 1,
    ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT = 1 << 2,
};

#define ADRENOTOOLS_GPU_MAPPING_SUCCEEDED_MAGIC 0xDEADBEEF

// host_ptr / size describe the CPU range. gpu_addr is the KGSL address after
// a successful import, or ADRENOTOOLS_GPU_MAPPING_SUCCEEDED_MAGIC once the
// mapping has been validated.
struct adrenotools_gpu_mapping {
    void* host_ptr;
    uint64_t gpu_addr;
    uint64_t size;
    uint64_t flags;
};
