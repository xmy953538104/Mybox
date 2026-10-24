"""Reject legacy/container binaries in the independently reconstructed host."""
import hashlib
import json
import pathlib
import sys
import zipfile

FORBIDDEN = (
    b"Ltop/niunaijun/blackbox/", b"Lcom/vlite/sdk/",
    b"Lcom/huawei/hms/", b"Lde/robv/android/xposed/", b"Ltop/canyie/pine/",
)
PRIVATE_CORE = "daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a"


def audit(path):
    native = []
    dex_count = 0
    with zipfile.ZipFile(path) as apk:
        for entry in apk.infolist():
            if entry.filename.endswith(".dex"):
                dex_count += 1
                data = apk.read(entry)
                for prefix in FORBIDDEN:
                    if prefix in data:
                        raise ValueError(f"Excluded engine/SDK {prefix!r} in {entry.filename}")
            if entry.filename.endswith(".so"):
                name = pathlib.PurePosixPath(entry.filename).name
                if name.startswith(("libblackbox", "libvlitecore", "libloader")):
                    raise ValueError(f"Excluded native engine: {entry.filename}")
                digest = hashlib.sha256(apk.read(entry)).hexdigest()
                if digest == PRIVATE_CORE:
                    raise ValueError("Renamed original VLite binary")
                native.append({"path": entry.filename, "sha256": digest})
    if not dex_count:
        raise ValueError("APK contains no DEX")
    return {"sha256": hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(),
            "dex_count": dex_count, "native": native,
            "runtime_acceptance": "Separate device or emulator verification required"}


def audit_fixture_trust(host, tests):
    with zipfile.ZipFile(host) as apk, zipfile.ZipFile(tests) as test_apk:
        try:
            text = apk.read("assets/mybox/trusted-installer-fixture.properties").decode("utf-8")
        except KeyError as failure:
            raise ValueError("Debug host is missing its installer fixture trust asset") from failure
        pins = dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#"))
        digest = hashlib.sha256(test_apk.read("assets/engine-fixture.apk")).hexdigest()
        if pins.get("fixture.package") != "io.github.xmy953538104.mybox.fixture" or pins.get("fixture.apk.sha256") != digest:
            raise ValueError("Debug installer pin does not match the actual fixture in the test APK")
        certificate = pins.get("fixture.cert.sha256", "")
        if len(certificate) != 64 or any(c not in "0123456789abcdef" for c in certificate):
            raise ValueError("Debug installer fixture signer pin is invalid")
        return {"apk_sha256": digest, "certificate_sha256": certificate}


if __name__ == "__main__":
    result = audit(sys.argv[1])
    if len(sys.argv) == 3:
        result["installer_fixture"] = audit_fixture_trust(sys.argv[1], sys.argv[2])
    print(json.dumps(result, indent=2))
