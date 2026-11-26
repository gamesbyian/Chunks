"""Offline guard: every invariant control still targets exactly one place in current source."""
import tempfile
import unittest
from pathlib import Path

from tools import invariant_controls as ic


class InvariantControlTests(unittest.TestCase):
    def test_every_control_target_exists_exactly_once_and_changes_the_file(self):
        for control in ic.load_controls():
            text = (ic.ROOT / control["file"]).read_text()
            mutated = ic.mutate(text, control)
            self.assertNotEqual(text, mutated, control["id"])
            self.assertTrue(control["tests"], control["id"])

    def test_ambiguous_or_missing_targets_are_refused(self):
        with self.assertRaises(ValueError):
            ic.mutate("a a", {"id": "x", "old": "a", "new": "b"})
        with self.assertRaises(ValueError):
            ic.mutate("a", {"id": "x", "old": "z", "new": "b"})

    def test_run_control_restores_the_file_even_when_the_runner_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "F.kt"
            target.write_text("val x = 1\n")
            control = {"id": "c", "invariant": "i", "file": "F.kt", "old": "1", "new": "2", "tests": ["*T"]}
            verdict = ic.run_control(control, ["false"], root)  # `false` exits 1: tests "failed"
            self.assertEqual("KILLED", verdict)
            self.assertEqual("val x = 1\n", target.read_text())
            self.assertEqual("SURVIVED", ic.run_control(control, ["true"], root))
            self.assertEqual("val x = 1\n", target.read_text())


if __name__ == "__main__":
    unittest.main()
