// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cerrno>
#include <cstddef>
#include <sys/syscall.h>
#include <sys/uio.h>

extern "C" long mybox_raw_syscall(long, long, long, long, long, long, long);

namespace mybox {
// Typed arguments matter: libc's syscall varargs can leave the high stack word of flags undefined.
inline int ReadSelfMemory(const void* source, void* destination, std::size_t length) {
    iovec local{destination, length}, remote{const_cast<void*>(source), length};
    long pid = mybox_raw_syscall(__NR_getpid, 0, 0, 0, 0, 0, 0);
    if (pid < 0) return static_cast<int>(-pid);
    long copied = mybox_raw_syscall(__NR_process_vm_readv, pid,
            reinterpret_cast<long>(&local), 1, reinterpret_cast<long>(&remote), 1, 0);
    if (copied < 0) return static_cast<int>(-copied);
    return copied == static_cast<long>(length) ? 0 : EFAULT;
}
}
