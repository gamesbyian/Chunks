"""Tests that the activated entrypoint is concise and routes on demand.

These checks validate documentation, not autonomous agent performance.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONTENT = (ROOT / "SKILL.md").read_text(encoding="utf-8")

class EntrypointRoutingTests(unittest.TestCase):
    def test_short_and_standard(self):
        self.assertTrue(CONTENT.startswith("---\nname: mechanised-expertise\n"))
        self.assertLess(len(CONTENT.encode("utf-8")), 9000)

    def test_first_action_is_visible_immediately(self):
        first = CONTENT[:1700]
        self.assertIn("Start here", first)
        self.assertIn("Read the current task", first)
        self.assertIn("Routine and reversible", first)

    def test_routine_negative_control(self):
        self.assertIn("Stop; do not create an inquiry card", CONTENT)
        self.assertIn("No card required for minor changes", CONTENT)

    def test_selection_is_on_demand(self):
        self.assertIn("first linked reference", CONTENT)
        self.assertIn("Do not pre-load other skills", CONTENT)
        for ref in ("../android-app-development/SKILL.md",
                    "../concept-model-engineering/SKILL.md",
                    "../engineering-judgment/SKILL.md"):
            self.assertIn(ref, CONTENT)

    def test_evidence_and_mechanical_gates(self):
        for fragment in ("both lead to the same", "Ask the owner only",
                         "Map only if needed", "planned or skipped",
                         "when additional plausible answers", "Source recommendations"):
            self.assertIn(fragment.lower(), CONTENT.lower())

    def test_preserves_legacy_research_routing(self):
        for ref in ("references/source-synthesis.md",
                    "references/mechanised-intuition-method.md",
                    "references/user-needs-and-prior-art.md",
                    "examples/transfer-list-discovery.md"):
            self.assertIn(ref, CONTENT)
        old = (ROOT/"references/legacy-entrypoint-v0.7.md").read_text(encoding="utf-8")
        self.assertIn('version: "0.7.0"', old)
        self.assertGreater(len(old), len(CONTENT))

    def test_all_inline_relative_links_exist(self):
        paths = re.findall(r"\]\(([^)#]+)(?:#[^)]*)?\)", CONTENT)
        self.assertGreaterEqual(len(paths), 20)
        for ref in paths:
            if "://" not in ref and not ref.startswith("#"):
                self.assertTrue((ROOT / ref).is_file(), "Broken reference: "+ref)

    def test_example_exposes_no_claimed_runtime_results(self):
        sample = (ROOT / "examples/first-run.md").read_text(encoding="utf-8")
        self.assertIn("not evidence", sample)
        self.assertIn("no questions", CONTENT.lower())
        self.assertIn("not a recorded agent trial", sample)

if __name__ == "__main__":
    unittest.main()
