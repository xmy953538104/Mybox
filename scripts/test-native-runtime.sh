#!/usr/bin/env bash
set -euo pipefail
build="${1:?native build directory required}"
output="${2:?diagnostic directory required}"
mkdir -p "$output"
result=0
for round in 1 2 3; do
    if ctest --test-dir "$build" --output-on-failure --timeout 45 > "$output/round-$round.txt" 2>&1; then
        cat "$output/round-$round.txt"
    else
        cat "$output/round-$round.txt"
        result=1
    fi
    if [[ -f "$build/Testing/Temporary/LastTest.log" ]]; then
        cp "$build/Testing/Temporary/LastTest.log" "$output/round-$round-details.txt"
    fi
done
if [[ "$result" == 1 ]]; then
    # A traced run is diagnostic only; it cannot turn a failed acceptance round into success.
    if command -v strace >/dev/null; then
        timeout 45s strace -f -tt -o "$output/failure-strace.txt" \
            -e trace=clone,clone3,rt_sigaction,rt_sigprocmask,rt_sigreturn,seccomp,prctl,exit,exit_group \
            -e signal=SIGSYS "$build/mybox-mylite-native-test" \
            > "$output/failure-traced-output.txt" 2>&1 || true
    else
        printf '%s\n' 'strace unavailable; original failed rounds retained' > "$output/trace-unavailable.txt"
    fi
fi
exit "$result"
