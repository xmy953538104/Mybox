// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "path_rules.h"
#include <cstdint>

namespace mybox {
// Configuration is single-threaded before InstallIo. Success permanently seals these rules.
// Before guest code runs, the install guard parks existing threads and clears their SIGSYS masks.
// An unused default-ignored signal and a stable thread cohort are required; races fail explicitly.
// Do not race signal-action ownership with installation. EOWNERDEAD requires retiring this process.
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
