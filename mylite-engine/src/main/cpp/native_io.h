// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "path_rules.h"

namespace mybox {
// Configuration is single-threaded before InstallIo. Success permanently seals these rules.
// Other existing threads must not have SIGSYS blocked in their thread mask at installation.
// TSYNC synchronizes seccomp filters, not per-thread signal masks. Do not race signal-action
// replacement with installation; subsequent changes are mediated by the installed filter.
PathRules& ConfiguredPaths();
int InstallIo();
bool IoEnabled();
}
