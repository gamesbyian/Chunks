"""Regression gates: the owner-authorized development Seedhost requires explicit dispatch.

The one-time push-merge fixtures were accepted in runs 41349305222 and
41350485602. A push-title substring is not a genuinely one-time authorization
on an unprotected branch. Do not allow secret-bearing automatic replays.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    ".github/workflows/seedhost-active-start-once.yml",
    ".github/workflows/seedhost-exact-peer-bytes-once.yml",
)


class SeedhostManualOnlyGates(unittest.TestCase):
    def test_protected_mutations_require_manual_apply_on_main(self):
        for path in TARGETS:
            with self.subTest(path=path):
                text = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("  workflow_dispatch:", text)
                before_permissions = text.partition("permissions:")[0]
                self.assertNotIn("  push:", before_permissions)
                self.assertNotIn("  pull_request:", before_permissions)
                self.assertNotIn("  schedule:", before_permissions)
                self.assertIn("github.event_name == 'workflow_dispatch'", text)
                self.assertIn("github.ref == 'refs/heads/main'", text)
                self.assertIn("contents: read", text)
                self.assertNotIn("github.event.head_commit.message", text)
                self.assertNotIn("contains(github.event.head_commit.message", text)
                self.assertIn("cancel-in-progress: false", text)

    def test_manual_native_peer_probe_is_explicit_and_non_replaying(self):
        text = (ROOT / TARGETS[1]).read_text(encoding="utf-8")
        self.assertIn("      apply:", text)
        self.assertIn("      native_start:", text)
        self.assertIn("        default: false", text)
        self.assertIn("inputs.apply", text)
        self.assertIn("inputs.native_start", text)
        self.assertIn("github.run_attempt == 1", text)
        self.assertIn("CHUNKS_HOST_PEER_PROOF_NATIVE_START:", text)

    def test_manual_native_activity_requires_explicit_mutation_gate(self):
        text = (ROOT / TARGETS[0]).read_text(encoding="utf-8")
        self.assertIn("      apply_mutation:", text)
        self.assertIn("inputs.apply_mutation", text)
        self.assertIn("        default: false", text)


if __name__ == "__main__":
    unittest.main()
