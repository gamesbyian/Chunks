"""Guard genuine cross-UID first VIEW without a redundant task-destroying flag.

These static checks protect test routing only, not a claim of Android success.
The installed five-client campaign remains the actual behavioural oracle.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ForeignFirstViewHarnessContract(unittest.TestCase):
    def test_first_view_preserves_real_task_with_one_preexisting_data_clear(self):
        source = (ROOT / "tools/installed_external_document_process_loss.sh").read_text()
        self.assertIn('adb shell pm clear --user 0 "$pkg"', source)
        self.assertIn('open_source "--ez fixture_keep_task true" ||', source)
        self.assertNotIn('\nopen_source ||', source)
        # The same original task and a different process must still be proved.
        for required in (
            'old_task="$(task_id)"',
            'old_pid="$(app_pid)"',
            'resume_saved_task "$old_task"',
            '[[ "$new_task" != "$old_task" ]]',
            '[[ "$new_pid" == "$old_pid" ]]',
            'open_source "--ez fixture_keep_task true" ||',
            'await_source_review ||',
        ):
            self.assertIn(required, source)
        self.assertIn('if ! await_source_review; then', source)

    def test_sender_retains_real_foreign_grant_and_bounded_task_flag(self):
        sender = (ROOT / "app/src/androidTest/java/io/github/gamesbyian/chunks/external/ExternalSourceSenderActivity.java").read_text()
        self.assertIn('Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION', sender)
        self.assertIn('getBooleanExtra("fixture_keep_task", false)', sender)
        self.assertIn('handoff.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);', sender)
        self.assertIn('handoff.setClipData(ClipData.newUri(', sender)
        self.assertIn('startActivity(handoff);', sender)


if __name__ == "__main__":
    unittest.main()
