// SPDX-License-Identifier: Apache-2.0
#include "path_rules.h"
#include <cerrno>
#include <cstring>

namespace mybox {
namespace {
bool Prefix(const char* path, std::size_t length, const char* root, std::size_t size) {
    return length >= size && std::memcmp(path, root, size) == 0
            && (length == size || path[size] == '/');
}
bool ValidRoot(const char* path, std::size_t size) {
    if (size < 2 || size >= kPathSize || path[0] != '/') return false;
    for (std::size_t i = 0; i < size; ++i) {
        if (path[i] != '/') continue;
        if (i + 1 < size && path[i + 1] == '/') return false;
        if (i + 1 < size && path[i + 1] == '.'
                && (i + 2 == size || path[i + 2] == '/'
                    || (path[i + 2] == '.' && (i + 3 == size || path[i + 3] == '/')))) return false;
    }
    return true;
}
}

int PathRules::Add(const char* source, const char* destination) {
    if (!source || !destination) return EINVAL;
    std::size_t a = strnlen(source, kPathSize), b = strnlen(destination, kPathSize);
    if (!ValidRoot(source, a) || !ValidRoot(destination, b)) return EINVAL;
    if (source[a - 1] == '/') --a;
    if (destination[b - 1] == '/') --b;
    if (a == b && std::memcmp(source, destination, a) == 0) return EINVAL;
    for (std::size_t i = 0; i < count_; ++i) {
        if (rules_[i].source_size == a && std::memcmp(rules_[i].source, source, a) == 0) return EEXIST;
    }
    if (count_ == kMaxRules) return ENOSPC;
    Rule& rule = rules_[count_++];
    std::memcpy(rule.source, source, a);
    std::memcpy(rule.destination, destination, b);
    rule.source[a] = rule.destination[b] = 0;
    rule.source_size = a;
    rule.destination_size = b;
    return 0;
}

int PathRules::Rewrite(const char* path, char* output, bool reverse) const {
    if (!path || !output) return -EINVAL;
    std::size_t length = strnlen(path, kPathSize);
    if (length == kPathSize) return -ENAMETOOLONG;
    const Rule* chosen = nullptr;
    std::size_t matched = 0;
    if (!reverse) {
        for (std::size_t i = 0; i < count_; ++i) {
            const Rule& r = rules_[i];
            if (Prefix(path, length, r.destination, r.destination_size)) {
                std::memmove(output, path, length + 1);
                return static_cast<int>(length);
            }
        }
    }
    for (std::size_t i = 0; i < count_; ++i) {
        const Rule& r = rules_[i];
        std::size_t size = reverse ? r.destination_size : r.source_size;
        const char* from = reverse ? r.destination : r.source;
        if (size > matched && Prefix(path, length, from, size)) { chosen = &r; matched = size; }
    }
    if (!chosen) { std::memmove(output, path, length + 1); return static_cast<int>(length); }
    const char* destination = reverse ? chosen->source : chosen->destination;
    std::size_t size = reverse ? chosen->source_size : chosen->destination_size;
    if (size + length - matched >= kPathSize) return -ENAMETOOLONG;
    std::memmove(output + size, path + matched, length - matched + 1);
    std::memcpy(output, destination, size);
    return static_cast<int>(size + length - matched);
}
}
