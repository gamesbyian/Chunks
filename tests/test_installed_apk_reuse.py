"""Offline failure-boundary checks for the exact-source APK reuse pilot."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/reuse_installed_apks.sh"


class InstalledApkReuseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit",
                        "--allow-empty", "-qm", "fixture"], check=True)
        self.sha = self.git("HEAD")
        self.tree = self.git("HEAD^{tree}")
        self.temp = self.root / "runner"
        self.artifact = self.temp / "shared-installed-apks"
        self.artifact.mkdir(parents=True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text('#!/bin/bash\ncat "$RUN_METADATA"\n')
        gh.chmod(0o755)
        self.metadata = self.root / "run.json"
        self.run_data = dict(head_repository=dict(full_name="fixture/repo"),
                             head_sha=self.sha, event="workflow_dispatch",
                             path=".github/workflows/installed-protocol-client.yml",
                             status="completed", conclusion="success")
        self.metadata.write_text(json.dumps(self.run_data))
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        APK_RUN_ID="123", GITHUB_SHA=self.sha,
                        GITHUB_REPOSITORY="fixture/repo", RUNNER_TEMP=str(self.temp),
                        RUN_METADATA=str(self.metadata))
        self.app = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.test = self.root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        (self.artifact / "source-sha").write_text(self.sha + "\n")
        (self.artifact / "source-tree").write_text(self.tree + "\n")
        (self.artifact / "app.apk").write_bytes(b"app")
        (self.artifact / "test.apk").write_bytes(b"test")
        (self.artifact / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256((self.artifact / name).read_bytes()).hexdigest()}  {name}\n"
            for name in ("app.apk", "test.apk")))

    def git(self, ref):
        return subprocess.check_output(["git", "-C", str(self.root), "rev-parse", ref],
                                       text=True).strip()

    def invoke(self, mode, **env):
        return subprocess.run(["bash", str(SCRIPT), mode], cwd=self.root,
                              env=dict(self.env, **env), capture_output=True,
                              text=True, timeout=10)

    def reject(self, mode, **env):
        result = self.invoke(mode, **env)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.app.exists())
        self.assertFalse(self.test.exists())

    def test_exact_run_and_pair(self):
        for mode in ("verify-run", "stage"):
            result = self.invoke(mode)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.app.read_bytes(), b"app")
        self.assertEqual(self.test.read_bytes(), b"test")

    def test_rejects_wrong_run_metadata(self):
        for key, value in (("head_sha", "0" * 40), ("event", "pull_request"),
                           ("head_repository", {"full_name": "other/repo"}),
                           ("path", ".github/workflows/other.yml"),
                           ("status", "in_progress"), ("conclusion", "failure")):
            with self.subTest(key=key):
                self.metadata.write_text(json.dumps(dict(self.run_data, **{key: value})))
                self.reject("verify-run")

    def test_invalid_id_and_checkout(self):
        self.reject("verify-run", APK_RUN_ID="123/other")
        self.reject("stage", GITHUB_SHA="0" * 40)

    def test_wrong_tree_and_source(self):
        for name, original in (("source-sha", self.sha), ("source-tree", self.tree)):
            with self.subTest(name=name):
                path = self.artifact / name
                path.write_text("0" * 40)
                self.reject("stage")
                path.write_text(original + "\n")

    def test_changed_bytes(self):
        for name in ("app.apk", "test.apk"):
            with self.subTest(name=name):
                path = self.artifact / name
                previous = path.read_bytes()
                path.write_bytes(b"changed")
                self.reject("stage")
                path.write_bytes(previous)

    def test_missing_and_unexpected_manifest_member(self):
        path = self.artifact / "SHA256SUMS"
        original = path.read_text()
        path.write_text(original.splitlines()[0] + "\n")
        self.reject("stage")
        path.write_text(original + original.splitlines()[0] + "\n")
        self.reject("stage")
        path.write_text(original)
        (self.artifact / "test.apk").unlink()
        self.reject("stage")


if __name__ == "__main__":
    unittest.main()
