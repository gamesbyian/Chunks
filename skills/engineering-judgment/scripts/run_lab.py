#!/usr/bin/env python3
"""Create isolated *task packages* and verify built-in behavioral oracles.

The lab is intentionally toy/stdlib only. It does not run agents or sandbox
untrusted candidate code. External code evaluation requires an explicit opt-in
and MUST happen inside a disposable OS/container sandbox with no secrets/network.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TASKS = ROOT / "lab" / "tasks"
MANIFEST = ROOT / "lab" / "manifest.json"
TEST_COUNT = re.compile(r"Ran (\d+) tests? in")
TIME_LIMIT = 12


def task_ids(root=ROOT):
    manifest = json.loads((root / "lab" / "manifest.json").read_text(encoding="utf-8"))
    ids = [item["id"] for item in manifest["tasks"]]
    if len(ids) != len(set(ids)) or not ids:
        raise ValueError("Empty or duplicate lab task IDs")
    for ident in ids:
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", ident):
            raise ValueError("Unsafe task ID")
    return ids


def package_task(task, destination, root=ROOT):
    if task not in task_ids(root):
        raise ValueError("Unknown task")
    destination = Path(destination)
    if destination.is_symlink() or destination.exists():
        raise FileExistsError("Refusing to overwrite an existing evaluation package")
    src = root / "lab" / "tasks" / task
    destination.mkdir(parents=True)
    try:
        for name in ("task.md", "candidate.py"):
            shutil.copyfile(src / name, destination / name)
    except Exception:
        shutil.rmtree(destination)
        raise
    return {"task": task, "paths": ["task.md", "candidate.py"],
            "warning": "No hidden tests, reference solutions or answer key included; this is a public practice task, not a blind benchmark."}


def run_tests(task, implementation, root=ROOT):
    if task not in task_ids(root):
        raise ValueError("Unknown task")
    taskdir = root / "lab" / "tasks" / task
    if implementation in ("baseline", "reference"):
        source = taskdir / ("candidate.py" if implementation == "baseline" else "reference.py")
    else:
        source = Path(implementation)
        if not source.is_file():
            raise ValueError("External candidate must be an existing Python file")
    with tempfile.TemporaryDirectory(prefix="judgment-fixture-") as td:
        sandbox = Path(td)
        shutil.copyfile(source, sandbox / "candidate.py")
        shutil.copyfile(taskdir / "test_contract.py", sandbox / "test_contract.py")
        # Use a deliberately minimal environment. This is NOT an OS sandbox.
        env = {"PATH": os.environ.get("PATH", ""), "PYTHONPATH": td,
               "PYTHONDONTWRITEBYTECODE": "1"}
        digest = hashlib.sha256((sandbox / "candidate.py").read_bytes()).hexdigest()
        try:
            run = subprocess.run(
                [sys.executable, "-B", "-m", "unittest", "discover",
                 "-s", td, "-p", "test_contract.py", "-v"],
                cwd=td, env=env, capture_output=True, text=True,
                timeout=TIME_LIMIT, check=False,
            )
            report = run.stdout + "\n" + run.stderr
            found = TEST_COUNT.search(report)
            executed = int(found.group(1)) if found else 0
            return {"task": task, "implementation": implementation if implementation in ("baseline", "reference") else "candidate",
                    "sha256": digest, "executed_tests": executed, "returncode": run.returncode,
                    "passed": run.returncode == 0 and executed > 0,
                    "test_summary": next((line.strip() for line in report.splitlines() if line.strip().startswith("Ran ")), "No executed-test receipt"),
                    "output_tail": "\n".join(report.splitlines()[-12:])}
        except subprocess.TimeoutExpired:
            return {"task": task, "implementation": implementation, "sha256": digest,
                    "executed_tests": 0, "passed": False, "timed_out": True,
                    "output_tail": "Execution exceeded local fixture time limit"}


def selfcheck(root=ROOT):
    results = []
    for task in task_ids(root):
        original = run_tests(task, "baseline", root)
        repaired = run_tests(task, "reference", root)
        valid = (original["executed_tests"] > 0
                 and repaired["executed_tests"] == original["executed_tests"]
                 and not original["passed"] and repaired["passed"])
        results.append({"task": task, "baseline": original, "reference": repaired,
                        "valid_two_sided_gate": valid})
    return {"tasks": len(results), "valid_tasks": sum(item["valid_two_sided_gate"] for item in results),
            "results": results,
            "scope": "Built-in toy Python fixtures, not real Android/runtime acceptance"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--selfcheck", action="store_true")
    modes.add_argument("--export", metavar="TASK_ID")
    modes.add_argument("--evaluate", metavar="TASK_ID")
    parser.add_argument("--output", type=Path, help="New directory for --export")
    parser.add_argument("--candidate", type=Path, help="Python file for --evaluate")
    parser.add_argument("--permit-untrusted-code", action="store_true", help="Explicitly acknowledge untrusted Python executes without OS sandbox")
    args = parser.parse_args()
    try:
        if args.selfcheck:
            report = selfcheck()
            print(json.dumps(report, indent=2))
            return 0 if report["tasks"] == report["valid_tasks"] else 1
        if args.export:
            if not args.output:
                parser.error("--export requires --output")
            print(json.dumps(package_task(args.export, args.output), indent=2))
            return 0
        if not args.candidate or not args.permit_untrusted_code:
            parser.error("--evaluate requires --candidate and --permit-untrusted-code; first run in a disposable no-secret OS sandbox")
        print(json.dumps(run_tests(args.evaluate, args.candidate), indent=2))
        return 0  # candidate failure is recorded; do not suppress report generation
    except (ValueError, OSError, FileExistsError) as exc:
        print("FAIL:", exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
