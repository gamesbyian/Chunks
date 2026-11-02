"""Offline positive and negative controls for documentation workflow selection."""
import tempfile
from pathlib import Path
import unittest

from tools.check_docs_dispatch_contract import event_filters, problems


class DocumentationDispatchContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "docs").mkdir()
        (self.root / "docs" / "README.md").write_text("sample")

    @staticmethod
    def workflow(push, pr):
        def section(name, entries):
            lines = [f"  {name}:", "    paths:"]
            lines.extend(f"      - '{entry}'" for entry in entries)
            return "\n".join(lines)
        return ("name: Example\non:\n" +
                section("push", push) + "\n" +
                section("pull_request", pr) +
                "\n  workflow_dispatch:\njobs:\n  checks:\n    runs-on: ubuntu-latest\n")

    def test_current_workflow_filters_are_unique_symmetric_and_resolve(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / ".github/workflows/documentation-validation.yml").read_text()
        self.assertEqual([], problems(source, root))
        self.assertIn("tools/check_docs_dispatch_contract.py",
                      event_filters(source, "pull_request"))
        self.assertIn("tests/test_docs_dispatch_contract.py",
                      event_filters(source, "pull_request"))

    def test_valid_exact_and_glob_filters(self):
        source = self.workflow(["docs/**", "docs/README.md"],
                               ["docs/**", "docs/README.md"])
        self.assertEqual([], problems(source, self.root))

    def test_duplicate_single_event_fails(self):
        source = self.workflow(["docs/**", "docs/**"], ["docs/**"])
        self.assertTrue(any("duplicated" in e for e in problems(source, self.root)))

    def test_missing_file_fails_closed_without_echoing_sensitive_name(self):
        secret = "PASSWORD-IN-FILENAME"
        source = self.workflow([secret], [secret])
        errors = problems(source, self.root)
        self.assertTrue(any("unmatched" in e for e in errors))
        self.assertNotIn(secret, repr(errors))

    def test_pr_push_divergence_fails_even_when_all_paths_exist(self):
        source = self.workflow(["docs/**"], ["docs/README.md"])
        self.assertIn("push and pull_request docs filters differ",
                      problems(source, self.root))

    def test_absent_event_and_path_traversal_refused(self):
        self.assertTrue(problems("on:\n  push:\n    paths:\n      - 'docs/**'\n",
                                 self.root))
        text = self.workflow(["../external"], ["../external"])
        self.assertTrue(any("invalid" in e for e in problems(text, self.root)))

    def test_path_list_is_scoped_to_event_and_paths_key(self):
        text = (self.workflow(["docs/**"], ["docs/README.md"])
                .replace("  pull_request:\n", "  pull_request:\n    branches:\n"
                         "      - 'main'\n"))
        self.assertEqual(["docs/README.md"], event_filters(text, "pull_request"))
        self.assertEqual(["docs/**"], event_filters(text, "push"))


if __name__ == "__main__":
    unittest.main()
