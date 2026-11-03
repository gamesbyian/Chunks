"""The APK gate must include disposable precompleted payload preservation."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from finish_installed_magnet_evidence import precompleted_control_preserved, safe_failure_diagnostic


class PrecompletedControlEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {"CHUNKS_CONTROLLED_PEER": "1", "CHUNKS_DISPOSABLE": "1",
                    "GITHUB_RUN_ID": "12345"}
        self.paths = (self.root / "chunks-real-peer/magnet0/chunks-precompleted-control.bin",
                      self.root / "chunks-disposable/data/chunks-precompleted-control.bin")
        original = hashlib.sha256(b"12345").digest() * 2048
        for path in self.paths:
            path.parent.mkdir(parents=True)
            path.write_bytes(original)

    def test_both_exact_originals_are_required(self):
        self.assertIs(precompleted_control_preserved(self.root, self.env), True)
        self.paths[1].write_bytes(b"changed")
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)
        self.paths[1].unlink()
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_matching_but_substituted_copies_do_not_pass(self):
        for path in self.paths:
            path.write_bytes(b"x" * 65536)
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_alias_to_original_does_not_count_as_two_preserved_files(self):
        self.paths[1].unlink()
        self.paths[1].symlink_to(self.paths[0])
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_other_modes_do_not_fabricate_control_evidence(self):
        for env in ({"CHUNKS_CONTROLLED_PEER": "1", "CHUNKS_DISPOSABLE": "0"},
                    dict(self.env, CHUNKS_PUBLIC_DISCOVERY="1")):
            self.assertIsNone(precompleted_control_preserved(self.root, env))
        self.assertIs(precompleted_control_preserved(self.root, dict(self.env, GITHUB_RUN_ID="bad")), False)


class SafeInstalledFailureDiagnosticTests(unittest.TestCase):
    def test_real_android_connect_failure_is_actionable_without_raw_stack(self):
        data = {
            "runner": {"failed_tests": 1, "runner_finished": True},
            "app_stage_evidence": ["runner-ready", "connect-click-attempt",
                                   "endpoint=https://private.example?passkey=NEVER_ECHO"],
            "safe_failure_source_frames": [
                {"file": "MainActivityRealMagnetJourney.kt", "line": 53},
                {"file": "../../secret/host.kt", "line": 144},
            ],
        }
        detail = safe_failure_diagnostic(data, peer_valid=False, control_preserved=True)
        self.assertEqual({
            "reason": "android-test-failed",
            "last_ui_stage": "connect-click-attempt",
            "first_safe_frame": "MainActivityRealMagnetJourney.kt:53",
        }, detail)
        self.assertNotIn("NEVER_ECHO", str(detail))
        self.assertNotIn("private.example", str(detail))

    def test_separate_causal_categories_do_not_override_gate(self):
        cases = [
            ({"runner": {"failed_tests": 0, "runner_finished": False}},
             False, None, "instrumentation-incomplete"),
            ({"runner": {"failed_tests": 0, "runner_finished": True}},
             False, None, "independent-peer-evidence-not-proven"),
            ({"runner": {"failed_tests": 0, "runner_finished": True}},
             True, False, "precompleted-control-bytes-mismatch"),
            ({"runner": {"failed_tests": 0, "runner_finished": True}},
             True, True, "required-case-or-phase-evidence-incomplete"),
        ]
        for receipt, peer_valid, control, reason in cases:
            with self.subTest(reason=reason):
                self.assertEqual(reason,
                    safe_failure_diagnostic(receipt, peer_valid, control)["reason"])

    def test_untrusted_labels_and_frames_are_not_echoed(self):
        data = {
            "runner": {"failed_tests": "SECRET", "runner_finished": False},
            "app_stage_evidence": ["http://private.example", 3, {"secret": "NEVER_ECHO"}],
            "safe_failure_source_frames": [
                {"file": ["MainActivityRealMagnetJourney.kt"], "line": 53},
                {"file": "MainActivityRealMagnetJourney.kt", "line": True},
                {"file": "MainActivityRealMagnetJourney.kt", "line": -100},
                {"file": "Fake.kt:NEVER_ECHO", "line": 7},
            ],
        }
        detail = safe_failure_diagnostic(data, peer_valid=False, control_preserved=None)
        self.assertEqual("none", detail["last_ui_stage"])
        self.assertEqual("none", detail["first_safe_frame"])
        self.assertEqual("instrumentation-incomplete", detail["reason"])
        self.assertNotIn("NEVER_ECHO", str(detail))
        self.assertNotIn("private.example", str(detail))



if __name__ == "__main__":
    unittest.main()
