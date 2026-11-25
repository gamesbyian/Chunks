"""Regression controls for cheap syntax validation before workflow execution."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "tools/check_ci_yaml.rb"


class CiYamlTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.workflows = self.root / ".github/workflows"
        self.workflows.mkdir(parents=True)
        self.actions = self.root / ".github/actions/example"
        self.actions.mkdir(parents=True)

    def run_check(self):
        return subprocess.run(["ruby", str(SCRIPT), str(self.root)],
                              capture_output=True, text=True, timeout=10)

    def test_literal_colon_in_block_and_local_action_pass(self):
        (self.workflows / "valid.yml").write_text(
            'name: Example\non: workflow_dispatch\njobs:\n  check:\n'
            '    runs-on: ubuntu-latest\n    steps:\n      - run: |\n'
            '          echo "RECEIPT: valid"\n')
        (self.actions / "action.yaml").write_text(
            'name: Example\nruns:\n  using: composite\n  steps: []\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("parsed 2 definitions; 0 invalid", result.stdout)

    def test_reports_both_invalid_workflow_and_action(self):
        (self.workflows / "broken.yml").write_text(
            'name: Example\non: workflow_dispatch\njobs:\n  check:\n'
            '    runs-on: ubuntu-latest\n    steps:\n'
            '      - run: echo "RECEIPT: invalid"\n')
        (self.actions / "action.yml").write_text('name: [unterminated\n')
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("broken.yml", result.stderr)
        self.assertIn("action.yml", result.stderr)
        self.assertIn("2 invalid", result.stdout)

    def test_no_definitions_fails(self):
        self.assertNotEqual(self.run_check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
