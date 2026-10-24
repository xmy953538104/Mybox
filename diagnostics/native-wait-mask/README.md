# Native signal mechanism probe

Manual Linux diagnostics for the currently documented temporary wait-mask and older interrupted-frame gaps. This independent program links the selected Mylite source and runs isolated child processes. It does not include or execute a VLite binary and does not access a physical device.

Each of five real kernel waits has unfiltered/blocked, filtered/unblocked and filtered/blocked controls. The parent observes the actual wait syscall before sending the signal. Three further cases retain an older signal frame across installation. JSON records contain observed masks, real wait status and evidence matching.

**Expected SIGSYS deaths reproduce a known gap. A successful mechanism workflow is not product compatibility or stability acceptance.** It is not part of ordinary APK acceptance. Its expectations must change when the corresponding product limitation is fixed; do not retry away a mismatch.

Build with `cmake -S diagnostics/native-wait-mask -B work/signal-mechanism -DMYLITE_CORE_DIR=/absolute/path/to/mylite-engine/src/main/cpp`, then run `work/signal-mechanism/mylite-waitmask-mechanism`. Use the uncorrected reference core `1ce7b5abf40248a8d5ebe100476f7757c8c60b88` for these baseline expectations. The manual `native-signal-diagnostics.yml` workflow pins that exact reference and retains separate x86_64 and AArch64 results. Future corrected behavior belongs in the ordinary native correctness suite.

[CI40071950577](https://github.com/xmy953538104/Mybox/actions/runs/40071950577) reproduced all 18 expected cases on each architecture: the six filtered/blocked hazard children died from actual SIGSYS, while the controls completed. This identifies reproducible mechanisms, without attributing an older unexplained process death to them.
