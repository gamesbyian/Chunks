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

    def test_fenced_examples_ignored(self):
        fence = chr(96) * 3
        src = "before\n" + fence + "\n[bad](missing.md)\n" + fence + "\nafter"
        self.assertNotIn("missing", mod.without_fenced_code(src))


if __name__ == "__main__":
    unittest.main()
