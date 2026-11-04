"""Offline negative tests: no private target paths or secrets."""
import unittest
from seedhost_p0_directory_probe import CANDIDATE_PARENT, acceptable

class NumberedDirectorySelectionTest(unittest.TestCase):
    def test_only_exact_accessible_path_is_authorized(self):
        self.assertTrue(acceptable({"path": CANDIDATE_PARENT + "/",
            "directories": [], "files": []}))

    def test_picker_fallback_to_home_root_is_refused(self):
        for path in ("/", "/home20/ianseeds", "/tmp",
                     CANDIDATE_PARENT + "/other", CANDIDATE_PARENT + "/../other",
                     "../downloads", CANDIDATE_PARENT + "/./"):
            with self.subTest(path=path):
                self.assertFalse(acceptable({"path": path,
                    "directories": [], "files": []}))

    def test_html_and_incomplete_json_are_never_directories(self):
        for value in (None, [], "login", {"path": CANDIDATE_PARENT},
                      {"path": CANDIDATE_PARENT, "directories": [], "files": {},
                       "redirect": "other"}):
            with self.subTest(value=value):
                self.assertFalse(acceptable(value))

    def test_wrong_path_or_bogus_inventory_shape_refused(self):
        self.assertFalse(acceptable({"path": CANDIDATE_PARENT,
            "directories": "not-a-list", "files": []}))
        self.assertFalse(acceptable({"path": CANDIDATE_PARENT,
            "directories": [], "files": ["x"] * 10001}))

if __name__ == "__main__":
    unittest.main()
