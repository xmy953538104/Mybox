#include <cassert>
#include <cstdint>
#include "../../main/cpp/JniHook/Layout.h"

int main() {
    using art_layout::findOffset;
    uint32_t method[] = {0, 0x10200119, 0, 0, 0x12345678, 0};
    assert(findOffset(method, sizeof(method), uint32_t(0x119), uint32_t(0xffff)) == 4);
    assert(findOffset(method, sizeof(method), uint32_t(0x12345678)) == 16);
    assert(findOffset(method, 16, uint32_t(0x12345678)) == art_layout::missing);
    assert(findOffset(method, 0, uint32_t(0)) == art_layout::missing);
    uint64_t method64[] = {0, 0x1122334455667788};
    assert(findOffset(method64, sizeof(method64), uint64_t(0x1122334455667788)) == 8);
    assert(findOffset(method64, 12, uint64_t(0x1122334455667788)) == art_layout::missing);
}
