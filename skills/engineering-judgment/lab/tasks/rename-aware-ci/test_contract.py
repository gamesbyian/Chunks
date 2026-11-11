"""Dispatch-graph examples, including rename-out and unknown scope."""
import unittest
from candidate import requires_android_checks

class Tests(unittest.TestCase):
    def test_docs_only_is_not_android(self):
        self.assertFalse(requires_android_checks([{"path": "docs/readme.md", "status": "modified"}]))
    def test_native_file_edit(self):
        self.assertTrue(requires_android_checks([{"path": "android/app/Main.kt", "status": "modified"}]))
    def test_rename_out_of_native_directory(self):
        self.assertTrue(requires_android_checks([{"path": "docs/Main.kt", "previous_path": "android/app/Main.kt", "status": "renamed"}]))
    def test_unknown_rename_metadata_fails_closed(self):
        self.assertTrue(requires_android_checks([{"path": "docs/Main.kt", "status": "renamed"}]))
    def test_missing_page_or_unreadable_change_fails_closed(self):
        self.assertTrue(requires_android_checks(None))
        self.assertTrue(requires_android_checks([None]))

if __name__ == "__main__":
    unittest.main()
