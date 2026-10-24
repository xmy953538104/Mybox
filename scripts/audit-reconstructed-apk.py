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


if __name__ == "__main__":
    print(json.dumps(audit(sys.argv[1]), indent=2))
