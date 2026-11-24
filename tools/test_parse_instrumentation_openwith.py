"""Privacy and evidence gate for Open With instrumentation diagnostics."""
import unittest

from tools.parse_instrumentation_stream import (
    safe_ime_focus_failure, safe_media_first_positive_failure,
    safe_media_review_failure, safe_openwith_failure,
)


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
        self.assertEqual(
            "stage=cleanup kind=assertion",
            safe_openwith_failure(self.status(stack=(
                "java.lang.AssertionError: openwith staged failure: "
                "stage=cleanup kind=assertion"))),
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


class ControlledMediaFailureFingerprintTest(unittest.TestCase):
    CLASS = "io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest"
    TEST = "phaseA_submitTwoSourcesAndRefreshInventory"
    DIAGNOSTIC = (
        "java.lang.AssertionError: Controlled first metadata request absent: "
        "sourceFetched=true online=true metered=false powerSaver=false "
        "onlineConsent=true automatic=true showArtwork=true downloadArtwork=true "
        "tvmazeEnabled=true localTransferPresent=true kind=TV_EPISODE"
    )

    def status(self, klass=None, test=None, failure=None, code="-2"):
        return "\n".join([
            "INSTRUMENTATION_STATUS: class=" + (klass or self.CLASS),
            "INSTRUMENTATION_STATUS: test=" + (test or self.TEST),
            "INSTRUMENTATION_STATUS: stack=" + (failure or self.DIAGNOSTIC),
            "INSTRUMENTATION_STATUS_CODE: " + code,
        ])

    def test_exact_safe_positive_witness_is_decoded(self):
        expected = self.DIAGNOSTIC.split("absent: ", 1)[1]
        self.assertEqual(expected, safe_media_first_positive_failure(self.status()))

    def test_other_test_and_success_cannot_expose_diagnostic(self):
        self.assertIsNone(safe_media_first_positive_failure(self.status(test="other")))
        self.assertIsNone(safe_media_first_positive_failure(self.status(klass="secret.Test")))
        self.assertIsNone(safe_media_first_positive_failure(self.status(code="0")))

    def test_private_suffix_uri_arbitrary_kind_or_newline_is_rejected(self):
        for extra in (" uri=content://secret/item", "\nhttps://secret.example", 
                      " hash=private", " kind=./secret"):
            self.assertIsNone(safe_media_first_positive_failure(self.status(
                failure=self.DIAGNOSTIC + extra)))
        self.assertIsNone(safe_media_first_positive_failure(self.status(
            failure=self.DIAGNOSTIC.replace("kind=TV_EPISODE", "kind=secret/name"))))
        self.assertIsNone(safe_media_first_positive_failure(self.status(
            failure="java.lang.AssertionError: token=passkey")))




class ControlledMediaReviewFailureFingerprintTest(unittest.TestCase):
    CLASS = "io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest"
    PHASE_A = "phaseA_submitTwoSourcesAndRefreshInventory"
    PHASE_B = "phaseB_restoredExclusionAndFreshPositiveControl"
    SIGNED_SHARE = "sharedSignedLinkWaitsForReplacementAndExplicitFetch"
    GOOD = (
        "java.lang.AssertionError: Controlled HTTPS review was not retained: "
        "fixtureSourceRequests=0 ownerHasAnyReviewedTorrent=false "
        "addFooterVisible=true sourceFieldVisible=true intakeProblem=NO_HOST"
    )

    def status(self, klass=None, test=None, failure=None, code="-2"):
        return "\n".join([
            "INSTRUMENTATION_STATUS: class=" + (klass or self.CLASS),
            "INSTRUMENTATION_STATUS: test=" + (test or self.PHASE_A),
            "INSTRUMENTATION_STATUS: stack=" + (failure or self.GOOD),
            "INSTRUMENTATION_STATUS_CODE: " + code,
        ])

    def test_exact_failure_whitelist_produces_only_bounded_nonsecret_values(self):
        result = ("fixtureSourceRequests=0 ownerHasAnyReviewedTorrent=false "
                  "addFooterVisible=true sourceFieldVisible=true intakeProblem=NO_HOST")
        self.assertEqual(result, safe_media_review_failure(self.status()))
        self.assertEqual(result, safe_media_review_failure(self.status(test=self.PHASE_B)))
        self.assertEqual(result, safe_media_review_failure(self.status(test=self.SIGNED_SHARE)))
        self.assertEqual(
            result.replace("Requests=0", "Requests=9999"),
            safe_media_review_failure(self.status(
                failure=self.GOOD.replace("Requests=0", "Requests=9999"))),
        )

    def test_only_conventional_java_stack_frames_may_follow_signature(self):
        expected = ("fixtureSourceRequests=0 ownerHasAnyReviewedTorrent=false "
                    "addFooterVisible=true sourceFieldVisible=true intakeProblem=NO_HOST")
        self.assertEqual(expected, safe_media_review_failure(self.status(
            failure=self.GOOD +
            "\n    at io.github.gamesbyian.chunks.SampleTest.review(SampleTest.kt:119)")))
        # One non-frame line after the assertion must never disappear when
        # the instrumentation parser extracts just the first stack line.
        self.assertIsNone(safe_media_review_failure(self.status(
            failure=self.GOOD + "\n    at https://tracker.invalid/passkey")))
        self.assertIsNone(safe_media_review_failure(self.status(
            failure=self.GOOD + "\nCaused by: token=private")))

    def test_known_compose_timeout_cause_and_conventional_elided_frames(self):
        expected = ("fixtureSourceRequests=0 ownerHasAnyReviewedTorrent=false "
                    "addFooterVisible=true sourceFieldVisible=true intakeProblem=NO_HOST")
        stack = (self.GOOD +
                 "\n    at io.github.gamesbyian.chunks.SampleTest.review(SampleTest.kt:119)"
                 "\nCaused by: androidx.compose.ui.test.ComposeTimeoutException: "
                 "Condition still not satisfied after 30000 ms"
                 "\n    at androidx.compose.ui.test.ComposeTestRule.waitUntil(ComposeTestRule.kt:90)"
                 "\n    ... 23 more")
        self.assertEqual(expected, safe_media_review_failure(self.status(failure=stack)))

    def test_cause_with_source_data_and_unbounded_elision_stay_silent(self):
        for suffix in (
            "\nCaused by: androidx.compose.ui.test.ComposeTimeoutException: token=secret",
            "\nCaused by: java.lang.AssertionError: https://private.example",
            "\n    ... 10000 more",
            "\n    ... -1 more",
        ):
            self.assertIsNone(safe_media_review_failure(self.status(failure=self.GOOD + suffix)))

    def test_other_class_method_and_success_or_error_are_silent(self):
        self.assertIsNone(safe_media_review_failure(self.status(klass="secret.Class")))
        self.assertIsNone(safe_media_review_failure(self.status(test="unrelated")))
        self.assertIsNone(safe_media_review_failure(self.status(code="0")))
        self.assertIsNone(safe_media_review_failure(self.status(code="-1")))

    def test_secret_suffix_and_unbounded_or_negative_counts_are_rejected(self):
        for invalid in (
            self.GOOD + " token=private",
            self.GOOD + " uri=content://private.test/secret",
            self.GOOD + "\nhttp://tracker.invalid/private",
            self.GOOD.replace("Requests=0", "Requests=10000"),
            self.GOOD.replace("Requests=0", "Requests=-1"),
            self.GOOD.replace("addFooterVisible=true", "addFooterVisible=secret"),
            self.GOOD.replace("intakeProblem=NO_HOST", "intakeProblem=private-url"),
            "java.lang.AssertionError: HTTPS error token=private",
        ):
            with self.subTest(invalid=invalid[:50]):
                self.assertIsNone(safe_media_review_failure(self.status(failure=invalid)))


class ImeFocusFailureFingerprintTest(unittest.TestCase):
    CLASS = "io.github.gamesbyian.chunks.FooterImeInstalledInstrumentedTest"
    TEST = "footerRemainsAvailableThroughSoftKeyboardAndAfterSearchKey"
    MESSAGE = (
        "java.lang.AssertionError: Installed keyboard journey did not acquire Chunks window focus; "
        "foregroundIsChunks=unavailable; externalLauncherAnrVisible=true; "
        "externalLauncherWaitClicked=false. No input or transfer write was attempted."
    )
    CAUSE = (
        "\nCaused by: androidx.compose.ui.test.ComposeTimeoutException: "
        "Condition still not satisfied after 15000 ms"
        "\n    at androidx.compose.ui.test.ComposeTestRule.waitUntil(ComposeTestRule.kt:90)"
        "\n    ... 23 more"
    )

    def status(self, message, klass=CLASS, test=TEST, code="-2"):
        return "\n".join([
            "INSTRUMENTATION_STATUS: class=" + klass,
            "INSTRUMENTATION_STATUS: test=" + test,
            "INSTRUMENTATION_STATUS: stack=" + message,
            "INSTRUMENTATION_STATUS_CODE: " + code,
        ])

    def test_exact_focus_timeout_emits_three_bounded_values(self):
        self.assertEqual(
            "foregroundIsChunks=unavailable externalLauncherAnrVisible=true externalLauncherWaitClicked=false",
            safe_ime_focus_failure(self.status(self.MESSAGE + self.CAUSE)),
        )

    def test_other_tests_and_untrusted_stack_continuations_stay_redacted(self):
        stack = self.MESSAGE + self.CAUSE
        for raw in (
            self.status(stack, klass="foreign.Class"),
            self.status(stack, test="catalogSearchSurvivesActivityRecreation"),
            self.status(stack, code="0"),
            self.status(stack.replace("15000 ms", "30000 ms")),
            self.status(self.MESSAGE + " password=value"),
            self.status(stack + "\nhttps://private.example/passkey"),
        ):
            self.assertIsNone(safe_ime_focus_failure(raw))


if __name__ == "__main__":
    unittest.main()
