"""Offline negative controls for all-workflow CI inventory."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.ci_workflow_inventory import collect, inspect, markdown

ROOT = Path(__file__).resolve().parents[1]


class WorkflowInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.folder = self.root / ".github" / "workflows"
        self.folder.mkdir(parents=True)

    def fixture(self, name, value):
        path = self.folder / name
        path.write_text(value, encoding="utf-8")
        return path

    def test_complete_scope_and_trigger_metadata(self):
        self.fixture("pr.yml", """name: PR safe
on:
  pull_request:
    paths:
      - "app/**"
  workflow_dispatch:
permissions:
  contents: read
concurrency:
  group: safe-PR
  cancel-in-progress: true
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
""")
        self.fixture("manual.yaml", """name: Manual
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: echo okay
""")
        report = collect(self.root)
        self.assertEqual(2, report["workflow_count"])
        self.assertEqual(1, report["event_counts"]["pull_request"])
        self.assertEqual([], report["missing_concurrency"])
        self.assertEqual(2, len(report["workflows"]))
        self.assertTrue(report["workflows"][1]["pr_paths_filter"])
        self.assertIn("2 workflow files", markdown(report))

    def test_missing_workflow_fails_closed(self):
        with self.assertRaises(ValueError):
            collect(self.root)
        self.fixture("bad.yml", "name: Invalid\non:\n  pull_request:\n")
        with self.assertRaisesRegex(ValueError, "missing_jobs"):
            collect(self.root)

    def test_never_exposes_secret_values_or_credential_names(self):
        source = """name: Protected
on:
  workflow_dispatch:
permissions:
  contents: write
jobs:
  operation:
    runs-on: ubuntu-latest
    steps:
      - run: echo secret-value-123
        env:
          PASSWORD: \${{ secrets.ACTUAL_KEY_NAME }}
"""
        file = self.fixture("secret.yml", source)
        data = inspect(file, self.root)
        self.assertTrue(data["flags"]["secrets_reference"])
        self.assertTrue(data["flags"]["write_permission"])
        self.assertNotIn("ACTUAL_KEY_NAME", json.dumps(data))
        self.assertNotIn("secret-value-123", json.dumps(data))

    def test_uncancelled_pr_is_flagged_but_not_omitted(self):
        self.fixture("research.yml", """name: Research
on:
  pull_request:
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - run: echo hello
""")
        result = collect(self.root)
        self.assertEqual(1, len(result["missing_concurrency"]))
        self.assertEqual(1, result["workflow_count"])

    def test_current_repo_has_no_missing_workflows(self):
        result = collect(ROOT)
        expected = sorted(p.relative_to(ROOT).as_posix()
                          for suffix in ("*.yml", "*.yaml")
                          for p in (ROOT / ".github" / "workflows").glob(suffix))
        self.assertEqual(expected, [w["path"] for w in result["workflows"]])
        self.assertGreater(len(expected), 0)
        self.assertEqual(len(expected), result["workflow_count"])


if __name__ == "__main__":
    unittest.main()
