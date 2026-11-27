"""Offline checks of tools/release_shrink_smoke.sh with a fake adb (no emulator)."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "release_shrink_smoke.sh"
PKG = "io.github.gamesbyian.chunks"
UI = '<hierarchy>' + ''.join(f'<node package="{PKG}" text="{label}"/>'
                            for label in ('Welcome', 'Add', 'Connections')) + '</hierarchy>'
SETTINGS_NOISE = ("W SliceDataConverter: Caused by: java.lang.ClassNotFoundException: "
                  "com.android.settings.Foo\n")
APP_CRASH = ("E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: "
             "io.github.gamesbyian.chunks, PID: 4242\nE AndroidRuntime: java.lang.RuntimeException\n")


def fake_adb(dir_: Path, pid: str, global_log: str, app_log: str, ui: str = UI,
             installed: bytes = b'candidate', split: bool = False,
             fail_dump: bool = False, fail_log: bool = False) -> None:
    (dir_ / "global.log").write_text(global_log)
    (dir_ / "app.log").write_text(app_log)
    (dir_ / "ui.xml").write_text(ui)
    (dir_ / "base.apk").write_bytes(installed)
    adb = dir_ / "adb"
    split_line = 'echo package:/data/app/test/split_config.x86_64.apk' if split else ':'
    adb.write_text(textwrap.dedent(f"""\
        #!/usr/bin/env bash
        case "$*" in
          "shell pm path"*) echo package:/data/app/test/base.apk; {split_line} ;;
          "pull /data/app/test/base.apk"*) cp "{dir_}/base.apk" "$3" ;;
          "shell pidof"*) echo "{pid}" ;;
          "shell uiautomator dump"*) exit {int(fail_dump)} ;;
          "pull /sdcard/chunks-release-smoke-"*) cp "{dir_}/ui.xml" "$3" ;;
          "logcat -d --pid="*) cat "{dir_}/app.log" ;;
          "logcat -d") if [ {int(fail_log)} = 1 ]; then exit 1; fi; cat "{dir_}/global.log" ;;
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
        (work / "x.apk").write_bytes(b'candidate')
        output = work / 'app/build/outputs'
        output.mkdir(parents=True)
        # Pre-existing output must never be accepted as this attempt's capture.
        (output / 'release-ui.xml').write_text(UI)
        env = dict(os.environ, PATH=f"{d}:{os.environ['PATH']}")
        (d / "sleep").write_text("#!/bin/sh\nexit 0\n")
        (d / "sleep").chmod(0o755)
        result = subprocess.run(["bash", str(SCRIPT), "x.apk"], cwd=work, env=env,
                                capture_output=True, text=True, timeout=60)
        if result.returncode == 0:
            receipt = (output / 'release-installed-identity.txt').read_text().strip()
            self.assertEqual(receipt, 'CHUNKS_INSTALLED_APK: matched size_bytes=9 sha256=' +
                             hashlib.sha256(b'candidate').hexdigest())
        return result

    def test_other_processes_noise_does_not_fail_a_live_rendered_app(self):
        r = self.run_smoke(pid="4242", global_log=SETTINGS_NOISE, app_log="")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("CHUNKS_RELEASE_SMOKE: PASS", r.stdout)

    def test_a_chunks_crash_fails_even_with_a_large_following_log(self):
        r = self.run_smoke(pid="4242", global_log=APP_CRASH + APP_CRASH * 3000, app_log="")
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

    def test_wrong_installed_bytes_and_unexpected_splits_fail(self):
        r = self.run_smoke(pid="4242", global_log="", app_log="", installed=b'other')
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("CHUNKS_INSTALLED_APK: byte-mismatch", r.stdout)
        r = self.run_smoke(pid="4242", global_log="", app_log="", split=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("exactly one installed base APK", r.stdout)

    def test_foreign_ui_or_stale_capture_fails(self):
        r = self.run_smoke(pid="4242", global_log="", app_log="", ui=UI.replace(PKG, 'com.android.settings'))
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("app-owned Compose UI did not render", r.stdout)
        r = self.run_smoke(pid="4242", global_log="", app_log="", fail_dump=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("fresh UI capture failed", r.stdout)

    def test_unavailable_logs_are_not_a_clean_runtime(self):
        r = self.run_smoke(pid="4242", global_log="", app_log="", fail_log=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("cannot read global logcat", r.stdout)


class ReleaseTrackerAddressTests(unittest.TestCase):
    def test_default_and_emulator_alias_are_distinct_compact_peer_addresses(self):
        # Use a real local HTTP server, not a string-only source assertion.
        import importlib.util
        from http.server import ThreadingHTTPServer
        from threading import Thread
        from urllib.parse import urlencode
        from urllib.request import urlopen

        spec = importlib.util.spec_from_file_location(
            "release_tracker_fixture", ROOT / "tools/interop/loopback_tracker.py")
        tracker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tracker)

        class EmulatorTracker(tracker.Handler):
            peer_address = bytes([10, 0, 2, 2])

        for handler, expected in ((tracker.Handler, bytes([127, 0, 0, 1])),
                                  (EmulatorTracker, bytes([10, 0, 2, 2]))):
            with self.subTest(handler=handler.__name__):
                tracker.SWARMS.clear()
                server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
                worker = Thread(target=server.serve_forever, daemon=True)
                worker.start()
                try:
                    base = f"http://127.0.0.1:{server.server_port}/announce?"
                    def announce(port):
                        query = urlencode({"info_hash": b"x" * 20, "port": port})
                        with urlopen(base + query, timeout=3) as response:
                            return response.read()
                    announce(18792)
                    self.assertIn(b"5:peers6:" + expected + (18792).to_bytes(2, "big"),
                                  announce(18794))
                finally:
                    server.shutdown()
                    server.server_close()
                    worker.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
