"""Fail-closed offline contract for opt-in CI-only Gradle JVM worker experiment."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci_jvm_two_forks.init.gradle"
FOUNDATION = ROOT / ".github/workflows/android-check.yml"
DOCS = ROOT / ".github/workflows/documentation-validation.yml"


class CiJvmTwoForkTest(unittest.TestCase):
    def test_only_debug_unit_test_task_has_parallel_forks(self):
        source = SCRIPT.read_text(encoding="utf-8")
        code = "\n".join(line for line in source.splitlines()
                         if not line.lstrip().startswith("//"))
        self.assertIn('project.path == ":app"', code)
        self.assertIn('task.name == "testDebugUnitTest"', code)
        self.assertRegex(code, r"(?m)^\s*task\.maxParallelForks\s*=\s*2\s*$")
        self.assertEqual(1, len(re.findall(r"\bmaxParallelForks\b", code)))
        self.assertNotIn("forkEvery", code)
        self.assertNotIn("systemProperty", code)

    def test_full_unit_suite_and_apk_remain_in_one_verified_gradle_invocation(self):
        source = FOUNDATION.read_text(encoding="utf-8")
        self.assertIn(
            "gradle --no-daemon --init-script tools/ci_jvm_two_forks.init.gradle "
            ":app:testDebugUnitTest :app:assembleDebug", source
        )
        self.assertIn('      - "tools/ci_jvm_two_forks.init.gradle"', source)
        self.assertIn("if ! gradle ", source)
        self.assertIn("--require-executed", source)
        # Never silently drop failed tests or turn off exact-source APK stamping.
        self.assertNotIn("--tests ", source)
        self.assertIn("CHUNKS_SOURCE_SHA", source)
        self.assertIn("app/build/test-results/testDebugUnitTest/*.xml", source)

    def test_only_foundation_includes_explicit_init_script(self):
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            # Documentation validation references this script to test it,
            # but must never pass it to Gradle. All other workflows stay untouched.
            if path.name in ("android-check.yml", "documentation-validation.yml"):
                continue
            with self.subTest(path=path.name):
                self.assertNotIn("ci_jvm_two_forks.init.gradle",
                                 path.read_text(encoding="utf-8"))
        source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn("maxParallelForks", source)

    def test_docs_checks_it_on_pr_and_historical_push(self):
        source = DOCS.read_text(encoding="utf-8")
        self.assertEqual(2, source.count("      - 'tools/ci_jvm_two_forks.init.gradle'"))
        self.assertEqual(2, source.count("      - 'tests/test_ci_jvm_parallelism.py'"))
        self.assertIn("python3 -m unittest discover -s tests -p test_ci_jvm_parallelism.py",
                      source)


if __name__ == "__main__":
    unittest.main()
