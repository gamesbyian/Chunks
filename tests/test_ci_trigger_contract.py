"""Offline guard against silently skipping Android preflight regressions.

The Android workflow executes both preflight shell scripts, so their edits
must trigger that workflow rather than waiting for a later unrelated PR.
"""
from pathlib import Path
import re
import unittest

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


class AndroidCiTriggerContract(unittest.TestCase):
    def test_preflight_edits_trigger_android_check(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        allowed = pull_request_paths(source)
        for path in (
            "tools/physical_android_preflight.sh",
            "tools/test_physical_android_preflight.sh",
            "tests/test_ci_trigger_contract.py",
        ):
            with self.subTest(path=path):
                self.assertIn(path, allowed)

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
