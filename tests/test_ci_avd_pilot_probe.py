"""Offline, fail-closed controls for the manual clean AVD pilot."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "tools/ci_avd_pilot_probe.sh"
PILOT = ROOT / ".github/workflows/avd-snapshot-pilot.yml"
DOC_CI = ROOT / ".github/workflows/documentation-validation.yml"


class CleanAvdProbeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        fake_adb = self.directory / "adb"
        fake_adb.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "query = ' '.join(sys.argv[1:])\n"
            "if query == os.getenv('FAKE_FAIL_QUERY'): sys.exit(97)\n"
            "responses = {\n"
            "  'shell getprop ro.build.version.sdk': os.getenv('FAKE_SDK', '35'),\n"
            "  'shell getprop ro.product.cpu.abi': os.getenv('FAKE_ABI', 'x86_64'),\n"
            "  'shell getprop sys.boot_completed': os.getenv('FAKE_BOOT', '1'),\n"
            "  'shell pm list packages': os.getenv('FAKE_PACKAGES', 'package:android'),\n"
            "}\n"
            "if query not in responses: sys.exit(96)\n"
            "print(responses[query])\n",
            encoding="utf-8",
        )
        fake_adb.chmod(0o755)
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.directory) + os.pathsep + self.env["PATH"]

    def probe(self, phase="measure", **variables):
        env = self.env.copy()
        env.update(variables)
        return subprocess.run(
            ["bash", str(PROBE), phase], env=env, cwd=ROOT,
            capture_output=True, text=True, check=False,
        )

    def test_valid_clean_pristine_and_measure_probes(self):
        for phase in ("pristine", "measure"):
            with self.subTest(phase=phase):
                result = self.probe(phase)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f"phase={phase}", result.stdout)
                self.assertIn("clean=verified", result.stdout)
                self.assertIn("tests=none", result.stdout)

    def test_reject_unknown_or_missing_phase(self):
        self.assertNotEqual(0, self.probe("other").returncode)
        self.assertNotEqual(0, subprocess.run(
            ["bash", str(PROBE)], env=self.env,
            capture_output=True, check=False,
        ).returncode)

    def test_refuse_wrong_sdk_abi_or_unfinished_boot(self):
        for value in (
            {"FAKE_SDK": "34"},
            {"FAKE_ABI": "arm64-v8a"},
            {"FAKE_BOOT": "0"},
        ):
            with self.subTest(value=value):
                self.assertNotEqual(0, self.probe(**value).returncode)

    def test_refuse_installed_chunks_exact_identity(self):
        result = self.probe(FAKE_PACKAGES=(
            "package:android\npackage:io.github.gamesbyian.chunks"
        ))
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("clean=verified", result.stdout)

    def test_similarly_named_package_is_not_the_target(self):
        result = self.probe(FAKE_PACKAGES=(
            "package:io.github.gamesbyian.chunks.other"
        ))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_reject_crlf_installed_package(self):
        result = self.probe(FAKE_PACKAGES=(
            "package:android\r\npackage:io.github.gamesbyian.chunks\r"
        ))
        self.assertNotEqual(0, result.returncode)

    def test_reject_empty_or_unstructured_package_inventory(self):
        for inventory in ("", "error: unauthorized"):
            with self.subTest(inventory=inventory):
                result = self.probe(FAKE_PACKAGES=inventory)
                self.assertNotEqual(0, result.returncode)

    def test_fail_closed_on_adb_read_errors(self):
        for command in (
            "shell getprop ro.build.version.sdk",
            "shell getprop ro.product.cpu.abi",
            "shell getprop sys.boot_completed",
            "shell pm list packages",
        ):
            with self.subTest(command=command):
                result = self.probe(FAKE_FAIL_QUERY=command)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("clean=verified", result.stdout)

    def test_ci_selection_and_coverage_contract(self):
        source = PILOT.read_text(encoding="utf-8")
        docs = DOC_CI.read_text(encoding="utf-8")
        self.assertIn("on:\n  workflow_dispatch:\n", source)
        self.assertNotIn("\n  push:", source)
        self.assertNotIn("\n  pull_request:", source)
        self.assertIn("if: github.ref == 'refs/heads/main'", source)
        self.assertEqual(1, source.count("script: bash tools/ci_avd_pilot_probe.sh pristine"))
        self.assertEqual(1, source.count("script: bash tools/ci_avd_pilot_probe.sh measure"))
        self.assertNotIn("script: |\n", source)
        self.assertIn("if: inputs.mode == 'snapshot'\n        id: android-sdk", source)
        self.assertLess(
            source.index("Fail early if the cache quota has insufficient headroom"),
            source.index("Install Android 35 image and derive versioned cache identity"),
        )
        self.assertIn("if (( used >= 7000000000 ))", source)
        self.assertIn("if (( prospective > 9000000000 ))", source)
        self.assertIn("actions: read", source)
        for path in (
            ".github/workflows/avd-snapshot-pilot.yml",
            "tools/ci_avd_pilot_probe.sh",
            "tests/test_ci_avd_pilot_probe.py",
        ):
            self.assertEqual(2, docs.count(f"      - '{path}'"))
        self.assertIn(
            "python3 -m unittest discover -s tests -p test_ci_avd_pilot_probe.py",
            docs,
        )


if __name__ == "__main__":
    unittest.main()
