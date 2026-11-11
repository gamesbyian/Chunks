"""Checks final visibility, preservation and exception/crash-model behavior."""
import tempfile
import unittest
from pathlib import Path
from candidate import publish_original

class Tests(unittest.TestCase):
    def test_positive_writes_complete_object(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "stable.bin"
            result = publish_original(iter([b"abc", b"def"]), target)
            self.assertEqual(result, target)
            self.assertEqual(target.read_bytes(), b"abcdef")
            self.assertEqual(sorted(x.name for x in Path(temp).iterdir()), ["stable.bin"])
    def test_interruption_does_not_publish_partial_final_object(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "stable.bin"
            def interrupted():
                yield b"partial"
                raise RuntimeError("simulated stream interruption")
            with self.assertRaises(RuntimeError):
                publish_original(interrupted(), target)
            self.assertFalse(target.exists(), "partial immutable final must not be exposed")
            self.assertEqual(list(Path(temp).iterdir()), [], "own staging should be cleaned")
    def test_existing_original_and_other_file_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "stable.bin"
            target.write_bytes(b"immutable-old")
            other = Path(temp) / "unowned.bin"
            other.write_bytes(b"keep-me")
            with self.assertRaises(FileExistsError):
                publish_original(iter([b"new"]), target)
            self.assertEqual(target.read_bytes(), b"immutable-old")
            self.assertEqual(other.read_bytes(), b"keep-me")
            self.assertEqual(sorted(x.name for x in Path(temp).iterdir()), ["stable.bin", "unowned.bin"])

if __name__ == "__main__":
    unittest.main()
