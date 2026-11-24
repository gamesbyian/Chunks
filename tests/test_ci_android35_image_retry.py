"""Offline regression for the bounded API35 emulator SDK download preflight."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/install_android35_image_retry.sh"

FAKE_SDKMANAGER = """#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == "--channel=0 --install system-images;android-35;google_apis;x86_64" ]] || exit 81
n=0
[[ ! -f "$CHUNKS_TEST_COUNT" ]] || n="$(cat "$CHUNKS_TEST_COUNT")"
n=$((n+1))
printf '%s' "$n" > "$CHUNKS_TEST_COUNT"
folder="$ANDROID_HOME/system-images/android-35/google_apis/x86_64"
mkdir -p "$folder"
if (( n <= CHUNKS_TEST_FAILS )); then
  printf corrupt > "$folder/partial"
  exit 17
fi
[[ "$CHUNKS_TEST_NO_RECEIPT" != "yes" ]] || exit 0
printf 'pinned image' > "$folder/package.xml"
"""

class PinnedEmulatorImageRetryTest(unittest.TestCase):
    def invoke(self, failures=0, no_receipt=False, installed=False):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            sdk_root = base / "sdk"
            sdk_root.mkdir()
            counter = base / "calls"
            manager = base / "sdkmanager"
            manager.write_text(FAKE_SDKMANAGER)
            manager.chmod(0o700)
            receipt = sdk_root / "system-images/android-35/google_apis/x86_64/package.xml"
            if installed:
                receipt.parent.mkdir(parents=True)
                receipt.write_text("preinstalled image")
            env = dict(os.environ, ANDROID_HOME=str(sdk_root),
                       CHUNKS_SDKMANAGER=str(manager),
                       CHUNKS_TEST_COUNT=str(counter),
                       CHUNKS_TEST_FAILS=str(failures),
                       CHUNKS_TEST_NO_RECEIPT="yes" if no_receipt else "no",
                       CHUNKS_SDK_RETRY_DELAY="0", CHUNKS_SDK_TIMEOUT="10s")
            process = subprocess.run(["bash", str(SCRIPT)],
                                     env=env, capture_output=True, text=True, timeout=25)
            calls = int(counter.read_text()) if counter.exists() else 0
            return process, calls, receipt.exists(), (
                sdk_root / "system-images/android-35/google_apis/x86_64/partial").exists()

    def test_recovers_from_two_download_failures_without_accepting_partial_image(self):
        outcome, calls, receipt, partial = self.invoke(failures=2)
        self.assertEqual(0, outcome.returncode, outcome.stderr)
        self.assertEqual(3, calls)
        self.assertTrue(receipt)
        self.assertFalse(partial)
        self.assertIn("package.xml verified, attempt=3", outcome.stdout)

    def test_exhausted_transient_failure_stays_red(self):
        outcome, calls, receipt, _ = self.invoke(failures=4)
        self.assertNotEqual(0, outcome.returncode)
        self.assertEqual(3, calls)
        self.assertFalse(receipt)
        self.assertIn("unavailable after 3 bounded attempts", outcome.stderr)

    def test_exit_zero_without_actual_package_receipt_is_not_a_pass(self):
        outcome, calls, receipt, _ = self.invoke(no_receipt=True)
        self.assertNotEqual(0, outcome.returncode)
        self.assertEqual(3, calls)
        self.assertFalse(receipt)

    def test_already_present_package_requires_no_download(self):
        outcome, calls, receipt, _ = self.invoke(installed=True)
        self.assertEqual(0, outcome.returncode, outcome.stderr)
        self.assertEqual(0, calls)
        self.assertTrue(receipt)

    def test_workflow_preflight_occurs_before_the_real_emulator_without_bypassing_it(self):
        workflow = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        self.assertIn("bash tools/install_android35_image_retry.sh", workflow)
        self.assertLess(workflow.index("bash tools/install_android35_image_retry.sh"),
                        workflow.index("uses: reactivecircus/android-emulator-runner@v2"))
        self.assertIn("script: bash tools/native_add_prebuilt.sh", workflow)
        self.assertIn("tools/install_android35_image_retry.sh", workflow)

if __name__ == "__main__":
    unittest.main()
