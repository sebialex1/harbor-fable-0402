// SPDX-License-Identifier: MIT
// Small JSON reader for driver meta.json and Vulkan layer config.
// Supports objects, arrays, strings, numbers, booleans, and null.

#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace fable {

enum class JsonType { Null, Bool, Number, String, Object, Array };

struct Json {
    JsonType type = JsonType::Null;
    bool b = false;
    bool is_int = false;
    int64_t i = 0;
    double n = 0.0;
    std::string s;
    std::map<std::string, Json> o;
    std::vector<Json> a;

    const Json* find(const std::string& key) const;
    std::optional<std::string> string_field(const std::string& key) const;
    std::optional<int64_t> int_field(const std::string& key) const;
    bool is_object() const { return type == JsonType::Object; }
};

// Parse a full JSON document. On failure returns false and sets error.
bool parse_json(const std::string& text, Json* out, std::string* error);

}  // namespace fable
