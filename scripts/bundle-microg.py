"""Bundle only the microG APK whose bytes match this workflow's trust file."""
import hashlib
import json
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "32bc8954ff872d0e1a05ddfae81561b6dbecef69"


def expected_patches():
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted((ROOT / "microg-patches").glob("*.patch"))}


def bundle(source, target):
    source, target = pathlib.Path(source), pathlib.Path(target)
    properties = {}
    for line in (source / "trusted-microg.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    for key in ("gms.apk.sha256", "gms.cert.sha256"):
        value = properties.get(key, "")
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise ValueError("Invalid or absent " + key)
    apk = source / "microg-nohms.apk"
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != properties["gms.apk.sha256"]:
        raise ValueError("microG APK digest does not match its trust file")
    try:
        manifest = json.loads((source / "artifact.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as failure:
        raise ValueError("A verified microG build manifest is required") from failure
    if (manifest.get("schema") != 1 or manifest.get("package_name") != "com.google.android.gms"
            or manifest.get("source_repository") != "https://github.com/microg/GmsCore"
            or manifest.get("source_commit") != SOURCE_COMMIT
            or manifest.get("variant") != "mapboxDefaultDebug"
            or manifest.get("hms_runtime_dependency") is not False
            or manifest.get("apk_sha256") != digest
            or manifest.get("certificate_sha256") != properties["gms.cert.sha256"]
            or manifest.get("patches") != expected_patches()):
        raise ValueError("microG artifact provenance does not match the current pinned source and patches")
    target.mkdir(parents=True, exist_ok=True)
    for name in ("microg-nohms.apk", "trusted-microg.properties"):
        shutil.copyfile(source / name, target / name)


if __name__ == "__main__":
    bundle(sys.argv[1], ROOT / "vlite-engine/src/main/assets/mybox")
