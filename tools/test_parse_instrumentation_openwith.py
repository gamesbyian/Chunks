"""Privacy and evidence gate for Open With instrumentation diagnostics."""
import unittest

from tools.parse_instrumentation_stream import safe_openwith_failure


class OpenWithFailureFingerprintTest(unittest.TestCase):
    CLASS = "io.github.gamesbyian.chunks.InstalledOpenWithHandoffInstrumentedTest"
    TEST = "installedSecondOpenWithReturnsToExistingChunksAndOffersAnExplicitSourceChoice"
    FINGERPRINT = (
        "java.lang.AssertionError: openwith second-source timeout: "
        "intent=true queued=false reviewed=true choice=false finishing=false"
    )

    def status(self, klass=None, test=None, stack=None, code="-2"):
        return "\n".join([
            "INSTRUMENTATION_STATUS: class=" + (klass or self.CLASS),
            "INSTRUMENTATION_STATUS: test=" + (test or self.TEST),
            "INSTRUMENTATION_STATUS: stack=" + (stack or self.FINGERPRINT),
            "INSTRUMENTATION_STATUS_CODE: " + code,
        ])

    def test_known_fixture_reports_only_fixed_boolean_state(self):
        self.assertEqual(
            "intent=true queued=false reviewed=true choice=false finishing=false",
            safe_openwith_failure(self.status()),
        )

    def test_other_test_identity_cannot_emit_private_details(self):
        self.assertIsNone(safe_openwith_failure(self.status(test="otherTest")))
        self.assertIsNone(safe_openwith_failure(self.status(klass="other.Test")))

    def test_stack_suffix_and_private_uri_are_refused(self):
        self.assertIsNone(safe_openwith_failure(self.status(
            stack=self.FINGERPRINT + " uri=content://private.test/passkey")))
        self.assertIsNone(safe_openwith_failure(self.status(
            stack="java.lang.AssertionError: token=private")))

    def test_pre_timeout_phase_is_allowlisted_but_never_forwards_causes(self):
        self.assertEqual(
            "stage=initial-review kind=state",
            safe_openwith_failure(self.status(stack=(
                "java.lang.AssertionError: openwith staged failure: "
                "stage=initial-review kind=state"))),
        )
        self.assertIsNone(safe_openwith_failure(self.status(stack=(
            "java.lang.AssertionError: openwith staged failure: "
            "stage=private-uri kind=state"))))
        self.assertIsNone(safe_openwith_failure(self.status(stack=(
            "java.lang.AssertionError: openwith staged failure: "
            "stage=launch kind=other uri=content://secrets"))))

    def test_only_failed_instrumentation_is_reported(self):
        self.assertIsNone(safe_openwith_failure(self.status(code="0")))
        self.assertIsNone(safe_openwith_failure(self.status(code="-1")))


if __name__ == "__main__":
    unittest.main()
