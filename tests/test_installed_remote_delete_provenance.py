"""Offline failure-path proof; real deletion remains an installed integration gate."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent


class RemoteDeleteProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "run"
        self.run.mkdir()
        (self.run / "chunks-remote-delete-hash.txt").write_text("a" * 40)
        self.out = self.run / "chunks-remote-delete-evidence"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        tools = self.root / "tools"
        tools.mkdir()
        shutil.copy(ROOT / "tools/verify_installed_apk_bytes.sh", tools)
        self.candidate = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.candidate.parent.mkdir(parents=True)
        self.candidate.write_bytes(b"disposable-app-candidate")
        adb = self.bin / "adb"
        adb.write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
a = sys.argv[1:]
root = pathlib.Path(os.environ['FIXTURE_ROOT'])
if a[:3] == ['shell', 'dumpsys', 'package']:
    print('    versionName=0.1.0-dev+' + os.environ['GITHUB_SHA'][:12])
elif a[:3] == ['shell', 'pm', 'path']:
    print('package:/private-fixture/base.apk')
elif a[0] == 'pull':
    target = pathlib.Path(a[2])
    if os.environ['PULL_MODE'] == 'matched':
        shutil.copy(root / 'app/build/outputs/apk/debug/app-debug.apk', target)
    else:
        target.write_bytes(b'different-installed-app')
elif a[:3] == ['shell', 'am', 'instrument']:
    (root / 'instrumentation-started').touch()
    print('INSTRUMENTATION_CODE: 0')
    sys.exit(1)
''')
        adb.chmod(0o755)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"], check=True)
        self.source = self.git("HEAD")
        self.tree = self.git("HEAD^{tree}")

    def git(self, ref):
        return subprocess.check_output(["git", "-C", str(self.root), "rev-parse", ref], text=True).strip()

    def invoke(self, mode):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   RUNNER_TEMP=str(self.run), GITHUB_SHA=self.source, CHUNKS_SOURCE_SHA=self.source,
                   FIXTURE_ROOT=str(self.root), PULL_MODE=mode, CHUNKS_ADB_PULL_RETRY_DELAY="0")
        result = subprocess.run(["bash", str(ROOT / "tools/installed_remote_delete_data.sh")],
                                cwd=self.root, env=env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(str(self.root), result.stdout + result.stderr)
        self.assertNotIn("/private-fixture/base.apk", result.stdout + result.stderr)
        self.assertFalse((self.out / "installed-pull-tmp.apk").exists())
        return result

    def test_later_journey_failure_retains_actual_installed_source_and_bytes(self):
        self.invoke("matched")
        self.assertTrue((self.root / "instrumentation-started").exists())
        receipt = json.loads((self.out / "APK-IDENTITY.json").read_text())
        self.assertEqual(self.source, receipt["source_sha"])
        self.assertEqual(self.tree, receipt["git_tree_sha"])
        self.assertEqual("io.github.gamesbyian.chunks", receipt["application_id"])
        self.assertEqual("0.1.0-dev+" + self.source[:12], receipt["version_name"])
        self.assertEqual(self.candidate.stat().st_size, receipt["installed_apk_size_bytes"])
        self.assertEqual(hashlib.sha256(self.candidate.read_bytes()).hexdigest(), receipt["installed_apk_sha256"])
        self.assertIs(True, receipt["installed_equals_built_bytes"])

    def test_mismatched_installed_app_prevents_the_removal_journey(self):
        result = self.invoke("altered")
        self.assertIn("CHUNKS_INSTALLED_APK: byte-mismatch", result.stderr)
        self.assertFalse((self.root / "instrumentation-started").exists())
        self.assertFalse((self.out / "APK-IDENTITY.json").exists())


if __name__ == "__main__":
    unittest.main()
