"""Prevent a methodological hierarchy from creeping into skill routing."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCH = ROOT.parent / "CAPABILITY-ARCHITECTURE.md"


class CapabilityArchitectureTests(unittest.TestCase):
    def test_capabilities_are_peers_not_universal_prerequisites(self):
        doc = ARCH.read_text(encoding="utf-8")
        self.assertIn("complementary capabilities", doc)
        self.assertIn("none is a universal prerequisite", doc.lower())
        for phrase in ("Domain reference", "Operational execution", "Mechanised inquiry",
                       "Conceptual modelling", "Verification and evidence"):
            self.assertIn(phrase, doc)

    def test_concept_modelling_is_standalone_and_mechanical(self):
        skill = (ROOT / "SKILL.md").read_text(encoding="utf-8")
        mechanics = (ROOT / "references" / "mechanical-model-operations.md").read_text(encoding="utf-8")
        self.assertIn("independent skill", skill)
        self.assertIn("standalone", skill.lower())
        self.assertNotIn("first apply [Engineering Judgment]", skill)
        for i in range(1,8):
            self.assertIn("## M" + str(i) + ".", mechanics)
        for field in ("**Input:**", "**Transform:**", "**Output:**", "**Reject:**", "**Stop:**"):
            self.assertEqual(7, mechanics.count(field))

    def test_auditor_is_optional_and_non_authoritative(self):
        skill = (ROOT / "SKILL.md").read_text(encoding="utf-8")
        self.assertIn("scripts/audit_coverage.py", skill)
        self.assertIn("review candidates", skill)
        self.assertTrue((ROOT / "scripts" / "audit_coverage.py").is_file())


if __name__ == "__main__":
    unittest.main()
