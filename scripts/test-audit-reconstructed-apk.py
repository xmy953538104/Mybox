import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
import warnings
import zipfile
from unittest import mock

spec = importlib.util.spec_from_file_location("audit", pathlib.Path(__file__).with_name("audit-reconstructed-apk.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def host(path, assets):
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("classes.dex", b"dex\n035\0fixture audit bytes")
        for name, value in assets:
            output.writestr(name, value)


class AuditTest(unittest.TestCase):
    def test_clean_host_cannot_hide_play_assets_inside_the_uploaded_android_test_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            app, tests = root / "host.apk", root / "tests.apk"
            fixture = b"fixture APK bytes"
            pins = ("fixture.package=io.github.xmy953538104.mybox.fixture\nfixture.apk.sha256="
                    + hashlib.sha256(fixture).hexdigest() + "\nfixture.cert.sha256=" + "a" * 64 + "\n")
            host(app, [("assets/mybox/trusted-installer-fixture.properties", pins.encode())])
            for play in (audit.SINGLE, audit.DIRECTORY + "base.apk"):
                host(tests, [("assets/engine-fixture.apk", fixture), (play, b"private Play")])
                with self.subTest(play=play), self.assertRaisesRegex(ValueError, "public CI"):
                    audit.audit_artifacts(app, tests)
                result = audit.audit_artifacts(app, tests, True)
                self.assertIsNone(result["private_google_assets"])
                self.assertIsNotNone(result["android_test"]["private_google_assets"])
                self.assertEqual(hashlib.sha256(fixture).hexdigest(), result["installer_fixture"]["apk_sha256"])

    def test_public_audit_rejects_both_plaintext_play_forms(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "host.apk"
            for entries in [[(audit.SINGLE, b"play")], [(audit.DIRECTORY + "base.apk", b"base")]]:
                host(path, entries)
                with self.assertRaisesRegex(ValueError, "public CI"):
                    audit.audit(path)

    def test_explicit_local_flag_reports_full_set_without_claiming_publisher_authority(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "host.apk"
            entries = [(audit.DIRECTORY + "base.apk", b"base")]
            entries.extend((audit.DIRECTORY + f"split_config.{i}.apk", bytes([i])) for i in range(6))
            host(path, entries)
            result = audit.audit(path, True)["private_google_assets"]
            self.assertEqual(7, len(result["apks"]))
            self.assertFalse(result["publisher_verified"])
            self.assertEqual(sum(len(value) for name, value in entries), sum(item["bytes"] for item in result["apks"]))
            host(path, [(audit.SINGLE, b"legacy")])
            self.assertEqual(1, len(audit.audit(path, True)["private_google_assets"]["apks"]))

    def test_conflicts_missing_base_nested_empty_and_duplicate_members_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "host.apk"
            cases = [
                [(audit.SINGLE, b"single"), (audit.DIRECTORY + "base.apk", b"base")],
                [(audit.DIRECTORY + "split_en.apk", b"split")],
                [(audit.DIRECTORY + "base.apk", b"base"), (audit.DIRECTORY + "nested/", b"")],
                [(audit.DIRECTORY + "base.apk", b"")],
                [(audit.DIRECTORY + "base.apk", b"a"), (audit.DIRECTORY + "base.apk", b"b")],
            ]
            for entries in cases:
                with self.subTest(entries=entries), warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    host(path, entries)
                    with self.assertRaises(ValueError):
                        audit.audit(path, True)
            host(path, [(audit.DIRECTORY + "base.apk", b"base"), (audit.DIRECTORY + "split_en.apk", b"too big")])
            with mock.patch.object(audit, "MAX_BYTES", 8), self.assertRaisesRegex(ValueError, "oversized"):
                audit.audit(path, True)

    def test_interrupted_staging_assets_are_never_accepted_even_privately(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "host.apk"
            host(path, [("assets/mybox/.google-play-stage-fixture/base.apk", b"partial")])
            for private in (False, True):
                with self.subTest(private=private), self.assertRaisesRegex(ValueError, "Incomplete"):
                    audit.audit(path, private)

    def test_public_microg_and_fixture_assets_and_existing_engine_checks_are_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "host.apk"
            host(path, [("assets/mybox/microg-nohms.apk", b"fixture service"), ("assets/engine-fixture.apk", b"fixture")])
            self.assertIsNone(audit.audit(path)["private_google_assets"])
            with zipfile.ZipFile(path, "a") as output:
                output.writestr("classes2.dex", b"Lcom/vlite/sdk/Original;")
            with self.assertRaisesRegex(ValueError, "Excluded engine"):
                audit.audit(path, True)


if __name__ == "__main__":
    unittest.main()
