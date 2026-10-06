// SPDX-License-Identifier: MIT
// Wine process launcher. Prepares the container environment (including a
// preloaded adrenotools Vulkan driver) and starts Wine in a child process. On an
// ARM64 device Wine is an x86_64 binary, so it is started through Box64:
// `box64 wine <exe> [args...]`.

#pragma once

#include <string>
#include <vector>

namespace fable {

struct WineLaunchRequest {
    std::string container_path;
    // Windows program to run: a file, a Windows path (C:\...) or a Wine built-in
    // such as "explorer".
    std::string exe_path;
    std::vector<std::string> args;  // extra arguments after exe_path
    std::vector<std::string> env;   // KEY=VALUE
    std::string driver_path;        // optional installed .so
    // Path to the box64 executable. When empty the launcher looks for bin/box64
    // inside the container and otherwise starts wine directly (x86_64 hosts).
    std::string box64_path;
};

// Returns the child pid (>0) on success, or -1 on failure. error is optional;
// the message is also available from last_launch_error().
int launch_wine_container(const WineLaunchRequest& request, std::string* error);

// Message of the most recent failed launch; empty when the last launch succeeded.
std::string last_launch_error();
void set_launch_error(const std::string& message);

// True when path is a regular file the process may execute.
bool wine_binary_available(const std::string& wine_path);

}  // namespace fable
