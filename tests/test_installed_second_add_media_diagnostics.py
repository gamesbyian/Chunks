"""#2118: a failing phase must still save its bounded diagnostic and receipts.

Under `set -e` the parser's nonzero exit for a failed test used to end the script
before the diagnostic line was saved, so recurrences carried no evidence. This runs
the script's real run_phase function against stub adb/curl and a failing raw stream.
"""
import json
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "installed_second_add_media_consent.sh"
KLASS = "io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest"


def raw_stream(method, outcome, message=""):
    head = (f"INSTRUMENTATION_STATUS: class={KLASS}\nINSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n")
    tail = (f"INSTRUMENTATION_STATUS: class={KLASS}\nINSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS: numtests=1\n")
    if message:
        tail += f"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: {message}\n"
    tail += f"INSTRUMENTATION_STATUS_CODE: {outcome}\nINSTRUMENTATION_CODE: -1\n"
    return head + tail


class SecondAddMediaDiagnosticsTest(unittest.TestCase):
    def run_phase(self, method, raw):
        text = SCRIPT.read_text()
        function = re.search(r"^run_phase\(\) \{.*?^\}\n", text, re.S | re.M).group(0)
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            (tmp / "raw.txt").write_text(raw)
            for name, body in (("adb", f"#!/bin/sh\ncat '{tmp / 'raw.txt'}'\n"),
                               ("curl", "#!/bin/sh\necho '{}'\n")):
                path = bin_dir / name
                path.write_text(body)
                path.chmod(path.stat().st_mode | stat.S_IEXEC)
            out, scratch = tmp / "out", tmp / "scratch"
            out.mkdir()
            scratch.mkdir()
            driver = (f"set -euo pipefail\ncase_class={KLASS}\nrunner=r\ncert=c\ncounts=http://x/counts\n"
                      f"out='{out}'\nscratch='{scratch}'\n{function}\nrun_phase {method}\necho PHASE-RETURNED\n")
            result = subprocess.run(["bash", "-c", driver], cwd=ROOT, text=True, capture_output=True,
                                    env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}"})
            files = {p.name: p.read_text() for p in out.iterdir()}
        return result, files

    def test_failed_phase_saves_diagnostic_and_receipts_then_still_fails(self):
        method = "phaseA_submitTwoSourcesAndRefreshInventory"
        message = ("Controlled first metadata request absent: sourceFetched=true online=true metered=false "
                   "powerSaver=false onlineConsent=true automatic=true kind=TV")
        result, files = self.run_phase(method, raw_stream(method, -2, message))
        self.assertNotEqual(0, result.returncode, "a failed phase must fail the script")
        self.assertNotIn("PHASE-RETURNED", result.stdout)
        self.assertIn("CHUNKS_SECOND_ADD_MEDIA_PHASE_FAILED", result.stdout)
        self.assertIn("sourceFetched=true online=true", files[f"{method}-assertion.txt"])
        self.assertIn(f"{method}.xml", files, "JUnit receipt saved")
        self.assertIn(f"{method}-digest.json", files, "digest saved")
        self.assertEqual({}, json.loads(files[f"{method}-counts.json"]))

    def test_passing_phase_returns_normally(self):
        method = "phaseB_restoredExclusionAndFreshPositiveControl"
        result, files = self.run_phase(method, raw_stream(method, 0))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PHASE-RETURNED", result.stdout)
        self.assertEqual("", files[f"{method}-assertion.txt"])


if __name__ == "__main__":
    unittest.main()
