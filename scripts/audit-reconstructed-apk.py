"""Reject legacy/container binaries in the independently reconstructed host."""
import argparse
import hashlib
import json
import pathlib
import zipfile
from google_play_assets import DIRECTORY, SINGLE, MAX_BYTES, members

FORBIDDEN = (
    b"Ltop/niunaijun/blackbox/", b"Lcom/vlite/sdk/",
    b"Lcom/huawei/hms/", b"Lde/robv/android/xposed/", b"Ltop/canyie/pine/",
)
PRIVATE_CORE = "daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a"


def audit(path, allow_private_google_assets=False):
    native = []
    dex_count = 0
    with zipfile.ZipFile(path) as apk:
        store = audit_store_assets(apk, allow_private_google_assets)
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
            "dex_count": dex_count, "native": native, "private_google_assets": store,
            "runtime_acceptance": "Separate device or emulator verification required"}



def audit_store_assets(apk, allowed):
    if any(entry.filename.startswith("assets/mybox/.google-play-stage-") for entry in apk.infolist()):
        raise ValueError("Incomplete private Store staging directory was packaged")
    entries = [entry for entry in apk.infolist() if entry.filename == SINGLE or entry.filename.startswith(DIRECTORY)]
    if not entries:
        return None
    if not allowed:
        raise ValueError("Private Google Play assets are forbidden in public CI artifacts; local private builds require --allow-private-google-assets")
    single = [entry for entry in entries if entry.filename == SINGLE]
    directory = [entry for entry in entries if entry.filename.startswith(DIRECTORY)]
    if single and directory or len(single) > 1:
        raise ValueError("Conflicting Store sources")
    if directory:
        if any(entry.is_dir() and entry.filename != DIRECTORY for entry in directory):
            raise ValueError("Store APK set must be flat")
        files = [entry for entry in directory if not entry.is_dir()]
        names = members(entry.filename[len(DIRECTORY):] for entry in files)
        by_name = {entry.filename[len(DIRECTORY):]: entry for entry in files}
        entries = [by_name[name] for name in names]
    else:
        entries = single
    total, result = 0, []
    for entry in entries:
        if entry.file_size <= 0 or entry.file_size > MAX_BYTES - total:
            raise ValueError("Empty or oversized private Store APK set")
        digest, size = hashlib.sha256(), 0
        with apk.open(entry) as stream:
            while data := stream.read(65536):
                total += len(data)
                if total > MAX_BYTES:
                    raise ValueError("Private Store APK set exceeds 512 MiB")
                size += len(data)
                digest.update(data)
        if size == 0:
            raise ValueError("Empty private Store APK")
        result.append({"path": entry.filename, "bytes": size, "sha256": digest.hexdigest()})
    return {"apks": result, "publisher_verified": False,
            "acceptance": "Private packaging audit only; actual PackageStore publisher verification and runtime acceptance remain required"}


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


def audit_artifacts(host, tests=None, allow_private_google_assets=False):
    result = audit(host, allow_private_google_assets)
    if tests:
        result["android_test"] = audit(tests, allow_private_google_assets)
        result["installer_fixture"] = audit_fixture_trust(host, tests)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("tests", nargs="?")
    parser.add_argument("--allow-private-google-assets", action="store_true",
                        help="local private builds only; never enable for public artifact uploads")
    arguments = parser.parse_args()
    result = audit_artifacts(arguments.host, arguments.tests, arguments.allow_private_google_assets)
    print(json.dumps(result, indent=2))
