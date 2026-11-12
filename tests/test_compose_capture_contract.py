"""Offline negative controls for installed screenshot producer/consumer parity."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from check_compose_capture_contract import ContractError, PREFIX, capture_names, preflight, verify_dir, verify_wiring

SAMPLE = '''private fun savePixels(name: String) {}
@Test fun journey() {
    savePixels("transfers-before-connection")
    savePixels("connections-return")
}'''
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00" * 12


class TestCaptureContract(unittest.TestCase):
    def test_actual_producer_and_consumer_connected(self):
        self.assertGreaterEqual(len(preflight()), 2)

    def test_source_owns_current_names(self):
        self.assertEqual(capture_names(SAMPLE), ("transfers-before-connection", "connections-return"))

    def test_rejects_dynamic_and_duplicate_producers(self):
        with self.assertRaisesRegex(ContractError, "Dynamic"):
            capture_names(SAMPLE + "\nsavePixels(unknownName)")
        with self.assertRaisesRegex(ContractError, "Duplicate"):
            capture_names(SAMPLE + '\nsavePixels("connections-return")')

    def test_rejects_retired_and_missing_screens(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            (folder / (PREFIX + "places.png")).write_bytes(PNG)
            with self.assertRaisesRegex(ContractError, "Artifact names mismatch"):
                verify_dir(folder, ("connections-return",))

    def test_rejects_producer_collector_path_drift(self):
        source = 'put(MediaStore.Images.Media.DISPLAY_NAME, "chunks-compose-200pct-$name.png")\n' \
                 'Environment.DIRECTORY_PICTURES + "/ChunksCompose200Pct"'
        runner = 'adb pull /sdcard/Pictures/ChunksCompose200Pct/. "$dest"\n' \
                 'python3 tools/check_compose_capture_contract.py --verify-dir "$dest"'
        verify_wiring(source, runner)
        with self.assertRaisesRegex(ContractError, "producer filename"):
            verify_wiring(source.replace("chunks-compose-200pct-", "obsolete-"), runner)
        with self.assertRaisesRegex(ContractError, "collector directory"):
            verify_wiring(source, runner.replace("ChunksCompose200Pct", "OldScreens"))

    def test_preflight_runs_before_emulator_setup(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/compose-system-font-200pct.yml").read_text()
        self.assertLess(
            workflow.index("check_compose_capture_contract.py --preflight"),
            workflow.index("actions/setup-java@"),
        )

    def test_rejects_corrupt_png_and_accepts_current_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            target = folder / (PREFIX + "connections-return.png")
            target.write_bytes(b"not a png")
            with self.assertRaisesRegex(ContractError, "Bad PNG"):
                verify_dir(folder, ("connections-return",))
            target.write_bytes(PNG)
            verify_dir(folder, ("connections-return",))


if __name__ == "__main__":
    unittest.main()
