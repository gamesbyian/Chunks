"""Offline tests of the behavioral-task package and two-sided acceptance gate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("lab", ROOT / "scripts" / "run_lab.py")
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class LabTests(unittest.TestCase):
    def test_all_tasks_have_failing_baseline_and_passing_reference(self):
        report = lab.selfcheck()
        self.assertEqual(report["tasks"], 5)
        self.assertEqual(report["valid_tasks"], 5)
        for task in report["results"]:
            self.assertFalse(task["baseline"]["passed"])
            self.assertTrue(task["reference"]["passed"])
            self.assertGreater(task["reference"]["executed_tests"], 0)

    def test_export_only_contains_editable_source_and_task(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / "task"
            report = lab.package_task("authenticated-profile", dest)
            self.assertEqual(sorted(x.name for x in dest.iterdir()), ["candidate.py", "task.md"])
            self.assertEqual(report["paths"], ["task.md", "candidate.py"])
            with self.assertRaises(FileExistsError):
                lab.package_task("authenticated-profile", dest)

    def test_unknown_task_fails(self):
        with self.assertRaises(ValueError):
            lab.package_task("../secrets", "/tmp/outside")

    def test_reference_executes_more_than_zero_tests(self):
        for tid in lab.task_ids():
            with self.subTest(task=tid):
                res = lab.run_tests(tid, "reference")
                self.assertTrue(res["passed"])
                self.assertIn("Ran ", res["test_summary"])


if __name__ == "__main__":
    unittest.main()
