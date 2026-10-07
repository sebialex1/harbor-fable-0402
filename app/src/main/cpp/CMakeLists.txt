cmake_minimum_required(VERSION 3.22.1)
project(fable_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# adrenotools driver loader, a minimal ZIP reader (NDK zlib — the NDK does not
# ship libzip), the Wine process launcher, and the Vulkan extension probe
# (header-only Vulkan use: entry points are resolved through
# vkGetInstanceProcAddr, so libvulkan is not linked).
add_library(fable_native SHARED
    native_bridge.cpp
    json/mini_json.cpp
    zip/zip_archive.cpp
    meta/driver_meta.cpp
    adrenotools/src/driver.cpp
    adrenotools/lib/linkernsbypass/linkernsbypass.cpp
    wine/wine_launcher.cpp
    vulkan/vulkan_probe.cpp
)

target_include_directories(fable_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/adrenotools/include
    ${CMAKE_CURRENT_SOURCE_DIR}/adrenotools/lib/linkernsbypass
)

find_library(log-lib log)
find_library(nativewindow-lib nativewindow)
find_library(dl-lib dl)
find_library(z-lib z)

target_link_libraries(fable_native
    ${log-lib}
    ${nativewindow-lib}
    ${dl-lib}
    ${z-lib}
)

target_compile_options(fable_native PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# JNI exports stay visible; everything else can be hidden.
set_target_properties(fable_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# Winlator's X server natives (MIT, vendored from Winlator 7.1 by brunodev85 via the
# Pipetto-crypto/winlator fork; see THIRD_PARTY_NOTICES.md): the epoll socket connector the
# Java X server (com.winlator.xconnector / com.winlator.xserver) listens with, the drawable
# blitters, and the SysV shared-memory helper. JNI names are com_winlator_*, so the library
# keeps Winlator's name: System.loadLibrary("winlator").
add_library(winlator SHARED
    winlator/drawable.c
    winlator/xconnector_epoll.c
    winlator/sysvshared_memory.c
)

target_compile_options(winlator PRIVATE
    -O2
    -Wno-unused-function
    -Wno-unused-parameter
    -Wno-sign-compare
)

find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(winlator
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

# libvulkan.so.1 shim (vulkan/vulkan_shim.c): wraps Android's /system/lib64/libvulkan.so,
# forwards the entry points Wine's win32u / winex11 dlsym directly and stubs
# vkGetPhysicalDeviceXlibPresentationSupportKHR, which Android's loader doesn't export (without
# it winevulkan asserts and DXVK gets no instance), and masquerades VK_KHR_xlib_surface as an
# instance extension (translated to Android WSI in vkCreateInstance). ImageReader provides a
# real Android BufferQueue inside Wine; presented frames go to the X server over a Unix socket.
# Packaged in the APK
# as libvulkan_shim.so;
# NativeLibResolver installs it as <filesDir>/x11/lib/libvulkan.so.1. Nothing JNI-loads it.
add_library(vulkan_shim SHARED
    vulkan/vulkan_shim.c
    vulkan/android_surface_bridge.c
)

target_compile_options(vulkan_shim PRIVATE
    -O2
    -Wall
    -Wextra
    -fvisibility=hidden
)

# Only the vk* entry points (VK_SHIM_EXPORT) are visible. The SONAME is the name Wine asks for;
# --no-undefined keeps a missing dl/log symbol from becoming a runtime failure.
set_target_properties(vulkan_shim PROPERTIES
    C_VISIBILITY_PRESET hidden
    LINK_FLAGS "-Wl,-soname,libvulkan.so.1 -Wl,--no-undefined -Wl,--exclude-libs,ALL"
)

target_link_libraries(vulkan_shim
    ${dl-lib}
    ${log-lib}
    ${android-lib}
    mediandk
)
