"""Guard genuine cross-UID first VIEW without a redundant task-destroying flag.

These static checks protect test routing only, not a claim of Android success.
The installed five-client campaign remains the actual behavioural oracle.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
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
        self.assertIn('if ! await_text "$notice"; then', source)
        self.assertIn('diagnose_missing_interrupted_notice', source)
        self.assertIn('CHUNKS_DOCUMENT_RESTORE_DIAG:', source)
        # The actual content URI, UI XML, full logcat and signed source
        # must remain private even when a restored task fails.
        self.assertIn('sender_replayed=repeated-admission', source)
        self.assertIn('"$out/document-restored-diagnostic.txt"', source)

    def window_witness(self, windows, activities):
        source = (ROOT / "tools/installed_external_document_process_loss.sh").read_text()
        parser = source.split("<<'PY_FIRST_VIEW'\n", 1)[1].split("\nPY_FIRST_VIEW\n", 1)[0]
        with tempfile.TemporaryDirectory() as scratch:
            win = Path(scratch) / "windows"
            act = Path(scratch) / "activities"
            win.write_text(windows)
            act.write_text(activities)
            result = subprocess.run([sys.executable, "-c", parser, str(win), str(act),
                                     "io.github.gamesbyian.chunks"],
                                    capture_output=True, text=True, check=True)
        return dict(field.split("=", 1) for field in result.stdout.strip().split())

    def test_window_witness_distinguishes_exact_surface_and_activity(self):
        windows = (
            "  Window #0 Window{a u0 io.github.gamesbyian.chunks.test/.MainActivity}:\n"
            "    mHasSurface=true mDrawState=HAS_DRAWN mViewVisibility=0x0\n"
            "  Window #1 Window{b u0 io.github.gamesbyian.chunks/.MainActivity}:\n"
            "    mHasSurface=false mDrawState=DRAW_PENDING mViewVisibility=0x8\n"
            "    mObscured=true secret=private-document-uri\n"
            "  Window #2 Window{c u0 com.android.launcher/.Launcher}:\n"
            "    mHasSurface=true mDrawState=HAS_DRAWN\n"
        )
        activities = (
            "  * Hist #0: ActivityRecord{a u0 io.github.gamesbyian.chunks/.MainActivity t42}\n"
            "    state=RESUMED mVisibleRequested=true mClientVisible=false\n"
            "  * Hist #1: ActivityRecord{b u0 other.app/.Other t43}\n"
            "    state=STOPPED mVisibleRequested=false mClientVisible=true\n"
        )
        observed = self.window_witness(windows, activities)
        self.assertEqual({
            "window_count": "1", "has_surface": "false", "draw_state": "draw_pending",
            "view_visibility": "0x8", "obscured": "true", "activity_count": "1",
            "activity_state": "resumed", "visible_requested": "true",
            "client_visible": "false",
        }, observed)

    def test_window_witness_refuses_ambiguous_or_missing_fields(self):
        window = (
            "  Window #1 Window{b u0 io.github.gamesbyian.chunks/.MainActivity}:\n"
            "    mHasSurface=true mHasSurface=false mDrawState=private-document-uri\n"
        )
        observed = self.window_witness(window, "")
        self.assertEqual("1", observed["window_count"])
        self.assertEqual("unknown", observed["has_surface"])
        self.assertEqual("unknown", observed["draw_state"])
        self.assertEqual("0", observed["activity_count"])
        self.assertEqual("unknown", observed["activity_state"])
        observed = self.window_witness(window + window.replace("#1", "#2"), "")
        self.assertEqual("2", observed["window_count"])
        self.assertEqual("unknown", observed["has_surface"])
        observed = self.window_witness(window.replace("chunks/", "chunks.test/"), "")
        self.assertEqual("0", observed["window_count"])

    def test_window_witness_is_failure_only_and_precedes_diagnostic_focus(self):
        source = (ROOT / "tools/installed_external_document_process_loss.sh").read_text()
        start = source.index("diagnose_missing_source_review() {")
        witness = source.index("CHUNKS_DOCUMENT_WINDOW_DIAG:")
        focus = source.index('adb shell am task focus "$exact_task"', witness)
        self.assertLess(start, witness)
        self.assertLess(witness, focus)
        self.assertIn('if ! await_source_review; then\n  diagnose_missing_source_review\n  fail ', source)
        self.assertIn('tee "$out/document-window-diagnostic.txt"', source)

    def test_sender_retains_real_foreign_grant_and_bounded_task_flag(self):
        sender = (ROOT / "app/src/androidTest/java/io/github/gamesbyian/chunks/external/ExternalSourceSenderActivity.java").read_text()
        self.assertIn('Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION', sender)
        self.assertIn('getBooleanExtra("fixture_keep_task", false)', sender)
        self.assertIn('handoff.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);', sender)
        self.assertIn('handoff.setClipData(ClipData.newUri(', sender)
        self.assertIn('startActivity(handoff);', sender)


if __name__ == "__main__":
    unittest.main()
