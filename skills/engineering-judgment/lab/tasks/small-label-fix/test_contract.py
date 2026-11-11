"""Focused positive and regression checks for a trivial change."""
import unittest
from candidate import status_label

class Tests(unittest.TestCase):
    def test_requested_copy(self):
        self.assertEqual(status_label("done"), "Complete")
    def test_other_existing_labels_unchanged(self):
        self.assertEqual(status_label("queued"), "Queued")
        self.assertEqual(status_label("working"), "Working")
        self.assertEqual(status_label("other"), "Unknown")

if __name__ == "__main__":
    unittest.main()
