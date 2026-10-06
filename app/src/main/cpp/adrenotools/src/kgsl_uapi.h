// SPDX-License-Identifier: MIT
// Minimal KGSL ioctl argument layouts for the adrenotools GPU-mapping helpers.
// Field order and widths match the Linux KGSL userspace ABI on arm64 so the
// _IOWR/_IOW request numbers agree with the kernel. This is not a copy of the
// kernel header.

#pragma once

#include <stdint.h>
#include <sys/ioctl.h>

static_assert(sizeof(void*) == 8, "KGSL helpers are built for the arm64 LP64 ABI");

#define FABLE_KGSL_IOC_TYPE 0x09

#define FABLE_KGSL_CACHEMODE_SHIFT 26
#define FABLE_KGSL_CACHEMODE_WRITEBACK 3
#define FABLE_KGSL_MEMFLAGS_IOCOHERENT (1ull << 31)
#define FABLE_KGSL_USER_MEM_TYPE_ADDR 0x00000002u
#define FABLE_KGSL_PROP_PWRCTRL 0xEu

// Flags stored on an imported mapping. The Qualcomm allocator expects this
// combination for a userspace mapping that the Vulkan driver will adopt.
#define FABLE_KGSL_IMPORTED_MAPPING_FLAGS 0xc2600ull

struct fable_kgsl_gpuobj_alloc {
    uint64_t size;
    uint64_t flags;
    uint64_t va_len;
    uint64_t mmapsize;
    unsigned int id;
    unsigned int metadata_len;
    uint64_t metadata;
};

struct fable_kgsl_gpuobj_info {
    uint64_t gpuaddr;
    uint64_t flags;
    uint64_t size;
    uint64_t va_len;
    uint64_t va_addr;
    unsigned int id;
};

struct fable_kgsl_gpuobj_import {
    uint64_t priv;
    uint64_t priv_len;
    uint64_t flags;
    unsigned int type;
    unsigned int id;
};

struct fable_kgsl_gpuobj_import_useraddr {
    uint64_t virtaddr;
};

struct fable_kgsl_device_property {
    unsigned int type;
    void* value;
    size_t sizebytes;
};

static_assert(sizeof(fable_kgsl_gpuobj_alloc) == 48, "gpuobj_alloc ABI size");
static_assert(sizeof(fable_kgsl_gpuobj_info) == 48, "gpuobj_info ABI size");
static_assert(sizeof(fable_kgsl_gpuobj_import) == 32, "gpuobj_import ABI size");
static_assert(sizeof(fable_kgsl_device_property) == 24, "device_property ABI size");

#define FABLE_IOCTL_KGSL_GPUOBJ_ALLOC \
    _IOWR(FABLE_KGSL_IOC_TYPE, 0x45, struct fable_kgsl_gpuobj_alloc)
#define FABLE_IOCTL_KGSL_GPUOBJ_INFO \
    _IOWR(FABLE_KGSL_IOC_TYPE, 0x47, struct fable_kgsl_gpuobj_info)
#define FABLE_IOCTL_KGSL_GPUOBJ_IMPORT \
    _IOWR(FABLE_KGSL_IOC_TYPE, 0x48, struct fable_kgsl_gpuobj_import)
#define FABLE_IOCTL_KGSL_SETPROPERTY \
    _IOW(FABLE_KGSL_IOC_TYPE, 0x32, struct fable_kgsl_device_property)
