"""Negative/positive source guards for non-shipping owned LocalTorrentEngine overlay."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "stage_owned_localengine_overlay", ROOT / "tools/stage_owned_localengine_overlay.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

ENGINE_RELATIVE = Path(
    "app/src/main/java/io/github/gamesbyian/chunks/engine/LocalTorrentEngine.kt")
FAKE_ENGINE = """package io.github.gamesbyian.chunks.engine

import java.io.File
enum class LocalDiskIoMode { DEFAULT, POSIX }
data class LocalEngineConfig(val stateDirectory: File? = null)
class LocalTorrentEngine(private val config: LocalEngineConfig) {
    private fun start() {
        val resumeFiles = config.stateDirectory?.let(LocalResumeDirectoryInventory::prepare)
        if (config.diskIoMode == LocalDiskIoMode.POSIX) params.setPosixDiskIO()
    }
}
"""


class OwnedLocalEngineOverlayTests(unittest.TestCase):
    def make_fixture(self, root):
        checkout = root / "checkedout"
        src = checkout / ENGINE_RELATIVE
        src.parent.mkdir(parents=True)
        src.write_text(FAKE_ENGINE)
        out = root / "isolated" / "LocalTorrentEngine.preview.kt"
        return checkout, src, out

    def test_stage_is_external_and_immutable(self):
        with tempfile.TemporaryDirectory() as raw:
            checkout, src, out = self.make_fixture(Path(raw))
            module.stage(src, out)
            body = out.read_text()
            self.assertIn("OWNED_TEST_ONLY", body)
            self.assertIn("params.setOwnedDiskIO(receipts.absolutePath)", body)
            self.assertIn("NON-SHIPPING", body)
            self.assertEqual(FAKE_ENGINE, src.read_text())
            manifest = json.loads(out.with_suffix(".json").read_text())
            self.assertEqual(module.sha256(out.read_bytes()), manifest["patched_sha256"])
            self.assertEqual(module.sha256(src.read_bytes()), manifest["source_sha256"])
            with self.assertRaises(ValueError):
                module.stage(src, out)

    def test_arbitrary_paths_and_in_repo_writes_are_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            checkout, src, out = self.make_fixture(Path(raw))
            with self.assertRaises(ValueError):
                module.stage(src, checkout / "tools" / "untrusted.kt")
            bad = Path(raw) / "FakeEngine.kt"
            bad.write_text(FAKE_ENGINE)
            with self.assertRaises(ValueError):
                module.stage(bad, out)
            self.assertFalse(out.exists())

    def test_changed_or_compromised_source_seams_are_refused(self):
        with self.assertRaises(ValueError):
            module.render_engine(FAKE_ENGINE.replace("params.setPosixDiskIO()",
                                                      "params.setDefaultDiskIO()"))
        with self.assertRaises(ValueError):
            module.render_engine(FAKE_ENGINE.replace(
                "enum class LocalDiskIoMode { DEFAULT, POSIX }",
                "enum class LocalDiskIoMode { DEFAULT, POSIX, OWNED_TEST_ONLY }"))
        with self.assertRaises(ValueError):
            module.render_engine(FAKE_ENGINE.replace(
                "LocalResumeDirectoryInventory::prepare", "readEverythingSilently"))
        with self.assertRaises(ValueError):
            module.render_engine(FAKE_ENGINE.replace(
                "package io.github.gamesbyian.chunks.engine", "package not.chunks"))


if __name__ == "__main__":
    unittest.main()
