// SPDX-License-Identifier: MIT
// Wine process launcher. Prepares the container environment (including a
// preloaded adrenotools Vulkan driver) and execs wine in a child process.

#pragma once

#include <string>
#include <vector>

namespace fable {

struct WineLaunchRequest {
    std::string container_path;
    std::string exe_path;
    std::vector<std::string> env;  // KEY=VALUE
    std::string driver_path;       // optional installed .so
};

// Returns the child pid (>0) on success, or -1 on failure. error is optional.
int launch_wine_container(const WineLaunchRequest& request, std::string* error);

// True when path is a regular file the process may execute.
bool wine_binary_available(const std::string& wine_path);

}  // namespace fable
