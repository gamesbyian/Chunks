"""Font verification is intentionally read-only across PR, main and manual runs."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NAMES = (
    "chunks-font-family.yml",
    "chunks-reference-alphabet.yml",
    "chunks-reference-panel-font.yml",
    "chunks-reference-punctuation.yml",
    "reference-layered-latin-coverage.yml",
    "reference-mixed-mark-studies.yml",
    "reference-panel-accent-kerned.yml",
    "reference-panel-accent-studies.yml",
    "reference-panel-consolidated.yml",
    "reference-panel-dotless-kerned.yml",
    "reference-panel-stacked-studies.yml",
    "reference-panel-weight-studies.yml",
)


class FontPermissionsTests(unittest.TestCase):
    def test_only_read_only_token_and_nonpersisted_checkout(self):
        for name in NAMES:
            with self.subTest(name=name):
                text = (ROOT / ".github/workflows" / name).read_text()
                self.assertIn("permissions:\n  contents: read", text)
                self.assertNotIn("contents: write", text)
                self.assertEqual(1, text.count("persist-credentials: false"))
                self.assertNotIn("persist-credentials: true", text)
                self.assertNotIn("git push", text)
                self.assertNotIn("git commit", text)

    def test_auto_commit_guards_removed_only_from_unreachable_branches(self):
        for name in NAMES:
            with self.subTest(name=name):
                text = (ROOT / ".github/workflows" / name).read_text()
                self.assertIn("  pull_request:", text)
                self.assertIn("  workflow_dispatch:", text)
                self.assertIn("  push:\n", text)
                self.assertIn("    branches: [main]", text)
                self.assertNotIn(
                    "github.event_name == 'push' && github.ref != 'refs/heads/main'",
                    text,
                )
                self.assertIn("  cancel-in-progress: " + "$" +
                              "{{ github.event_name == 'pull_request' }}", text)
                self.assertIn("\nconcurrency:\n", text)

    def test_documentation_checks_cover_every_font_workflow(self):
        text = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        for path in ("tests/test_ci_font_permissions.py",) + tuple(
            ".github/workflows/" + n for n in NAMES
        ):
            with self.subTest(path=path):
                self.assertEqual(2, text.count("      - '" + path + "'"))
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_ci_font_permissions.py",
            text,
        )


if __name__ == "__main__":
    unittest.main()
