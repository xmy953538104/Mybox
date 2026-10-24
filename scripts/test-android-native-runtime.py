#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Manual CI only: three real bionic native-suite runs under the debug host UID."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys
import time
import unittest
import uuid

PACKAGE = "io.github.xmy953538104.mybox"
DEVICE_DEADLINE = 60
HOST_DEADLINE = 90
EXPECTED_GUARDS = set(range(11))


def command_text(arguments):
    return shlex.join(str(argument) for argument in arguments)


def emulator_serial(devices):
    targets = [line.split()[0] for line in devices.splitlines()
               if re.fullmatch(r"emulator-\d+\s+device", line.strip())]
    if len(targets) != 1:
        raise ValueError("Exactly one running emulator is required; physical devices are never selected")
    return targets[0]


def package_uid(packages):
    matches = re.findall(rf"^package:{re.escape(PACKAGE)} uid:(\d+)\s*$", packages, re.M)
    if len(matches) != 1 or int(matches[0]) < 10000:
        raise ValueError("Cannot identify an ordinary installed host UID")
    return int(matches[0])


def result_errors(log, code, expired, aborted):
    errors = []
    if code != "0":
        errors.append(f"native exit code is {code!r}")
    if expired:
        errors.append("on-device deadline expired")
    if aborted:
        errors.append("session was interrupted")
    if re.search(r"\b(?:FAILED|FAILURE)\b", log):
        errors.append("native assertion failed")
    guards = [int(value) for value in re.findall(r"PASS: install guard case=(\d+)\b", log)]
    if len(guards) != 11 or set(guards) != EXPECTED_GUARDS:
        errors.append("not all eleven guard cases passed exactly once")
    for filtered in (0, 1):
        if f"wait masks filtered={filtered} error/null/readonly/partial-copy cases=389," not in log:
            errors.append(f"missing exact 389-case matrix filtered={filtered}")
    waits = [int(value) for value in re.findall(r"PASS: wait-mask operation=(-?\d+) observed=1 status=0", log)]
    if sorted(waits) != [-1, 0, 1, 2, 3, 4]:
        errors.append("missing exact observed wait/EINTR cases")
    for marker in (
        "PASS: stack pool capacity=8 concurrent=10 leased_peak=8",
        "PASS: stack pool initialization errno=12; fallback requests=2 failures=1; I/O recovered",
        "PASS: preinstalled signal masks/flags, realtime signal and nested I/O on main/TSYNC worker",
        "PASS: path boundaries, syscall routing, TSYNC/new threads, dirfd, stat, rename, symlink/readlink, EFAULT, signal masks and unlink",
    ):
        if marker not in log:
            errors.append(f"missing native completion: {marker}")
    if "PROFILE native pid=" not in log or "PROFILE end /proc/self/attr/current" not in log:
        errors.append("native UID/SELinux profile was not recorded")
    return errors


def all_rounds_pass(rounds):
    return len(rounds) == 3 and all(item["passed"] for item in rounds)


def identity_errors(log, identity, test_pid, cookie, uid):
    marker = identity.split()
    profile = re.search(r"^PROFILE native (.+)$", log, re.M)
    if len(marker) != 4 or marker[0] != cookie or not all(part.isdigit() for part in marker[1:]) \
            or marker[3] != str(uid) or not test_pid.isdigit() or not profile:
        return ["invalid session/native identity record"]
    leader = marker[1]
    fields = dict(re.findall(r"([a-z_]+)=(\d+)", profile.group(1)))
    expected = {"pid": test_pid, "uid": str(uid), "euid": str(uid), "gid": str(uid),
                "egid": str(uid), "pgid": leader, "sid": leader}
    if int(leader) <= 1 or int(marker[2]) <= 0 or int(fields.get("page_size", "0")) <= 0 \
            or any(fields.get(key) != value for key, value in expected.items()):
        return ["native executable UID/PID/session does not match the recorded private session"]
    return []


