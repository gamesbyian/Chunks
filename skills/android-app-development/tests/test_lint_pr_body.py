"""GitHub PR description safety checks, including the historical negation trap."""
import importlib.util
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pr_lint", root / "scripts" / "lint_pr_body.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class PRLanguageTests(unittest.TestCase):
    def test_reject_negated_closing_directives(self):
        for phrase in ("Do not close #143", "does not fix gamesbyian/Glimpse#72",
                       "Never resolve #12", "This doesn't close #7",
                       "Do not automatically closes #8"):
            with self.subTest(phrase=phrase):
                self.assertEqual(1, len(mod.findings(phrase)))

    def test_allow_plain_status_and_intentional_close(self):
        for phrase in ("Issue #143 remains open", "Closes #143",
                       "Addresses #143; acceptance pending"):
            with self.subTest(phrase=phrase):
                self.assertEqual([], mod.findings(phrase))

    def test_line_number(self):
        self.assertEqual([(143, 3)], mod.findings("a\nb\nDo not close #143"))

    def test_invalid_input(self):
        with self.assertRaises(TypeError):
            mod.findings(None)

if __name__ == "__main__":
    unittest.main()
