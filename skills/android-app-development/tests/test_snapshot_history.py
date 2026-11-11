"""Offline edge-case and security tests for GitHub metadata inventory."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("history", root / "scripts" / "snapshot_history.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
BASE = "https://api.github.com/repos/gamesbyian/Chunks/"
LIST = BASE + "pulls?state=all&per_page=100&page=1"
SHA = "f" * 40


class Tests(unittest.TestCase):
    def test_link_next(self):
        self.assertEqual(LIST, mod.next_url('<' + LIST + '>; rel="next"'))
        self.assertIsNone(mod.next_url('<https://example.com>; rel="last"'))

    def test_live_collection_url_is_accepted_before_network(self):
        self.assertEqual("/repos/gamesbyian/Chunks/pulls", mod.check_url(LIST).path)
        with self.assertRaises(ValueError):
            mod.check_url(BASE + "pulls?page=1")

    def test_url_rejects_host_confusion_port_redirect_and_cross_repo(self):
        for url in ("https://api.github.com.evil.org/repos/x/y/pulls",
                    "https://api.github.com:443/repos/a/b/pulls",
                    "http://api.github.com/repos/a/b/pulls",
                    "https://api.github.com@evil.org/repos/a/b/pulls"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                mod.check_url(url)
        with self.assertRaises(ValueError):
            mod.check_url(BASE + "issues?state=all&per_page=100&page=2", "/repos/gamesbyian/Chunks/pulls", 2)

    def test_pagination_duplicates_and_wrong_page(self):
        second = BASE + "pulls?state=all&per_page=100&page=2"
        def duplicate(url):
            return ([{"number": 3}], second if url == LIST else None)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            mod.collection(LIST, duplicate)
        with self.assertRaisesRegex(ValueError, "pagination"):
            mod.collection(LIST, lambda _: ([{"number": 1}], LIST))

    def test_empty_page_with_next_is_failure(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            mod.collection(LIST, lambda _: ([], BASE + "pulls?state=all&per_page=100&page=2"))

    def test_snapshot_omits_bodies_and_prs_from_issue_collection(self):
        calls = []
        def getter(url):
            calls.append(url)
            if url.endswith("/branches/main"):
                return {"commit": {"sha": SHA}}, None
            if "/pulls?" in url:
                return [{"number": 3, "title": "PR", "body": "SECRET", "state": "closed"}], None
            return ([{"number": 3, "pull_request": {}, "body": "SECRET"},
                     {"number": 1, "title": "Issue", "body": "SECRET"}], None)
        data = mod.snapshot(["gamesbyian/Chunks"], getter)
        row = data["repositories"]["gamesbyian/Chunks"]
        self.assertEqual((1, 1), (row["pr_count"], row["issue_count"]))
        self.assertFalse(row["main_moved_during_scan"])
        self.assertNotIn("SECRET", str(data))
        self.assertEqual(2, len([x for x in calls if x.endswith("branches/main")]))

    def test_snapshots_require_allowlisted_repo(self):
        with self.assertRaisesRegex(ValueError, "allowlist"):
            mod.snapshot(["other/private"], lambda _: ([], None))

    def test_output_refuses_overwrite_and_symlink(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "results.json"
            mod.atomic_write(p, {"ok": 1})
            with self.assertRaises(FileExistsError):
                mod.atomic_write(p, {"ok": 2})
            self.assertIn('"ok": 1', p.read_text())
            mod.atomic_write(p, {"ok": 2}, overwrite=True)
            self.assertIn('"ok": 2', p.read_text())
            link = Path(d) / "symlink.json"
            link.symlink_to(p)
            with self.assertRaises(FileExistsError):
                mod.atomic_write(link, {"ok": 3}, overwrite=True)
            self.assertIn('"ok": 2', p.read_text())

    def test_get_does_not_send_credentials_to_unknown_host(self):
        with self.assertRaises(ValueError):
            mod.github_get("https://api.github.com.attacker.net/repos/x/y/pulls", "dummy", opener=lambda *_a, **_k: self.fail("sent token"))

    def test_redirect_refusal(self):
        with self.assertRaisesRegex(ValueError, "redirect"):
            mod.RejectRedirect().redirect_request(None, None, 302, "", {}, "https://evil.org")


if __name__ == "__main__":
    unittest.main()