class RunnerChecks(unittest.TestCase):
    def test_target_and_uid_are_exact(self):
        self.assertEqual(emulator_serial("List of devices attached\nphysical123\tdevice\nemulator-5554\tdevice\n"), "emulator-5554")
        for listing in ("physical123\tdevice\n", "emulator-5554\tdevice\nemulator-5556\tdevice\n"):
            with self.assertRaises(ValueError):
                emulator_serial(listing)
        self.assertEqual(package_uid(f"package:{PACKAGE} uid:10123\n"), 10123)
        for listing in (f"package:{PACKAGE}.test uid:10123\n", f"package:{PACKAGE} uid:0\n"):
            with self.assertRaises(ValueError):
                package_uid(listing)

    def test_failure_is_not_erased_by_markers(self):
        self.assertTrue(result_errors("PASS", "0", False, False))
        errors = result_errors("FAILED line 1", "0", True, True)
        self.assertIn("native assertion failed", errors)
        self.assertIn("on-device deadline expired", errors)
        self.assertIn("session was interrupted", errors)
        self.assertEqual(shlex.split(command_text(["sh", "-c", "printf '%s' '$not_expanded'", "a b"])),
                         ["sh", "-c", "printf '%s' '$not_expanded'", "a b"])

    def test_complete_log_and_precise_negative_cases(self):
        # Synthetic parser input only. These strings do not stand in for an Android run.
        log = "\n".join([
            "PROFILE native pid=51 uid=10123 euid=10123 gid=10123 egid=10123 pgid=50 sid=50 page_size=4096",
            "PROFILE end /proc/self/attr/current",
            *[f"PASS: wait masks filtered={filtered} error/null/readonly/partial-copy cases=389, real readiness and timeout writeback"
              for filtered in (0, 1)],
            *[f"PASS: wait-mask operation={operation} observed=1 status=0" for operation in range(-1, 5)],
            *[f"PASS: install guard case={case} errno={error}"
              for case, error in enumerate((0, 16, 110, 11, 130, 110, 110, 130, 0, 0, 16))],
            "PASS: stack pool capacity=8 concurrent=10 leased_peak=8 fallback_requests=6",
            "PASS: stack pool initialization errno=12; fallback requests=2 failures=1; I/O recovered",
            "PASS: preinstalled signal masks/flags, realtime signal and nested I/O on main/TSYNC worker",
            "PASS: path boundaries, syscall routing, TSYNC/new threads, dirfd, stat, rename, symlink/readlink, EFAULT, signal masks and unlink",
        ]) + "\n"
        self.assertEqual(result_errors(log, "0", False, False), [])
        self.assertTrue(result_errors(log.replace("PASS: wait-mask operation=4 observed=1 status=0\n", ""), "0", False, False))
        self.assertTrue(result_errors(log + "PASS: install guard case=0 errno=0\n", "0", False, False))
        self.assertTrue(result_errors(log, "134", False, False))
        self.assertTrue(result_errors(log, "0", True, False))
        self.assertEqual(identity_errors(log, "abc 50 1234 10123", "51", "abc", 10123), [])
        self.assertTrue(identity_errors(log.replace("pgid=50", "pgid=49"), "abc 50 1234 10123", "51", "abc", 10123))
        self.assertTrue(identity_errors(log, "abc 50 1234 10123", "51", "wrong-cookie", 10123))

    def test_all_three_rounds_must_pass(self):
        self.assertTrue(all_rounds_pass([{"passed": True}] * 3))
        self.assertFalse(all_rounds_pass([{"passed": False}, {"passed": True}, {"passed": True}]))
        self.assertFalse(all_rounds_pass([{"passed": True}] * 2))


