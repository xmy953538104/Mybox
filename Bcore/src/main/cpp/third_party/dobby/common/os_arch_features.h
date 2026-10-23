#pragma once

#include <sys/types.h>
#include <stddef.h>
#include "pac_kit.h"

// Mybox: use the platform API directly to avoid the upstream circular include.
#if defined(__ANDROID__)
#include <sys/mman.h>
#include <unistd.h>
#endif

#if defined(__arm64e__) && __has_feature(ptrauth_calls)
#include <ptrauth.h>
#endif

namespace features {

template <typename T> inline T arm_thumb_fix_addr(T &addr) {
#if defined(__arm__) || defined(__aarch64__)
  addr = (T)((uintptr_t)addr & ~1);
#endif
  return addr;
}

namespace apple {
template <typename T> inline T arm64e_pac_strip(T &addr) {
  return pac_strip(addr);
}

template <typename T> inline T arm64e_pac_sign(T &addr) {
  return pac_sign(addr);
}

template <typename T> inline T arm64e_pac_strip_and_sign(T &addr) {
  return pac_strip_and_sign(addr);
}
} // namespace apple

namespace android {
inline bool make_memory_readable(void *address, size_t size) {
#if defined(__ANDROID__)
  const auto page_size = (uintptr_t)sysconf(_SC_PAGESIZE);
  const auto start = (uintptr_t)address & ~(page_size - 1);
  const auto end = ((uintptr_t)address + size + page_size - 1) & ~(page_size - 1);
  return mprotect((void *)start, end - start, PROT_READ | PROT_EXEC) == 0;
#else
  return true;
#endif
}
} // namespace android
} // namespace features