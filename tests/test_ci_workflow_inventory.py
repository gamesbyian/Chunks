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

    def test_workflow_only_main_push_duplicates_pr_verification(self):
        self.fixture("redundant.yml", """name: Redundant
on:
  push:
    branches: [main]
    paths:
      - '.github/workflows/redundant.yml'
      - 'assets/fonts/**'
  pull_request:
    paths:
      - '.github/workflows/redundant.yml'
  workflow_dispatch:
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - run: echo check
""")
        self.fixture("intended.yml", """name: Intended
on:
  push:
    branches: [main]
    paths:
      - 'assets/fonts/**'
  pull_request:
    paths:
      - '.github/workflows/intended.yml'
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - run: echo check
""")
        report = collect(self.root)
        self.assertEqual(
            [".github/workflows/redundant.yml"],
            report["main_self_trigger_candidates"],
        )
        rows = {Path(w["path"]).name: w for w in report["workflows"]}
        self.assertTrue(rows["redundant.yml"]["self_trigger_on_pr"])
        self.assertFalse(rows["intended.yml"]["self_trigger_on_main_push"])

    def test_multiline_main_push_is_reported_without_false_branch_matches(self):
        cases = {
            "multiline.yml": """name: Multiline main
on:
  push:
    branches:
      - development
      # Inline comments and quotes are valid.
      - "main" # merged main
    paths:
      - '.github/workflows/multiline.yml'
  pull_request:
    paths:
      - '.github/workflows/multiline.yml'
jobs:
  check:
    runs-on: ubuntu-latest
""",
            "inline.yml": """name: Quoted inline
on:
  push:
    branches: [release, 'main'] # both refs
    paths:
      - '.github/workflows/inline.yml'
  pull_request:
    paths:
      - '.github/workflows/inline.yml'
jobs:
  check:
    runs-on: ubuntu-latest
""",
            "different.yml": """name: Different branch
on:
  push:
    branches:
      - main-release
    paths:
      - '.github/workflows/different.yml'
  pull_request:
    paths:
      - '.github/workflows/different.yml'
jobs:
  check:
    runs-on: ubuntu-latest
""",
            "ignored.yml": """name: Branch ignore does not include main
on:
  push:
    branches-ignore:
      - main
    paths:
      - '.github/workflows/ignored.yml'
  pull_request:
    paths:
      - '.github/workflows/ignored.yml'
jobs:
  check:
    runs-on: ubuntu-latest
""",
            "unrelated.yml": """name: Path name must not count as a branch
on:
  push:
    branches:
      - development
    paths:
      - '.github/workflows/unrelated.yml'
      - 'main'
  pull_request:
    paths:
      - '.github/workflows/unrelated.yml'
jobs:
  check:
    runs-on: ubuntu-latest
""",
        }
        for name, source in cases.items():
            self.fixture(name, source)
        report = collect(self.root)
        self.assertEqual(
            [".github/workflows/inline.yml", ".github/workflows/multiline.yml"],
            report["main_self_trigger_candidates"],
        )
        self.assertEqual(5, report["workflow_count"])
        for row in report["workflows"]:
            self.assertTrue(row["self_trigger_on_pr"])
        self.assertFalse(next(row for row in report["workflows"]
                              if row["path"].endswith("different.yml"))[
                                  "self_trigger_on_main_push"])

    def test_feature_push_is_not_flagged_as_main_duplicate(self):
        path = self.fixture("feature.yml", """name: Feature
on:
  push:
    branches: [development]
    paths:
      - '.github/workflows/feature.yml'
  pull_request:
    paths:
      - '.github/workflows/feature.yml'
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - run: echo okay
""")
        result = inspect(path, self.root)
        self.assertFalse(result["self_trigger_on_main_push"])

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
          PASSWORD: ${{ secrets.ACTUAL_KEY_NAME }}
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

    def test_artifact_default_retention_counts_real_steps_not_shell_text(self):
        self.fixture("uploads.yml", """name: Uploads
on:
  workflow_dispatch:
jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - name: Explicit short retention
        uses: actions/upload-artifact@v4
        with:
          name: reports
          retention-days: 3
      - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
        with:
          name: implicit-repository-default
      - run: |
          echo testing
          - uses: actions/upload-artifact@v4
      - uses: actions/upload-artifact@v4
        with:
          name: second-implicit
""")
        self.fixture("bounded.yml", """name: Bounded
on:
  workflow_dispatch:
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/upload-artifact@v4
        with:
          retention-days: 7
""")
        report = collect(self.root)
        rows = {Path(w["path"]).name: w for w in report["workflows"]}
        self.assertEqual(3, rows["uploads.yml"]["artifact_upload_steps"])
        self.assertEqual(
            2, rows["uploads.yml"]["artifact_upload_steps_with_default_retention"])
        self.assertEqual(1, rows["bounded.yml"]["artifact_upload_steps"])
        self.assertEqual(
            0, rows["bounded.yml"]["artifact_upload_steps_with_default_retention"])
        self.assertEqual(2, report["artifact_default_retention_upload_steps"])
        self.assertEqual([".github/workflows/uploads.yml"],
                         report["artifact_default_retention_candidates"])
        self.assertIn("2 steps across 1 workflows", markdown(report))

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
