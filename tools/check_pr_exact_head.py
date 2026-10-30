#!/usr/bin/env python3
"""Fail-closed, read-only GitHub PR CI receipt verification.

This intentionally verifies *records*, not tests, source integrity, or merge safety.
A successful run must be linked to the selected PR at its current exact head.
No API token is required for a public repo (GITHUB_TOKEN is optional).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


def pr_identity(pr: dict[str, Any]) -> tuple[str, str]:
    return pr.get("head", {}).get("sha", ""), pr.get("base", {}).get("sha", "")


def run_for_pr(run: dict[str, Any], number: int, head_sha: str) -> bool:
    return (run.get("head_sha") == head_sha
            and run.get("event") == "pull_request"
            and any(p.get("number") == number for p in run.get("pull_requests", [])))


def latest(items: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    matches = [item for item in items if item.get("name") == name]
    if not matches:
        return None
    return max(matches, key=lambda item: (item.get("created_at") or "",
                                          item.get("run_attempt") or 0,
                                          item.get("id") or 0))


def verify_receipts(
    first_pr: dict[str, Any], final_pr: dict[str, Any],
    runs: list[dict[str, Any]], checks: list[dict[str, Any]],
    expected_sha: str, required_workflows: list[str],
    required_checks: list[str], expected_base: str | None = None,
    require_ready: bool = False,
) -> tuple[bool, list[str]]:
    """Return (safe-to-count-receipts, human-readable evidence); never merge."""
    report: list[str] = []
    errors: list[str] = []
    number = first_pr.get("number")
    head, base = pr_identity(first_pr)
    head_after, base_after = pr_identity(final_pr)
    if not re.fullmatch(r"[0-9a-f]{40}", expected_sha):
        errors.append("Expected head must be a full 40-character lowercase SHA")
    if first_pr.get("state") != "open" or final_pr.get("state") != "open":
        errors.append("PR is not open at both observations")
    if require_ready and (first_pr.get("draft") or final_pr.get("draft")):
        errors.append("PR remains draft")
    if head != expected_sha or head_after != expected_sha:
        errors.append(f"PR head moved or mismatches expected SHA: before={head}, after={head_after}")
    if base != base_after:
        errors.append(f"PR base advanced during receipt inspection: {base} -> {base_after}")
    if expected_base and (base != expected_base or base_after != expected_base):
        errors.append(f"PR base not at expected SHA: before={base}, after={base_after}")
    if not required_workflows and not required_checks:
        errors.append("At least one required workflow or check name must be specified")

    applicable = [r for r in runs if run_for_pr(r, number, expected_sha)]
    for name in dict.fromkeys(required_workflows):
        run = latest(applicable, name)
        if run is None:
            errors.append(f"MISSING workflow: {name}")
            continue
        outcome = f"{run.get('status')}/{run.get('conclusion')}"
        report.append(f"workflow {name}: {outcome} (run {run.get('id')}, attempt {run.get('run_attempt')})")
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            errors.append(f"NOT PASS workflow: {name} ({outcome})")

    exact_checks = [c for c in checks if c.get("head_sha") == expected_sha]
    for name in dict.fromkeys(required_checks):
        check = latest(exact_checks, name)
        if check is None:
            errors.append(f"MISSING check: {name}")
            continue
        outcome = f"{check.get('status')}/{check.get('conclusion')}"
        report.append(f"check {name}: {outcome} (check {check.get('id')})")
        if check.get("status") != "completed" or check.get("conclusion") != "success":
            errors.append(f"NOT PASS check: {name} ({outcome})")
    return not errors, [f"PR #{number} exact head {expected_sha} base {base}",
                        *report, *(f"BLOCKER: {e}" for e in errors)]


def github_get(path: str) -> dict[str, Any]:
    url = f"https://api.github.com{path}"
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "chunks-ci-receipt-check/1",
               "X-GitHub-Api-Version": "2022-11-28"}
    if os.getenv("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def paginated(path: str, key: str) -> list[dict[str, Any]]:
    all_items: list[dict[str, Any]] = []
    for page in range(1, 11):
        join = "&" if "?" in path else "?"
        response = github_get(f"{path}{join}per_page=100&page={page}")
        items = response.get(key)
        if not isinstance(items, list):
            raise ValueError(f"GitHub response omitted {key}")
        all_items.extend(items)
        if len(all_items) >= response.get("total_count", len(all_items)):
            return all_items
        if len(items) < 100:
            raise ValueError(f"Incomplete {key} pages; refusing partial evidence")
    raise ValueError(f"Too many {key} pages; refusing partial evidence")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", required=True, help="owner/name")
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--head", required=True, help="full expected head SHA")
    p.add_argument("--base", help="optional exact expected base SHA")
    p.add_argument("--require-workflow", action="append", default=[], metavar="NAME")
    p.add_argument("--require-check", action="append", default=[], metavar="NAME")
    p.add_argument("--require-ready", action="store_true")
    p.add_argument("--fixture", help="offline JSON: first_pr, final_pr, workflow_runs, check_runs")
    args = p.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        p.error("--repo must be owner/name")
    if args.pr <= 0:
        p.error("--pr must be positive")
    try:
        if args.fixture:
            with open(args.fixture, encoding="utf-8") as f:
                source = json.load(f)
            first, final = source["first_pr"], source["final_pr"]
            runs, checks = source["workflow_runs"], source["check_runs"]
        else:
            prefix = f"/repos/{args.repo}"
            first = github_get(f"{prefix}/pulls/{args.pr}")
            # Restrict server-side by SHA; still recheck PR association and head.
            sha = urllib.parse.quote(args.head, safe="")
            runs = paginated(f"{prefix}/actions/runs?head_sha={sha}&event=pull_request", "workflow_runs")
            checks = paginated(f"{prefix}/commits/{sha}/check-runs", "check_runs")
            final = github_get(f"{prefix}/pulls/{args.pr}")
        if first.get("number") != args.pr or final.get("number") != args.pr:
            raise ValueError("PR number mismatch in source")
        ok, report = verify_receipts(first, final, runs, checks, args.head,
                                     args.require_workflow, args.require_check,
                                     args.base, args.require_ready)
        print("\n".join(report))
        print("PASS: required exact-head CI receipts" if ok else "BLOCKED: incomplete exact-head CI receipts")
        return 0 if ok else 1
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, urllib.error.HTTPError) as exc:
        # Do not echo API response bodies or tokens.
        print(f"BLOCKED: unable to verify CI receipts ({type(exc).__name__})", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
