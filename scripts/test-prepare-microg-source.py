"""Real-Git regression tests; no Android SDK, remote checkout or build is needed."""
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare_microg", pathlib.Path(__file__).with_name("prepare-microg-source.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PatchSeriesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="mybox-patch-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.git("init", "--quiet")
        self.git("config", "user.name", "Patch test")
        self.git("config", "user.email", "patch-test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        (self.source / ".gitignore").write_text("build/\nlocal.properties\nuser.gradle\n", encoding="utf-8")
        (self.source / "original.txt").write_text("original\n", encoding="utf-8")
        (self.source / "unrelated.txt").write_text("preserve\n", encoding="utf-8")
        self.commit()
        self.base = self.git("rev-parse", "HEAD").stdout.decode().strip()
        (self.source / "Identity.java").write_text("class Identity { int value = 1; }\n", encoding="utf-8")
        self.commit()
        first = self.git("rev-parse", "HEAD").stdout.decode().strip()
        (self.source / "Identity.java").write_text("class Identity { int value = 2; }\n", encoding="utf-8")
        (self.source / "original.txt").write_text("approved\n", encoding="utf-8")
        self.commit()
        second = self.git("rev-parse", "HEAD").stdout.decode().strip()
        self.patches = [self.root / "0001.patch", self.root / "0002.patch"]
        self.patches[0].write_bytes(self.git("diff", "--binary", self.base, first).stdout)
        self.patches[1].write_bytes(self.git("diff", "--binary", first, second).stdout)
        self.git("checkout", "--quiet", "--detach", self.base)
        self.diff = self.root / "actual-source.patch"

    def git(self, *args):
        return module.git(self.source, *args)

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", "fixture")

    def snapshot(self):
        files = {p.relative_to(self.source).as_posix(): p.read_bytes()
                 for p in self.source.rglob("*") if p.is_file()
                 and ".git" not in p.relative_to(self.source).parts}
        return files, (self.source / ".git/index").read_bytes()

    def prepare(self, patches=None):
        return module.prepare(self.source, self.base, patches or self.patches, self.diff)

    def rejected_unchanged(self, patches=None):
        before = self.snapshot()
        with self.assertRaises(module.SourceStateError):
            self.prepare(patches)
        self.assertEqual(before, self.snapshot())

    def test_overlapping_new_file_and_repeat_preserve_main_index(self):
        original_index = self.snapshot()[1]
        self.assertEqual("applied", self.prepare())
        self.assertEqual(original_index, self.snapshot()[1])
        self.assertIn("value = 2", (self.source / "Identity.java").read_text())
        self.assertIn(b"Identity.java", self.diff.read_bytes())
        # Normal generated build files are ignored, not misclassified as source changes.
        (self.source / "build").mkdir()
        (self.source / "build/IdentitySelfTest.class").write_bytes(b"generated")
        before = self.snapshot()
        recorded = self.diff.read_bytes()
        for _ in range(2):
            self.assertEqual("already applied", self.prepare())
            self.assertEqual(before, self.snapshot())
            self.assertEqual(recorded, self.diff.read_bytes())

    def test_old_intent_to_add_index_is_preserved(self):
        for patch in self.patches:
            self.git("apply", "--intent-to-add", str(patch))
        before = self.snapshot()
        for _ in range(2):
            self.assertEqual("already applied", self.prepare())
            self.assertEqual(before, self.snapshot())

    def test_partial_or_older_series_rejected(self):
        self.git("apply", str(self.patches[0]))
        self.rejected_unchanged()

    def test_wrong_series_rejected(self):
        self.prepare()
        self.rejected_unchanged(self.patches[:1])
        wrong = self.root / "wrong.patch"
        wrong.write_bytes(self.patches[1].read_bytes().replace(b"value = 2", b"value = 3"))
        self.rejected_unchanged([self.patches[0], wrong])

    def test_extra_tracked_or_untracked_source_rejected(self):
        self.prepare()
        for path in ("unrelated.txt", "Identity.java", "Extra.java"):
            with self.subTest(path=path):
                target = self.source / path
                previous = target.read_bytes() if target.exists() else None
                target.write_text("additional source\n", encoding="utf-8")
                self.rejected_unchanged()
                if previous is None:
                    target.unlink()
                else:
                    target.write_bytes(previous)

    def test_staged_changes_rejected_even_with_pristine_worktree(self):
        target = self.source / "unrelated.txt"
        target.write_text("staged work\n", encoding="utf-8")
        self.git("add", "unrelated.txt")
        target.write_text("preserve\n", encoding="utf-8")
        self.rejected_unchanged()

    def test_invalid_later_patch_cannot_partly_modify_pristine_source(self):
        broken = self.root / "broken.patch"
        broken.write_text("not a patch\n", encoding="utf-8")
        self.rejected_unchanged([self.patches[0], broken])

    def test_wrong_head_rejected(self):
        (self.source / "unrelated.txt").write_text("other commit\n", encoding="utf-8")
        self.commit()
        self.rejected_unchanged()


if __name__ == "__main__":
    unittest.main()
