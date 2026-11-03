#!/usr/bin/env python3
"""Read-only snapshot of CI already running before an optional PR revision push.

Run: python3 tools/ci_active_runner_snapshot.py --repo gamesbyian/Chunks --branch BRANCH
Requires authenticated gh CLI. Does not change workflows, cancel runs, or block pushes.
Job-time figures use current GitHub timestamps and are provisional, not billed usage
or recoverable savings. Inspect failures and preserve required acceptance.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import re
import subprocess
from urllib.parse import quote

STATES = ("in_progress", "queued", "waiting")
SHA_PATTERN = re.compile(r"[a-fA-F0-9]{40}")


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError("Missing GitHub timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("Unzoned GitHub timestamp")
    return parsed


def gh_json(path):
    result = subprocess.run(
        ["gh", "api", "--method", "GET", path],
        capture_output=True, text=True, check=True, timeout=60,
    )
    data = json.loads(result.stdout)
    if not isinstance(data, dict):
        raise ValueError("Invalid GitHub API response")
    return data


def get_snapshot_data(repo, branch, fetch=gh_json):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository name")
    if not branch or len(branch) > 200 or any(c in branch for c in "\r\n"):
        raise ValueError("Invalid branch")
    collected = []
    seen = set()
    for state in STATES:
        response = fetch(
            f"repos/{repo}/actions/runs?branch={quote(branch, safe='')}"
            f"&status={state}&per_page=100"
        )
        runs = response.get("workflow_runs")
        if not isinstance(runs, list):
            raise ValueError("Missing workflow runs")
        if response.get("total_count", len(runs)) > len(runs):
            raise ValueError(f"Incomplete {state} workflow page")
        for run in runs:
            run_id = int(run["id"])
            if run_id in seen:
                raise ValueError(f"Repeated active workflow run {run_id}")
            seen.add(run_id)
            collected.append(run)
    if len(collected) > 50:
        raise ValueError("Too many active runs; narrow scope rather than sampling")
    jobs_by_run = {}
    for run in collected:
        run_id = int(run["id"])
        payload = fetch(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=all&per_page=100")
        jobs = payload.get("jobs")
        if not isinstance(jobs, list):
            raise ValueError(f"Missing jobs on run {run_id}")
        if payload.get("total_count", len(jobs)) > len(jobs):
            raise ValueError(f"Incomplete jobs on run {run_id}")
        jobs_by_run[run_id] = jobs
    return collected, jobs_by_run


def snapshot(runs, jobs_by_run, now):
    if now.utcoffset() is None:
        raise ValueError("Snapshot clock requires timezone")
    seen = set()
    totals = {
        "active_workflows": 0, "queued_or_waiting_workflows": 0,
        "running_jobs": 0, "completed_jobs": 0,
        "skipped_jobs": 0, "unknown_timing_jobs": 0,
        "known_completed_runner_minutes": 0.0, "estimated_running_runner_minutes": 0.0,
    }
    rows = []
    for run in runs:
        run_id = int(run["id"])
        if run_id in seen:
            raise ValueError(f"Repeated run {run_id}")
        seen.add(run_id)
        sha = run.get("head_sha")
        if not isinstance(sha, str) or not SHA_PATTERN.fullmatch(sha):
            raise ValueError(f"Invalid GitHub head SHA on run {run_id}")
        status = run.get("status")
        if status not in STATES:
            raise ValueError(f"Run {run_id} is not in a snapshot-eligible state")
        if status == "in_progress":
            totals["active_workflows"] += 1
        else:
            totals["queued_or_waiting_workflows"] += 1
        jobs = jobs_by_run.get(run_id)
        if not isinstance(jobs, list):
            raise ValueError(f"Missing jobs for run {run_id}")
        known_elapsed = 0.0
        running = 0
        unknown = 0
        seen_jobs = set()
        for job in jobs:
            job_id = int(job["id"])
            if job_id in seen_jobs:
                raise ValueError(f"Repeated job {job_id} on run {run_id}")
            seen_jobs.add(job_id)
            state = job.get("status")
            if state in ("queued", "waiting", "pending", "requested") or job.get("conclusion") == "skipped":
                if job.get("conclusion") == "skipped":
                    totals["skipped_jobs"] += 1
                continue
            start, end = job.get("started_at"), job.get("completed_at")
            if state not in ("completed", "in_progress") or not start or (state == "completed" and not end):
                unknown += 1
                totals["unknown_timing_jobs"] += 1
                continue
            begin = timestamp(start)
            finish = timestamp(end) if end else now
            elapsed = (finish - begin).total_seconds() / 60
            if elapsed < 0:
                unknown += 1
                totals["unknown_timing_jobs"] += 1
                continue
            known_elapsed += elapsed
            if state == "in_progress":
                running += 1
                totals["running_jobs"] += 1
                totals["estimated_running_runner_minutes"] += elapsed
            else:
                totals["completed_jobs"] += 1
                totals["known_completed_runner_minutes"] += elapsed
        rows.append({
            "run_id": run_id,
            "head_sha": sha.lower(),
            "status": status,
            "running_jobs": running,
            "unknown_timing_jobs": unknown,
            "known_elapsed_runner_minutes": round(known_elapsed, 3),
        })
    result = {
        "schema": "chunks-ci-active-runner-snapshot-v1",
        "observed_at": now.isoformat(),
        "scope": ("Read-only moment-in-time branch snapshot. Running-job minutes are "
                  "elapsed estimates, completed-job minutes are timestamp observations; "
                  "unknown jobs are not counted as zero. This is sunk runner time, "
                  "not future or avoidable savings. No automatic push/cancel decision."),
        **{k: round(v, 3) if isinstance(v, float) else v for k, v in totals.items()},
        "runs": sorted(rows, key=lambda x: x["run_id"]),
    }
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", required=True, help="owner/repo")
    p.add_argument("--branch", required=True, help="exact PR branch")
    p.add_argument("--json-out", help="optional output JSON path")
    args = p.parse_args()
    runs, jobs = get_snapshot_data(args.repo, args.branch)
    result = snapshot(runs, jobs, datetime.now(timezone.utc))
    print(f"Active workflow runs: {result['active_workflows']}; "
          f"queued/waiting: {result['queued_or_waiting_workflows']}; "
          f"running jobs: {result['running_jobs']}; "
          f"already consumed: {result['known_completed_runner_minutes']:.2f} "
          f"completed + ~{result['estimated_running_runner_minutes']:.2f} running "
          f"runner-minutes; unknown-timing jobs: {result['unknown_timing_jobs']}")
    for row in result["runs"]:
        print(f"run={row['run_id']} head={row['head_sha'][:12]} "
              f"status={row['status']} running={row['running_jobs']} "
              f"known_elapsed_min={row['known_elapsed_runner_minutes']:.2f}")
    if args.json_out:
        from pathlib import Path
        Path(args.json_out).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n",
                                       encoding="utf-8")


if __name__ == "__main__":
    main()
