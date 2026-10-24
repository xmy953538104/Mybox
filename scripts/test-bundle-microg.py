import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("bundle", pathlib.Path(__file__).with_name("bundle-microg.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BundleTest(unittest.TestCase):
    def test_exact_artifact_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source, target = root / "source", root / "target"
            source.mkdir()
            apk = b"test apk bytes"
            (source / "microg-nohms.apk").write_bytes(apk)
            (source / "trusted-microg.properties").write_text(
                "gms.apk.sha256=" + hashlib.sha256(apk).hexdigest() + "\n" +
                "gms.cert.sha256=" + "a" * 64 + "\n", encoding="utf-8")
            manifest = {"schema": 1, "package_name": "com.google.android.gms",
                        "source_repository": "https://github.com/microg/GmsCore",
                        "source_commit": module.SOURCE_COMMIT, "variant": "mapboxDefaultDebug",
                        "hms_runtime_dependency": False, "apk_sha256": hashlib.sha256(apk).hexdigest(),
                        "certificate_sha256": "a" * 64, "patches": module.expected_patches()}
            (source / "artifact.json").write_text(json.dumps(manifest), encoding="utf-8")
            module.bundle(source, target)
            self.assertEqual(apk, (target / "microg-nohms.apk").read_bytes())
            for key, wrong in (("source_commit", "0" * 40), ("patches", {}),
                               ("certificate_sha256", "b" * 64), ("hms_runtime_dependency", True)):
                changed = dict(manifest)
                changed[key] = wrong
                (source / "artifact.json").write_text(json.dumps(changed), encoding="utf-8")
                with self.subTest(key=key), self.assertRaises(ValueError):
                    module.bundle(source, target)
            (source / "artifact.json").write_text(json.dumps(manifest), encoding="utf-8")
            (source / "microg-nohms.apk").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                module.bundle(source, target)


if __name__ == "__main__":
    unittest.main()
