#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate all six independent Linux prototype runs. Self-tests use synthetic logs."""
import argparse
import copy
import json
from pathlib import Path
import tempfile

CASES = [
    ("unfiltered-old-frame-control", 0, 0),
    ("raw-action-pc-sp-calibration", 0, 0),
    ("restorer-address-wrong-number-traps", 0, 0),
    ("filtered-old-frame-control-sigsys", -1, 31),
    ("two-return-main-register-fp-mask", 0, 0),
    ("two-return-ready-worker", 0, 0),
    ("two-return-two-older-frames", 0, 0),
    ("two-return-altstack", 0, 0),
    ("two-return-already-on-altstack", 0, 0),
    ("adjacent-nonexempt-restorer-traps", 0, 0),
    ("bad-frame-kernel-control-sigsegv", -1, 11),
    ("bad-frame-safe-read-failure", 85, 0),
    ("frame-address-overflow-failure", 84, 0),
    ("readonly-old-mask-safe-write-failure", 86, 0),
]
PROFILES = ("Debug", "Release")


def validate(text, exit_code, abi):
    errors = []
    if exit_code != 0:
        errors.append(f"process exit was {exit_code}")
    try:
        records = [json.loads(line) for line in text.splitlines() if line.strip()]
    except (ValueError, TypeError) as error:
        return errors + [f"invalid JSONL: {error}"]
    if len(records) != len(CASES) + 2 or not all(isinstance(row, dict) for row in records):
        return errors + ["expected one environment, 14 cases and one summary"]
    env, summary = records[0], records[-1]
    if env.get("type") != "environment" or env.get("abi") != abi:
        errors.append("missing or wrong ABI environment")
    if env.get("production_engine_linked") is not False or env.get("tsync_used") is not False:
        errors.append("unexpected product/TSYNC scope")
    if summary.get("type") != "summary" or summary.get("cases") != len(CASES) \
            or summary.get("failed") != 0 or summary.get("success") is not True \
            or summary.get("cleanup_confirmed") is not True:
        errors.append("summary does not report all 14 matched cases")
    if not isinstance(summary.get("uncovered"), list) or not summary["uncovered"]:
        errors.append("missing prototype coverage limits")
    for row, (name, expected_exit, expected_signal) in zip(records[1:-1], CASES):
        if row.get("type") != "case" or row.get("name") != name:
            errors.append(f"missing, duplicated or reordered case {name}")
            continue
        required = {"expected_exit": expected_exit, "expected_signal": expected_signal,
                    "exit": expected_exit, "signal": expected_signal, "wait_error": 0}
        if any(type(row.get(key)) is not int or row[key] != value for key, value in required.items()):
            errors.append(f"{name}: wrong expected/actual status")
        if row.get("matched") is not True or row.get("timeout") is not False \
                or row.get("reaped") is not True or row.get("unavailable") is not False \
                or row.get("cleanup_confirmed") is not True:
            errors.append(f"{name}: failed, timed out, unavailable or not reaped")
        status = row.get("wait_status")
        if type(status) is not int or not 0 <= status <= 65535 \
                or (expected_signal and status & 127 != expected_signal) \
                or (not expected_signal and status != expected_exit << 8):
            errors.append(f"{name}: raw wait status disagrees")
    return errors


def check_directory(root, abi):
    runs = []
    for profile in PROFILES:
        for iteration in range(1, 4):
            prefix = root / profile / f"round-{iteration}"
            try:
                code = int(prefix.with_suffix(".exit").read_text(encoding="utf-8").strip())
                text = prefix.with_suffix(".jsonl").read_text(encoding="utf-8")
                errors = validate(text, code, abi)
            except (OSError, ValueError) as error:
                errors = [str(error)]
            runs.append({"profile": profile, "round": iteration, "errors": errors})
    return {"abi": abi, "runs": runs, "success": all(not item["errors"] for item in runs)}


def self_test():
    rows = [{"type": "environment", "abi": "x86_64", "production_engine_linked": False, "tsync_used": False}]
    for name, code, signal in CASES:
        rows.append({"type": "case", "name": name, "expected_exit": code, "expected_signal": signal,
                     "exit": code, "signal": signal, "wait_error": 0, "matched": True, "timeout": False,
                     "reaped": True, "unavailable": False, "cleanup_confirmed": True,
                     "wait_status": signal if signal else code << 8})
    rows.append({"type": "summary", "cases": 14, "failed": 0, "success": True,
                 "cleanup_confirmed": True, "uncovered": ["synthetic"]})
    encode = lambda data: "\n".join(json.dumps(row) for row in data) + "\n"
    good = encode(rows)
    if validate(good, 0, "x86_64"):
        raise AssertionError("valid synthetic log was rejected")
    bad = []
    bad.append((encode(rows[:-2] + rows[-1:]), 0))  # Missing final negative case.
    duplicate = copy.deepcopy(rows)
    duplicate[2] = copy.deepcopy(duplicate[1])
    bad.append((encode(duplicate), 0))
    unavailable = copy.deepcopy(rows)
    unavailable[1]["unavailable"] = True
    bad.append((encode(unavailable), 0))
    timed_out = copy.deepcopy(rows)
    timed_out[1]["timeout"] = True
    bad.append((encode(timed_out), 0))
    not_reaped = copy.deepcopy(rows)
    not_reaped[1]["cleanup_confirmed"] = False
    bad.append((encode(not_reaped), 0))
    false_status = copy.deepcopy(rows)
    false_status[4]["wait_status"] = 11
    bad.append((encode(false_status), 0))
    bad.append((good, 1))
    bad.append((good + "not-json\n", 0))
    for text, code in bad:
        if not validate(text, code, "x86_64"):
            raise AssertionError("invalid synthetic log was accepted")
    with tempfile.TemporaryDirectory(prefix="old-frame-validator-") as folder:
        root = Path(folder)
        for profile in PROFILES:
            (root / profile).mkdir()
            for iteration in range(1, 4):
                (root / profile / f"round-{iteration}.jsonl").write_text(good, encoding="utf-8")
                (root / profile / f"round-{iteration}.exit").write_text("0\n", encoding="utf-8")
        if not check_directory(root, "x86_64")["success"]:
            raise AssertionError("six valid synthetic runs were rejected")
        (root / "Debug" / "round-1.exit").write_text("1\n", encoding="utf-8")
        if check_directory(root, "x86_64")["success"]:
            raise AssertionError("later passing runs erased an earlier failure")
    print(json.dumps({"self_test": "passed", "synthetic_logs_only": True, "negative_cases": len(bad) + 1}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--abi", choices=("x86_64", "aarch64"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.root is None or args.abi is None:
        parser.error("--root and --abi are required")
    result = check_directory(args.root, args.abi)
    print(json.dumps(result, indent=2))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
