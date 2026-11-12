"""Offline counterexamples for combined campaign publication; not device acceptance."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from ci_failure_digest import make_digest
from finish_installed_magnet_evidence import required_journeys_verified, safe_failure_diagnostic


class InstalledJourneyGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(GITHUB_SHA="a" * 40, GITHUB_RUN_ID="123", CHUNKS_HTTPS_ADD="1", CHUNKS_REAL_MEDIA="1")

    def receipt(self, path, suite, classname, method, skipped=False):
        xml = self.root / "actual.xml"
        xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="' + str(int(skipped)) +
                       '"><testcase classname="' + classname + '" name="' + method + '">' +
                       ('<skipped/>' if skipped else '') + '</testcase></testsuite>')
        data = make_digest([xml], "success", suite, self.env)
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data))
        return target

    def local(self, **kw):
        return self.receipt("chunks-https-local-digest.json", "https-local",
                            "io.github.gamesbyian.chunks.HttpsLocalAddJourneyInstrumentedTest",
                            "fetchedHttpsTorrentUsesOneTimePlaceThenNextAddUsesUnchangedDefault", **kw)

    def media(self, phase):
        methods = (["phaseA_tvmazeConsentSelectionAndArtwork", "phaseA_musicbrainzSelectionAndArtwork", "phaseA_openlibrarySelectionAndArtwork"]
                   if phase == "a" else ["phaseB_tvmazeOfflineProcess", "phaseB_musicbrainzOfflineProcess", "phaseB_openlibraryOfflineProcess"])
        paths = [self.receipt("chunks-real-media/" + method + "-digest.json", "installed-real-media-" + method,
                            "io.github.gamesbyian.chunks.InstalledRealMediaInstrumentedTest", method) for method in methods]
        return paths[-1]

    def test_missing_local_and_media_cannot_publish_remote_only_success(self):
        self.assertFalse(required_journeys_verified(self.root, self.env))

    def test_online_success_does_not_replace_cold_offline_process(self):
        self.local(); self.media("a")
        self.assertFalse(required_journeys_verified(self.root, self.env))

    def test_all_seven_actual_same_source_journeys_pass(self):
        self.local(); self.media("a"); self.media("b")
        self.assertTrue(required_journeys_verified(self.root, self.env))

    def test_skipped_local_cannot_publish(self):
        self.local(skipped=True); self.media("a"); self.media("b")
        self.assertFalse(required_journeys_verified(self.root, self.env))

    def test_stale_source_or_other_run_cannot_be_reused(self):
        self.local(); self.media("a"); p = self.media("b")
        data = json.loads(p.read_text())
        for key, value in [("source_sha", "b" * 40), ("run_id", "different")]:
            changed = dict(data, **{key: value}); p.write_text(json.dumps(changed))
            self.assertFalse(required_journeys_verified(self.root, self.env))

    def test_extra_or_substituted_test_identity_is_rejected(self):
        self.local(); self.media("a"); p = self.media("b")
        data = json.loads(p.read_text()); data['test_cases'][0]['id_sha256'] = 'f' * 64
        p.write_text(json.dumps(data))
        self.assertFalse(required_journeys_verified(self.root, self.env))

    def test_missing_journey_is_named_even_when_remote_and_peer_passed(self):
        diagnostic = safe_failure_diagnostic({"runner": {"runner_finished": True, "failed_tests": 0}},
                                             True, True, journeys_verified=False)
        self.assertEqual("required-installed-journey-not-proven", diagnostic["reason"])

    def test_exhausted_read_only_rpc_is_a_safe_failure_stage_not_acceptance(self):
        diagnostic = safe_failure_diagnostic({
            "runner": {"failed_tests": 1, "runner_finished": True},
            "app_stage_evidence": ["place-ready", "read-only-exhausted"],
        }, peer_valid=True, control_preserved=True)
        self.assertEqual("android-test-failed", diagnostic["reason"])
        self.assertEqual("read-only-exhausted", diagnostic["last_ui_stage"])
        unsafe = safe_failure_diagnostic({
            "runner": {"failed_tests": 1},
            "app_stage_evidence": ["https://private/?passkey=SECRET"],
        }, peer_valid=True, control_preserved=True)
        self.assertEqual("none", unsafe["last_ui_stage"])
        self.assertNotIn("SECRET", str(unsafe))

    def test_magnet_without_requested_extra_journeys_is_unchanged(self):
        self.assertTrue(required_journeys_verified(self.root, dict(self.env, CHUNKS_HTTPS_ADD="0", CHUNKS_REAL_MEDIA="0")))


if __name__ == '__main__':
    unittest.main()
