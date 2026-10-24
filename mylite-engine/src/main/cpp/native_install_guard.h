// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>

namespace mybox {
// One attempt in a dedicated process, before guest code. Success parks the existing threads;
// until FinishInstallGuard only raw syscalls and lock-free operations may run on the installer.
int BeginInstallGuard();
int FinishInstallGuard(bool filter_installed);

// Read only after the install attempt returns, on its serialized configuration thread.
struct InstallGuardCandidate {
    int signal = 0;
    const char* reason = "not-examined";
    int tid = 0;
    std::uint64_t blocked = 0, pending = 0;
};
struct InstallGuardDiagnostics {
    const char* stage = "not-started";
    int cause_errno = 0, cleanup_errno = 0;
    int signal = 0, tid = 0, mask_errno = 0;
    std::uint64_t blocked = 0, pending = 0;
    unsigned threads = 0, targets = 0, acknowledged = 0;
    InstallGuardCandidate candidates[2];
};
InstallGuardDiagnostics GetInstallGuardDiagnostics();

#if defined(MYLITE_NATIVE_TESTING)
// Runs before any thread is parked. Linux tests use this boundary to create real races deterministically.
using InstallGuardPreparedHook = void (*)(int signal, void* argument);
void SetInstallGuardPreparedHookForTest(InstallGuardPreparedHook hook, void* argument);
unsigned InstallGuardAcknowledgementsForTest();
#endif
}
