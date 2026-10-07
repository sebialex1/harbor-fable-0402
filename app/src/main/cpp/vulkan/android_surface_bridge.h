// SPDX-License-Identifier: MIT
#pragma once
#include <android/native_window.h>
#include <vulkan/vulkan_core.h>

/* Wine runs in an exec'd Box64 process. An ANativeWindow pointer from the app's
 * GLSurfaceView cannot cross that boundary (and is already owned by EGL).
 * Instead the guest owns an ImageReader BufferQueue and sends its presented
 * pixels to the app's X drawable over a private, same-UID Unix socket. */
struct AndroidSurfaceBridge;
struct AndroidSurfaceBridge *android_bridge_create(uint32_t xwindow);
ANativeWindow *android_bridge_window(struct AndroidSurfaceBridge *bridge);
void android_bridge_attach(struct AndroidSurfaceBridge *bridge, VkSurfaceKHR surface);
void android_bridge_delete(struct AndroidSurfaceBridge *bridge);
int android_bridge_contains(VkSurfaceKHR surface);
int android_bridge_refresh(VkSurfaceKHR surface);
void android_bridge_destroy_surface(VkSurfaceKHR surface);
