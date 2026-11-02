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
        data = self.success_log()
        names = ("installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes",
                 "installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes")
        for label, name in zip(SOURCES, names):
            marker = "INSTRUMENTATION_STATUS: chunks_real_add_hash_" + label + "="
            data = data.replace(marker, "INSTRUMENTATION_STATUS: test=" + name + "\n" + marker)
            extra = "".join("INSTRUMENTATION_STATUS: chunks_real_add_stage=" + label + "-" + s + "\n"
                            for s in ("ui-progress", "stopped", "resumed-traffic", "ui-controls"))
            extra += "INSTRUMENTATION_STATUS: chunks_real_add_traffic_" + label + "_initial=300000,262144,1000000,1,10000\n"
            extra += "INSTRUMENTATION_STATUS: chunks_real_add_traffic_" + label + "_resumed=600000,524288,1000000,1,10000\n"
            data = data.replace("INSTRUMENTATION_STATUS: chunks_real_add_stage=" + label + "-passed", extra +
                                "INSTRUMENTATION_STATUS: chunks_real_add_stage=" + label + "-passed")
        return data.replace("INSTRUMENTATION_CODE: -1",
            "INSTRUMENTATION_STATUS: chunks_real_add_hash_negative=" + "c" * 40 + "\n" +
            "INSTRUMENTATION_STATUS: chunks_real_add_negative=peerless-ui-and-cleanup-passed\nINSTRUMENTATION_CODE: -1")

    def test_controlled_gate_requires_actual_ui_restart_growth_and_peerless_truth(self):
        env = dict(self.env, CHUNKS_CONTROLLED_PEER="1", CHUNKS_DISPOSABLE="1")
        data = self.controlled_log()
        self.assertEqual("passed", parse(data, 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "chunks_real_add_negative=peerless-ui-and-cleanup-passed", "unknown=ignored"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "_resumed=600000,524288", "_resumed=300000,262144"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data.replace(
            "installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes",
            "installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes"), 0, env)["result"])
        self.assertNotEqual("passed", parse(data + "\nINSTRUMENTATION_CODE: -1", 0, env)["result"])

    def test_actual_pass_is_preserved_when_other_case_fails(self):
        data = self.controlled_log().replace(
            "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS: chunks_real_add_hash_negative=",
            "INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_STATUS: chunks_real_add_hash_negative=")
        result = parse(data, 0, dict(self.env, CHUNKS_CONTROLLED_PEER="1"))
        self.assertNotEqual("passed", result["result"])
        self.assertEqual(["passed", "failed"], [x["outcome"] for x in result["actual_case_results"]])

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
