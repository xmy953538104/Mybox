// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>

namespace mybox {
constexpr std::size_t kPathSize = 4096;
constexpr std::size_t kMaxRules = 32;

// Filled before seccomp installation and then immutable. No locks or allocation in a signal.
class PathRules {
public:
    int Add(const char* source, const char* destination);
    int Rewrite(const char* path, char* output, bool reverse = false) const;
    std::size_t size() const { return count_; }
private:
    struct Rule {
        char source[kPathSize];
        char destination[kPathSize];
        std::size_t source_size;
        std::size_t destination_size;
    };
    Rule rules_[kMaxRules]{};
    std::size_t count_ = 0;
};
}
