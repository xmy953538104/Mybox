"""Bundle only the microG APK whose bytes match this workflow's trust file."""
import hashlib
import pathlib
import shutil
import sys


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
    target.mkdir(parents=True, exist_ok=True)
    for name in ("microg-nohms.apk", "trusted-microg.properties"):
        shutil.copyfile(source / name, target / name)


if __name__ == "__main__":
    root = pathlib.Path(__file__).resolve().parents[1]
    bundle(sys.argv[1], root / "vlite-engine/src/main/assets/mybox")
