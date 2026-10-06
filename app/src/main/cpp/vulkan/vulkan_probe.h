// SPDX-License-Identifier: MIT
// Vulkan extension probe.
//
// Enumerates what a Vulkan implementation reports on this device: the instance
// version, the instance extensions, and for every physical device its
// properties and device extensions (vkEnumerateDeviceExtensionProperties).
//
// Two sources are supported:
//   * an installed driver — the ICD .so is opened through the adrenotools
//     namespace loader and its vkGetInstanceProcAddr / vk_icdGetInstanceProcAddr
//     is used directly, the same way the Vulkan loader talks to an ICD;
//   * the system Vulkan — libvulkan.so, i.e. whatever driver the vendor ships.
//
// The result is a JSON document (see probe_vulkan_extensions) so the Kotlin
// side can parse it with org.json without a JNI type per field. Failures are
// reported inside the document ("ok": false, "error": "..."); the function
// never throws.

#pragma once

#include <string>

namespace fable {

// library_path: absolute path of an installed ICD, or empty for the system
// libvulkan.so.
//
// Document layout:
// {
//   "ok": true,
//   "source": "driver" | "system",
//   "library": "<path or soname>",
//   "error": null | "<message>",
//   "instanceVersion": "1.4.358",
//   "instanceExtensions": [{"name": "VK_KHR_surface", "specVersion": 25}, ...],
//   "devices": [{
//     "name": "...", "apiVersion": "1.4.358", "apiVersionRaw": 4210990,
//     "driverVersion": "26.3.0", "driverVersionRaw": 109051904,
//     "vendorId": 4098, "deviceId": 5761, "deviceType": "integrated",
//     "driverName": "radv", "driverInfo": "Mesa 26.3.0-devel",
//     "conformanceVersion": "1.4.0.0",
//     "extensions": [{"name": "VK_KHR_swapchain", "specVersion": 70}, ...]
//   }]
// }
std::string probe_vulkan_extensions(const std::string& library_path);

}  // namespace fable
