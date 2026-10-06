// SPDX-License-Identifier: MIT

#include "mini_json.h"

#include <cctype>

namespace fable {
namespace {

struct Parser {
    const std::string& in;
    size_t i = 0;
    std::string* error = nullptr;
    int depth = 0;

    explicit Parser(const std::string& text, std::string* err) : in(text), error(err) {}

    void fail(const std::string& msg) {
        if (error && error->empty()) {
            *error = msg;
        }
    }

    void skip() {
        while (i < in.size() && std::isspace(static_cast<unsigned char>(in[i]))) {
            ++i;
        }
    }

    bool consume(char c) {
        skip();
        if (i < in.size() && in[i] == c) {
            ++i;
            return true;
        }
        return false;
    }

    bool parse_value(Json* out) {
        if (depth > 32) {
            fail("JSON nesting is too deep");
            return false;
        }
        skip();
        if (i >= in.size()) {
            fail("Unexpected end of JSON");
            return false;
        }
        const char c = in[i];
        if (c == '{') return parse_object(out);
        if (c == '[') return parse_array(out);
        if (c == '"') return parse_string_value(out);
        if (c == 't' || c == 'f') return parse_bool(out);
        if (c == 'n') return parse_null(out);
        if (c == '-' || (c >= '0' && c <= '9')) return parse_number(out);
        fail(std::string("Unexpected character in JSON: ") + c);
        return false;
    }

    bool parse_object(Json* out) {
        if (!consume('{')) {
            fail("Expected '{'");
            return false;
        }
        out->type = JsonType::Object;
        ++depth;
        skip();
        if (consume('}')) {
            --depth;
            return true;
        }
        while (true) {
            skip();
            std::string key;
            if (!parse_string(&key)) {
                fail("Expected object key");
                --depth;
                return false;
            }
            if (!consume(':')) {
                fail("Expected ':' after object key");
                --depth;
                return false;
            }
            Json value;
            if (!parse_value(&value)) {
                --depth;
                return false;
            }
            out->o.emplace(std::move(key), std::move(value));
            skip();
            if (consume('}')) {
                --depth;
                return true;
            }
            if (!consume(',')) {
                fail("Expected ',' or '}' in object");
                --depth;
                return false;
            }
        }
    }

    bool parse_array(Json* out) {
        if (!consume('[')) {
            fail("Expected '['");
            return false;
        }
        out->type = JsonType::Array;
        ++depth;
        skip();
        if (consume(']')) {
            --depth;
            return true;
        }
        while (true) {
            Json value;
            if (!parse_value(&value)) {
                --depth;
                return false;
            }
            out->a.push_back(std::move(value));
            skip();
            if (consume(']')) {
                --depth;
                return true;
            }
            if (!consume(',')) {
                fail("Expected ',' or ']' in array");
                --depth;
                return false;
            }
        }
    }

    bool parse_string_value(Json* out) {
        out->type = JsonType::String;
        return parse_string(&out->s);
    }

    bool parse_string(std::string* out) {
        skip();
        if (i >= in.size() || in[i] != '"') {
            fail("Expected string");
            return false;
        }
        ++i;
        out->clear();
        while (i < in.size()) {
            unsigned char c = static_cast<unsigned char>(in[i++]);
            if (c == '"') return true;
            if (c == '\\') {
                if (i >= in.size()) {
                    fail("Unterminated escape in string");
                    return false;
                }
                char e = in[i++];
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        out->push_back(e);
                        break;
                    case 'b':
                        out->push_back('\b');
                        break;
                    case 'f':
                        out->push_back('\f');
                        break;
                    case 'n':
                        out->push_back('\n');
                        break;
                    case 'r':
                        out->push_back('\r');
                        break;
                    case 't':
                        out->push_back('\t');
                        break;
                    case 'u': {
                        if (i + 4 > in.size()) {
                            fail("Short unicode escape");
                            return false;
                        }
                        int cp = 0;
                        for (int k = 0; k < 4; ++k) {
                            char h = in[i++];
                            cp <<= 4;
                            if (h >= '0' && h <= '9') cp += h - '0';
                            else if (h >= 'a' && h <= 'f') cp += h - 'a' + 10;
                            else if (h >= 'A' && h <= 'F') cp += h - 'A' + 10;
                            else {
                                fail("Invalid unicode escape");
                                return false;
                            }
                        }
                        append_utf8(out, cp);
                        break;
                    }
                    default:
                        fail("Invalid string escape");
                        return false;
                }
            } else if (c < 0x20) {
                fail("Raw control character in string");
                return false;
            } else {
                out->push_back(static_cast<char>(c));
            }
        }
        fail("Unterminated string");
        return false;
    }

