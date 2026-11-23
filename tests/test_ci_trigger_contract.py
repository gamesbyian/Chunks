"""Offline guard against silently skipping Android preflight regressions.

The Android build exercises the physical APK preflight, but standalone edits
of its offline mocks now trigger the cheap documentation validation workflow.
"""
from pathlib import Path
import re
import unittest
from fnmatch import fnmatchcase

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/android-check.yml"


def pull_request_paths(source: str) -> set[str]:
    # Match only the path allowlist under on.pull_request, not unrelated
    # lists of checkout steps, artifacts, or workflow_dispatch entries.
    match = re.search(
        r"(?m)^  pull_request:\s*\n(?:    branches:\s*\[main\]\s*\n)?"
        r"(?:    types:\s*\[[^\]]+\]\s*\n)?    paths:\s*\n"
        r"((?:      (?:- [^\n]+|#[^\n]*)\n)+)", source
    )
    if not match:
        raise AssertionError("Android workflow has no pull_request.paths allowlist")
    return {
        line.split("-", 1)[1].strip().strip("'\"")
        for line in match.group(1).splitlines() if line.strip().startswith("- ")
    }



def dispatches_for_changed_paths(source: str, changed: tuple[str, ...]) -> bool:
    """Model the simple ordered include/exclude globs this CI lane actually uses.

    This is a bounded offline selector contract, not a full GitHub glob parser.
    Assertions also pin the exact GitHub-compatible patterns and their order.
    """
    match = re.search(
        r"(?m)^  pull_request:\s*\n(?:    branches:\s*\[main\]\s*\n)?"
        r"(?:    types:\s*\[[^\]]+\]\s*\n)?    paths:\s*\n"
        r"((?:      (?:- [^\n]+|#[^\n]*)\n)+)", source
    )
    if not match:
        return False
    patterns = [line.split("- ", 1)[1].strip().strip(chr(34) + chr(39))
                for line in match.group(1).splitlines()
                if line.strip().startswith("- ")]
    for changed_path in changed:
        selected = False
        for pattern in patterns:
            negative = pattern.startswith("!")
            if fnmatchcase(changed_path, pattern[1:] if negative else pattern):
                selected = not negative
        if selected:
            return True
    return False


