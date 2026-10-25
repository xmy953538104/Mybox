#!/usr/bin/env python3
"""Capture only the installed virtual Play APK generation, encrypted for one recipient."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

HOST = "io.github.xmy953538104.mybox"
PACKAGE = "com.android.vending"
RECORD = "files/vlite/records/" + PACKAGE + ".properties"
GOOGLE = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
MAX_BYTES = 512 * 1024 * 1024


class CaptureError(Exception):
    pass


def package_record(data):
    if len(data) > 65536:
        raise CaptureError("Package record exceeds its limit")
    values = {}
    for line in data.decode("iso-8859-1").splitlines():
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise CaptureError("Malformed or duplicate package record key")
        values[key] = value
    if values.get("format") != "1" or values.get("package") != PACKAGE:
        raise CaptureError("Unexpected package record identity")
    generation = values.get("generation", "")
    try:
        if str(uuid.UUID(generation)) != generation:
            raise ValueError()
    except ValueError:
        raise CaptureError("Invalid package generation") from None
    version = values.get("version", "")
    count = values.get("split.count", "")
    if not re.fullmatch(r"[0-9]{1,19}", version) or not 0 < int(version) < 2**63:
        raise CaptureError("Invalid package version")
    if not re.fullmatch(r"[0-9]{1,3}", count) or not 0 <= int(count) <= 255:
        raise CaptureError("Invalid APK split count")
    count = int(count)
    if {key for key in values if key.startswith("split.") and key != "split.count"} != {f"split.{i}" for i in range(count)}:
        raise CaptureError("Inconsistent APK split entries")
    names = [values[f"split.{i}"] for i in range(count)]
    if len(set(names)) != count or any(not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}", name) for name in names):
        raise CaptureError("Invalid or duplicate APK split name")
    if values.get("signer.count") != "1" or values.get("signer.0") != GOOGLE:
        raise CaptureError("Package record lacks the approved Google signer")
    files = ["base.apk", *[f"split_{name}.apk" for name in names]]
    directory = f"files/vlite/packages/{PACKAGE}/versions/{generation}/"
    return int(version), generation, [(name, directory + name) for name in files]


def capture(serial, recipient):
    if not re.fullmatch(r"emulator-[0-9]{4,5}", serial):
        raise CaptureError("An explicit emulator serial is required")
    if len(recipient) > 16384 or not re.fullmatch(r"\s*-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----\s*", recipient):
        raise CaptureError("A single public X509 recipient certificate is required")
    temporary = Path(os.environ.get("RUNNER_TEMP", ""))
    if not temporary.is_absolute() or not temporary.is_dir():
        raise CaptureError("RUNNER_TEMP must be an existing absolute directory")
    temporary = temporary.resolve(strict=True)
    output = Path("work/runtime/play-analysis.cms")
    metadata_output = output.with_suffix(".json")
    if output.exists() or metadata_output.exists():
        raise CaptureError("Analysis output already exists; refusing to replace evidence")
    private = Path(tempfile.mkdtemp(prefix="mybox-private-play-", dir=temporary))
    deadline = time.monotonic() + 180

    def command(arguments, *, destination=None):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise CaptureError("Analysis capture exceeded its time limit")
        try:
            result = subprocess.run(arguments, stdout=destination or subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=min(90, remaining), check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise CaptureError("Analysis command unavailable or timed out") from None
        if result.returncode:
            raise CaptureError("Analysis command failed; raw output omitted")
        return result.stdout if destination is None else None

    def adb(*arguments):
        return command(["adb", "-s", serial, *arguments])

    def record():
        return adb("exec-out", "run-as", HOST, "head", "-c", "65537", RECORD)

    try:
        cert = private / "recipient.pem"
        cert.write_text(recipient, encoding="ascii")
        command(["openssl", "x509", "-in", str(cert), "-noout", "-checkend", "0"])
        cert_der = command(["openssl", "x509", "-in", str(cert), "-outform", "DER"])
        devices = command(["adb", "devices"]).decode("ascii", errors="strict").splitlines()
        emulators = [line.split() for line in devices if line.startswith("emulator-")]
        if emulators != [[serial, "device"]]:
            raise CaptureError("Exactly one online emulator matching the serial is required")
        host_directory = adb("exec-out", "run-as", HOST, "pwd").decode("ascii").strip()
        if host_directory != f"/data/user/0/{HOST}":
            raise CaptureError("Unexpected host data directory or Android user")
        before = record()
        version, generation, files = package_record(before)
        metadata = {"package": PACKAGE, "versionCode": version, "generation": generation,
                    "recipientCertificateSha256": hashlib.sha256(cert_der).hexdigest(), "apks": []}
        total = 0
        for name, remote in files:
            canonical = adb("exec-out", "run-as", HOST, "readlink", "-f", remote).decode("ascii").strip()
            if canonical != host_directory + "/" + remote:
                raise CaptureError("APK path escapes its committed generation")
            identity = adb("exec-out", "run-as", HOST, "stat", "-c", "%s:%f:%d:%i:%y:%z", remote)
            fields = identity.decode("ascii").strip().split(":", 2)
            try:
                size, mode = int(fields[0]), int(fields[1], 16)
            except (ValueError, IndexError):
                raise CaptureError("Invalid APK file identity") from None
            if not stat.S_ISREG(mode) or mode & 0o222 or not 0 < size <= MAX_BYTES - total:
                raise CaptureError("APK must be a sealed regular file within the size limit")
            local = private / name
            with local.open("xb") as stream:
                command(["adb", "-s", serial, "exec-out", "run-as", HOST, "head", "-c", str(size + 1), remote], destination=stream)
            if local.stat().st_size != size or identity != adb("exec-out", "run-as", HOST, "stat", "-c", "%s:%f:%d:%i:%y:%z", remote):
                raise CaptureError("APK changed during capture")
            with local.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            metadata["apks"].append({"name": name, "size": size, "sha256": digest})
            total += size
        if record() != before:
            raise CaptureError("Installed generation changed during capture")
        archive = private / "play-generation.zip"
        with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_STORED) as bundle:
            for name, _ in files:
                bundle.write(private / name, name)
            bundle.writestr("metadata.json", json.dumps(metadata, sort_keys=True))
        encrypted = private / "play-analysis.cms"
        command(["openssl", "cms", "-encrypt", "-binary", "-aes-256-gcm", "-outform", "DER",
                 "-in", str(archive), "-out", str(encrypted), str(cert)])
        if encrypted.stat().st_size == 0:
            raise CaptureError("Encryption produced no evidence")
        output.parent.mkdir(parents=True, exist_ok=True)
        os.replace(encrypted, output)
    finally:
        # Do not change permissions or ignore failures when removing private plaintext.
        if private.parent != temporary or private.is_symlink():
            raise CaptureError("Private capture cleanup path is invalid")
        try:
            shutil.rmtree(private)
        except OSError:
            raise CaptureError("Private plaintext cleanup failed; runner temporary directory requires cleanup") from None
    metadata["encryptedPath"] = output.as_posix()
    metadata_output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, sort_keys=True))


def self_test():
    fixture = {"format": "1", "package": PACKAGE, "generation": "12345678-1234-4234-8234-123456789abc",
               "version": "83561110", "split.count": "1", "split.0": "config.en", "signer.count": "1", "signer.0": GOOGLE}
    def encode(values):
        return "\n".join(f"{key}={value}" for key, value in values.items()).encode("ascii")
    version, generation, files = package_record(encode(fixture))
    assert version == 83561110 and len(files) == 2
    assert files[1] == ("split_config.en.apk", f"files/vlite/packages/{PACKAGE}/versions/{generation}/split_config.en.apk")
    for changes in ({"generation": "../outside"}, {"generation": "/absolute"}, {"split.count": "-1"},
                    {"split.count": "256"}, {"split.count": "0"}, {"split.0": "../private"},
                    {"split.0": "folder/name"}, {"split.0": "name\\escape"}, {"split.count": "2", "split.1": "config.en"},
                    {"package": "other.package"}, {"signer.0": "0" * 64}, {"version": "9223372036854775808"}):
        try:
            package_record(encode({**fixture, **changes}))
        except CaptureError:
            continue
        raise AssertionError("Invalid generation/count/path/identity accepted")
    try:
        package_record(encode(fixture) + b"\ngeneration=duplicate")
    except CaptureError:
        pass
    else:
        raise AssertionError("Duplicate record key accepted")
    print("Play capture record/path/count validation self-test passed; no ADB or binary capture executed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    recipient = os.environ.get("MYLITE_PLAY_ANALYSIS_RECIPIENT_CERT", "")
    if not recipient.strip():
        print("Play analysis capture skipped: no public recipient certificate supplied.")
        return
    try:
        capture(args.serial or "", recipient)
    except CaptureError as failure:
        print("Play analysis capture failed: " + str(failure), file=sys.stderr)
        raise SystemExit(1) from None
    except Exception:
        print("Play analysis capture failed: unexpected validation or I/O error; raw details omitted.", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
