"""Public automatic artwork remains manual and explicitly authorized before any ADB access."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PublicAutoMediaAuthorizationTest(unittest.TestCase):
    def refuse(self, authorization, event):
        with tempfile.TemporaryDirectory() as directory:
            sentinel = Path(directory) / "adb-called"
            adb = Path(directory) / "adb"
            adb.write_text('#!/bin/sh\ntouch "$SENTINEL"\nexit 99\n')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=directory + ":" + os.environ["PATH"],
                       SENTINEL=str(sentinel), CHUNKS_PUBLIC_MEDIA_AUTHORIZED=authorization,
                       GITHUB_EVENT_NAME=event)
            result = subprocess.run(["bash", str(ROOT / "tools/installed_public_auto_media.sh")],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(sentinel.exists(), "Authorization refusal must precede any ADB call")

    def test_manual_event_still_requires_explicit_authorization(self):
        self.refuse("", "workflow_dispatch")

    def test_pr_cannot_authorize_public_requests(self):
        self.refuse("bounded-public-tvmaze", "pull_request")

    def test_push_cannot_authorize_public_requests(self):
        self.refuse("bounded-public-tvmaze", "push")

    def test_historic_one_shot_authorization_is_not_a_new_manual_grant(self):
        self.refuse("one-shot-20261110", "workflow_dispatch")
