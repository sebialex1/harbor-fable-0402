cmake_minimum_required(VERSION 3.22.1)
project(fable_native)

# === Native bridge ===
add_library(fable_native SHARED
    native_bridge.cpp
)

find_library(log-lib log)
find_library(nativewindow-lib nativewindow)

target_link_libraries(fable_native
    ${log-lib}
    ${nativewindow-lib}
)

target_include_directories(fable_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
