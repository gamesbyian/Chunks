"""Catch explicitly nebulous imperative commands in the connected core skills.

This narrow text linter cannot establish that all instructions are executable.
"""
import re
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
IMPERATIVE = re.compile(
    r'(?im)^\s*(?:[-*]|\d+[.)])\s*\*{0,2}'
    r'(?:think like an? expert|use (?:your )?intuition|use common sense|'
    r'rely on (?:your )?gut|choose what feels right|consider everything)\b'
)
MUST = re.compile(
    r'(?i)\b(?:must|should|need to)\s+'
    r'(?:think like an? expert|use (?:your )?intuition|use common sense|'
    r'rely on (?:your )?gut|choose what feels right|consider everything)\b'
)

class OperationalDirectiveTests(unittest.TestCase):
    def test_finds_nebulous_commands(self):
        self.assertTrue(IMPERATIVE.search('- Think like an expert before fixing it'))
        self.assertTrue(IMPERATIVE.search('2. **Use your intuition** to choose'))
        self.assertTrue(MUST.search('The agent should use common sense here.'))

    def test_allows_prohibition_and_explanatory_comparison(self):
        self.assertFalse(IMPERATIVE.search('- Do not think like an expert'))
        self.assertFalse(MUST.search('An agent must not use your intuition'))
        self.assertFalse(IMPERATIVE.search('The phrase "think like an expert" is inadequate.'))

    def test_operational_skills_have_no_nebulous_imperatives(self):
        paths=[
            ROOT/'mechanised-expertise/SKILL.md',
            ROOT/'engineering-judgment/SKILL.md',
            ROOT/'concept-model-engineering/SKILL.md',
            ROOT/'android-app-development/SKILL.md',
            ROOT/'mechanised-expertise/references/mechanical-execution-contract.md',
        ]
        for path in paths:
            with self.subTest(path=path):
                text=path.read_text(encoding='utf-8')
                self.assertIsNone(IMPERATIVE.search(text),str(path))
                self.assertIsNone(MUST.search(text),str(path))

if __name__=='__main__':
    unittest.main()