class Runner:
    def __init__(self, output):
        self.output = output
        self.serial = None

    def adb(self, arguments, *, timeout=20, data=None, check=True):
        command = ["adb"]
        if self.serial:
            command += ["-s", self.serial]
        result = subprocess.run(command + arguments, input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
        if check and result.returncode:
            raise RuntimeError(f"adb command failed ({result.returncode}): {result.stderr.decode(errors='replace')}")
        return result

    def run_as(self, arguments, **kwargs):
        # Shell v2 without a PTY preserves binary bytes AND the remote exit status.
        # The raw exec-out channel is unsuitable for cleanup/error checks.
        return self.adb(["shell", "-T", command_text(["run-as", PACKAGE, *arguments])], **kwargs)

    def shell(self, arguments, **kwargs):
        return self.adb(["shell", "-T", command_text(arguments)], **kwargs)

    def write_remote(self, path, data):
        self.run_as(["sh", "-c", f"umask 077; cat > {shlex.quote(path)}"], data=data)

    def remote_file(self, path):
        result = self.run_as(["cat", path], check=False)
        return result.stdout if result.returncode == 0 else None

    def round(self, directory, cookie, uid, number):
        remote = f"{directory}/round-{number}"
        local = self.output / f"round-{number}"
        local.mkdir()
        self.run_as(["mkdir", remote])
        script = f"{directory}/session.sh"
        started = time.monotonic()
        details = {"round": number, "host_deadline_seconds": HOST_DEADLINE,
                   "device_deadline_seconds": DEVICE_DEADLINE, "uid": uid, "errors": []}
        try:
            # setsid -w preserves transport waiting even if toybox must fork a session leader.
            execution = self.run_as(["/system/bin/setsid", "-w", "/system/bin/sh", script,
                                     "run", remote, cookie, str(uid), str(DEVICE_DEADLINE)],
                                    timeout=HOST_DEADLINE, check=False)
            details["adb_exit_code"] = execution.returncode
            (local / "transport.txt").write_bytes(execution.stdout + execution.stderr)
        except subprocess.TimeoutExpired as error:
            details["errors"].append("host deadline expired; result remains failed")
            (local / "transport.txt").write_bytes((error.stdout or b"") + (error.stderr or b""))
        finally:
            # The watchdog remains independent of ADB. This request never signals by app name/UID.
            try:
                cleanup = self.run_as(["sh", script, "cleanup", remote, cookie, str(uid)],
                                      timeout=10, check=False)
                (local / "cleanup.txt").write_bytes(cleanup.stdout + cleanup.stderr)
                details["cleanup_exit_code"] = cleanup.returncode
                if cleanup.returncode:
                    details["errors"].append("dedicated session cleanup not confirmed")
            except (OSError, subprocess.TimeoutExpired) as error:
                details["errors"].append(f"cleanup unavailable: {type(error).__name__}")
        for name in ("identity", "test-pid", "session.log", "test.log", "exit-code", "timed-out", "aborted"):
            content = self.remote_file(f"{remote}/{name}")
            if content is not None:
                (local / name).write_bytes(content)
        code = (local / "exit-code").read_text().strip() if (local / "exit-code").exists() else None
        log = (local / "test.log").read_text(errors="replace") if (local / "test.log").exists() else ""
        details["errors"] += result_errors(log, code, (local / "timed-out").exists(), (local / "aborted").exists())
        identity = (local / "identity").read_text().strip() if (local / "identity").exists() else ""
        test_pid = (local / "test-pid").read_text().strip() if (local / "test-pid").exists() else ""
        details["errors"] += identity_errors(log, identity, test_pid, cookie, uid)
        details["elapsed_seconds"] = time.monotonic() - started
        details["passed"] = not details["errors"]
        (local / "result.json").write_text(json.dumps(details, indent=2) + "\n")
        print(json.dumps(details), flush=True)
        return details


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("work/android-native/results"))
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RunnerChecks))
        return 0 if result.wasSuccessful() else 1
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("CI") != "true":
        parser.error("This runner is restricted to disposable GitHub CI emulators")
    if not args.binary or not args.apk:
        parser.error("--binary and --apk are required")
    binary = args.binary.read_bytes()
    if binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", binary, 16) != (3, 62):
        parser.error("The runtime profile requires an x86_64 Android PIE executable")
    args.output.mkdir(parents=True, exist_ok=True)
    runner = Runner(args.output)
    summary = {"profile": "Android 14 x86_64 bionic standalone under debug host run-as UID",
               "binary_sha256": hashlib.sha256(binary).hexdigest(),
               "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
               "git_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
               "rounds": [], "passed": False}
    try:
        devices = runner.adb(["devices"]).stdout.decode()
        (args.output / "adb-devices.txt").write_text(devices)
        runner.serial = emulator_serial(devices)
        summary["emulator_serial"] = runner.serial
        features = re.split(r"[,\s]+", runner.adb(["features"]).stdout.decode().strip())
        if "shell_v2" not in features:
            raise RuntimeError("ADB shell v2 is required for trustworthy remote exit codes")
        for prop, expected in (("ro.kernel.qemu", "1"), ("ro.build.version.sdk", "34"), ("ro.product.cpu.abi", "x86_64")):
            actual = runner.shell(["getprop", prop]).stdout.decode().strip()
            if actual != expected:
                raise RuntimeError(f"Unexpected emulator profile {prop}={actual!r}")
        enforcing = runner.shell(["getenforce"]).stdout.decode().strip()
        if enforcing != "Enforcing":
            raise RuntimeError(f"SELinux must remain Enforcing, got {enforcing!r}")
        environment = runner.shell(["sh", "-c", "uname -a; getprop ro.build.fingerprint; getprop ro.build.version.security_patch; getenforce"]).stdout
        (args.output / "emulator-environment.txt").write_bytes(environment)
        installation = runner.adb(["install", "-r", str(args.apk)], timeout=90)
        (args.output / "install.txt").write_bytes(installation.stdout + installation.stderr)
        uid = package_uid(runner.shell(["cmd", "package", "list", "packages", "-U", "--user", "0", PACKAGE]).stdout.decode())
        summary["host_uid"] = uid
        if runner.run_as(["id", "-u"]).stdout.decode().strip() != str(uid):
            raise RuntimeError("run-as UID differs from the installed host UID")
        exit_probe = runner.run_as(["sh", "-c", "exit 73"], check=False).returncode
        summary["shell_v2_remote_exit_probe"] = exit_probe
        if exit_probe != 73:
            raise RuntimeError("ADB did not preserve the remote nonzero exit status")
        data_path = runner.run_as(["pwd", "-P"]).stdout.decode().strip()
        if data_path != f"/data/user/0/{PACKAGE}":
            raise RuntimeError(f"Unexpected private data directory {data_path!r}")
        cookie = uuid.uuid4().hex
        remote = f"{data_path}/cache/mylite-native-{cookie}"
        summary["private_run_directory"] = remote
        runner.run_as(["mkdir", "-m", "700", remote])
        runner.write_remote(f"{remote}/native-test", binary)
        runner.write_remote(f"{remote}/session.sh", Path(__file__).with_name("android-native-session.sh").read_bytes())
        runner.run_as(["chmod", "700", f"{remote}/native-test"])
        digest = runner.run_as(["sha256sum", f"{remote}/native-test"]).stdout.decode().split()[0]
        if digest != summary["binary_sha256"]:
            raise RuntimeError("On-device executable SHA256 differs from the built binary")
        for number in (1, 2, 3):
            try:
                summary["rounds"].append(runner.round(remote, cookie, uid, number))
            except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                details = {"round": number, "passed": False, "errors": [f"{type(error).__name__}: {error}"]}
                summary["rounds"].append(details)
                (args.output / f"round-{number}-runner-error.json").write_text(json.dumps(details, indent=2) + "\n")
        summary["passed"] = all_rounds_pass(summary["rounds"])
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        summary["setup_error"] = f"{type(error).__name__}: {error}"
    finally:
        (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary), flush=True)
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
