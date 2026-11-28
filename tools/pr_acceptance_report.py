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
    # Exact, explicitly enumerated aliases for reusable installed jobs. Do not
    # match arbitrary suffixes or treat a successful coordinator as test proof.
    aliases = {"combined / two-server-combined": "two-server-combined",
               "remove / remote-delete-data": "remote-delete-data",
               "protocol / installed-protocol-client": "installed-protocol-client",
               "api29 / api29-downloads": "api29-downloads",
               "resume / installed-resume-death (29)": "installed-resume-death (29)",
               "resume / installed-resume-death (35)": "installed-resume-death (35)"}
    grouped: dict[str, list[dict]] = {}
    for check in checks:
        name = aliases.get(check["name"], check["name"])
        grouped.setdefault(name, []).append(check)
    # GitHub reports a wholly skipped reusable call under the caller job ID,
    # with no child check (observed on draft runs 45565806452 and 45565309738).
    # A newer such check must invalidate an older successful child. A caller
    # summary is never positive execution evidence for its child.
    parents = {"two-server-combined": "combined",
               "remote-delete-data": "remove",
               "installed-protocol-client": "protocol",
               "api29-downloads": "api29",
               "installed-resume-death (29)": "resume",
               "installed-resume-death (35)": "resume"}

    def newest(candidates):
        if not candidates:
            return None
        workflows = [
            re.fullmatch(r"https://github\.com/[^/]+/[^/]+/actions/runs/([0-9]+)/job/[0-9]+",
                         check.get("details_url") or "")
            for check in candidates
        ]
        if all(workflows):
            return max(zip(workflows, candidates),
                       key=lambda pair: (int(pair[0][1]), pair[1].get("id", 0)))[1]
        return max(candidates, key=lambda check: check.get("id", 0))

    by_name = {name: newest(candidates) for name, candidates in grouped.items()}
    rows, blockers = [], []
    for gate in gates:
        if gate in waivers:
            rows.append({"gate": gate, "state": "waived", "detail": waivers[gate]})
            continue
        # A skipped matrix job may appear without its numeric shard suffix.
        # Compare exact child and parent checks by the same newest-run order,
        # rather than letting a stale exact-name success hide a later skip.
        parent_names = {parents.get(gate), re.sub(r" \(\d+\)$", "", gate)} - {None, gate}
        candidates = [*grouped.get(gate, ())]
        for parent in sorted(parent_names):
            candidates.extend(grouped.get(parent, ()))
        check = newest(candidates)
        if check is None:
            state, detail = NOT_REPORTED, "no check run with this name on this commit"
        else:
            state, detail = classify_check(check)
            if aliases.get(check["name"], check["name"]) != gate:
                if state == PASSED:
                    state, detail = NOT_REPORTED, "caller or matrix summary succeeded without exact child execution"
                else:
                    detail += "; caller or matrix summary, no exact child execution"
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
    if report["acceptable"]:
        verdict = ("ACCEPTABLE with explicit waivers: all non-waived gates passed."
                   if any(row["state"] == "waived" for row in report["rows"])
                   else "ACCEPTABLE: every required gate executed and passed on this exact commit.")
    else:
        verdict = "NOT ACCEPTABLE. Blocking: " + "; ".join(report["blockers"])
    lines += ["", verdict]
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


def immutable_sha(value: str) -> bool:
    return bool(re.fullmatch(r"[0-9a-fA-F]{40}", value))


def fetch_checks(repo: str, sha: str) -> list[dict]:
    if not immutable_sha(sha):
        raise ValueError("Live acceptance requires a full immutable commit SHA")
    checks, page, expected = [], 1, None
    seen_ids = set()
    while True:
        # Include earlier executions so evaluate() can compare exact child
        # and unexpanded caller results across workflow runs and attempts.
        data = _get(f"https://api.github.com/repos/{repo}/commits/{sha}/check-runs?filter=all&per_page=100&page={page}")
        total, batch = data["total_count"], data["check_runs"]
        if type(total) is not int or total < 0 or not isinstance(batch, list):
            raise ValueError("Invalid check-run page metadata")
        if expected is None:
            expected = total
        if total != expected:
            raise ValueError("Check-run inventory changed during pagination; fetch again")
        for check in batch:
            if not isinstance(check, dict) or check.get("head_sha") != sha.lower():
                raise ValueError("Check-run source does not match the requested exact commit")
            identity = check.get("id")
            if type(identity) is not int or identity <= 0 or identity in seen_ids:
                raise ValueError("Missing or duplicate check-run identity")
            seen_ids.add(identity)
        checks.extend(batch)
        if len(checks) > expected:
            raise ValueError("Check-run page exceeded declared inventory")
        if len(checks) == expected:
            return checks
        if not batch:
            raise ValueError("Incomplete check-run inventory; no acceptance verdict")
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
        if not args.sha or not immutable_sha(args.sha):
            parser.error("--sha must be a full immutable 40-hex commit SHA")
        try:
            checks, sha = fetch_checks(args.repo, args.sha), args.sha
        except ValueError as exc:
            parser.error(str(exc))
        attach_steps(args.repo, checks)
    report = evaluate(checks, profile_gates(config, args.profile), config, waivers, args.draft)
    if args.fixture:
        print("OFFLINE FIXTURE: synthetic input, not live commit acceptance evidence.")
    print(render(report, sha, args.profile))
    return 0 if report["acceptable"] else 1


if __name__ == "__main__":
    sys.exit(main())
