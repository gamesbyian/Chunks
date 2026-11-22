"""Protect bounded installed Android real-daemon/storage instrumentation without running an emulator."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RemoteInstalledDeadlineTest(unittest.TestCase):
    def test_every_selected_installed_journey_has_an_independent_deadline(self):
        expected = {
            "tools/installed_protocol_client.sh": (6, "--user 0 -w -r"),
            "tools/installed_combined_two_servers.sh": (2, "-w -r"),
            "tools/installed_rtorrent_magnets.sh": (2, "-w -r"),
            "tools/fgs_timeout_repro.sh": (2, "-w -r"),
            "tools/process_death_repro.sh": (2, "-w -r"),
            "tools/android_native_low_storage_repro.sh": (1, "-w -r"),
            "tools/owned_mediastore_document_probe.sh": (1, "-w -r"),
            "tools/batch_cold_restore_probe.sh": (1, "-w -r"),
            "tools/native_mediastore_fd_probe.sh": (1, "-w -r"),
            "tools/anonymous_zip_stage_api29.sh": (1, "-w -r"),
        }
        for path, (count, args) in expected.items():
            with self.subTest(path=path):
                src = (ROOT / path).read_text()
                needle = "adb shell am instrument " + args
                self.assertEqual(count, src.count(needle))
                pattern = (
                    r"timeout --signal=TERM --kill-after=15s [0-9]+m "
                    + re.escape(needle)
                )
                self.assertEqual(count, len(re.findall(pattern, src)))
                self.assertIn("set -euo pipefail", src)

    def test_document_process_loss_is_host_driven_and_bounded(self):
        runner = (ROOT / "tools/installed_protocol_client.sh").read_text()
        host = (ROOT / "tools/installed_external_document_process_loss.sh").read_text()
        self.assertIn("bash tools/installed_external_document_process_loss.sh || exit 1", runner)
        self.assertLess(runner.index("bash tools/installed_external_document_process_loss.sh"),
                        runner.index("run_pair TRANSMISSION"))
        self.assertNotIn("am instrument", host)
        self.assertIn("ro.kernel.qemu", host)
        self.assertIn('run-as "$pkg" /system/bin/kill -9 "$old_pid"', host)
        self.assertIn('if [[ "$new_task" != "$old_task" ]]', host)
        self.assertIn('if [[ "$new_pid" == "$old_pid" ]]', host)
        self.assertIn("Android restarted Chunks.", host)
        self.assertIn("external-first.bin", host)

    def test_intentional_death_does_not_treat_a_host_timeout_as_a_success(self):
        for name in ("installed_protocol_client.sh", "process_death_repro.sh"):
            with self.subTest(name=name):
                src = (ROOT / "tools" / name).read_text()
                self.assertIn("phase1_status", src) if name == "installed_protocol_client.sh" \
                    else self.assertIn("phase_a_status", src)
                self.assertIn("-eq 124", src)
                self.assertIn("-eq 137", src)


if __name__ == "__main__":
    unittest.main()
