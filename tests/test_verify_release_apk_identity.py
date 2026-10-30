"""Offline release provenance negative controls, requiring no SDK or keystore."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from verify_release_apk_identity import main, verify_badging

GOOD = ("package: name='io.github.gamesbyian.chunks' versionCode='123' "
        "versionName='0.2.0-alpha.1' compileSdkVersion='37'\\n"
        "sdkVersion:'29'\\n")


class ReleasePackageIdentityTest(unittest.TestCase):
    def test_valid_badging(self):
        verify_badging(GOOD, "0.2.0-alpha.1", "123")

    def test_missing_duplicate_and_conflicting_package(self):
        for bad in ("", "sdkVersion:'29'\\n", GOOD + GOOD,
                    GOOD.replace("io.github.gamesbyian.chunks", "evil.example"),
                    GOOD.replace("versionCode='123'", "versionCode='124'"),
                    GOOD.replace("versionName='0.2.0-alpha.1'", "versionName='0.1.0'"),
                    GOOD.replace("versionCode='123'", "versionCode='123' versionCode='123'"),
                    GOOD.replace("versionCode='123'", "")):
            with self.subTest(bad=bad[:75]), self.assertRaises(ValueError):
                verify_badging(bad, "0.2.0-alpha.1", "123")

    def test_failed_aapt_with_plausible_output_cannot_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = Path(folder) / "aapt"
            binary.write_text("#!/bin/sh\\ncat <<'BADGING'\\n" + GOOD + "BADGING\\nexit 7\\n")
            binary.chmod(0o700)
            args = ["--aapt", str(binary), "--apk", str(Path(folder) / "test.apk"),
                    "--version-name", "0.2.0-alpha.1", "--version-code", "123"]
            self.assertEqual(1, main(args))
            binary.write_text("#!/bin/sh\\ncat <<'BADGING'\\n" + GOOD + "BADGING\\n")
            self.assertEqual(0, main(args))


if __name__ == "__main__":
    unittest.main()
