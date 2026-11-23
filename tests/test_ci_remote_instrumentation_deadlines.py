"""Protect bounded installed Android real-daemon/storage instrumentation without running an emulator."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RemoteInstalledDeadlineTest(unittest.TestCase):
    def test_every_selected_installed_journey_has_an_independent_deadline(self):
        expected = {
            "tools/installed_protocol_client.sh": (6, "--user 0 -w -r"),
            "tools/installed_combined_two_servers.sh": (6, "-w -r"),
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

    def test_installed_snapshot_priority_consent_is_actually_executed(self):
        # Compiling an androidTest class must not count as its acceptance.
        source = (ROOT / "tools/installed_combined_two_servers.sh").read_text()
        self.assertIn("RemoteSnapshotPriorityConfirmationInstrumentedTest", source)
        self.assertIn("snapshot-priority-consent.txt", source)
        self.assertIn("grep -q '^OK (2 tests)'", source)
        self.assertIn("CHUNKS_SNAPSHOT_REMOTE_PRIORITY_INSTALLED:", source)
        self.assertIn("::error::Installed remote snapshot priority consent", source)

    def test_snapshot_folder_fixture_proves_root_reread_before_installed_journeys(self):
        workflow = (ROOT / ".github/workflows/installed-combined-two-servers.yml").read_text()
        fixture = (ROOT / "tools/interop/disposable_snapshot_folder_listing.py").read_text()
        self.assertIn('if requested == "" or requested == root:', fixture)
        self.assertIn('elif requested == target:', fixture)
        self.assertIn("Exact-tenant folder reread was refused", workflow)
        self.assertIn("Exact-tenant folder reread was inconsistent", workflow)
        self.assertIn('for suffix in "$root/a/data" "$root/a/data/reviewed"; do', workflow)
        self.assertLess(workflow.index('Exact-tenant folder reread was refused'),
                        workflow.index('Preload same-hash and distinct stopped fixtures'))

    def test_document_process_loss_is_host_driven_and_bounded(self):
        runner = (ROOT / "tools/installed_protocol_client.sh").read_text()
        host = (ROOT / "tools/installed_external_document_process_loss.sh").read_text()
        self.assertIn("bash tools/installed_external_document_process_loss.sh || failed=1", runner)
        # A failed external task/URI gate must keep this job red but can no
        # longer prevent independent actual-client and Add fault execution.
        self.assertGreater(runner.index("bash tools/installed_external_document_process_loss.sh"),
                           runner.index("run_local_add_death || failed=1"))
        self.assertLess(runner.index("bash tools/installed_external_document_process_loss.sh"),
                        runner.index("exit $failed"))
        self.assertNotIn("am instrument", host)
        self.assertIn("ro.kernel.qemu", host)
        self.assertIn('run-as "$pkg" /system/bin/kill -9 "$old_pid"', host)
        self.assertIn('resume_saved_task "$old_task"', host)
        self.assertIn('am task focus "$exact_task"', host)
        self.assertNotIn("android.intent.action.MAIN", host)
        self.assertLess(host.index('await_text "$notice"'), host.index('new_task="$(task_id)"'))
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
