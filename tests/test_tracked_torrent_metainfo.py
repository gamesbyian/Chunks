"""Isolated regression controls for raw tracker metainfo in tracked Git files."""
import contextlib
from io import StringIO
from fnmatch import fnmatchcase
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.check_tracked_torrent_metainfo import main, tracked_torrent_count


class TrackedMetainfoTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        (self.root / ".gitignore").write_text(
            "*.torrent\n*.[tT][oO][rR][rR][eE][nN][tT]\n", encoding="utf-8")
        self.git("add", ".gitignore")

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.root), *args],
                       check=True, capture_output=True, timeout=20)

    def test_untracked_download_is_not_approached_or_enumerated(self):
        (self.root / "private.torrent").write_bytes(b"passkey=DO-NOT-PRINT")
        self.assertEqual(0, tracked_torrent_count(self.root))
        out, err = StringIO(), StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(0, main(["--root", str(self.root)]))
        self.assertIn("0 raw .torrent", out.getvalue())
        self.assertNotIn("passkey", out.getvalue() + err.getvalue())

    def test_force_added_nested_and_mixed_case_are_blocked_without_leaking_names(self):
        a = self.root / "private.torrent"
        b = self.root / "nest" / "TRACKER.TORRENT"
        b.parent.mkdir()
        a.write_bytes(b"passkey=VERY-SENSITIVE")
        b.write_bytes(b"my-private-tracker.example")
        self.git("add", "-f", "private.torrent", "nest/TRACKER.TORRENT")
        self.assertEqual(2, tracked_torrent_count(self.root))
        output = StringIO()
        with contextlib.redirect_stderr(output):
            self.assertEqual(1, main(["--root", str(self.root)]))
        reported = output.getvalue()
        self.assertIn("2 raw .torrent", reported)
        for value in ("private.torrent", "TRACKER.TORRENT", "VERY-SENSITIVE",
                      "my-private-tracker.example"):
            self.assertNotIn(value, reported)

    def test_uppercase_extensions_are_ignored_and_trigger_covers_all_cases(self):
        for relative in ("ROOT.TORRENT", "nested/MiXeD.ToRrEnT"):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"harmless test fixture")
            result = subprocess.run(
                ["git", "-C", str(self.root), "check-ignore", "-q", relative],
                check=False, capture_output=True, timeout=20,
            )
            self.assertEqual(0, result.returncode, relative)
        workflow = (Path(__file__).resolve().parents[1] / ".github" /
                    "workflows" / "tracked-torrent-hygiene.yml").read_text()
        patterns = [
            "*.[tT][oO][rR][rR][eE][nN][tT]",
            "**/*.[tT][oO][rR][rR][eE][nN][tT]",
        ]
        for pattern in patterns:
            self.assertEqual(2, workflow.count("'" + pattern + "'"))
        for name in ("raw.torrent", "ROOT.TORRENT",
                     "nested/MiXeD.ToRrEnT"):
            self.assertTrue(any(fnmatchcase(name, pattern) for pattern in patterns),
                            name)

    def test_git_unavailable_fails_closed_without_dumping_checkout_paths(self):
        output = StringIO()
        with contextlib.redirect_stderr(output):
            self.assertEqual(2, main(["--root", str(self.root / "missing")]))
        self.assertIn("unavailable", output.getvalue())
        self.assertNotIn(str(self.root), output.getvalue())


if __name__ == "__main__":
    unittest.main()
