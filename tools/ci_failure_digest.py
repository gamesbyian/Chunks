#!/usr/bin/env python3
"""Bounded, secret-free CI failure digest. Never treats missing tests as success.

Consumes JUnit structure only (test names and counts), NOT exception messages,
system-out, environment secrets, or protected endpoint details. Full logs remain
in GitHub Actions; this is an index and an honest evidence-execution receipt.
"""
import argparse
import glob
import hashlib
import json
import math
import os
import re
from pathlib import Path
import xml.etree.ElementTree as ET

VERSION = 1

# JUnit test names can contain arbitrary input values (notably parameters).
# Summaries may be public even when original JUnit artifacts are restricted.
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_.$]*\\Z")


def safe_junit_identifier(value):
    """Keep conventional static test labels; redact arbitrary data-bearing ones."""
    if value and len(value) <= 160 and IDENTIFIER.fullmatch(value):
        return value
    return "<redacted-identifier>"


def safe_report_reference(path):
    """Opaque correlation identifier: no local path or filename is published."""
    digest = hashlib.sha256(os.fsencode(path)).hexdigest()[:20]
    return "junit-sha256-" + digest


def junit_declared_count_conflicts(root):
    """Check optional JUnit summary totals against actual testcase elements.

    The top-level testsuites aggregate and each testsuite may advertise
    counts. Accept reports with no totals, but never trust contradictory
    declared totals over the actual case outcomes. No XML text is exposed.
    """
    nodes = [root] if root.tag == "testsuites" else []
    nodes.extend(root.iter("testsuite"))
    for node in nodes:
        cases = list(node.iter("testcase"))
        counts = {
            "tests": len(cases),
            "failures": sum(c.find("failure") is not None for c in cases),
            "errors": sum(c.find("error") is not None for c in cases),
            "skipped": sum(c.find("skipped") is not None for c in cases),
        }
        for key, actual in counts.items():
            reported = node.get(key)
            if reported is None:
                continue
            if not re.fullmatch(r"[0-9]+", reported):
                return True
            if int(reported) != actual:
                return True
    return False


def make_digest(paths, status, suite, env=None):
    env = os.environ if env is None else env
    tests = failures = skipped = 0
    failed_cases = []
    test_cases = []
    seen_test_ids = set()
    invalid = []
    reports = 0
    timed_cases = 0
    invalid_case_times = 0
    class_seconds = {}
    for path in sorted(set(paths)):
        try:
            root = ET.parse(path).getroot()
            if root.tag not in ("testsuite", "testsuites"):
                raise ValueError("unexpected JUnit root")
            if junit_declared_count_conflicts(root):
                invalid.append({"report": safe_report_reference(path),
                                "problem": "JUnitDeclaredCountMismatch"})
            cases = root.findall(".//testcase")
            reports += 1
            for case in cases:
                tests += 1
                identity = (case.get("classname") or "") + "\\0" + (case.get("name") or "")
                test_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()
                outcome = ("skipped" if case.find("skipped") is not None else
                           "failed" if case.find("failure") is not None else
                           "error" if case.find("error") is not None else "passed")
                if len(test_cases) < 2500:
                    test_cases.append({"id_sha256": test_id, "outcome": outcome})
                if test_id in seen_test_ids:
                    invalid.append({"report": "duplicate-junit-identity", "problem": "DuplicateTestIdentity"})
                seen_test_ids.add(test_id)
                # Timing is advisory and separate from acceptance. A missing or
                # nonfinite JUnit duration cannot invent an executed test.
                duration_text = case.get("time")
                if duration_text is not None:
                    try:
                        duration = float(duration_text)
                        if not math.isfinite(duration) or duration < 0:
                            raise ValueError("invalid duration")
                        # Class identifiers only, sanitized before publishing.
                        klass = safe_junit_identifier(case.get("classname"))
                        class_seconds[klass] = class_seconds.get(klass, 0.0) + duration
                        timed_cases += 1
                    except (TypeError, ValueError, OverflowError):
                        invalid_case_times += 1
                if case.find("skipped") is not None:
                    skipped += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    failures += 1
                    if len(failed_cases) < 20:
                        # No exception text, output, paths or environment values.
                        failed_cases.append({
                            "class": safe_junit_identifier(case.get("classname")),
                            "test": safe_junit_identifier(case.get("name")),
                        })
        except (ET.ParseError, ValueError, OSError) as exc:
            invalid.append({"report": safe_report_reference(path),
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
    slow_classes = sorted(class_seconds.items(), key=lambda pair: (-pair[1], pair[0]))[:8]
    timing = {
        "case_duration_unit": "seconds",
        "valid_case_durations": timed_cases,
        "invalid_case_durations": invalid_case_times,
        "missing_case_durations": max(0, tests - timed_cases - invalid_case_times),
        "total_case_seconds": round(sum(class_seconds.values()), 2),
        "slowest_classes": [
            {"class": name, "summed_case_seconds": round(seconds, 2)}
            for name, seconds in slow_classes
        ],
        "interpretation": "Sum of testcase durations, not wall time or per-test concurrency",
    }
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
        "test_cases": test_cases,
        "test_identity_count": len(seen_test_ids),
        "test_identity_coverage_complete": len(test_cases) == tests,
        "invalid_reports": invalid[:20],
        "timing": timing,
        "classification_confidence": "observed" if failures or invalid else "unclassified",
        "next_action": next_action,
        "security_note": "No stdout, exception messages, protected URLs or credentials are copied",
    }


def rejects_unverified_green(digest, status, require_executed):
    """Fail closed only when prior steps claimed green; preserve original red cause."""
    return bool(require_executed and status.lower() == "success" and (
        not digest["evidence_executed"] or digest["junit"]["failed"] > 0
    ))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--glob", action="append", default=[], dest="patterns")
    parser.add_argument("--status", required=True)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--require-executed", action="store_true")
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
            out.write(f"- Source: `{digest['source_sha'] or 'unknown'}`; job: "
                      f"`{digest['reported_job_status']}`; phase: "
                      f"`{digest['phase']}`\n")
            out.write(f"- JUnit: {j['reports']} reports; {j['executed']} executed, "
                      f"{j['failed']} failed, {j['skipped']} skipped\n")
            if digest["timing"]["slowest_classes"]:
                top = digest["timing"]["slowest_classes"][:3]
                out.write("- Highest cumulative test-class durations (not wall time): "
                          + ", ".join(f"`{v['class']}` {v['summed_case_seconds']:.1f}s"
                                      for v in top) + "\n")
            if digest["timing"]["invalid_case_durations"]:
                out.write(f"- JUnit invalid duration fields: "
                          f"{digest['timing']['invalid_case_durations']}\n")
            if digest["failed_cases"]:
                out.write("- First failing tests (no private failure text): "
                          + ", ".join(json.dumps(c["class"] + "." + c["test"])
                                      for c in digest["failed_cases"][:5]) + "\n")
            if digest["next_action"]:
                out.write(f"- Next: {digest['next_action']}\n")
            out.write("\n")
    print(json.dumps({"phase": digest["phase"], "junit": digest["junit"],
                      "evidence_executed": digest["evidence_executed"]}))
    if rejects_unverified_green(digest, args.status, args.require_executed):
        print("::error::Required JUnit evidence absent, invalid, all-skipped or failed")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
