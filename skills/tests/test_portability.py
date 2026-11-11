"""Focused regression controls for portable skill packages."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check_portability.py"
SPEC = importlib.util.spec_from_file_location("portable_checker", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PortabilityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "another-project" / "skills"
        (self.root / "sample").mkdir(parents=True)
        (self.root / "CAPABILITY-ARCHITECTURE.md").write_text("# Capabilities\n")
        (self.root / "sample" / "SKILL.md").write_text(
            "---\nname: sample\ndescription: Example\n---\n\n"
            "[local](../CAPABILITY-ARCHITECTURE.md)\n"
            "[external evidence](https://developer.android.com/)\n"
        )

    def test_valid_copy_in_unrelated_directory(self):
        self.assertEqual(MODULE.check_skills(self.root), [])

    def test_missing_relative_link(self):
        entry = self.root / "sample" / "SKILL.md"
        entry.write_text(entry.read_text() + "\n[bad](references/missing.md)\n")
        self.assertTrue(any("missing local target" in e for e in MODULE.check_skills(self.root)))

    def test_parent_escape_is_not_portable(self):
        entry = self.root / "sample" / "SKILL.md"
        entry.write_text(entry.read_text() + "\n[bad](../../outside.md)\n")
        self.assertTrue(any("escapes skills package" in e for e in MODULE.check_skills(self.root)))

    def test_hardcoded_canonical_skill_navigation(self):
        entry = self.root / "sample" / "SKILL.md"
        entry.write_text(entry.read_text() + "\n[bad](https://github.com/GBIDEVELOPMENT/general-purpose/blob/main/skills/sample/SKILL.md)\n")
        self.assertTrue(any("stale source-repository" in e for e in MODULE.check_skills(self.root)))

    def test_frontmatter_name(self):
        entry = self.root / "sample" / "SKILL.md"
        entry.write_text(entry.read_text().replace("name: sample", "name: wrong"))
        self.assertTrue(any("name must match" in e for e in MODULE.check_skills(self.root)))


if __name__ == "__main__":
    unittest.main()
