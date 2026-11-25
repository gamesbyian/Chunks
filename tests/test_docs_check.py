"""Offline tests for tools/check_docs.py."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent.parent / "tools" / "check_docs.py"
SPEC = importlib.util.spec_from_file_location("check_docs", SCRIPT)
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class DocsCheckTests(unittest.TestCase):
    def test_heading_deduplication(self):
        self.assertEqual(mod.heading_ids("# Same\n## Same\n"), {"same", "same-1"})

    def test_local_path_and_anchor(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs").mkdir()
            (root / "README.md").write_text("[Contract](docs/spec.md#the-rule)\n")
            (root / "docs/spec.md").write_text("# The Rule\n")
            self.assertEqual(mod.verify(root, strict_anchors=True), ([], [], 1))

    def test_failing_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs").mkdir()
            (root / "README.md").write_text("[Absent](docs/missing.md)\n[Bad anchor](docs/a.md#no)\n")
            (root / "docs/a.md").write_text("# Yes\n")
            errors, _, count = mod.verify(root, strict_anchors=True)
            self.assertEqual((len(errors), count), (2, 1))

    def test_status_budget_applies_only_to_feature_diff(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = mod.governance(root)
            self.assertEqual(baseline, mod.governance(root, ["docs/WORK-QUEUE.md", "docs/SPEC-COVERAGE.md"]))
            self.assertEqual(baseline, mod.governance(root, ["app/src/main/A.kt", "docs/WORK-QUEUE.md"]))
            errors = mod.governance(root, ["app/src/main/A.kt", "docs/WORK-QUEUE.md", "docs/SPEC-COVERAGE.md"])
            self.assertEqual(len(baseline) + 1, len(errors))
            self.assertIn("multiple status files", errors[-1])

    def test_archive_and_size_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for path, limit in mod.ENTRY_LIMITS.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"x" * (limit + 1))
            archive = root / mod.LEDGER_ARCHIVE
            archive.write_text("not the original ledger")
            failures = mod.governance(root)
            self.assertEqual(len(mod.ENTRY_LIMITS) + 1, len(failures))
            self.assertIn("blob identity changed", failures[-1])

    def test_fenced_examples_ignored(self):
        fence = chr(96) * 3
        src = "before\n" + fence + "\n[bad](missing.md)\n" + fence + "\nafter"
        self.assertNotIn("missing", mod.without_fenced_code(src))


if __name__ == "__main__":
    unittest.main()
