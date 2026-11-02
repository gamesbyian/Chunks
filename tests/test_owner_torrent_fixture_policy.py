"""Guard explicit owner policy: genuine torrent metainfo may be Git fixtures."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class OwnerTorrentFixturePolicyTests(unittest.TestCase):
    def test_git_allows_intentionally_committed_torrent_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            shutil.copy(ROOT / ".gitignore", repo / ".gitignore")
            subprocess.run(["git", "init", "-q", str(repo)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            for filename in ("example.torrent", "NESTED/Example.TORRENT"):
                path = repo / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"d8:announce0:e")
                result = subprocess.run(
                    ["git", "-C", str(repo), "check-ignore", "-q", filename],
                    check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )
                self.assertEqual(1, result.returncode,
                                 "owner-approved fixture should not be blanket-ignored")

    def test_no_ci_gate_rejects_all_tracked_torrent_metainfo(self):
        self.assertFalse((ROOT / ".github/workflows/tracked-torrent-hygiene.yml").exists())
        self.assertFalse((ROOT / "tools/check_tracked_torrent_metainfo.py").exists())
        policy = (ROOT / "docs/PUBLIC-TORRENT-FIXTURES.md").read_text()
        self.assertIn("owner explicitly permits", policy)
        self.assertIn("Do not infer that a private-tracker torrent is publicly redistributable", policy)


if __name__ == "__main__":
    unittest.main()
