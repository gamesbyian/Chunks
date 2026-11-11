"""Offline fail-closed tests, including deliberate corruptions and negative controls."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/"scripts"/file)
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

checker=module("checker","check_skill.py")
evaluator=module("evaluator","evaluate_transfer.py")
PROBES,KEY=evaluator.cases()

class SkillTests(unittest.TestCase):
    def test_real_skill_integrity(self):
        self.assertEqual([],checker.check())

    def test_keys_not_exposed_to_candidate(self):
        pub=evaluator.public_prompts(PROBES)
        self.assertEqual([{"id","prompt","options"} for _ in pub],[set(x) for x in pub])
        txt=json.dumps(pub)
        for secret in ('"answer"','"reason"','"pattern"'):
            self.assertNotIn(secret,txt)
        self.assertNotIn("github.com/gamesbyian",txt)

    def test_correct_and_wrong_decisions(self):
        good=[{"id":p["id"],"choice":KEY[p["id"]]["answer"],"rationale":"test"} for p in PROBES]
        self.assertEqual(len(PROBES),evaluator.grade(PROBES,KEY,good)["correct"])
        bad=[{"id":p["id"],"choice":next(c for c in "ABC" if c!=KEY[p["id"]]["answer"]),
              "rationale":"wrong"} for p in PROBES]
        self.assertEqual(0,evaluator.grade(PROBES,KEY,bad)["correct"])

    def test_missing_answers_not_counted_as_success(self):
        report=evaluator.grade(PROBES,KEY,[])
        self.assertEqual(0,report["correct"])
        self.assertEqual(0,report["answered"])

    def test_duplicate_answers_rejected(self):
        a={"id":PROBES[0]["id"],"choice":"A","rationale":"test"}
        with self.assertRaisesRegex(ValueError,"Duplicate"):
            evaluator.grade(PROBES,KEY,[a,a])

    def test_answer_without_rationale_rejected(self):
        a={"id":PROBES[0]["id"],"choice":"A","rationale":""}
        with self.assertRaisesRegex(ValueError,"nonempty"):
            evaluator.grade(PROBES,KEY,[a])

    def test_unknown_case_rejected(self):
        with self.assertRaisesRegex(ValueError,"Unknown"):
            evaluator.grade(PROBES,KEY,[{"id":"J99","choice":"A","rationale":"x"}])

    def test_broken_local_reference_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            dst=Path(d)/"skill"
            shutil.copytree(ROOT,dst,ignore=shutil.ignore_patterns("__pycache__"))
            (dst/"references"/"signatures.md").unlink()
            self.assertTrue(any("Broken/out-of-root" in e for e in checker.check(dst)))

    def test_cross_package_reference_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            dst=Path(d)/"skill"
            shutil.copytree(ROOT,dst,ignore=shutil.ignore_patterns("__pycache__"))
            f=dst/"SKILL.md"
            f.write_text(f.read_text()+"\n[illegal](../other/SKILL.md)\n")
            self.assertTrue(any("Broken/out-of-root" in e for e in checker.check(dst)))

    def test_missing_pattern_source_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            dst=Path(d)/"skill"
            shutil.copytree(ROOT,dst,ignore=shutil.ignore_patterns("__pycache__"))
            p=dst/"assets"/"patterns.json"
            obj=json.loads(p.read_text())
            obj["patterns"][0]["source"]="anonymous"
            p.write_text(json.dumps(obj))
            self.assertTrue(any("missing source" in e for e in checker.check(dst)))

    def test_key_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            dst=Path(d)/"skill"
            shutil.copytree(ROOT,dst,ignore=shutil.ignore_patterns("__pycache__"))
            p=dst/"assets"/"transfer-probe-key.json"
            obj=json.loads(p.read_text())
            obj["answers"].pop()
            p.write_text(json.dumps(obj))
            self.assertIn("Prompt and key ID sets disagree",checker.check(dst))

if __name__=="__main__":
    unittest.main()
