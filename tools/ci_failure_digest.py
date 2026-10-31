#!/usr/bin/env python3
"""Bounded, secret-free CI failure digest. Never treats missing tests as success.

Consumes JUnit structure only (test names and counts), NOT exception messages,
system-out, environment secrets, or protected endpoint details. Full logs remain
in GitHub Actions; this is an index and an honest evidence-execution receipt.
"""
import argparse
import glob
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

VERSION = 1


def make_digest(paths, status, suite, env=None):
    env = os.environ if env is None else env
    tests = failures = skipped = 0
    failed_cases = []
    invalid = []
    reports = 0
    for path in sorted(set(paths)):
        try:
            root = ET.parse(path).getroot()
            if root.tag not in ("testsuite", "testsuites"):
                raise ValueError("unexpected JUnit root")
            cases = root.findall(".//testcase")
            reports += 1
            for case in cases:
                tests += 1
                if case.find("skipped") is not None:
                    skipped += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    failures += 1
                    if len(failed_cases) < 20:
                        # No exception text, output, paths or environment values.
                        failed_cases.append({
                            "class": (case.get("classname") or "")[:160],
                            "test": (case.get("name") or "")[:160],
                        })
        except (ET.ParseError, ValueError, OSError) as exc:
            invalid.append({"report": Path(path).name[:120],
                            "problem": type(exc).__name__})
    status = status.lower()
    if invalid:
        phase = "harness_report"
        next_action = "Inspect malformed JUnit XML and the originating test step"
    elif failures:
        phase = "test_assertion"
        next_action = "Open first failing test in the JUnit artifact and reproduce the targeted suite"
    elif status == "cancelled":
        phase = "cancellation"
        next_action = "Confirm whether this run was superseded before re-running"
    elif status in ("failure", "timed_out"):
        phase = "unknown_pre_or_post_test"
        next_action = "Inspect the earliest failed Actions step; tests may not have started"
    elif reports == 0 or tests == skipped:
        phase = "unexecuted"
        next_action = "Verify that the intended suite produced JUnit results"
    else:
        phase = "none"
        next_action = None
    base = env.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
    repository = env.get("GITHUB_REPOSITORY", "")
    run_id = env.get("GITHUB_RUN_ID", "")
    run_url = f"{base}/{repository}/actions/runs/{run_id}" if repository and run_id else None
    return {
        "schema_version": VERSION,
        "workflow": env.get("GITHUB_WORKFLOW"),
        "run_id": run_id or None,
        "run_attempt": env.get("GITHUB_RUN_ATTEMPT"),
        "job": env.get("GITHUB_JOB"),
        "run_url": run_url,
        "event": env.get("GITHUB_EVENT_NAME"),
        "source_sha": env.get("GITHUB_SHA"),
        "source_ref": env.get("GITHUB_REF"),
        "runner_os": env.get("RUNNER_OS"),
        "suite": suite,
        "reported_job_status": status,
        "evidence_executed": bool(reports and tests > skipped and not invalid),
        "phase": phase,
        "junit": {"reports": reports, "tests": tests, "failed": failures,
                  "skipped": skipped, "executed": tests - skipped},
        "failed_cases": failed_cases,
        "invalid_reports": invalid[:20],
        "classification_confidence": "observed" if failures or invalid else "unclassified",
        "next_action": next_action,
        "security_note": "No stdout, exception messages, protected URLs or credentials are copied",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--glob", action="append", default=[], dest="patterns")
    parser.add_argument("--status", required=True)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    paths = [path for pattern in args.patterns
             for path in glob.glob(pattern, recursive=True)]
    digest = make_digest(paths, args.status, args.suite)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(digest, indent=2, sort_keys=True) + "\n",
                                 encoding="utf-8")
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            j = digest["junit"]
            out.write(f"### CI evidence: {args.suite}\n\n")
            out.write(f"- Source: \`{digest['source_sha'] or 'unknown'}\`; job: "
                      f"\`{digest['reported_job_status']}\`; phase: "
                      f"\`{digest['phase']}\`\n")
            out.write(f"- JUnit: {j['reports']} reports; {j['executed']} executed, "
                      f"{j['failed']} failed, {j['skipped']} skipped\n")
            if digest["failed_cases"]:
                out.write("- First failing tests (no private failure text): "
                          + ", ".join(json.dumps(c["class"] + "." + c["test"])
                                      for c in digest["failed_cases"][:5]) + "\n")
            if digest["next_action"]:
                out.write(f"- Next: {digest['next_action']}\n")
            out.write("\n")
    print(json.dumps({"phase": digest["phase"], "junit": digest["junit"],
                      "evidence_executed": digest["evidence_executed"]}))


if __name__ == "__main__":
    main()
