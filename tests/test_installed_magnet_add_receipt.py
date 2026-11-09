"""Offline security and completeness controls for installed Seedhost Add receipts."""
import unittest
from tools.parse_installed_magnet_add_receipt import parse, SOURCES, PHASES


class InstalledMagnetReceiptTests(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123"}

    def success_log(self):
        lines = []
        for label in SOURCES:
            lines.append("INSTRUMENTATION_STATUS: chunks_real_add_hash_" + label +
                         "=" + ("a" * 40 if label == "magnet" else "b" * 40))
            lines.append("INSTRUMENTATION_STATUS_CODE: 1")
            for stage in ("preflight", "review", "ui-verified", "registered",
                          "metadata", "peer-progress", "bytes-verified", "restarted", "passed"):
                lines.append("INSTRUMENTATION_STATUS: chunks_real_add_stage=" +
                             label + "-" + stage)
                lines.append("INSTRUMENTATION_STATUS_CODE: 2")
            lines.append("INSTRUMENTATION_STATUS_CODE: 0")
        lines.append("INSTRUMENTATION_CODE: -1")
        return "\n".join(lines)

    def test_two_independent_genuine_source_receipts_can_pass(self):
        result = parse(self.success_log(), 0, self.env)
        self.assertEqual("passed", result["result"])
        self.assertEqual(2, result["runner"]["started_tests"])
        self.assertEqual(2, result["runner"]["passed_tests"])
        for label in SOURCES:
            self.assertTrue(result["sources"][label]["passed"])
            self.assertEqual("verified-by-test", result["sources"][label]["cleanup"])
        self.assertFalse(result["physical_device_proven"])

    def controlled_log(self):
        lines = ["INSTRUMENTATION_STATUS: class=io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest",
                 "INSTRUMENTATION_STATUS: test=installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace",
                 "INSTRUMENTATION_STATUS_CODE: 1",
                 "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet0=" + "f" * 40,
                 "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet3=" + "d" * 40,
                 "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet4=" + "e" * 40]
        for stage in ("preflight", "review", "ui-verified", "registered", "metadata", "fixture-precompleted", "removed"):
            lines.append("INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet0-" + stage)
        lines.append("INSTRUMENTATION_STATUS: chunks_real_add_traffic_magnet0_initial=0,65536,65536,0,0")
        for label in SOURCES:
            lines.append("INSTRUMENTATION_STATUS: chunks_real_add_hash_" + label + "=" +
                         ("a" * 40 if label == "magnet" else "b" * 40))
            for stage in ("preflight", "review", "ui-verified", "registered", "metadata", "peer-progress",
                          "bytes-verified", "ui-progress", "stopped", "restarted", "resumed-traffic", "ui-controls", "removed", "passed"):
                lines.append("INSTRUMENTATION_STATUS: chunks_real_add_stage=" + label + "-" + stage)
            lines.append("INSTRUMENTATION_STATUS: chunks_real_add_traffic_" + label + "_initial=300000,262144,1000000,1,10000")
            lines.append("INSTRUMENTATION_STATUS: chunks_real_add_traffic_" + label + "_resumed=600000,524288,1000000,1,10000")
        lines.append("INSTRUMENTATION_STATUS: chunks_real_add_app_stage=combined-details")
        lines += ["INSTRUMENTATION_STATUS: chunks_real_add_negative_stage=" + s for s in
                  ("preflight", "registered", "add-ui-truthful", "transfers-ui-truthful", "removed")]
        lines += ["INSTRUMENTATION_STATUS: chunks_real_add_hash_negative=" + "c" * 40,
                  "INSTRUMENTATION_STATUS: chunks_real_add_negative=peerless-ui-and-cleanup-passed",
                  "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_CODE: -1"]
        return "\n".join(lines)

    def test_controlled_gate_requires_actual_ui_restart_growth_and_peerless_truth(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1")
        data = self.controlled_log()
        self.assertEqual("passed", parse(data, 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "chunks_real_add_negative=peerless-ui-and-cleanup-passed", "unknown=ignored"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "_resumed=600000,524288", "_resumed=300000,262144"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace",
            "installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data + "\nINSTRUMENTATION_CODE: -1", 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace("chunks_real_add_hash_negative", "unknown"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace("SeedhostAndroidAddUiInstrumentedTest", "UnexpectedClass"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace("chunks_real_add_stage=magnet0-removed", "ignored=missing"), 0, env)["result"])

    def test_controlled_real_peer_requires_exactly_one_combined_details_readback_witness(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1")
        raw = self.controlled_log()
        marker = "INSTRUMENTATION_STATUS: chunks_real_add_app_stage=combined-details"
        self.assertEqual("passed", parse(raw, 0, env)["result"])
        self.assertEqual(1, parse(raw, 0, env)["app_stage_evidence"].count("combined-details"))
        self.assertNotEqual("passed", parse(raw.replace(marker, "ignored=missing"), 0, env)["result"])
        self.assertNotEqual("passed", parse(raw + "\n" + marker, 0, env)["result"])
        self.assertNotEqual("passed", parse(raw.replace(marker,
            "INSTRUMENTATION_STATUS: chunks_real_add_app_stage=combined-details-fake"), 0, env)["result"])
        # Non-controlled upstream tests have no MainActivity Combined hop;
        # their separate real piece/restart requirements remain unchanged.
        self.assertEqual("passed", parse(self.success_log(), 0, self.env)["result"])

    def test_https_gate_requires_actual_fetch_review_witnesses_for_each_passing_source(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1", CHUNKS_HTTPS_ADD="1")
        raw = self.controlled_log()
        self.assertNotEqual("passed", parse(raw, 0, env)["result"])
        witnessed = raw + "\n" + "\n".join(
            "INSTRUMENTATION_STATUS: chunks_https_review=" + label for label in SOURCES)
        result = parse(witnessed, 0, env)
        self.assertEqual("passed", result["result"])
        self.assertEqual("https-fetch-review", result["source_intake"])
        for label in SOURCES:
            self.assertNotEqual("passed", parse(witnessed.replace(
                "chunks_https_review=" + label, "ignored=missing"), 0, env)["result"])
        self.assertNotEqual("passed", parse(witnessed +
            "\nINSTRUMENTATION_STATUS: chunks_https_review=magnet", 0, env)["result"])

    def test_precompleted_candidate_rotates_only_after_exact_cleanup(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1")
        data = self.controlled_log()
        lines = data.splitlines()
        start = next(i for i, s in enumerate(lines) if s.endswith("stage=magnet-preflight"))
        end = next(i for i, s in enumerate(lines) if "traffic_magnet_resumed=" in s)
        original = lines[start:end + 1]
        rotated = [s.replace("stage=magnet-", "stage=magnet3-")
                     .replace("traffic_magnet_", "traffic_magnet3_") for s in original]
        precompleted = [
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-preflight",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-review",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-ui-verified",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-registered",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-metadata",
            "INSTRUMENTATION_STATUS: chunks_real_add_traffic_magnet_initial=0,1000000,1000000,0,0",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-fixture-precompleted",
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-removed",
        ]
        raw = "\n".join(lines[:start] + precompleted + rotated + lines[end + 1:])
        result = parse(raw, 0, env)
        self.assertEqual("passed", result["result"])
        self.assertFalse(result["sources"]["magnet"]["passed"])
        self.assertTrue(result["sources"]["magnet3"]["passed"])
        for bad in (raw.replace("chunks_real_add_stage=magnet-removed", "ignored=missing"),
                    raw.replace("_initial=0,1000000,1000000", "_initial=1,1000000,1000000"),
                    raw.replace("chunks_real_add_stage=magnet-fixture-precompleted", "ignored=missing"),
                    raw + "\nINSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-uncertain",
                    raw + "\nINSTRUMENTATION_STATUS: chunks_real_add_traffic_magnet_initial=0,1000000,1000000,0,0",
                    raw.replace("chunks_real_add_stage=magnet3-passed", "ignored=exhausted")):
            self.assertNotEqual("passed", parse(bad, 0, env)["result"])

    def test_existing_session_rotates_without_claiming_cleanup(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1")
        lines = self.controlled_log().splitlines()
        start = next(i for i, s in enumerate(lines) if s.endswith("stage=magnet2-preflight"))
        end = next(i for i, s in enumerate(lines) if "traffic_magnet2_resumed=" in s)
        alternate = [s.replace("stage=magnet2-", "stage=magnet4-")
                       .replace("traffic_magnet2_", "traffic_magnet4_") for s in lines[start:end + 1]]
        raw = "\n".join(lines[:start] + ["INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet2-already-present"] + alternate + lines[end + 1:])
        self.assertEqual("passed", parse(raw, 0, env)["result"])
        self.assertEqual("not-proven", parse(raw, 0, env)["sources"]["magnet2"]["cleanup"])
        self.assertNotEqual("passed", parse(raw.replace("chunks_real_add_stage=magnet4-passed", "ignored=exhausted"), 0, env)["result"])

    def test_actual_pass_is_preserved_when_other_case_fails(self):
        data = self.success_log()
        for label, name in zip(SOURCES, ("installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes",
                                        "installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes")):
            marker = "INSTRUMENTATION_STATUS: chunks_real_add_hash_" + label + "="
            data = data.replace(marker, "INSTRUMENTATION_STATUS: test=" + name + "\n" + marker)
        before, after = data.rsplit("INSTRUMENTATION_STATUS_CODE: 0", 1)
        result = parse(before + "INSTRUMENTATION_STATUS_CODE: -2" + after, 0, self.env)
        self.assertNotEqual("passed", result["result"])
        self.assertEqual(["passed", "failed"], [x["outcome"] for x in result["actual_case_results"]])

    def test_public_completed_transfer_can_restart_without_fabricated_growth(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_PUBLIC_DISCOVERY="1")
        data = self.controlled_log().replace("-resumed-traffic", "-completed-at-stop").replace(
            "_resumed=600000,524288,1000000", "_resumed=1000000,1000000,1000000")
        result = parse(data, 0, env)
        self.assertEqual("passed", result["result"])
        self.assertTrue(result["public_peer_discovery"])
        self.assertFalse(result["controlled_real_peer"])
        self.assertNotIn("magnet0", result["sources"])
        self.assertNotEqual("passed", parse(data, 0, dict(env, CHUNKS_PUBLIC_DISCOVERY="0"))["result"])
        self.assertNotEqual("passed", parse(data.replace("_resumed=1000000,1000000", "_resumed=1000000,900000"), 0, env)["result"])

    def test_immediately_resolved_metadata_is_reported_without_fabricating_peer_proof(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="0")
        data = self.controlled_log().replace(
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-registered",
            "INSTRUMENTATION_STATUS: chunks_real_add_app_stage=metadata-already-resolved\n"
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-metadata-already-resolved\n"
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-registered"
        )
        result = parse(data, 0, env)
        self.assertEqual("passed", result["result"])
        self.assertEqual(1, result["sources"]["magnet"]["phase_counts"]["metadata-already-resolved"])
        self.assertEqual(0, result["sources"]["magnet2"]["phase_counts"]["metadata-already-resolved"])
        self.assertEqual(["metadata-already-resolved", "combined-details"], result["app_stage_evidence"])
        self.assertTrue(result["sources"]["magnet"]["piece_verified"])
        self.assertNotEqual("passed", parse(data.replace("chunks_real_add_stage=magnet-peer-progress",
                                                       "arbitrary_early_metadata=1"), 0, env)["result"])

    def test_exhausted_read_only_rpc_marker_cannot_grant_acceptance(self):
        raw = ("INSTRUMENTATION_STATUS: chunks_real_add_app_stage=place-ready\n"
               "INSTRUMENTATION_STATUS: chunks_real_add_app_stage=read-only-exhausted\n"
               "INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1")
        receipt = parse(raw, 1, self.env)
        self.assertEqual(["place-ready", "read-only-exhausted"],
                         receipt["app_stage_evidence"])
        self.assertEqual("failed-or-inconclusive", receipt["result"])
        hostile = raw + ("\nINSTRUMENTATION_STATUS: chunks_real_add_app_stage="
                         "https://private.example/?passkey=SECRET")
        sanitized = parse(hostile, 1, self.env)
        self.assertEqual(receipt["app_stage_evidence"], sanitized["app_stage_evidence"])
        self.assertNotIn("SECRET", str(sanitized))

    def test_connect_click_attempt_does_not_claim_authenticated_submit(self):
        raw = ("INSTRUMENTATION_STATUS: chunks_real_add_app_stage=runner-ready\n"
               "INSTRUMENTATION_STATUS: chunks_real_add_app_stage=connect-click-attempt\n"
               "INSTRUMENTATION_STATUS_CODE: -2\n"
               "INSTRUMENTATION_CODE: -1")
        result = parse(raw, 1, self.env)
        self.assertEqual(["runner-ready", "connect-click-attempt"], result["app_stage_evidence"])
        self.assertNotIn("connect-submitted", result["app_stage_evidence"])
        self.assertEqual("failed-or-inconclusive", result["result"])

    def test_payload_timeout_preserves_only_safe_exact_hash_stall_state(self):
        raw = self.controlled_log().replace(
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-peer-progress",
            "INSTRUMENTATION_STATUS: chunks_real_add_diagnostic_magnet="
            "state=1,active=0,open=0,phase=0,hashfailed=0,hashing=0,meta=0,"
            "message=storage\n"
            "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-peer-progress"
        )
        result = parse(raw, 1, self.env)
        self.assertEqual("failed-or-inconclusive", result["result"])
        self.assertEqual(
            "state=1,active=0,open=0,phase=0,hashfailed=0,hashing=0,meta=0,message=storage",
            result["sources"]["magnet"]["read_only_stall_diagnostic"])
        self.assertIsNone(result["sources"]["magnet2"]["read_only_stall_diagnostic"])
        injected = raw.replace("message=storage", "message=https://private.example?passkey=SECRET")
        self.assertIsNone(parse(injected, 1, self.env)["sources"]["magnet"]["read_only_stall_diagnostic"])
        self.assertNotIn("SECRET", str(parse(injected, 1, self.env)))

    def test_missing_exact_hash_blocks_otherwise_green_completion(self):
        data = self.success_log().replace(
            "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet2=" + "b" * 40 + "\n", "")
        result = parse(data, 0, self.env)
        self.assertNotEqual("passed", result["result"])
        self.assertEqual("missing", result["sources"]["magnet2"]["hash_receipt"])

    def test_conflicting_duplicate_public_hash_is_never_qualified(self):
        data = self.success_log().replace(
            "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet2=" + "b" * 40,
            "INSTRUMENTATION_STATUS: chunks_real_add_hash_magnet2=" + "a" * 40
        )
        result = parse(data, 0, self.env)
        self.assertNotEqual("passed", result["result"])

    def test_missing_second_magnet_never_passes(self):
        result = parse("INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-passed\n"
                       "INSTRUMENTATION_CODE: -1", 0, self.env)
        self.assertNotEqual("passed", result["result"])
        self.assertFalse(result["sources"]["magnet2"]["passed"])

    def test_instrumentation_failure_without_last_stage_is_explicit(self):
        result = parse("INSTRUMENTATION_STATUS_CODE: -2\n", 1, self.env)
        self.assertEqual("failed-or-inconclusive", result["result"])
        self.assertEqual(1, result["runner"]["failed_tests"])
        self.assertEqual(0, result["runner"]["started_tests"])

    def test_raw_credentials_and_exception_details_never_enter_receipt(self):
        raw = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: secret=DO_NOT_PRINT\n"
               "INSTRUMENTATION_STATUS: arbitrary_secret=DO_NOT_PRINT\n"
               "INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-uncertain\n")
        result = parse(raw, 1, self.env)
        self.assertNotIn("DO_NOT_PRINT", str(result))
        self.assertIn("AssertionError", result["runner"]["exception_classes"])
        self.assertEqual("uncertain", result["sources"]["magnet"]["cleanup"])

    def test_no_fabricated_stages_or_success_from_unknown_labels(self):
        result = parse("INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-FAKE\n"
                       "INSTRUMENTATION_STATUS_CODE: 0\n", 0, self.env)
        self.assertNotEqual("passed", result["result"])
        self.assertTrue(all(x == 0 for x in result["sources"]["magnet"]["phase_counts"].values()))


if __name__ == "__main__":
    unittest.main()
