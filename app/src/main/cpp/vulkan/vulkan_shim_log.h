// SPDX-License-Identifier: MIT
/* Shared diagnostic logger of the libvulkan.so.1 shim (defined in vulkan_shim.c): logcat (tag
 * vulkan_shim), stderr prefixed "[vulkan_shim]" (the Wine process log) and $FABLE_VULKAN_SHIM_LOG.
 * Hidden symbol: -fvisibility=hidden keeps it out of the shim's export table. */
#pragma once

#include <android/log.h>

__attribute__((format(printf, 2, 3), visibility("hidden")))
void shim_log(int prio, const char *fmt, ...);

#define LOGI(...) shim_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) shim_log(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) shim_log(ANDROID_LOG_ERROR, __VA_ARGS__)
