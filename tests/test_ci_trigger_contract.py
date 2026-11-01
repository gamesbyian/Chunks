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
        r"(?m)^  pull_request:\s*\n    paths:\s*\n"
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
        r"(?m)^  pull_request:\s*\n    paths:\s*\n"
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

    def test_unknown_app_changes_still_dispatch_foundation(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        allowed = pull_request_paths(source)
        self.assertIn("app/**", allowed)
        self.assertIn(".github/workflows/android-check.yml", allowed)

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
