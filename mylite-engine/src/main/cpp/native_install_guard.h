// SPDX-License-Identifier: Apache-2.0
#pragma once

namespace mybox {
// One attempt in a dedicated process, before guest code. Success parks the existing threads;
// until FinishInstallGuard only raw syscalls and lock-free operations may run on the installer.
int BeginInstallGuard();
int FinishInstallGuard(bool filter_installed);

#if defined(MYLITE_NATIVE_TESTING)
// Runs before any thread is parked. Linux tests use this boundary to create real races deterministically.
using InstallGuardPreparedHook = void (*)(int signal, void* argument);
void SetInstallGuardPreparedHookForTest(InstallGuardPreparedHook hook, void* argument);
unsigned InstallGuardAcknowledgementsForTest();
#endif
}
