// SPDX-License-Identifier: MIT
// Logging helpers that work in the Android NDK and in host compile checks.

#pragma once

#include <cstdarg>
#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#define FABLE_LOG_TAG "FableNative"
#define FABLE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FABLE_LOG_TAG, __VA_ARGS__)
#define FABLE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, FABLE_LOG_TAG, __VA_ARGS__)
#define FABLE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FABLE_LOG_TAG, __VA_ARGS__)
#else
#define FABLE_LOGI(...) do { std::fprintf(stderr, "I/FableNative: "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define FABLE_LOGW(...) do { std::fprintf(stderr, "W/FableNative: "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define FABLE_LOGE(...) do { std::fprintf(stderr, "E/FableNative: "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
