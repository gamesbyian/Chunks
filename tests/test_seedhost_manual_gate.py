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
UI_WORKFLOW = ".github/workflows/seedhost-android-add-ui-once.yml"
ADAPTER_WORKFLOW = ".github/workflows/seedhost-kotlin-adapter-smoke.yml"


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
        job = text.split("  exact-hash-start:", 1)[1].split("    runs-on:", 1)[0]
        self.assertIn("github.run_attempt == 1", job)
        self.assertIn("three synthetic transfers", text)

    def test_installed_add_requires_first_manual_attempt(self):
        text = (ROOT / UI_WORKFLOW).read_text(encoding="utf-8")
        trigger = text.partition("permissions:")[0]
        self.assertIn("  workflow_dispatch:", trigger)
        for event in ("push", "pull_request", "schedule"):
            self.assertNotIn(f"  {event}:", trigger)
        job = text.split("  installed-add-ui:", 1)[1].split("    runs-on:", 1)[0]
        for guard in ("github.event_name == 'workflow_dispatch'", "github.ref == 'refs/heads/main'",
                      "inputs.apply_add", "github.run_attempt == 1"):
            self.assertIn(guard, job)

    def test_adapter_retries_cannot_replay_mutations_and_scope_is_explicit(self):
        text = (ROOT / ADAPTER_WORKFLOW).read_text(encoding="utf-8")
        trigger = text.partition("permissions:")[0]
        self.assertIn("  workflow_dispatch:", trigger)
        for event in ("push", "pull_request", "schedule"):
            self.assertNotIn(f"  {event}:", trigger)
        job = text.split("  read-only-native-adapter:", 1)[1].split("    runs-on:", 1)[0]
        for guard in ("github.event_name == 'workflow_dispatch'", "github.ref == 'refs/heads/main'",
                      "(!inputs.apply_mutation || github.run_attempt == 1)"):
            self.assertIn(guard, job)
        self.assertIn("three synthetic Adds", text)
        self.assertIn("mutation comparison is disabled", text)
        gate = text.split("      - name: Refuse unsafe diagnostic mutation mode", 1)[1].split("      - uses:", 1)[0]
        self.assertIn("inputs.apply_mutation && inputs.diagnose_add", gate)
        self.assertIn("exit 1", gate)
        self.assertIn("with apply_mutation, also session-erase", text)

    def test_protected_mutations_share_one_serialization_group(self):
        for path in (*TARGETS, UI_WORKFLOW, ADAPTER_WORKFLOW):
            with self.subTest(path=path):
                text = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("group: chunks-android-seedhost-scope-mutation", text)
                self.assertIn("cancel-in-progress: false", text)


if __name__ == "__main__":
    unittest.main()
