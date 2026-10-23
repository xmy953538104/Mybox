"""Fail the build if excluded runtimes return to the host or its APK."""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
BANNED = (b"Lcom/huawei/hms/", b"Lcom/huawei/hianalytics/", b"Lde/robv/android/xposed/", b"Ltop/canyie/pine/")


def main():
    for module in ("pine-core", "pine-xposed", "pine-xposed-res"):
        assert not (ROOT / "Bcore" / module).exists(), f"Excluded module: {module}"
    for folder in ("app", "Bcore", "android-mirror"):
        for path in (ROOT / folder).rglob("*.java"):
            if "build" in path.relative_to(ROOT).parts:
                continue
            source = path.read_text(encoding="utf-8")
            assert not re.search(r"import\s+(?:com\.huawei\.hms|de\.robv\.android\.xposed|top\.canyie\.pine)\.", source), path
        for path in (ROOT / folder).rglob("*.aidl"):
            if "build" in path.relative_to(ROOT).parts:
                continue
            package = re.search(r"\bpackage\s+([\w.]+)\s*;", path.read_text(encoding="utf-8"))
            assert package, f"AIDL has no package: {path}"
            expected = package[1].replace(".", "/") + "/" + path.name
            assert path.as_posix().endswith("/" + expected), f"AIDL path must end in {expected}: {path}"
    for filename in sys.argv[1:]:
        with zipfile.ZipFile(filename) as apk:
            for entry in apk.namelist():
                if entry.endswith(".dex"):
                    data = apk.read(entry)
                    for prefix in BANNED:
                        assert prefix not in data, f"Excluded SDK {prefix!r} in {entry}"
    print("Source/APK boundary checks passed (HMS SDK, Pine, Xposed).")


if __name__ == "__main__":
    main()
