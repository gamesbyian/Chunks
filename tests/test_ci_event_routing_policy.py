"""Static routing regression controls for every GitHub Actions workflow.

Protect cheap policy changes without suppressing production or security tests.
Event filters are eligibility signals, not exact-head acceptance receipts.
"""
from pathlib import Path
import re
import unittest

from test_ci_trigger_contract import dispatches_for_changed_paths, pull_request_paths
from tools.ci_workflow_inventory import block, event_block

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
APP_TEST_ROOT = "app/src/androidTest/java/io/github/gamesbyian/chunks/"
DEDICATED = {
    "SystemFontScaleComposeInstrumentedTest.kt": "compose-system-font-200pct.yml",
    "ExperimentalTypefaceNativeInstrumentedTest.kt": "experimental-font-android-render.yml",
    "AndroidNativeLowStorageRecoveryInstrumentedTest.kt": "android-native-low-storage-repro.yml",
    "SourceTorrentArchiveInstrumentedTest.kt": "installed-source-archive.yml",
    "RemoteDeleteDataInstalledInstrumentedTest.kt": "installed-remote-delete-data.yml",
    "ChunksLauncherIconInstrumentedTest.kt": "native-visual-atlas.yml",
    "ChunksBrandHeaderInstrumentedTest.kt": "native-visual-atlas.yml",
    "OwnedMediaStoreDocumentPublicationInstrumentedTest.kt": "storage-owned-mediastore-documents.yml",
    "Android10DownloadsRevocationInstrumentedTest.kt": "android10-downloads-instrumentation.yml",
    "Android10StoragePermissionDialogInstrumentedTest.kt": "android10-downloads-instrumentation.yml",
}
LEGACY_PUSH_BRANCHES = {
    "documentation-validation.yml": "docs/reconcile-specs-main-20261029",
    "svg-alternates-qa-refresh.yml": "feat/svg-font-independent-alternates-20261027",
    "svg-outlined-alternates.yml": "feat/svg-font-independent-alternates-20261027",
}


def workflow(name):
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def push_branches(push):
    """Parse the explicit inline or multiline branch selection in this repo."""
    inline = re.search(r"(?m)^    branches:\s*\[([^\]]+)\]", push)
    if inline:
        return tuple(s.strip().strip("'\"") for s in inline.group(1).split(","))
    match = re.search(r"(?m)^    branches:\s*$", push)
    if not match:
        return ()
    names = []
    for line in push[match.end():].splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= 4:
            break
        item = re.match(r"^      -\s+['\"]?([^'\"\s#]+)", line)
        if item:
            names.append(item.group(1))
    return tuple(names)


