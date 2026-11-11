"""Mutation tests for fail-closed skill validation."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("check_skill", root / "scripts" / "check_skill.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class Checks(unittest.TestCase):
    def test_real_skill_valid(self):
        self.assertEqual([], mod.validate(root))

    def test_missing_reference_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            temp = Path(d) / "skill"
            shutil.copytree(root, temp, ignore=shutil.ignore_patterns("__pycache__"))
            (temp / "references" / "platform-playbooks.md").unlink()
            self.assertTrue(any("Broken/outside local link" in e for e in mod.validate(temp)))

    def test_duplicate_case_id_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            temp = Path(d) / "skill"
            shutil.copytree(root, temp, ignore=shutil.ignore_patterns("__pycache__"))
            p = temp / "references" / "historical-casebook.md"
            p.write_text(p.read_text() + "\n| CH-01 | https://github.com/example | duplicate |\n")
            self.assertTrue(any("unique" in e for e in mod.validate(temp)))

    def test_missing_required_frontmatter_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            temp = Path(d) / "skill"
            shutil.copytree(root, temp, ignore=shutil.ignore_patterns("__pycache__"))
            p = temp / "SKILL.md"
            p.write_text(p.read_text().replace("name: android-app-development", "name: INVALID_SKILL"))
            self.assertTrue(any("skill name" in e for e in mod.validate(temp)))


if __name__ == "__main__":
    unittest.main()