class AndroidCiTriggerContract(unittest.TestCase):
    def test_pr_base_filter_does_not_hide_existing_path_selection(self):
        sample = (
            "on:\n"
            "  pull_request:\n"
            "    branches: [main]\n"
            "    paths:\n"
            "      - 'app/**'\n"
            "      - '!app/src/androidTest/**'\n"
            "  workflow_dispatch:\n"
        )
        self.assertEqual({"app/**", "!app/src/androidTest/**"},
                         pull_request_paths(sample))
        self.assertTrue(dispatches_for_changed_paths(
            sample, ("app/src/main/java/Example.kt",)))
        self.assertFalse(dispatches_for_changed_paths(
            sample, ("app/src/androidTest/java/Example.kt",)))
        with_types = sample.replace(
            "    paths:\n",
            "    types: [opened, reopened, synchronize, ready_for_review]\n    paths:\n",
        )
        self.assertEqual(pull_request_paths(sample), pull_request_paths(with_types))
        self.assertTrue(dispatches_for_changed_paths(
            with_types, ("app/src/main/java/Example.kt",)))

    def test_preflight_tool_edits_trigger_only_cheap_docs_validation(self):
        android = pull_request_paths(WORKFLOW.read_text(encoding="utf-8"))
        docs_source = (ROOT / ".github/workflows/documentation-validation.yml"
                       ).read_text(encoding="utf-8")
        docs = pull_request_paths(docs_source)
        for path in (
            "tools/physical_android_preflight.sh",
            "tools/test_physical_android_preflight.sh",
            "tests/test_ci_trigger_contract.py",
        ):
            with self.subTest(path=path):
                self.assertIn(path, docs)
                self.assertNotIn(path, android)
        self.assertIn("bash tools/test_physical_android_preflight.sh", docs_source)
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_ci_trigger_contract.py",
            docs_source,
        )

    def test_androidtest_only_does_not_duplicate_foundation_and_api35(self):
        # Github Actions applies ordered negative patterns per changed file:
        # app/** first, then the instrumentation-only exclusion.
        source = WORKFLOW.read_text(encoding="utf-8")
        broad = source.index('      - "app/**"')
        exempt = source.index('      - "!app/src/androidTest/**"')
        self.assertLess(broad, exempt)
        self.assertIn("!app/src/androidTest/**", pull_request_paths(source))
        native = (ROOT / ".github/workflows/native-add-instrumentation.yml"
                  ).read_text(encoding="utf-8")
        self.assertIn("app/src/androidTest/**", pull_request_paths(native))
        self.assertIn("app/src/main/**", pull_request_paths(native))
        # An app change alongside a test does not match the exclusion.
        self.assertIn("app/**", pull_request_paths(source))

    def test_dedicated_real_fixture_test_edits_do_not_duplicate_native_add(self):
        native = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        own_journeys = (
            ("CombinedTwoRealServersInstrumentedTest.kt",
             ".github/workflows/installed-combined-two-servers.yml"),
            ("HttpsLocalAddJourneyInstrumentedTest.kt",
             ".github/workflows/installed-rtorrent-magnets.yml"),
            ("ProtocolClientInstalledJourneyTest.kt",
             ".github/workflows/installed-protocol-client.yml"),
            ("MainActivityRealMagnetJourney.kt",
             ".github/workflows/installed-rtorrent-magnets.yml"),
        )
        for filename, owning_workflow in own_journeys:
            changed_test = "app/src/androidTest/java/io/github/gamesbyian/chunks/" + filename
            owner = (ROOT / owning_workflow).read_text()
            with self.subTest(filename=filename):
                self.assertTrue(dispatches_for_changed_paths(owner, (changed_test,)))
                self.assertFalse(dispatches_for_changed_paths(native, (changed_test,)))
                # A test-only exclusion never disarms the native Add gate for
                # any concurrent production or normally selected test change.
                self.assertTrue(dispatches_for_changed_paths(
                    native, (changed_test, "app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt")))
                self.assertTrue(dispatches_for_changed_paths(
                    native, (changed_test, "app/src/androidTest/java/AnotherInstrumentedTest.kt")))
        docs = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        self.assertFalse(dispatches_for_changed_paths(
            native, (".github/workflows/native-add-instrumentation.yml",)))
        self.assertTrue(dispatches_for_changed_paths(
            docs, (".github/workflows/native-add-instrumentation.yml",)))

    def test_shared_one_shot_compose_helper_selects_all_executing_installed_owners(self):
        # The shared lazy semantics helper drives actual installed Add,
        # Connect and removal gestures. Its owner workflow was previously
        # missing from the PR path matrix even though JVM and native Add ran.
        # Pin narrow routing rather than enabling every sharedTest edit.
        helper = "app/src/sharedTest/java/io/github/gamesbyian/chunks/LazyComposeActions.kt"
        for workflow in (
            "installed-protocol-client.yml",
            "installed-rtorrent-magnets.yml",
            "native-add-instrumentation.yml",
        ):
            with self.subTest(workflow=workflow):
                source = (ROOT / ".github/workflows" / workflow).read_text()
                self.assertTrue(dispatches_for_changed_paths(source, (helper,)))
        protocol = (ROOT / ".github/workflows/installed-protocol-client.yml").read_text()
        self.assertFalse(dispatches_for_changed_paths(protocol, (
            "app/src/sharedTest/java/io/github/gamesbyian/chunks/UnrelatedFixture.kt",)),
            "The route is intentionally limited to the executed helper")
        # Dedicated 200% system-font captures test unrelated geometric
        # behavior and should not allocate another emulator for this helper.
        font = (ROOT / ".github/workflows/compose-system-font-200pct.yml").read_text()
        self.assertFalse(dispatches_for_changed_paths(font, (helper,)))

    def test_remote_real_peer_helper_is_compiled_in_its_own_installed_journey(self):
        # It has no independent Android @Test. All its actual execution is
        # through the real installed SeedhostAndroidAddUi test, and that test
        # is run with real peer sources by the dedicated magnet workflow.
        helper = (ROOT / "app/src/androidTest/java/io/github/gamesbyian/chunks/"
                  "MainActivityRealMagnetJourney.kt").read_text()
        owner = (ROOT / "app/src/androidTest/java/io/github/gamesbyian/chunks/"
                 "SeedhostAndroidAddUiInstrumentedTest.kt").read_text()
        campaign = (ROOT / ".github/workflows/installed-rtorrent-magnets.yml").read_text()
        self.assertNotIn("@Test", helper)
        self.assertIn("MainActivityRealMagnetJourney(", owner)
        self.assertIn("SeedhostAndroidAddUiInstrumentedTest", campaign)
        changed = ("app/src/androidTest/java/io/github/gamesbyian/chunks/"
                   "MainActivityRealMagnetJourney.kt",)
        self.assertTrue(dispatches_for_changed_paths(campaign, changed))

    def test_https_journey_covers_fetcher_and_local_production_caller(self):
        source = (ROOT / ".github/workflows/installed-rtorrent-magnets.yml").read_text()
        paths = pull_request_paths(source)
        for name in ("TorrentLinkImport.kt", "LocalEngineScreen.kt"):
            self.assertIn("app/src/main/java/io/github/gamesbyian/chunks/" + name, paths)

    def test_peer_preparation_progress_routes_to_cheap_and_installed_owners(self):
        # A changed diagnostic must run the bounded offline parser checks,
        # while the real-source job remains the sole installed evidence owner.
        docs = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        installed = (ROOT / ".github/workflows/installed-rtorrent-magnets.yml").read_text()
        foundation = WORKFLOW.read_text()
        for path in ("tools/peer_preparation_progress.py",
                     "tests/test_peer_preparation_progress.py"):
            with self.subTest(path=path):
                self.assertTrue(dispatches_for_changed_paths(docs, (path,)))
                self.assertTrue(dispatches_for_changed_paths(installed, (path,)))
                self.assertFalse(dispatches_for_changed_paths(foundation, (path,)))
        self.assertIn("test_peer_preparation_progress.py", docs)
        self.assertIn("python3 tools/peer_preparation_progress.py", installed)

    def test_disposable_peer_numeric_diagnostic_selects_own_future_edits(self):
        source = (ROOT / ".github/workflows/installed-rtorrent-magnets.yml").read_text()
        paths = pull_request_paths(source)
        self.assertIn("tools/export_disposable_peer_numeric.py", paths)
        self.assertIn("tests/test_disposable_peer_numeric_export.py", paths)
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_disposable_peer_numeric_export.py",
            source,
        )
        self.assertIn("chunks-disposable-peer-numeric-diagnosis", source)
        self.assertIn("if: failure() && env.CHUNKS_DISPOSABLE == '1'", source)

    def test_real_source_draft_skip_has_a_ready_transition(self):
        source = (ROOT / ".github/workflows/installed-rtorrent-magnets.yml").read_text()
        self.assertIn("github.event_name == 'pull_request' && !github.event.pull_request.draft", source)
        self.assertIn("types: [opened, reopened, synchronize, ready_for_review, converted_to_draft]", source)
        self.assertIn("github.ref == 'refs/heads/main' && inputs.apply && github.run_attempt == 1", source)

    def test_unknown_app_changes_still_dispatch_foundation(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        allowed = pull_request_paths(source)
        self.assertIn("app/**", allowed)
        self.assertNotIn(".github/workflows/android-check.yml", allowed)
        docs = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        self.assertTrue(dispatches_for_changed_paths(
            docs, (".github/workflows/android-check.yml",)))

    def test_synthetic_event_dispatch_decisions(self):
        android = WORKFLOW.read_text(encoding="utf-8")
        docs = (ROOT / ".github/workflows/documentation-validation.yml"
                ).read_text(encoding="utf-8")
        api35 = (ROOT / ".github/workflows/native-add-instrumentation.yml"
                 ).read_text(encoding="utf-8")
        cases = (
            (("docs/CI-POLICY.md",), (False, True, False)),
            (("app/src/androidTest/java/ExampleInstrumentedTest.kt",),
             (False, False, True)),
            (("app/src/main/java/io/github/gamesbyian/chunks/Example.kt",),
             (True, False, True)),
            (("app/src/test/java/io/github/gamesbyian/chunks/ExampleTest.kt",),
             (True, False, False)),
            (("tools/physical_android_preflight.sh",), (False, True, False)),
            (("tests/test_ci_trigger_contract.py",), (False, True, False)),
            (("app/src/androidTest/java/Test.kt", "app/src/main/java/Real.kt"),
             (True, False, True)),
            (("app/src/main/res/drawable/icon.xml",), (True, False, True)),
            (("app/build.gradle.kts",), (True, True, True)),
            (("not-a-known-path.txt",), (False, False, False)),
        )
        for changed, expected in cases:
            with self.subTest(changed=changed):
                got = tuple(dispatches_for_changed_paths(wf, changed)
                            for wf in (android, docs, api35))
                self.assertEqual(expected, got)

    def test_visual_atlas_runs_both_real_instrumentation_classes_once(self):
        # Keep the separate launcher and accessibility assertions while
        # avoiding the second Gradle/UTP/installation session.
        source = (ROOT / ".github/workflows/native-visual-atlas.yml").read_text()
        self.assertEqual(source.count(":app:connectedDebugAndroidTest"), 1)
        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.class="
            "io.github.gamesbyian.chunks.ChunksLauncherIconInstrumentedTest,"
            "io.github.gamesbyian.chunks.ChunksBrandHeaderInstrumentedTest",
            source,
        )
        self.assertIn("bash tools/native_atlas_capture.sh", source)
        self.assertIn("--require-executed", source)
        self.assertIn("app/build/outputs/androidTest-results/connected/**/TEST-*.xml", source)
        for name in (
            "installedApplicationExposesNativeAdaptiveVectorIcon",
            "largeAccessibilityTypeStillExposesTheWholeHeader",
            "brandAndTaglineAreOneAccessibleHeading",
        ):
            self.assertIn(name, source)
        self.assertIn("Missing or duplicated native atlas Android test method", source)

    def test_r8_reuses_warm_gradle_and_preserves_installed_case_proof(self):
        # The two invocations run in one job; a cold daemon costs an extra
        # JVM/Gradle configuration cycle after the Android emulator boots.
        # Both still execute real qaMinified APK and connected Android cases.
        workflow = (ROOT / ".github/workflows/minified-smoke.yml").read_text()
        smoke = (ROOT / "tools/minified_smoke.sh").read_text()
        self.assertIn(
            "gradle --daemon -Pchunks.testBuildType=qaMinified "
            ":app:assembleQaMinified :app:assembleQaMinifiedAndroidTest", workflow)
        self.assertIn("timeout 900 gradle --daemon --info", smoke)
        self.assertIn(":app:connectedQaMinifiedAndroidTest", smoke)
        self.assertIn("CHUNKS_R8_BUILD_SECONDS=", workflow)
        self.assertIn("CHUNKS_R8_INSTALLED_SMOKE_SECONDS=", smoke)
        self.assertIn("require_minified_smoke_digest.py", workflow)
        self.assertIn("--require-executed", workflow)
        self.assertIn("MinifiedBuildSmokeInstrumentedTest", smoke)

    def test_r8_offline_gate_tests_only_run_in_existing_docs_job(self):
        docs = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        r8 = (ROOT / ".github/workflows/minified-smoke.yml").read_text()
        docs_paths = pull_request_paths(docs)
        r8_paths = pull_request_paths(r8)
        for path in ("tests/test_ci_failure_digest.py",
                     "tests/test_minified_smoke_digest.py"):
            with self.subTest(path=path):
                self.assertIn(path, docs_paths)
                self.assertNotIn(path, r8_paths)
                self.assertTrue(dispatches_for_changed_paths(docs, (path,)))
                self.assertFalse(dispatches_for_changed_paths(r8, (path,)))
                self.assertIn(
                    "python3 -m unittest discover -s tests -p " + path.split("/")[-1],
                    docs,
                )
        # Changing anything that affects the actual R8 execution or source-
        # bound evidence remains an installed-emulator gate.
        for path in ("tools/ci_failure_digest.py",
                     "tools/require_minified_smoke_digest.py",
                     "tools/minified_smoke.sh",
                     "app/build.gradle.kts",
                     ".github/workflows/minified-smoke.yml"):
            with self.subTest(implementation=path):
                self.assertIn(path, r8_paths)
                self.assertTrue(dispatches_for_changed_paths(r8, (path,)))

    def test_expensive_r8_and_visual_atlas_wait_for_review_ready_pr(self):
        # Preserve both real installed-device classes and R8 exact-case checks;
        # only draft event scheduling should change.
        for filename, job, obligatory in (
            ("minified-smoke.yml", "minified-smoke",
             ("require_minified_smoke_digest.py", "reactivecircus/android-emulator-runner@v2")),
            ("native-visual-atlas.yml", "android-native-atlas",
             ("native_atlas_capture.sh", "CHUNKS_NATIVE_ATLAS: all 4 required installed Android tests passed")),
        ):
            with self.subTest(filename=filename):
                source = (ROOT / ".github/workflows" / filename).read_text()
                self.assertIn(
                    "types: [opened, reopened, synchronize, ready_for_review, converted_to_draft]",
                    source,
                )
                self.assertIn("workflow_dispatch:", source)
                self.assertIn(
                    "if: github.event_name != 'pull_request' || !github.event.pull_request.draft",
                    source,
                )
                self.assertIn(f"  {job}:", source)
                self.assertIn("cancel-in-progress: true", source)
                for retained in obligatory:
                    self.assertIn(retained, source)
                self.assertNotIn("continue-on-error: true", source)

    def test_foundation_and_api29_defer_drafts_but_recheck_on_ready(self):
        # The selectors and real assertions stay intact; only costly draft
        # scheduling is postponed, never a manual dispatch or ready PR.
        for filename, job, must_keep in (
            ("android-check.yml", "build",
             (":app:testDebugUnitTest :app:assembleDebug", "app/**")),
            ("android10-downloads-instrumentation.yml", "api29-downloads",
             ("tools/android10_downloads_instrumentation.sh",
              "app/src/main/AndroidManifest.xml")),
        ):
            with self.subTest(workflow=filename):
                source = (ROOT / ".github/workflows" / filename).read_text()
                self.assertIn(
                    "types: [opened, reopened, synchronize, ready_for_review, converted_to_draft]",
                    source)
                self.assertIn("workflow_dispatch:", source)
                self.assertIn(
                    "if: github.event_name != 'pull_request' || !github.event.pull_request.draft",
                    source)
                self.assertIn(f"  {job}:", source)
                self.assertIn("cancel-in-progress: true", source)
                for retained in must_keep:
                    self.assertIn(retained, source)
                self.assertNotIn("continue-on-error: true", source)

    def test_disposable_installed_workflows_defer_draft_but_resume_on_ready(self):
        # Preserve complete expensive real-daemon acceptance when a PR is ready;
        # experimental draft edits and conversions must stay cost-free.
        for filename, job, script in (
            ("installed-remote-delete-data.yml", "remote-delete-data",
             "tools/installed_remote_delete_data.sh"),
            ("installed-combined-two-servers.yml", "two-server-combined",
             "tools/installed_combined_two_servers.sh"),
        ):
            with self.subTest(workflow=filename):
                source = (ROOT / ".github/workflows" / filename).read_text()
                self.assertIn(
                    "types: [opened, reopened, synchronize, ready_for_review, converted_to_draft]",
                    source)
                self.assertIn(
                    "if: github.event_name != 'pull_request' || !github.event.pull_request.draft",
                    source)
                self.assertIn(f"  {job}:", source)
                self.assertIn("workflow_dispatch:", source)
                self.assertIn("cancel-in-progress: true", source)
                self.assertIn(script, source)
                self.assertNotIn("continue-on-error: true", source)

    def test_engine_collision_edits_trigger_real_api29_downloads_acceptance(self):
        source = (ROOT / ".github/workflows/android10-downloads-instrumentation.yml"
                  ).read_text(encoding="utf-8")
        allowed = pull_request_paths(source)
        for path in (
            "app/src/main/java/io/github/gamesbyian/chunks/engine/LocalTorrentEngine.kt",
            "app/src/main/java/io/github/gamesbyian/chunks/engine/LocalCheckedPayloadReservations.kt",
            "app/src/main/java/io/github/gamesbyian/chunks/engine/LocalPayloadCollisionSurvey.kt",
        ):
            with self.subTest(path=path):
                self.assertIn(path, allowed)

    def test_transactional_resume_changes_keep_both_android_runtime_tiers(self):
        api29 = (ROOT / ".github/workflows/android10-downloads-instrumentation.yml").read_text()
        api35 = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        for filename in ("LocalResumeDatabase.kt", "LocalResumeStore.kt"):
            changed = (f"app/src/main/java/io/github/gamesbyian/chunks/engine/{filename}",)
            with self.subTest(filename=filename):
                self.assertTrue(dispatches_for_changed_paths(api29, changed))
                self.assertTrue(dispatches_for_changed_paths(api35, changed))

    def test_native_research_source_changes_but_not_markdown_only_edits(self):
        # Positive allowlist stays active for native source; the following
        # negative patterns prevent documentation-only edits wasting JNI/NDK CI.
        for name in (
            "native-owned-writer-experiment.yml",
            "native-owned-android-link.yml",
        ):
            with self.subTest(workflow=name):
                source = (ROOT / ".github/workflows" / name).read_text(
                    encoding="utf-8"
                )
                allowed = pull_request_paths(source)
                self.assertIn("experiments/native-owned-writer/**", allowed)
                self.assertIn("!experiments/native-owned-writer/**/*.md", allowed)
                self.assertIn("!experiments/native-owned-writer/*.md", allowed)
                self.assertIn(f".github/workflows/{name}", allowed)
                paths = [
                    line.split("-", 1)[1].strip().strip(chr(34) + chr(39))
                    for line in source.splitlines()
                    if line.startswith("      - ")
                ]
                self.assertLess(
                    paths.index("experiments/native-owned-writer/**"),
                    paths.index("!experiments/native-owned-writer/**/*.md"),
                )

    def test_yaml_comments_do_not_masquerade_as_paths(self):
        sample = ("on:\n  pull_request:\n    paths:\n"
                  "      # safety test comment\n"
                  "      - 'app/src/main/**'\n"
                  "jobs:\n")
        self.assertEqual({"app/src/main/**"}, pull_request_paths(sample))

    def test_missing_pull_request_allowlist_fails_closed(self):
        with self.assertRaisesRegex(AssertionError, "no pull_request.paths"):
            pull_request_paths("on:\n  workflow_dispatch:\n")

    def test_unrelated_lists_do_not_count_as_triggers(self):
        sample = ("on:\n  pull_request:\n    paths:\n"
                  "      - 'app/**'\n"
                  "jobs:\n  build:\n    steps:\n"
                  "      - 'tools/physical_android_preflight.sh'\n")
        self.assertNotIn("tools/physical_android_preflight.sh", pull_request_paths(sample))

    def test_preflight_checks_are_executed(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("bash tools/test_physical_android_preflight.sh", source)
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_ci_trigger_contract.py",
            source,
        )


if __name__ == "__main__":
    unittest.main()
