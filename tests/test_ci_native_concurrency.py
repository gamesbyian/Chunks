"""Contract for native research supersession without cross-PR cancellation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NAMES = (
    "native-owned-android-link.yml",
    "native-owned-writer-experiment.yml",
    "native-owned-debug-overlay-smoke.yml",
)


def group(workflow, pull_number, run_id):
    identity = str(pull_number) if pull_number is not None else str(run_id)
    return "chunks-" + workflow[:-4] + "-" + identity


def should_cancel(event_name):
    return event_name == "pull_request"


class NativeRunCancellationTests(unittest.TestCase):
    def test_workflow_specific_pr_and_manual_exprs(self):
        for name in NAMES:
            with self.subTest(name=name):
                text = (ROOT / ".github/workflows" / name).read_text()
                self.assertEqual(1, text.count("\nconcurrency:\n"))
                self.assertIn(
                    "group: chunks-" + name[:-4] +
                    "-" + "$" + "{{ github.event.pull_request.number || github.run_id }}",
                    text,
                )
                self.assertIn(
                    "cancel-in-progress: " + "$" +
                    "{{ github.event_name == 'pull_request' }}", text
                )
                self.assertIn("  workflow_dispatch:", text)
                self.assertIn("  pull_request:", text)
                self.assertIn("permissions:\n  contents: read", text)

    def test_only_newer_revision_of_same_pr_is_cancellable(self):
        for name in NAMES:
            with self.subTest(name=name):
                self.assertEqual(group(name, 13, 110), group(name, 13, 111))
                self.assertNotEqual(group(name, 13, 110), group(name, 14, 111))
                self.assertNotEqual(group(name, None, 110), group(name, None, 111))
                self.assertNotEqual(group(name, None, 110), group(name, 13, 110))
                self.assertTrue(should_cancel("pull_request"))
                for e in ("workflow_dispatch", "push", "schedule", "release"):
                    self.assertFalse(should_cancel(e))

    def test_other_workflows_do_not_share_group(self):
        for i, a in enumerate(NAMES):
            for b in NAMES[i + 1:]:
                self.assertNotEqual(group(a, 13, 555), group(b, 13, 555))

    def test_docs_gate_owns_all_changes(self):
        text = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        for path in ("tests/test_ci_native_concurrency.py",) + tuple(
            ".github/workflows/" + n for n in NAMES
        ):
            self.assertEqual(2, text.count("      - '" + path + "'"))
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_ci_native_concurrency.py",
            text,
        )


if __name__ == "__main__":
    unittest.main()
