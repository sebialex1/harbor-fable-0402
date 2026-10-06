// SPDX-License-Identifier: MIT
// Wine process launcher. Prepares the container environment (including a
// preloaded adrenotools Vulkan driver) and starts Wine in a child process. On an
// ARM64 device Wine is an x86_64 binary, so it is started through an x86_64
// translator: `box64 wine <exe> [args...]` or `FEXInterpreter wine <exe> [args...]`.

#pragma once

#include <string>
#include <vector>

namespace fable {

// x86_64 translation layer the Wine binary is started through.
enum class Translator {
    kBox64,  // ptitSeb/box64
    kFex,    // FEX-Emu's FEXInterpreter
};

// "box64" / "fex" (case-insensitive) -> Translator. Unknown or empty values are Box64,
// matching the app's default.
Translator parse_translator(const std::string& name);

// Display name used in error messages ("Box64", "FEX").
const char* translator_display_name(Translator translator);

struct WineLaunchRequest {
    std::string container_path;
    // Windows program to run: a file, a Windows path (C:\...) or a Wine built-in
    // such as "explorer".
    std::string exe_path;
    std::vector<std::string> args;  // extra arguments after exe_path
    std::vector<std::string> env;   // KEY=VALUE
    std::string driver_path;        // optional installed .so
    // Which translator starts Wine.
    Translator translator = Translator::kBox64;
    // Path to the translator executable (box64 or FEXInterpreter). When empty the
    // launcher looks for a copy inside the container (bin/box64, bin/FEXInterpreter)
    // and otherwise starts wine directly (x86_64 hosts).
    std::string translator_path;
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