class CiDispatchRelevance(unittest.TestCase):
    def test_all_automatic_events_have_path_and_push_branch_filters(self):
        definitions = sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))
        self.assertGreaterEqual(len(definitions), 90)
        for file in definitions:
            source = file.read_text(encoding="utf-8")
            events = block(source, "on")
            pr = event_block(events, "pull_request")
            push = event_block(events, "push")
            with self.subTest(file=file.name):
                if "  pull_request:" in events:
                    self.assertRegex(pr, r"(?m)^    paths:\s*$")
                    self.assertTrue(pull_request_paths(source))
                if "  push:" in events:
                    self.assertRegex(push, r"(?m)^    paths:\s*$")
                    expected = (LEGACY_PUSH_BRANCHES[file.name],) if file.name in LEGACY_PUSH_BRANCHES else ("main",)
                    self.assertEqual(expected, push_branches(push))

    def test_policy_only_changes_run_cheap_or_owning_workflows(self):
        foundation = workflow("android-check.yml")
        native = workflow("native-add-instrumentation.yml")
        docs = workflow("documentation-validation.yml")
        skills = workflow("portable-agent-skills.yml")
        font = workflow("compose-system-font-200pct.yml")
        cases = (
            ("docs/CI-POLICY.md", (False, False, True, False, False)),
            ("tools/check_compose_capture_contract.py", (False, False, True, False, True)),
            ("tests/test_compose_capture_contract.py", (False, False, True, False, True)),
            (".github/workflows/android-check.yml", (False, False, True, False, False)),
            (".github/workflows/native-add-instrumentation.yml", (False, False, True, False, False)),
            ("skills/references/change-aware-test-contracts.md", (False, False, False, True, False)),
            (APP_TEST_ROOT + "SystemFontScaleComposeInstrumentedTest.kt", (False, False, False, False, True)),
            ("app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt", (True, True, False, False, False)),
            (APP_TEST_ROOT + "NewSharedInstrumentedTest.kt", (False, True, False, False, False)),
            ("app/src/test/java/io/github/gamesbyian/chunks/NewUnitTest.kt", (True, False, False, False, False)),
        )
        for changed, expected in cases:
            with self.subTest(changed=changed):
                got = tuple(dispatches_for_changed_paths(s, (changed,)) for s in
                            (foundation, native, docs, skills, font))
                self.assertEqual(expected, got)
        mixed = (APP_TEST_ROOT + "SystemFontScaleComposeInstrumentedTest.kt",
                 "app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt")
        self.assertTrue(dispatches_for_changed_paths(native, mixed))
        self.assertTrue(dispatches_for_changed_paths(foundation, mixed))
        self.assertTrue(dispatches_for_changed_paths(font, mixed))
        self.assertFalse(dispatches_for_changed_paths(native, ("README.md",)))
        self.assertFalse(dispatches_for_changed_paths(foundation, ("README.md",)))

    def test_dedicated_instrumentation_is_never_left_unowned(self):
        native = workflow("native-add-instrumentation.yml")
        for filename, owner_name in DEDICATED.items():
            path = APP_TEST_ROOT + filename
            with self.subTest(filename=filename):
                self.assertTrue((ROOT / path).is_file())
                self.assertFalse(dispatches_for_changed_paths(native, (path,)))
                owner = workflow(owner_name)
                self.assertTrue(dispatches_for_changed_paths(owner, (path,)))
                self.assertIn("android-emulator-runner@v2", owner)
                self.assertTrue(dispatches_for_changed_paths(
                    native, (path, "app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt")))
                self.assertTrue(dispatches_for_changed_paths(
                    native, (path, APP_TEST_ROOT + "NewSharedInstrumentedTest.kt")))

    def test_skills_branch_push_is_main_only_and_sensitive_work_manual(self):
        skills = workflow("portable-agent-skills.yml")
        self.assertEqual(("main",), push_branches(event_block(block(skills, "on"), "push")))
        self.assertIn("  pull_request:", block(skills, "on"))
        for name in ("release-apk.yml", "publish-debug-apk.yml",
                     "seedhost-active-start-once.yml", "seedhost-android-mutation-once.yml"):
            with self.subTest(file=name):
                on = block(workflow(name), "on")
                self.assertIn("  workflow_dispatch:", on)
                self.assertNotIn("  push:", on)
                self.assertNotIn("  pull_request:", on)

    def test_docs_workflow_owns_policy_edits_and_fast_preflights(self):
        docs = workflow("documentation-validation.yml")
        expected = ("tests/test_ci_event_routing_policy.py",
                    "tests/test_ci_trigger_contract.py",
                    "tools/check_compose_capture_contract.py",
                    "tests/test_compose_capture_contract.py",
                    ".github/workflows/android-check.yml",
                    ".github/workflows/native-add-instrumentation.yml",
                    ".github/workflows/portable-agent-skills.yml")
        for path in expected:
            self.assertIn(path, pull_request_paths(docs))
        self.assertIn("python3 tools/check_compose_capture_contract.py --preflight", docs)
        self.assertIn("-p test_ci_event_routing_policy.py", docs)
        self.assertIn("-p test_compose_capture_contract.py", docs)
        self.assertNotIn("check_compose_capture_contract.py --preflight",
                         workflow("android-check.yml"))


if __name__ == "__main__":
    unittest.main()
