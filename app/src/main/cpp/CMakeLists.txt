cmake_minimum_required(VERSION 3.22.1)
project(fable_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# adrenotools driver loader, a minimal ZIP reader (NDK zlib — the NDK does not
# ship libzip), and the Wine process launcher.
add_library(fable_native SHARED
    native_bridge.cpp
    json/mini_json.cpp
    zip/zip_archive.cpp
    meta/driver_meta.cpp
    adrenotools/src/driver.cpp
    adrenotools/lib/linkernsbypass/linkernsbypass.cpp
    wine/wine_launcher.cpp
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
