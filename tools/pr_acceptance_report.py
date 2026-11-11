#!/usr/bin/env python3
"""Report whether the EXACT commit has executed every required gate.

Classifies each check run as passed, failed, not executed (skipped/cancelled), pending
or not reported. A skipped, cancelled or absent gate is never a pass. A failure is only
labelled *environment/dependency* when its failing step looks like setup; whether a
remaining failure is a product defect or test-contract drift needs human diagnosis and
is reported as such. Waivers must be named and are echoed so they cannot be silent.

  python3 tools/pr_acceptance_report.py --repo gamesbyian/Chunks --sha <SHA> --profile integration
  python3 tools/pr_acceptance_report.py --fixture checks.json --profile integration
Exit 0 only when acceptable.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

CONFIG = Path(__file__).resolve().parent / "acceptance_gates.json"
PASSED, FAILED = "passed", "failed"
NOT_EXECUTED, PENDING, NOT_REPORTED = "not-executed", "pending", "not-reported"


def load_config(text: str | None = None) -> dict:
    config = json.loads(text if text is not None else CONFIG.read_text(encoding="utf-8"))
    if config.get("schema") != "chunks.acceptance-gates.v1":
        raise ValueError("unknown acceptance gate schema")
    return config


def profile_gates(config: dict, name: str) -> list[str]:
    profiles = config["profiles"]
    if name not in profiles:
        raise ValueError("unknown profile: " + name)
    profile = profiles[name]
    gates = list(profile.get("gates", []))
    if profile.get("extends"):
        gates = profile_gates(config, profile["extends"]) + gates
    return gates


def classify_check(check: dict) -> tuple[str, str]:
    status, conclusion = check.get("status"), check.get("conclusion")
    if status != "completed":
        return PENDING, str(status)
    if conclusion == "success":
        return PASSED, "success"
    if conclusion in ("skipped", "cancelled", "neutral", "stale"):
        return NOT_EXECUTED, str(conclusion)
    return FAILED, str(conclusion)


def failure_class(check: dict, config: dict) -> str:
    """Best-effort label from the first failing step; never claims a product defect."""
    steps = [s for s in check.get("steps", []) if s.get("conclusion") == "failure"]
    if not steps:
        return "needs-diagnosis (no step detail)"
    name = steps[0].get("name", "").lower()
    hints = config.get("step_hints", {})
    if any(h in name for h in hints.get("environment", [])):
        return "environment/dependency (failed step: " + steps[0]["name"] + ")"
    return "needs-diagnosis: product defect or test-contract drift (failed step: " + steps[0]["name"] + ")"


def evaluate(checks: list[dict], gates: list[str], config: dict, waivers: dict[str, str],
             draft: bool = False) -> dict:
    by_name: dict[str, dict] = {}
    for check in checks:
        by_name[check["name"]] = check  # last wins; callers pass one attempt per name
    rows, blockers = [], []
    for gate in gates:
        if gate in waivers:
            rows.append({"gate": gate, "state": "waived", "detail": waivers[gate]})
            continue
        # A skipped matrix job is reported without its shard suffix, e.g. "name" not "name (0)".
        check = by_name.get(gate) or by_name.get(re.sub(r" \(\d+\)$", "", gate))
        if check is None:
            state, detail = NOT_REPORTED, "no check run with this name on this commit"
        else:
            state, detail = classify_check(check)
            if state == FAILED:
                detail += "; " + failure_class(check, config)
        rows.append({"gate": gate, "state": state, "detail": detail})
        if state != PASSED:
            blockers.append(gate)
    if draft:
        blockers.append("(pull request is a draft: heavy gates are skipped until it is ready)")
    return {"rows": rows, "blockers": blockers, "acceptable": not blockers,
            "unlisted": sorted(set(by_name) - set(gates))}


def render(report: dict, sha: str, profile: str) -> str:
    lines = [f"Acceptance report for exact commit {sha} (profile: {profile})", ""]
    for row in report["rows"]:
        lines.append(f"  {row['state'].upper():13} {row['gate']}  [{row['detail']}]")
    if report["unlisted"]:
        lines += ["", "Other checks on this commit (not part of the profile): " + ", ".join(report["unlisted"])]
    lines += ["", "ACCEPTABLE: every required gate executed and passed on this exact commit."
              if report["acceptable"] else
              "NOT ACCEPTABLE. Blocking: " + "; ".join(report["blockers"])]
    lines.append("Skipped, cancelled or absent gates are not passes; emulator results are not physical-device evidence.")
    return "\n".join(lines)


def _get(url: str):
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json",
                                                   "User-Agent": "chunks-acceptance-report"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_checks(repo: str, sha: str) -> list[dict]:
    checks, page = [], 1
    while True:
        data = _get(f"https://api.github.com/repos/{repo}/commits/{sha}/check-runs?per_page=100&page={page}")
        checks += data["check_runs"]
        if len(checks) >= data["total_count"] or not data["check_runs"]:
            return checks
        page += 1


def attach_steps(repo: str, checks: list[dict]) -> None:
    for check in checks:
        if classify_check(check)[0] != FAILED:
            continue
        match = re.search(r"/job/(\d+)", check.get("details_url") or "")
        if match:
            try:
                check["steps"] = _get(f"https://api.github.com/repos/{repo}/actions/jobs/{match.group(1)}").get("steps", [])
            except Exception:  # step detail is advisory only
                check["steps"] = []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default="gamesbyian/Chunks")
    parser.add_argument("--sha")
    parser.add_argument("--fixture", help="JSON file with a check_runs list (offline)")
    parser.add_argument("--profile", default="integration")
    parser.add_argument("--draft", action="store_true", help="the pull request is still a draft")
    parser.add_argument("--waive", action="append", default=[], metavar="GATE=REASON",
                        help="explicit named waiver; echoed in the report")
    args = parser.parse_args(argv)
    config = load_config()
    waivers = {}
    for item in args.waive:
        gate, _, reason = item.partition("=")
        if not reason.strip():
            parser.error("a waiver needs a reason: GATE=REASON")
        waivers[gate] = reason.strip()
    if args.fixture:
        checks = json.loads(Path(args.fixture).read_text(encoding="utf-8"))["check_runs"]
        sha = args.sha or "(fixture)"
    else:
        if not args.sha:
            parser.error("--sha is required (the exact commit being accepted)")
        checks, sha = fetch_checks(args.repo, args.sha), args.sha
        attach_steps(args.repo, checks)
    report = evaluate(checks, profile_gates(config, args.profile), config, waivers, args.draft)
    print(render(report, sha, args.profile))
    return 0 if report["acceptable"] else 1


if __name__ == "__main__":
    sys.exit(main())
