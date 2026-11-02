"""Negative and positive tests for conservative automatic-APK artifact pruning."""
from datetime import datetime, timedelta, timezone
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import actions_artifact_housekeeping as m

NOW = datetime(2026, 11, 2, tzinfo=timezone.utc)
HEAD = 'a' * 40

def artifact(i, days, rid=None, name=m.ARTIFACT_NAME):
    stamp = (NOW - timedelta(days=days)).isoformat()
    return {"id": i, "name": name, "created_at": stamp,
            "size_in_bytes": 5000+i, "expired": False, "digest": "sha256:" + "0" * 64,
            "workflow_run": {"id": rid or i, "head_sha": HEAD}}

class FakeGH:
    def __init__(self, records=None, workflow=m.AUTO_WORKFLOW, prs=None):
        self.records = records or []
        self.workflow = workflow
        self.prs = prs or []
        self.deleted = []
    def get(self, endpoint):
        if endpoint.endswith('/pulls?state=open&per_page=100'):
            return [{"head":{"sha":x}} for x in self.prs]
        if '/actions/artifacts?name=' in endpoint:
            page = int(endpoint.rsplit('page=', 1)[-1]); start = (page-1)*100
            return {"total_count":len(self.records), "artifacts":self.records[start:start+100]}
        if '/actions/runs/' in endpoint:
            i = int(endpoint.rsplit('/', 1)[-1])
            return {"id": i, "path":self.workflow, "head_sha": HEAD,"status":"completed"}
        if '/actions/artifacts/' in endpoint:
            ident = int(endpoint.rsplit('/',1)[-1]);return next(x for x in self.records if x['id']==ident)
        raise ValueError(endpoint)
    def delete(self, endpoint): self.deleted.append(int(endpoint.rsplit('/',1)[-1]))
    def absent(self, endpoint): return True

class CleanupTests(unittest.TestCase):
    def test_only_old_auto_and_preserve_50(self):
        gh=FakeGH([artifact(i+1, 70) for i in range(90)] + [artifact(91, 10)])
        plan=m.select(gh,now=NOW)
        self.assertEqual(len(plan['candidates']),20)
        self.assertEqual(plan['excluded_in_reviewed_subset']['young'],0)
        self.assertEqual(plan['excluded_in_reviewed_subset']['keep_newest'],50)
        self.assertEqual(plan['candidates'][0]['id'],1)

    def test_no_cleanup_of_manual_owner_publications(self):
        gh=FakeGH([artifact(i+1, 80) for i in range(80)], workflow='.github/workflows/publish-debug-apk.yml')
        self.assertEqual(m.select(gh,now=NOW)['candidates'],[])

    def test_no_cleanup_of_current_pr(self):
        gh=FakeGH([artifact(i+1, 80) for i in range(80)], prs=[HEAD])
        self.assertEqual(m.select(gh,now=NOW)['candidates'],[])

    def test_wrong_age_or_limit_refused(self):
        gh=FakeGH()
        with self.assertRaises(m.ScanError):m.select(gh,now=NOW,min_days=6)
        with self.assertRaises(m.ScanError):m.select(gh,now=NOW,keep=0)
        with self.assertRaises(m.ScanError):m.select(gh,now=NOW,limit=21)

    def test_apply_requires_exact_manual_ack(self):
        gh=FakeGH([artifact(i+1, 80) for i in range(80)])
        plan=m.select(gh,now=NOW)
        with self.assertRaises(m.ScanError):m.apply(gh,plan,confirmation='yes', expected_sha256=plan['candidate_sha256'])
        self.assertEqual(gh.deleted,[])

    def test_apply_proves_id_and_absence(self):
        gh=FakeGH([artifact(i+1, 80) for i in range(80)])
        plan=m.select(gh,now=NOW)
        got=m.apply(gh,plan,confirmation=m.CONFIRMATION, expected_sha256=plan['candidate_sha256'])
        self.assertEqual(len(got),20)
        self.assertEqual(len(gh.deleted),20)

    def test_changed_record_refused_before_delete(self):
        gh=FakeGH([artifact(i+1, 80) for i in range(80)])
        plan=m.select(gh,now=NOW)
        target=plan['candidates'][0]['id']
        next(x for x in gh.records if x['id']==target)['size_in_bytes']+=1
        with self.assertRaisesRegex(m.ScanError,'changed'):
            m.apply(gh,plan,confirmation=m.CONFIRMATION, expected_sha256=plan['candidate_sha256'])
        self.assertEqual(gh.deleted,[])

    def test_incomplete_listing_refused(self):
        class Incomplete(FakeGH):
            def get(self, endpoint):
                out=super().get(endpoint)
                if isinstance(out,dict) and 'total_count' in out:out['total_count']+=10
                return out
        gh=Incomplete([artifact(1,80)])
        with self.assertRaisesRegex(m.ScanError,'incomplete'):
            m.select(gh,now=NOW)

if __name__ == '__main__':unittest.main()
