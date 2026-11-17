"""Privacy gate for fixed Open With diagnostics in Android CI."""
import unittest

from tools.parse_instrumentation_stream import safe_openwith_failure


class SafeOpenWithFailureTest(unittest.TestCase):
    CLASS = "io.github.gamesbyian.chunks.InstalledOpenWithHandoffInstrumentedTest"
    TEST = "installedSecondOpenWithReentersSameTaskAndQueuesSourceWithoutSubmitting"

    def record(self, klass=None, name=None, message=None):
        return "\n".join([
            "INSTRUMENTATION_STATUS: class=" + (klass or self.CLASS),
            "INSTRUMENTATION_STATUS: test=" + (name or self.TEST),
            "INSTRUMENTATION_STATUS: stack=" + (message or (
                "java.lang.AssertionError: openwith second-source timeout: "
                "intent=true queued=false reviewed=true choice=false finishing=false")),
            "INSTRUMENTATION_STATUS_CODE: -2",
        ])

    def test_whitelisted_fixture_reports_only_boolean_state(self):
        self.assertEqual(
            "intent=true queued=false reviewed=true choice=false finishing=false",
            safe_openwith_failure(self.record()),
        )

    def test_other_test_identity_never_leaks_same_message(self):
        self.assertIsNone(safe_openwith_failure(self.record(name="unrelatedTest")))

    def test_arbitrary_exception_text_and_private_uris_cannot_pass(self):
        self.assertIsNone(safe_openwith_failure(self.record(message=(
            "java.lang.AssertionError: openwith second-source timeout: "
            "intent=true queued=false reviewed=true choice=false finishing=false "
            "uri=content://private.example/passkey"))))

    def test_only_failed_instrumentation_may_report(self):
        self.assertIsNone(safe_openwith_failure(
            self.record().replace("INSTRUMENTATION_STATUS_CODE: -2",
                                  "INSTRUMENTATION_STATUS_CODE: 0")))


if __name__ == "__main__":
    unittest.main()
