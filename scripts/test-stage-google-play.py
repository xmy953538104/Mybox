import importlib.util
import pathlib
import os
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("stage", pathlib.Path(__file__).with_name("stage-google-play.py"))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class StageTest(unittest.TestCase):
    def test_complete_set_is_copied_atomically_without_changing_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source"
            source.mkdir()
            for index in range(6):
                (source / f"split_config.{index}.apk").write_bytes(bytes([index]))
            (source / "base.apk").write_bytes(b"base")
            target = root / "assets/google-play"
            result = stage.stage(source, target)
            self.assertEqual(7, len(result["apks"]))
            self.assertEqual("base.apk", result["apks"][0]["name"])
            self.assertFalse(result["publisher_verified"])
            for original in source.iterdir():
                self.assertEqual(original.read_bytes(), (target / original.name).read_bytes())
            self.assertEqual([target], list(target.parent.iterdir()))
            with self.assertRaises(FileExistsError):
                stage.stage(source, target)

    def test_single_apk_is_packaged_as_base_and_legacy_conflict_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "play.apk"
            source.write_bytes(b"original")
            target = root / "assets/google-play"
            target.parent.mkdir()
            legacy = target.parent / "google-play.apk"
            legacy.write_bytes(b"keep legacy")
            with self.assertRaises(FileExistsError):
                stage.stage(source, target)
            self.assertEqual(b"keep legacy", legacy.read_bytes())
            separate = root / "separate/google-play"
            stage.stage(source, separate)
            self.assertEqual(b"original", (separate / "base.apk").read_bytes())

    def test_dangling_legacy_asset_is_a_conflict_and_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source.apk"
            source.write_bytes(b"source")
            target = root / "assets/google-play"
            target.parent.mkdir()
            legacy = target.parent / "google-play.apk"
            missing = root / "external-missing.apk"
            try:
                legacy.symlink_to(missing)
            except OSError as unavailable:
                self.skipTest("Host cannot create symbolic links: " + str(unavailable))
            with self.assertRaises(FileExistsError):
                stage.stage(source, target)
            self.assertTrue(legacy.is_symlink())
            self.assertFalse(missing.exists())
            self.assertFalse(target.exists())

    def test_target_parent_link_is_rejected_before_external_directory_is_touched(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source.apk"
            source.write_bytes(b"source")
            external = root / "external"
            external.mkdir()
            sentinel = external / "keep"
            sentinel.write_bytes(b"keep external")
            link = root / "assets"
            try:
                link.symlink_to(external, target_is_directory=True)
            except OSError as unavailable:
                self.skipTest("Host cannot create symbolic links: " + str(unavailable))
            with self.assertRaisesRegex(ValueError, "link or junction"):
                stage.stage(source, link / "not-created/google-play")
            self.assertEqual([sentinel], list(external.iterdir()))
            self.assertEqual(b"keep external", sentinel.read_bytes())

    @unittest.skipUnless(os.name == "nt", "Real Windows junction boundary")
    def test_windows_target_parent_junction_is_rejected_without_following_it(self):
        import _winapi
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source.apk"
            source.write_bytes(b"source")
            external = root / "external"
            external.mkdir()
            sentinel = external / "keep"
            sentinel.write_bytes(b"keep external")
            junction = root / "assets"
            _winapi.CreateJunction(str(external), str(junction))
            self.assertTrue(junction.is_junction())
            with self.assertRaisesRegex(ValueError, "link or junction"):
                stage.stage(source, junction / "not-created/google-play")
            self.assertEqual([sentinel], list(external.iterdir()))
            self.assertEqual(b"keep external", sentinel.read_bytes())

    def test_names_count_empty_and_nested_sources_fail_before_publication(self):
        from google_play_assets import members
        for names in [[], ["split_en.apk"], ["base.apk", "base.apk"],
                      ["base.apk", "split_EN.apk", "split_en.apk"], ["base.apk", "../split_en.apk"],
                      ["base.apk", "nested/file.apk"], ["base.apk", "README.md"],
                      ["base.apk", "split_" + "a" * 160 + ".apk"], ["base.apk"] * 257]:
            with self.subTest(names=names), self.assertRaises(ValueError):
                members(names)
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source"
            source.mkdir()
            (source / "base.apk").write_bytes(b"")
            with self.assertRaises(ValueError):
                stage.stage(source, root / "assets/google-play")
            (source / "base.apk").write_bytes(b"ok")
            (source / "split_nested.apk").mkdir()
            with self.assertRaises(ValueError):
                stage.stage(source, root / "assets/google-play")
            self.assertFalse((root / "assets/google-play").exists())

    def test_link_source_is_rejected_without_following_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source"
            source.write_bytes(b"original")
            link = root / "link.apk"
            try:
                link.symlink_to(source)
            except OSError as unavailable:
                self.skipTest("Host cannot create symbolic links: " + str(unavailable))
            with self.assertRaises(ValueError):
                stage.stage(link, root / "assets/google-play")
            self.assertEqual(b"original", source.read_bytes())

    def test_budget_and_partial_copy_failure_remove_only_owned_temporary_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "play.apk"
            source.write_bytes(b"source data")
            target = root / "assets/google-play"
            with mock.patch.object(stage, "MAX_BYTES", 4), self.assertRaises(ValueError):
                stage.stage(source, target)
            with mock.patch.object(stage.os, "fsync", side_effect=OSError("fixture full disk")), self.assertRaisesRegex(OSError, "fixture full disk"):
                stage.stage(source, target)
            self.assertEqual([], list(target.parent.iterdir()))
            self.assertEqual(b"source data", source.read_bytes())

    def test_stream_total_is_checked_after_preflight_even_when_a_later_source_grows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "source"
            source.mkdir()
            (source / "base.apk").write_bytes(b"aa")
            split = source / "split_en.apk"
            split.write_bytes(b"bb")
            target = root / "assets/google-play"
            real_sync = stage.os.fsync
            def grow(fd):
                real_sync(fd)
                split.write_bytes(b"grown beyond the budget")
            # Prove the streaming budget independently of metadata (which can be coarse).
            with mock.patch.object(stage, "MAX_BYTES", 4), mock.patch.object(stage, "identity", return_value="same metadata"), mock.patch.object(stage.os, "fsync", side_effect=grow), self.assertRaisesRegex(ValueError, "exceeds"):
                stage.stage(source, target)
            self.assertFalse(target.exists())
            self.assertEqual([], list(target.parent.iterdir()))

    def test_changed_source_cannot_publish_partial_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "play.apk"
            source.write_bytes(b"source data")
            target = root / "assets/google-play"
            real_sync = stage.os.fsync
            def mutate(fd):
                real_sync(fd)
                source.write_bytes(b"other bytes with changed length")
            with mock.patch.object(stage.os, "fsync", side_effect=mutate), self.assertRaisesRegex(ValueError, "source changed"):
                stage.stage(source, target)
            self.assertFalse(target.exists())
            self.assertEqual([], list(target.parent.iterdir()))


if __name__ == "__main__":
    unittest.main()
