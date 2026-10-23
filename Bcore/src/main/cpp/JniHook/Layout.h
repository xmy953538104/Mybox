#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace art_layout {
constexpr size_t missing = static_cast<size_t>(-1);

template <typename T>
size_t findOffset(const void *record, size_t size, T expected, T mask = ~T(0)) {
    const auto *bytes = static_cast<const uint8_t *>(record);
    for (size_t offset = 0; offset + sizeof(T) <= size; offset += alignof(T)) {
        T value;
        std::memcpy(&value, bytes + offset, sizeof(value));
        if ((value & mask) == expected) return offset;
    }
    return missing;
}
}
