#!/usr/bin/env python3
"""Audit the built variant and emit the exact identity consumed by Mybox."""

import argparse
import hashlib
import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path


FORBIDDEN = (
    b"Lcom/huawei/hms/", b"Lcom/huawei/agconnect/", b"Lcom/huawei/hmf/",
    b"Lorg/microg/gms/maps/hms/",
)


def audit_dex(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex = [archive.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name)]
        if not dex:
            raise ValueError("APK contains no DEX")
        for prefix in FORBIDDEN:
            if any(prefix in body for body in dex):
                raise ValueError(f"HMS class descriptor/reference present: {prefix.decode()}")
        if not any(b"Lcom/mapbox/mapboxsdk/maps/MapView;" in body for body in dex):
            raise ValueError("Selected MapLibre map implementation is missing")
        if not any(name.startswith("lib/arm64-v8a/") and name.endswith(".so") for name in names):
            raise ValueError("ARM64 native libraries are missing")
        return len(dex)


def audit_dependencies(text):
    if re.search(r"com\.huawei\.(hms|agconnect|hmf):|project :play-services-maps-core-hms|\bFAILED\b", text):
        raise ValueError("HMS dependency or unresolved runtime dependency present")
    for dependency in ("org.maplibre.gl:android-sdk:10.2.0", "project :play-services-location-core-provider"):
        if dependency not in text:
            raise ValueError(f"Required runtime dependency missing: {dependency}")
    if "project :vending-app" in text:
        raise ValueError("Companion must not be part of the Play Store runtime")


def write_identity(output, source_commit, patches):
    apk = output / "microg-nohms.apk"
    audit_dependencies((output / "runtime-dependencies.txt").read_text())
    dex_count = audit_dex(apk)
    badging = (output / "apk-badging.txt").read_text()
    identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not identity or identity[1] != "com.google.android.gms":
        raise ValueError("Unexpected APK identity")
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", (output / "apk-signature.txt").read_text())
    if len(signers) != 1:
        raise ValueError("Expected one verified APK signer")
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    cert_hash = signers[0].lower()
    (output / "trusted-microg.properties").write_text(
        f"gms.apk.sha256={apk_hash}\ngms.cert.sha256={cert_hash}\n", encoding="ascii"
    )
    metadata = {
        "schema": 1, "package_name": identity[1], "version_code": int(identity[2]),
        "version_name": identity[3], "source_repository": "https://github.com/microg/GmsCore",
        "source_commit": source_commit, "variant": "mapboxDefaultDebug",
        "apk_sha256": apk_hash, "certificate_sha256": cert_hash,
        "dex_count": dex_count, "hms_runtime_dependency": False,
        "patches": {patch.name: hashlib.sha256(patch.read_bytes()).hexdigest() for patch in patches},
        "signing": "Public Mybox development key; production must use a private stable key. Changing signer blocks in-place updates.",
        "maps": "MapLibre 10.2.0; OpenFreeMap vector basemap when no Mapbox/Stadia key. Satellite/terrain and custom Google styles unavailable in keyless mode.",
        "runtime_status": "Build audit only; Google login, Play installation and device compatibility need real-device verification.",
    }
    (output / "artifact.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Verified no-HMS build: {apk_hash}; signer {cert_hash}")


class AuditTests(unittest.TestCase):
    def test_hms_rejected_even_as_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"Lcom/mapbox/mapboxsdk/maps/MapView;")
                archive.writestr("classes2.dex", b"Lcom/huawei/hms/maps/MapView;")
                archive.writestr("lib/arm64-v8a/libmapbox-gl.so", b"")
            with self.assertRaisesRegex(ValueError, "HMS"):
                audit_dex(path)

    def test_dependency_errors_rejected(self):
        for text in ("com.huawei.hms:maps:6.9.0.300", "some.library:artifact:1.0 FAILED"):
            with self.assertRaises(ValueError):
                audit_dependencies(text)

    def test_identity_matches_exact_apk_and_signer(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with zipfile.ZipFile(output / "microg-nohms.apk", "w") as archive:
                archive.writestr("classes.dex", b"Lcom/mapbox/mapboxsdk/maps/MapView;")
                archive.writestr("lib/arm64-v8a/libmapbox-gl.so", b"test")
            (output / "runtime-dependencies.txt").write_text("org.maplibre.gl:android-sdk:10.2.0\nproject :play-services-location-core-provider")
            (output / "apk-badging.txt").write_text("package: name='com.google.android.gms' versionCode='123' versionName='test'")
            (output / "apk-signature.txt").write_text("Signer #1 certificate SHA-256 digest: " + "a" * 64)
            write_identity(output, "test", [])
            manifest = json.loads((output / "artifact.json").read_text())
            expected = hashlib.sha256((output / "microg-nohms.apk").read_bytes()).hexdigest()
            self.assertEqual(manifest["apk_sha256"], expected)
            self.assertEqual((output / "trusted-microg.properties").read_text(), f"gms.apk.sha256={expected}\ngms.cert.sha256={'a' * 64}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source-commit")
    parser.add_argument("--patches", type=Path, nargs="*", default=[])
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    else:
        if not args.output or not args.source_commit:
            parser.error("--output and --source-commit are required")
        write_identity(args.output, args.source_commit, args.patches)