    static void append_utf8(std::string* out, int cp) {
        if (cp < 0 || cp > 0x10FFFF) {
            out->push_back('?');
            return;
        }
        if (cp < 0x80) {
            out->push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out->push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out->push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out->push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool parse_bool(Json* out) {
        out->type = JsonType::Bool;
        if (in.compare(i, 4, "true") == 0) {
            i += 4;
            out->b = true;
            return true;
        }
        if (in.compare(i, 5, "false") == 0) {
            i += 5;
            out->b = false;
            return true;
        }
        fail("Invalid boolean");
        return false;
    }

    bool parse_null(Json* out) {
        if (in.compare(i, 4, "null") != 0) {
            fail("Expected null");
            return false;
        }
        i += 4;
        out->type = JsonType::Null;
        return true;
    }

    bool parse_number(Json* out) {
        skip();
        const size_t start = i;
        if (i < in.size() && in[i] == '-') ++i;
        if (i >= in.size() || in[i] < '0' || in[i] > '9') {
            fail("Invalid number");
            return false;
        }
        if (in[i] == '0') {
            ++i;
        } else {
            while (i < in.size() && in[i] >= '0' && in[i] <= '9') ++i;
        }
        bool is_int = true;
        if (i < in.size() && in[i] == '.') {
            is_int = false;
            ++i;
            if (i >= in.size() || in[i] < '0' || in[i] > '9') {
                fail("Invalid fractional number");
                return false;
            }
            while (i < in.size() && in[i] >= '0' && in[i] <= '9') ++i;
        }
        if (i < in.size() && (in[i] == 'e' || in[i] == 'E')) {
            is_int = false;
            ++i;
            if (i < in.size() && (in[i] == '+' || in[i] == '-')) ++i;
            if (i >= in.size() || in[i] < '0' || in[i] > '9') {
                fail("Invalid exponent");
                return false;
            }
            while (i < in.size() && in[i] >= '0' && in[i] <= '9') ++i;
        }
        const std::string token = in.substr(start, i - start);
        out->type = JsonType::Number;
        out->is_int = is_int;
        try {
            if (is_int) {
                out->i = std::stoll(token);
                out->n = static_cast<double>(out->i);
            } else {
                out->n = std::stod(token);
                out->i = static_cast<int64_t>(out->n);
            }
        } catch (...) {
            fail("Number out of range");
            return false;
        }
        return true;
    }
};

}  // namespace

const Json* Json::find(const std::string& key) const {
    if (type != JsonType::Object) return nullptr;
    auto it = o.find(key);
    if (it == o.end()) return nullptr;
    return &it->second;
}

std::optional<std::string> Json::string_field(const std::string& key) const {
    const Json* v = find(key);
    if (!v || v->type != JsonType::String) return std::nullopt;
    return v->s;
}

std::optional<int64_t> Json::int_field(const std::string& key) const {
    const Json* v = find(key);
    if (!v || v->type != JsonType::Number || !v->is_int) return std::nullopt;
    return v->i;
}

bool parse_json(const std::string& text, Json* out, std::string* error) {
    if (!out) return false;
    if (error) error->clear();
    std::string local;
    Parser p(text, error ? error : &local);
    // Allow a UTF-8 BOM.
    if (p.in.size() >= 3 &&
        static_cast<unsigned char>(p.in[0]) == 0xEF &&
        static_cast<unsigned char>(p.in[1]) == 0xBB &&
        static_cast<unsigned char>(p.in[2]) == 0xBF) {
        p.i = 3;
    }
    if (!p.parse_value(out)) return false;
    p.skip();
    if (p.i != p.in.size()) {
        p.fail("Trailing data after JSON value");
        return false;
    }
    return true;
}

}  // namespace fable
