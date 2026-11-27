"""Offline checks of tools/release_shrink_smoke.sh with a fake adb (no emulator)."""
from pathlib import Path
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "release_shrink_smoke.sh"

UI = '<hierarchy><node text="Welcome"/><node text="Add"/><node text="Connections"/></hierarchy>'
SETTINGS_NOISE = ("W SliceDataConverter: Caused by: java.lang.ClassNotFoundException: "
                  "com.android.settings.Foo\n")
APP_CRASH = ("E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: "
             "io.github.gamesbyian.chunks, PID: 4242\nE AndroidRuntime: java.lang.RuntimeException\n")


def fake_adb(dir_: Path, pid: str, global_log: str, app_log: str, ui: str = UI) -> None:
    (dir_ / "global.log").write_text(global_log)
    (dir_ / "app.log").write_text(app_log)
    (dir_ / "ui.xml").write_text(ui)
    adb = dir_ / "adb"
    adb.write_text(textwrap.dedent(f"""\
        #!/usr/bin/env bash
        case "$*" in
          "shell pidof"*) echo "{pid}" ;;
          "pull /sdcard/ui.xml"*) cp "{dir_}/ui.xml" "$3" ;;
          "logcat -d --pid="*) cat "{dir_}/app.log" ;;
          "logcat -d") cat "{dir_}/global.log" ;;
          *) : ;;
        esac
        """))
    adb.chmod(0o755)


class ReleaseShrinkSmokeTests(unittest.TestCase):
    def run_smoke(self, **kw) -> subprocess.CompletedProcess:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        d = Path(tmp.name)
        fake_adb(d, **kw)
        work = d / "work"
        work.mkdir()
        env = dict(os.environ, PATH=f"{d}:{os.environ['PATH']}")
        # The script sleeps 20 s to let the app run; replace sleep for the offline test.
        (d / "sleep").write_text("#!/bin/sh\nexit 0\n")
        (d / "sleep").chmod(0o755)
        return subprocess.run(["bash", str(SCRIPT), "x.apk"], cwd=work, env=env,
                              capture_output=True, text=True, timeout=60)

    def test_other_processes_noise_does_not_fail_a_live_rendered_app(self):
        r = self.run_smoke(pid="4242", global_log=SETTINGS_NOISE, app_log="")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("CHUNKS_RELEASE_SMOKE: PASS", r.stdout)

    def test_a_chunks_crash_fails(self):
        r = self.run_smoke(pid="4242", global_log=APP_CRASH, app_log="")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("Chunks crashed", r.stdout)

    def test_an_r8_failure_in_the_app_process_fails(self):
        r = self.run_smoke(pid="4242", global_log="",
                           app_log="E Chunks: java.lang.NoSuchMethodError: foo\n")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("R8/JNI runtime failure", r.stdout)

    def test_a_dead_process_or_blank_ui_fails(self):
        self.assertNotEqual(self.run_smoke(pid="", global_log="", app_log="").returncode, 0)
        self.assertNotEqual(self.run_smoke(pid="4242", global_log="", app_log="",
                                           ui="<hierarchy/>").returncode, 0)


if __name__ == "__main__":
    unittest.main()
