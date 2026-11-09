"""Fail-closed Pixel Launcher-only atlas recovery selector contracts."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from native_atlas_launcher_anr import close_point


def fixture(title="Pixel Launcher isn't responding", title_package="android",
            button_package="android", button_text="Close app",
            button_id="android:id/aerr_close", bounds="[108,1137][960,1192]",
            title_id="android:id/alertTitle", button_clickable="true"):
    return ('<hierarchy rotation="0"><node package="android">'
            f'<node package="{title_package}" resource-id="{title_id}" text="{title}"/>'
            f'<node package="{button_package}" resource-id="{button_id}" text="{button_text}" '
            f'clickable="{button_clickable}" bounds="{bounds}"/>'
            '</node></hierarchy>')


class LauncherAnrSelectorTest(unittest.TestCase):
    def test_only_true_pixel_launcher_system_anr_is_dismissible(self):
        self.assertEqual((534, 1164), close_point(fixture()))

    def test_other_apps_and_custom_dialogs_are_not_dismissed(self):
        for altered in (dict(title="Chunks isn't responding"),
                        dict(title="System UI isn't responding"),
                        dict(title_package="io.github.gamesbyian.chunks"),
                        dict(button_package="io.github.gamesbyian.chunks"),
                        dict(title_id=""), dict(button_id=""),
                        dict(button_text="Wait"), dict(button_clickable="false")):
            with self.subTest(altered=altered):
                self.assertIsNone(close_point(fixture(**altered)))

    def test_missing_malformed_duplicate_and_oversized_xml_refused(self):
        self.assertIsNone(close_point("<hierarchy><node"))
        self.assertIsNone(close_point(""))
        self.assertIsNone(close_point(fixture().replace("</hierarchy>",
            '<node package="android" resource-id="android:id/aerr_close" text="Close app" '
            'clickable="true" bounds="[1,1][55,55]"/></hierarchy>')))
        self.assertIsNone(close_point(fixture() + " " * 262145))

    def test_bad_bounds_never_choose_an_arbitrary_tap(self):
        for bounds in ("[0,0][0,0]", "[1,1][9,10]", "[9000,1][9045,100]",
                       "[-1,0][100,100]", "[400,100][100,200]", "junk"):
            with self.subTest(bounds=bounds):
                self.assertIsNone(close_point(fixture(bounds=bounds)))


if __name__ == "__main__":
    unittest.main()
