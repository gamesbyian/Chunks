"""Mutation tests for the instructional completeness reviewer."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "audit_instruction_completeness.py"
SPEC = importlib.util.spec_from_file_location("instruction_audit", MODULE_PATH)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class InstructionAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "unfamiliar-repo" / "skills"
        (self.root / "sample").mkdir(parents=True)
        (self.root / "sample" / "SKILL.md").write_text(
            "# Example\n\n- Investigate the user's needs carefully.\n- Do not research everything.\n",
            encoding="utf-8",
        )
        (self.root / "sample" / "method.md").write_text(
            "# Procedure\n\nInput then check source then decide.\n", encoding="utf-8",
        )
        self.contract = {
            "id": "TEST-01",
            "path": "sample/SKILL.md",
            "anchor": "Investigate the user's needs carefully.",
            "method": "sample/method.md",
            "trigger": "User needs are underdetermined and design could change.",
            "inputs": "User request and real source evidence inventory.",
            "operation": "Read the source, extract exact claims, compare discrepancies.",
            "branch": "If source contradicts proposal, investigate; otherwise proceed.",
            "output": "One attributed evidence and decision comparison ledger.",
            "evidence": "Direct original-source citation or reproducible observation.",
            "stop": "Stop when no material affordable discriminator remains.",
            "fallback": "If unavailable, mark inaccessible and retain uncertainty.",
        }
        self.path = self.root / "contract.json"

    def verify(self, record=None):
        self.path.write_text(json.dumps([record or self.contract]), encoding="utf-8")
        return AUDIT.check_contracts(self.root, self.path)

    def test_unresolved_directive_is_a_review_lead(self):
        findings = AUDIT.candidates(self.root)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["priority"], "inspect_first")

    def test_valid_contract(self):
        self.assertEqual(self.verify(), [])

    def test_missing_input_is_rejected(self):
        item = dict(self.contract)
        del item["inputs"]
        self.assertTrue(any("missing" in x for x in self.verify(item)))

    def test_placeholder_is_rejected(self):
        item = dict(self.contract)
        item["operation"] = "Just do it as appropriate"
        self.assertTrue(any("placeholder" in x for x in self.verify(item)))

    def test_nonexistent_method_is_rejected(self):
        item = dict(self.contract)
        item["method"] = "sample/no-such-file.md"
        self.assertTrue(any("missing/out-of-package" in x for x in self.verify(item)))

    def test_source_change_invalidates_review(self):
        item = dict(self.contract)
        item["anchor"] = "No longer in source instructions"
        self.assertTrue(any("anchor changed" in x for x in self.verify(item)))

    def test_escape_from_package_rejected(self):
        item = dict(self.contract)
        item["method"] = "../../secret-doc.md"
        self.assertTrue(any("out-of-package" in x for x in self.verify(item)))

    def test_actual_reviewed_contracts(self):
        skills = MODULE_PATH.parents[1]
        ledger = skills / "assets" / "reviewed-instruction-contracts.json"
        self.assertEqual(AUDIT.check_contracts(skills, ledger), [])


if __name__ == "__main__":
    unittest.main()
