"""Publishing debug APKs must be explicit, main-only and SHA-pinned."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
FILE = ROOT / ".github/workflows/publish-debug-apk.yml"
PINS = (
    "actions/checkout@11d5960a326750d5838078e36cf38b85af677262",
    "actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020",
    "actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3",
    "gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a",
)


class DebugPublicationTests(unittest.TestCase):
    def test_never_auto_publishes_on_push_or_pr(self):
        s = FILE.read_text()
        on = s.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual("  workflow_dispatch:", on.strip("\n"))
        self.assertIn("if: github.ref == 'refs/heads/main'", s)
        self.assertIn("cancel-in-progress: false", s)

    def test_no_privileged_token_in_build_environment(self):
        s = FILE.read_text()
        job_env = s.split("jobs:\n  publish:", 1)[1].split("    steps:", 1)[0]
        self.assertNotIn("GH_TOKEN", job_env)
        # Read/write credentials are only injected in the three explicit
        # main-only publication steps, never as job-level or PR credentials.
        authorized = {
            "Refuse duplicate debug release before Android SDK and Gradle",
            "Publish immutable pre-release assets inside this repository",
            "Retire superseded owner-test APK releases after successful publication",
        }
        steps = s.split("      - name: ")[1:]
        scoped = {step.split("\n", 1)[0]
                  for step in steps
                  if "GH_TOKEN: " + "$" + "{{ github.token }}" in step}
        self.assertEqual(authorized, scoped)
        self.assertIn("persist-credentials: false", s)

    def test_redundant_release_refused_before_sdk(self):
        s = FILE.read_text()
        self.assertLess(s.index("Refuse duplicate debug release"),
                        s.index("Test and build exact main checkout"))
        self.assertIn('gh release view "$tag"', s)
        self.assertIn('gh release create "$tag"', s)
        self.assertIn("sha256sum -c CHUNKS-BUILD-SHA256.txt", s)
        self.assertIn("chunks-preview.apk", s)
        self.assertLess(s.index('gh release create "$tag"'),
                        s.index('gh release list --repo "$GITHUB_REPOSITORY"'))
        self.assertIn('test("^android-debug-[0-9a-f]{12}$")', s)
        self.assertIn('gh release delete "$obsolete"', s)
        self.assertIn('"$obsolete" != "$current"', s)
        self.assertNotIn("actions/upload-artifact@", s)

    def test_reviewed_immutable_action_revisions(self):
        s = FILE.read_text()
        for action in PINS:
            with self.subTest(action=action):
                self.assertIn("uses: " + action, s)
        self.assertNotIn("uses: actions/checkout@v4", s)
        self.assertNotIn("uses: actions/upload-artifact@v4", s)

    def test_ci_self_change_is_tested_offline(self):
        s = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        self.assertEqual(2, s.count("      - 'tests/test_ci_debug_publish_gate.py'"))
        self.assertEqual(2, s.count("      - '.github/workflows/publish-debug-apk.yml'"))
        self.assertIn("python3 -m unittest discover -s tests -p test_ci_debug_publish_gate.py", s)


if __name__ == "__main__":
    unittest.main()
