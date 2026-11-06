"""Offline authorization negatives, never live provider acceptance."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class InstalledRealMediaGateTest(unittest.TestCase):
    def refuse(self, package, **overrides):
        with tempfile.TemporaryDirectory() as directory:
            sentinel = Path(directory) / "adb-called"
            adb = Path(directory) / "adb"
            adb.write_text('#!/bin/sh\ntouch "$SENTINEL"\nexit 99\n')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=directory + ":" + os.environ["PATH"],
                       SENTINEL=str(sentinel), CHUNKS_REAL_MEDIA="1",
                       GITHUB_EVENT_NAME="workflow_dispatch")
            env.update(overrides)
            result = subprocess.run(["bash", str(ROOT / "tools/installed_real_media.sh"), package],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(sentinel.exists(), "Refusal must precede any device operation")

    def test_no_live_network_without_explicit_opt_in(self):
        self.refuse("io.github.gamesbyian.chunks.magnet0123456789ab", CHUNKS_REAL_MEDIA="0")

    def test_pr_cannot_authorize_provider_requests(self):
        self.refuse("io.github.gamesbyian.chunks.magnet0123456789ab", GITHUB_EVENT_NAME="pull_request")

    def test_ordinary_installed_app_cannot_be_used(self):
        self.refuse("io.github.gamesbyian.chunks")

    def test_package_must_be_source_identified(self):
        self.refuse("io.github.gamesbyian.chunks.magnetunknown")


if __name__ == "__main__":
    unittest.main()
