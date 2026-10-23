import hashlib
import importlib.util
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
            module.bundle(source, target)
            self.assertEqual(apk, (target / "microg-nohms.apk").read_bytes())
            (source / "microg-nohms.apk").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                module.bundle(source, target)


if __name__ == "__main__":
    unittest.main()
