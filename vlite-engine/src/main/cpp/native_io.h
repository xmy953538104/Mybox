// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "path_rules.h"

namespace mybox {
// Configuration is single-threaded before InstallIo. Success permanently seals these rules.
PathRules& ConfiguredPaths();
int InstallIo();
bool IoEnabled();
}
