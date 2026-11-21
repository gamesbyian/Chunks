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
