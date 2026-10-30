"""No-network negative controls for Android CI toolchain declaration drift."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "tools/check_gradle_toolchain_pins.py"
spec = importlib.util.spec_from_file_location("check_gradle_toolchain_pins", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

GOOD_WORKFLOW = """name: Example
jobs:
  check:
    steps:
      - uses: actions/setup-java@v4
        with:
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "9.3.1"
      - run: gradle --no-daemon :app:testDebugUnitTest
"""
GOOD_ROOT = '''
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
'''
GOOD_APP = '''
dependencies {
    val libtorrent4j = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
}
'''


class PinContractTest(unittest.TestCase):
    def check(self, workflows=None, root=GOOD_ROOT, app=GOOD_APP):
        return module.validate(workflows if workflows is not None else
                               {name: GOOD_WORKFLOW for name in module.WORKFLOWS},
                               root, app)

    def test_all_explicit_fixed_pins_pass(self):
        self.assertEqual([], self.check())

    def test_missing_build_workflow_fails(self):
        self.assertTrue(self.check(workflows={}))

    def test_missing_jdk_pin_fails(self):
        bad = GOOD_WORKFLOW.replace('java-version: "17"', "")
        self.assertTrue(self.check(workflows={name: bad for name in module.WORKFLOWS}))

    def test_jdk_drift_fails(self):
        bad = GOOD_WORKFLOW.replace('java-version: "17"', 'java-version: "21"')
        self.assertTrue(self.check(workflows={name: bad for name in module.WORKFLOWS}))

    def test_gradle_drift_fails(self):
        bad = GOOD_WORKFLOW.replace('gradle-version: "9.3.1"', 'gradle-version: "9.4"')
        self.assertTrue(self.check(workflows={name: bad for name in module.WORKFLOWS}))

    def test_missing_gradle_action_fails(self):
        bad = GOOD_WORKFLOW.replace('gradle/actions/setup-gradle@v4', 'some/other-action@v4')
        self.assertTrue(self.check(workflows={name: bad for name in module.WORKFLOWS}))

    def test_unverified_wrapper_invocation_fails(self):
        bad = GOOD_WORKFLOW.replace('run: gradle ', 'run: ./gradlew ')
        self.assertTrue(self.check(workflows={name: bad for name in module.WORKFLOWS}))

    def test_dynamic_plugin_version_fails(self):
        self.assertTrue(self.check(root=GOOD_ROOT.replace('"9.1.1"', '"9.+"')))

    def test_dynamic_jni_version_fails(self):
        self.assertTrue(self.check(app=GOOD_APP.replace('"2.1.0-39"', '"latest.release"')))

    def test_duplicate_plugin_pin_fails(self):
        self.assertTrue(self.check(root=GOOD_ROOT.replace("plugins {", '''plugins {
    id("com.android.application") version "9.1.1" apply false''')))

    def test_dynamic_dependency_string_fails(self):
        self.assertTrue(self.check(app=GOOD_APP + '\nimplementation("example:unsafe:+")'))

    def test_bom_managed_unversioned_module_is_allowed(self):
        self.assertEqual([], self.check(app=GOOD_APP + '\nimplementation("androidx.compose.material3:material3")'))


if __name__ == "__main__":
    unittest.main()
