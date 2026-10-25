"""Stage an explicitly supplied local APK set. No download, resigning or publisher authorization."""
import argparse
import hashlib
import json
import os
import pathlib
import stat
import tempfile
from google_play_assets import MAX_BYTES, members

ROOT = pathlib.Path(__file__).absolute().parents[1]
TARGET = ROOT / "mylite-engine/src/main/assets/mybox/google-play"


def identity(value):
    return value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns, value.st_ctime_ns



def redirected(path):
    try:
        value = path.lstat()
    except FileNotFoundError:
        return False
    return stat.S_ISLNK(value.st_mode) or bool(getattr(value, "st_file_attributes", 0) & 0x400)


def no_redirected_parents(path):
    # Inspect root-to-leaf with lstat; do not resolve a junction/symlink into an external target.
    for parent in reversed((path, *path.parents)):
        if redirected(parent):
            raise ValueError("Store target parent must not be a link or junction: " + str(parent))


def source_conflict(target):
    legacy = target.parent / "google-play.apk"
    return target.exists() or redirected(target) or legacy.exists() or redirected(legacy)


def stage(source, target=TARGET):
    source, target = pathlib.Path(source), pathlib.Path(target).absolute()
    no_redirected_parents(target.parent)
    if redirected(source):
        raise ValueError("Store source must not be a symbolic link")
    if source.is_dir():
        inputs = {path.name: path for path in source.iterdir()}
        names = members(inputs)
    elif source.is_file():
        inputs, names = {"base.apk": source}, ["base.apk"]
    else:
        raise ValueError("Provide a local APK or flat base/split directory")
    source_bytes = 0
    for name in names:
        value = inputs[name].lstat()
        if not stat.S_ISREG(value.st_mode):
            raise ValueError("Store source must contain only regular files, without links or directories")
        if value.st_size <= 0:
            raise ValueError("Store APK is empty")
        source_bytes += value.st_size
        if source_bytes > MAX_BYTES:
            raise ValueError("Store APK set exceeds 512 MiB")
    if source_conflict(target):
        raise FileExistsError("A Store source already exists; explicitly move it before staging another")
    target.parent.mkdir(parents=True, exist_ok=True)
    no_redirected_parents(target.parent)
    # Default staging stays outside Android assets, so an interrupted build cannot bundle partial APKs.
    staging_parent = ROOT / ".build" if target == TARGET else target.parent
    no_redirected_parents(staging_parent)
    staging_parent.mkdir(parents=True, exist_ok=True)
    no_redirected_parents(staging_parent)
    owned = pathlib.Path(tempfile.mkdtemp(prefix=".google-play-stage-", dir=staging_parent))
    created, result, total = [], [], 0
    try:
        for name in names:
            source_file = inputs[name]
            before = source_file.lstat()
            destination = owned / name
            created.append(destination)
            digest, size = hashlib.sha256(), 0
            with source_file.open("rb") as incoming, destination.open("xb") as outgoing:
                if identity(before) != identity(os.fstat(incoming.fileno())):
                    raise ValueError("Store source changed while opening")
                while data := incoming.read(65536):
                    total += len(data)
                    if total > MAX_BYTES:
                        raise ValueError("Store APK set exceeds 512 MiB")
                    size += len(data)
                    digest.update(data)
                    outgoing.write(data)
                outgoing.flush()
                os.fsync(outgoing.fileno())
                if identity(before) != identity(os.fstat(incoming.fileno())) or identity(before) != identity(source_file.lstat()):
                    raise ValueError("Store source changed while copying")
            if size == 0:
                raise ValueError("Store APK is empty")
            result.append({"name": name, "bytes": size, "sha256": digest.hexdigest()})
        no_redirected_parents(target.parent)
        no_redirected_parents(owned)
        if source_conflict(target):
            raise FileExistsError("A Store source appeared while staging")
        owned.rename(target)
        return {"target": str(target), "apks": result, "publisher_verified": False,
                "acceptance": "PackageStore must verify all APKs and the actual Google publisher before installation"}
    except BaseException as failure:
        # Only paths created in this unique flat directory; never sources or previous assets.
        cleanup = []
        safe = True
        try:
            no_redirected_parents(owned)
        except (OSError, ValueError) as error:
            cleanup.append(error)
            safe = False
        if safe and owned.exists():
            for path in created:
                try:
                    if path.exists():
                        path.unlink()
                except OSError as error:
                    cleanup.append(error)
            try:
                owned.rmdir()
            except OSError as error:
                cleanup.append(error)
        for error in cleanup:
            failure.add_note("Temporary Store cleanup failed: " + str(error))
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="private APK file or flat directory containing base.apk and split_*.apk")
    print(json.dumps(stage(parser.parse_args().source), indent=2))
