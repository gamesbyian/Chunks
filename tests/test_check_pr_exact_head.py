"""Offline negative controls for independent PR CI receipt verification."""

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "tools/check_pr_exact_head.py"
spec = importlib.util.spec_from_file_location("check_pr_exact_head", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SHA = "a" * 40
BASE = "b" * 40


def pr(sha=SHA, base=BASE, draft=False):
    return {"number": 703, "state": "open", "draft": draft,
            "head": {"sha": sha}, "base": {"sha": base}}


def run(name="native", sha=SHA, conclusion="success", linked=True, id=5):
    return {"id": id, "name": name, "head_sha": sha, "status": "completed",
            "conclusion": conclusion, "run_attempt": 1, "created_at": "2026-10-30T00:00:00Z",
            "event": "pull_request", "pull_requests": [{"number": 703 if linked else 702}]}


def check(name="build", sha=SHA, conclusion="success"):
    return {"id": 6, "name": name, "head_sha": sha, "status": "completed",
            "conclusion": conclusion, "created_at": "2026-10-30T00:00:00Z"}


class ReceiptTest(unittest.TestCase):
    def verify(self, first=None, final=None, runs=None, checks=None, workflows=None,
               required_checks=None, **kwargs):
        return module.verify_receipts(first or pr(), final or pr(),
                                      [run()] if runs is None else runs,
                                      [check()] if checks is None else checks,
                                      SHA, ["native"] if workflows is None else workflows,
                                      ["build"] if required_checks is None else required_checks,
                                      **kwargs)

    def test_exact_head_workflow_and_check_pass(self):
        self.assertTrue(self.verify()[0])

    def test_cancelled_jobless_r8_is_not_success(self):
        ok, messages = self.verify(runs=[run(), run("R8", conclusion="cancelled")],
                                   workflows=["native", "R8"])
        self.assertFalse(ok)
        self.assertIn("NOT PASS workflow: R8", "\n".join(messages))

    def test_missing_jobless_run_never_inferred_from_green_checks(self):
        self.assertFalse(self.verify(workflows=["native", "R8"])[0])

    def test_stale_head_cannot_satisfy_required_workflow(self):
        self.assertFalse(self.verify(runs=[run(sha="c" * 40)])[0])

    def test_other_pr_same_sha_cannot_satisfy_required_workflow(self):
        self.assertFalse(self.verify(runs=[run(linked=False)])[0])

    def test_head_advance_mid_inspection_fails(self):
        self.assertFalse(self.verify(final=pr(sha="c" * 40))[0])

    def test_base_advance_mid_inspection_fails(self):
        self.assertFalse(self.verify(final=pr(base="c" * 40))[0])

    def test_old_expected_base_fails(self):
        self.assertFalse(self.verify(expected_base="c" * 40)[0])

    def test_required_check_skipped_fails(self):
        self.assertFalse(self.verify(checks=[check(conclusion="skipped")])[0])

    def test_draft_can_be_examined_but_not_merge_qualified(self):
        self.assertTrue(self.verify(first=pr(draft=True), final=pr(draft=True))[0])
        self.assertFalse(self.verify(first=pr(draft=True), final=pr(draft=True), require_ready=True)[0])

    def test_no_required_receipts_fails_closed(self):
        self.assertFalse(self.verify(workflows=[], required_checks=[])[0])

    def test_newer_cancelled_run_overrides_old_success(self):
        later = run(conclusion="cancelled", id=10)
        later["created_at"] = "2026-10-31T00:00:00Z"
        self.assertFalse(self.verify(runs=[run(), later])[0])


if __name__ == "__main__":
    unittest.main()
