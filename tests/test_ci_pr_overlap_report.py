"""Read-only PR overlap diagnostics: real #1171/#1175 shape and negative controls."""
import json
import unittest
from unittest.mock import patch

from tools.ci_pr_overlap_report import file_identity, live_pr_files, overlap_report


def changed(path, patch, status="modified", previous=None):
    return {"filename": path, "status": status, "patch": patch,
            **({"previous_filename": previous} if previous is not None else {})}


class PrOverlapTests(unittest.TestCase):
    def test_identical_small_pr_is_fully_subsumed_by_larger_ux_wave(self):
        # Structural analogue of the verified identical AndroidManifest and
        # PhoneImeManifestTest patches in actual #1171 and #1175.
        manifest = changed("app/src/main/AndroidManifest.xml", "@@ -25 +25 @@\n+adjustResize")
        test = changed("app/src/test/java/PhoneImeManifestTest.kt", "@@ -0 +1 @@\n+test")
        other = changed("app/src/main/java/MainActivity.kt", "@@ -1 +1 @@\n+new")
        result = overlap_report([
            {"number": 1171, "files": [manifest, test]},
            {"number": 1175, "files": [manifest, test, other]},
        ])
        self.assertEqual(2, result["open_prs"])
        self.assertEqual(1, result["overlapping_pairs"])
        self.assertEqual(1, result["fully_subsumed_candidates"])
        row = result["pairs"][0]
        self.assertEqual(2, row["shared_files"])
        self.assertEqual(2, row["exact_patch_files"])
        self.assertEqual(0, row["different_patch_files"])
        self.assertEqual(0, row["unknown_patch_files"])
        self.assertEqual(0, row["only_a_files"])
        self.assertEqual(1, row["only_b_files"])
        self.assertEqual([{"contained_pr": 1171, "covering_pr": 1175}],
                         row["fully_subsumed_candidates"])
        self.assertNotIn("adjustResize", json.dumps(result))
        self.assertIn("Do not auto-close", result["qualification"])

    def test_same_file_distinct_patch_is_conflict_not_duplicate(self):
        result = overlap_report([
            {"number": 1, "files": [changed("app/Main.kt", "left")]},
            {"number": 2, "files": [changed("app/Main.kt", "right")]},
        ])
        row = result["pairs"][0]
        self.assertEqual(1, row["different_patch_files"])
        self.assertEqual(0, row["exact_patch_files"])
        self.assertEqual([], row["fully_subsumed_candidates"])

    def test_missing_binary_patch_and_changed_status_are_unknown_or_different(self):
        result = overlap_report([
            {"number": 1, "files": [changed("icon.png", None),
                                    changed("screen.kt", "same", "added")]},
            {"number": 2, "files": [changed("icon.png", None),
                                    changed("screen.kt", "same", "modified")]},
        ])
        row = result["pairs"][0]
        self.assertEqual(1, row["unknown_patch_files"])
        self.assertEqual(1, row["different_patch_files"])
        self.assertEqual([], row["fully_subsumed_candidates"])

    def test_rename_origin_must_match(self):
        result = overlap_report([
            {"number": 1, "files": [changed("target.kt", "same", "renamed", "old.kt")]},
            {"number": 2, "files": [changed("target.kt", "same", "renamed", "other.kt")]},
        ])
        self.assertEqual(1, result["pairs"][0]["different_patch_files"])

    def test_disjoint_pairs_do_not_produce_noise(self):
        result = overlap_report([
            {"number": 1, "files": [changed("a.kt", "x")]},
            {"number": 2, "files": [changed("b.kt", "x")]},
            {"number": 3, "files": []},
        ])
        self.assertEqual(0, result["overlapping_pairs"])

    def test_invalid_inputs_fail_closed(self):
        fixtures = [
            ([{"number": 1, "files": []}, {"number": 1, "files": []}], "Duplicate"),
            ([{"number": 1, "files": "missing"}], "Missing file"),
            ([{"number": 1, "files": [changed("x", "a"), changed("x", "b")]}], "Duplicate filename"),
            ([{"number": 1, "files": [{"filename": "../x", "status": "added", "patch": 123}]}], "Invalid patch"),
            ([{"number": 1, "files": [{"filename": "/x", "status": "added", "patch": "x"}]}], "Invalid repository filename"),
            ([{"number": 1, "files": [changed("x", "abc", "other")]}], "Unsupported"),
        ]
        for rows, err in fixtures:
            with self.subTest(error=err), self.assertRaisesRegex(ValueError, err):
                overlap_report(rows)

    def test_live_reader_requires_complete_open_pr_and_file_pages(self):
        responses = [
            [{"number": 1}, {"number": 2}],
            [changed("a.kt", "x")],
            [changed("a.kt", "x"), changed("b.kt", "y")],
        ]
        calls = []
        def fetch(p):
            calls.append(p)
            return responses[len(calls)-1]
        records = live_pr_files("gamesbyian/Chunks", fetch)
        self.assertEqual([1, 2], [r["number"] for r in records])
        self.assertEqual(3, len(calls))
        self.assertEqual("repos/gamesbyian/Chunks/pulls?state=open&per_page=100", calls[0])
        self.assertEqual("repos/gamesbyian/Chunks/pulls/1/files?per_page=100", calls[1])
        self.assertEqual(1, overlap_report(records)["fully_subsumed_candidates"])
        for response, error in [
            ([{"number": 1}] * 41, "safe complete sample"),
            ([{"number": 1}] * 100, "safe complete sample"),
            ({"unexpected": "data"}, "Unexpected"),
        ]:
            with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                live_pr_files("gamesbyian/Chunks", lambda _: response)
        with self.assertRaisesRegex(ValueError, "Invalid owner"):
            live_pr_files("not/repo/extra", lambda _: [])

    def test_truncated_per_pr_file_list_is_unknown_not_subset(self):
        with self.assertRaisesRegex(ValueError, "possibly truncated"):
            live_pr_files("gamesbyian/Chunks", lambda p: (
                [{"number": 7}] if "state=open" in p else
                [changed(f"file{x}.kt", "patch") for x in range(100)]))


if __name__ == "__main__":
    unittest.main()
