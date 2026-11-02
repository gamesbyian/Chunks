#!/usr/bin/env python3
"""Bounded, auditable cleanup of superseded Chunks *automatic* debug APK artifacts.

Manual-only, default dry run. This is intentionally NOT a general artifact cleaner.
The identical artifact name is used by a separate owner-publication workflow;
only the automatic android-check.yml producer is eligible.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import hashlib
import re
import subprocess
import sys

REPO = "gamesbyian/Chunks"
ARTIFACT_NAME = "chunks-debug-prealpha"
AUTO_WORKFLOW = ".github/workflows/android-check.yml"
PAGE_SIZE = 100
MIN_AGE_DAYS = 30
KEEP_NEWEST = 50
MAX_DELETE = 20
CONFIRMATION = "DELETE-CHUNKS-AUTO-APK-ARTIFACTS"
SHA40 = re.compile(r"[0-9a-f]{40}\Z")


class ScanError(RuntimeError):
    pass


class GH:
    def get(self, endpoint):
        call = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, timeout=45)
        if call.returncode:
            raise ScanError(f"GitHub API GET failed for {endpoint.split('?')[0]}")
        try:
            return json.loads(call.stdout)
        except json.JSONDecodeError as exc:
            raise ScanError("GitHub response was not JSON") from exc

    def delete(self, endpoint):
        call = subprocess.run(["gh", "api", "--include", "--method", "DELETE", endpoint],
                              capture_output=True, text=True, timeout=45)
        if call.returncode != 0 or not re.search(r"HTTP/\S+\s+204\b", call.stdout):
            raise ScanError("Deletion did not return confirmed HTTP 204")

    def absent(self, endpoint):
        # gh must fail on 404, not on some other transient API or auth failure.
        call = subprocess.run(["gh", "api", "--include", endpoint],
                              capture_output=True, text=True, timeout=45)
        if not re.search(r"HTTP/\S+\s+404\b", call.stdout + "\n" + call.stderr):
            raise ScanError("Deleted artifact absence not verified by HTTP 404")


def parse_date(value):
    if not isinstance(value, str):
        raise ScanError("missing timestamp")
    try:
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ScanError("invalid timestamp") from exc
    if date.tzinfo is None:
        raise ScanError("timestamp missing timezone")
    return date.astimezone(timezone.utc)


def list_named(gh):
    """Fail closed if artifact count changes or a page is incomplete."""
    items, ids, count = [], set(), None
    for page in range(1, 101):
        data = gh.get(f"repos/{REPO}/actions/artifacts?name={ARTIFACT_NAME}&per_page=100&page={page}")
        batch = data.get("artifacts")
        n = data.get("total_count")
        if type(n) is not int or not isinstance(batch, list) or len(batch) > PAGE_SIZE:
            raise ScanError("malformed named-artifact listing")
        if count is None:
            count = n
        if n != count:
            raise ScanError("artifact count changed while scanning")
        for x in batch:
            if x.get("name") != ARTIFACT_NAME or type(x.get("id")) is not int or x["id"] in ids:
                raise ScanError("wrong-name, duplicate or invalid artifact ID")
            if type(x.get("size_in_bytes")) is not int or x["size_in_bytes"] < 0:
                raise ScanError("invalid artifact byte count")
            ids.add(x["id"])
            items.append(x)
        if len(batch) < PAGE_SIZE:
            if len(items) != count:
                raise ScanError("incomplete artifact inventory")
            return items
    raise ScanError("100-page listing limit reached; no deletion authorized")


def open_pr_heads(gh):
    data = gh.get(f"repos/{REPO}/pulls?state=open&per_page=100")
    if not isinstance(data, list) or len(data) >= 100:
        raise ScanError("open PR listing missing or may be truncated")
    heads = set()
    for item in data:
        sha = item.get("head", {}).get("sha")
        if not isinstance(sha, str) or not SHA40.fullmatch(sha):
            raise ScanError("missing open PR source SHA")
        heads.add(sha)
    return heads


def plan_digest(candidates):
    """Stable plan identifier; a different candidate ID or provenance refuses apply."""
    canonical = json.dumps(candidates, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def select(gh, *, now=None, min_days=MIN_AGE_DAYS, keep=KEEP_NEWEST, limit=MAX_DELETE):
    if min_days < 30 or keep < 50 or not 1 <= limit <= 20:
        raise ScanError("requested boundaries are weaker than the safety policy")
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ScanError("naive scan timestamp")
    rows = list_named(gh)
    heads = open_pr_heads(gh)
    # Retain 50 most recent artifacts of this name unconditionally, including
    # owner-publication artifacts. Age filtering also protects every recent one.
    rows.sort(key=lambda x: (parse_date(x.get("created_at")), x["id"]), reverse=True)
    recent_ids = {a["id"] for a in rows[:keep]}
    excluded = {"not_auto_workflow": 0, "active_pr": 0, "young": 0,
                "invalid": 0, "keep_newest": len(recent_ids)}
    candidates = []
    runs = {}
    for item in reversed(rows):
        if len(candidates) == limit:
            break
        if item["id"] in recent_ids:
            continue
        if item.get("expired") is not False:
            excluded["invalid"] += 1
            continue
        created = parse_date(item.get("created_at"))
        if created > now - timedelta(days=min_days):
            excluded["young"] += 1
            continue
        assoc = item.get("workflow_run")
        if not isinstance(assoc, dict) or type(assoc.get("id")) is not int:
            excluded["invalid"] += 1
            continue
        run_id = assoc["id"]
        if run_id not in runs:
            runs[run_id] = gh.get(f"repos/{REPO}/actions/runs/{run_id}")
        run = runs[run_id]
        head = run.get("head_sha")
        if (run.get("path") != AUTO_WORKFLOW or run.get("id") != run_id or
                not isinstance(head, str) or not SHA40.fullmatch(head)):
            excluded["not_auto_workflow"] += 1
            continue
        if (head != assoc.get("head_sha") or head in heads or
                run.get("status") != "completed"):
            excluded["active_pr"] += 1
            continue
        candidates.append({
            "id": item["id"], "name": item["name"], "bytes": item["size_in_bytes"],
            "created_at": item["created_at"], "run_id": run_id, "source_sha": head,
            "digest": item.get("digest"),
        })
    return {"schema": "chunks-auto-apk-cleanup/v1", "repository": REPO,
            "workflow": AUTO_WORKFLOW, "artifact_name": ARTIFACT_NAME,
            "mode": "plan", "min_age_days": min_days, "keep_newest": keep,
            "max_deletions": limit, "listed_named_artifacts": len(rows),
            "excluded_in_reviewed_subset": excluded, "candidates": candidates,
            "candidate_sha256": plan_digest(candidates),
            "candidate_bytes": sum(x["bytes"] for x in candidates)}

def apply(gh, plan, *, confirmation, expected_sha256):
    if confirmation != CONFIRMATION:
        raise ScanError("manual deletion acknowledgement is missing")
    if plan["repository"] != REPO or plan["workflow"] != AUTO_WORKFLOW:
        raise ScanError("wrong repository or workflow")
    if not isinstance(expected_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256) or expected_sha256 != plan["candidate_sha256"]:
        raise ScanError("candidate set changed or expected SHA-256 not supplied")
    receipt = []
    for artifact in plan["candidates"]:
        endpoint = f"repos/{REPO}/actions/artifacts/{artifact['id']}"
        current = gh.get(endpoint)
        # Every deletion revalidates all immutable recorded identity fields.
        if (current.get("id") != artifact["id"] or
            current.get("name") != ARTIFACT_NAME or
            current.get("size_in_bytes") != artifact["bytes"] or
            current.get("created_at") != artifact["created_at"] or
            current.get("digest") != artifact["digest"] or
            current.get("workflow_run", {}).get("id") != artifact["run_id"] or
            current.get("workflow_run", {}).get("head_sha") != artifact["source_sha"] or
            current.get("expired") is not False):
            raise ScanError(f"artifact {artifact['id']} changed since scan; stopped")
        # Re-check current open PRs just before each irreversible operation.
        if artifact["source_sha"] in open_pr_heads(gh):
            raise ScanError("an artifact belongs to a currently open PR; stopped")
        gh.delete(endpoint)
        gh.absent(endpoint)
        receipt.append({"id": artifact["id"], "bytes_deleted": artifact["bytes"],
                        "deletion_confirmed": True, "http": "204 then 404"})
        # Durable workflow logs still show partial success if a later API call fails.
        print(f"VERIFIED DELETION id={artifact['id']} bytes={artifact['bytes']} HTTP=204,404",
              file=sys.stderr, flush=True)
    return receipt


def main(argv=None, gh=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mode", choices=("plan", "apply"), default="plan")
    p.add_argument("--confirmation", default="")
    p.add_argument("--expected-plan-sha256", default="")
    p.add_argument("--json-out")
    args = p.parse_args(argv)
    gh = gh or GH()
    plan = select(gh)
    receipt = apply(gh, plan, confirmation=args.confirmation, expected_sha256=args.expected_plan_sha256) if args.mode == "apply" else []
    plan["mode"] = args.mode
    plan["verified_deletions"] = receipt
    plan["verified_deleted_bytes"] = sum(r["bytes_deleted"] for r in receipt)
    result = json.dumps(plan, indent=2) + "\n"
    if args.json_out:
        from pathlib import Path
        Path(args.json_out).write_text(result)
    print(result)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ScanError as exc:
        print(f"INCOMPLETE / REFUSED: {exc}", file=sys.stderr)
        raise SystemExit(2)
