"""Offline negative controls for the exact installed-APK byte gate.

Fake adb is confined to a private temporary directory. No Android emulator,
protected server, token, downloaded artifact or external network is used.
"""
import os
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parent.parent / "tools/verify_installed_apk_bytes.sh"


class InstalledApkIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.expected = self.root / "candidate.apk"
        self.expected.write_bytes(b"controlled-candidate-apk")
        self.pulled = self.root / "pulled.apk"
        adb = self.bin / "adb"
        adb.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            '[[ "$#" == 3 && "$1" == pull && "$2" == /fixture/base.apk ]] || exit 38\n'
            'case "$CHUNKS_FAKE_ADB" in\n'
            '  matched) cp "$CHUNKS_EXPECTED" "$3" ;;\n'
            '  altered) printf "different-installed-bytes" > "$3" ;;\n'
            '  unavailable) exit 39 ;;\n'
            '  *) exit 40 ;;\n'
            "esac\n",
            encoding="utf-8",
        )
        adb.chmod(0o755)

    def invoke(self, mode="matched", expected=None, pulled=None):
        env = dict(os.environ)
        env["PATH"] = str(self.bin) + os.pathsep + env.get("PATH", "")
        env["CHUNKS_FAKE_ADB"] = mode
        env["CHUNKS_EXPECTED"] = str(self.expected)
        result = subprocess.run(
            ["bash", str(SCRIPT), str(expected or self.expected),
             "/fixture/base.apk", str(pulled or self.pulled)],
            env=env, capture_output=True, text=True, check=False, timeout=10,
        )
        if result.returncode != 0:
            self.assertEqual("", result.stdout)
        self.assertNotIn(str(self.root), result.stdout)
        self.assertNotIn("/fixture/base.apk", result.stdout)
        self.assertNotIn(str(self.root), result.stderr)
        self.assertNotIn("/fixture/base.apk", result.stderr)
        self.assertFalse(self.pulled.exists(), "pulled candidate must be removed")
        return result

    def test_matching_installed_bytes_pass_without_extra_output(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertEqual(
            "CHUNKS_INSTALLED_APK: matched size_bytes=24 sha256=" +
            hashlib.sha256(b"controlled-candidate-apk").hexdigest() + "\n",
            result.stdout,
        )

    def test_unavailable_adb_pull_is_a_specific_fail_closed_preflight(self):
        result = self.invoke("unavailable")
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: adb-pull-failed\n", result.stderr)

    def test_changed_installed_bytes_are_never_accepted(self):
        result = self.invoke("altered")
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: byte-mismatch\n", result.stderr)

    def test_broken_cmp_success_cannot_hide_different_installed_bytes(self):
        # Even a malfunctioning comparison must not conceal the independent
        # pulled-file SHA mismatch or create a success receipt.
        cmp = self.bin / "cmp"
        cmp.write_text("#!/usr/bin/env bash\\nexit 0\\n", encoding="utf-8")
        cmp.chmod(0o755)
        result = self.invoke("altered")
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: byte-mismatch\\n", result.stderr)

    def test_comparison_error_is_not_reported_as_verified_identity(self):
        cmp = self.bin / "cmp"
        cmp.write_text("#!/usr/bin/env bash\nexit 2\n", encoding="utf-8")
        cmp.chmod(0o755)
        result = self.invoke()
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: comparison-unavailable\n", result.stderr)

    def test_absent_expected_apk_fails_before_device_pull(self):
        self.expected.unlink()
        result = self.invoke()
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: missing-or-invalid-input\n", result.stderr)

    def test_cannot_reuse_the_expected_file_as_pull_destination(self):
        result = self.invoke(pulled=self.expected)
        self.assertEqual(1, result.returncode)
        self.assertEqual("CHUNKS_INSTALLED_APK: missing-or-invalid-input\n", result.stderr)
        self.assertEqual(b"controlled-candidate-apk", self.expected.read_bytes())


if __name__ == "__main__":
    unittest.main()
