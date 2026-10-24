// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "path_rules.h"
#include <cstdint>

namespace mybox {
// Configuration is single-threaded before InstallIo. Success permanently seals these rules.
// Other existing threads must not have SIGSYS blocked in their thread mask at installation.
// TSYNC synchronizes seccomp filters, not per-thread signal masks. Do not race signal-action
// replacement with installation; subsequent changes are mediated by the installed filter.
PathRules& ConfiguredPaths();
int InstallIo();
bool IoEnabled();

// Read-only diagnostics; counters are updated only on the mmap fallback path.
struct IoStackStats {
    unsigned capacity;
    unsigned leased;
    int initialization_error;
    std::uint64_t fallback_requests;
    std::uint64_t fallback_failures;
};
IoStackStats GetIoStackStats();
}
