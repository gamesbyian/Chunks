#!/usr/bin/env python3
"""Read-only GitHub Actions runner-cost report; no new CI dispatch or telemetry service.

Run: python3 tools/ci_runner_cost_report.py --repo gamesbyian/Chunks --branch BRANCH
Requires authenticated gh CLI only for live mode. Offline mode:
  --runs-json runs.json --jobs-dir downloaded-job-json/
The REST data is the source; durations are measured, not predicted or billed minutes.
Source-SHA groups may include multiple PR events (draft, ready, retry), not one dispatch.
Queue and first-failing-job times are observed proxies, not proof of human-actionable feedback.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


def instant(raw: str | None) -> datetime | None:
    if raw is None:
        return None
    value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if value.utcoffset() is None:
        raise ValueError("GitHub timestamp has no timezone")
    return value


def minutes(start: str | None, end: str | None) -> float:
    if not start or not end:
        if start or end:
            raise ValueError("Incomplete job timestamp pair")
        return 0.0
    delta = (instant(end) - instant(start)).total_seconds() / 60
    if delta < 0:
        raise ValueError("Negative job duration")
    return delta


def gh_json(path: str) -> dict:
    # No shell, authentication disclosure, API writes or unchecked arbitrary URL.
    result = subprocess.run(
        ["gh", "api", "--method", "GET", path],
        capture_output=True, text=True, check=True, timeout=60,
    )
    data = json.loads(result.stdout)
    if not isinstance(data, dict):
        raise ValueError("Unexpected GitHub JSON response")
    return data


def get_runs(repo: str, branch: str) -> list[dict]:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid owner/repository")
    if not branch or len(branch) > 200 or any(x in branch for x in ("\n", "\r")):
        raise ValueError("Invalid branch name")
    response = gh_json(
        f"repos/{repo}/actions/runs?branch={quote(branch, safe='')}&per_page=100"
    )
    runs = response.get("workflow_runs", [])
    if not isinstance(runs, list):
        raise ValueError("Invalid workflow_runs")
    if response.get("total_count", len(runs)) > len(runs):
        raise ValueError("More than 100 runs; narrow the branch/time window; do not report a partial total")
    for run in runs:
        run_id = int(run["id"])
        jobs = gh_json(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
        if jobs.get("total_count", len(jobs.get("jobs", []))) > len(jobs.get("jobs", [])):
            raise ValueError(f"Truncated jobs on run {run_id}")
        run["jobs"] = jobs["jobs"]
    return runs


def cost_report(runs: list[dict]) -> dict:
    """Completed runs only: sum each job's actual timestamps, including matrix jobs."""
    seen_runs = set()
    by_name = defaultdict(lambda: {
        "runs": 0, "cancelled_runs": 0, "failed_runs": 0,
        "runner_minutes": 0.0, "cancelled_run_runner_minutes": 0.0,
        "wall_minutes": 0.0, "job_window_minutes": 0.0, "queue_minutes": 0.0,
        "runs_with_timed_jobs": 0, "jobs": 0,
    })
    rows = []
    by_source = {}
    unattributed_runs = 0
    for run in runs:
        run_id = int(run["id"])
        if run_id in seen_runs:
            raise ValueError(f"Duplicate workflow run {run_id}")
        seen_runs.add(run_id)
        if run.get("status") != "completed":
            raise ValueError(f"Run {run_id} is not complete; reject partial measurements")
        name = run["name"]
        outcome = run["conclusion"]
        if not isinstance(name, str) or not name or not isinstance(outcome, str):
            raise ValueError("Missing workflow name or conclusion")
        jobs = run.get("jobs")
        if not isinstance(jobs, list):
            raise ValueError(f"Missing jobs on run {run_id}")
        seen_jobs = set()
        total = 0.0
        timed_starts = []
        timed_ends = []
        failed_job_ends = []
        for job in jobs:
            job_id = int(job["id"])
            if job_id in seen_jobs:
                raise ValueError(f"Duplicate job {job_id} on run {run_id}")
            seen_jobs.add(job_id)
            # GitHub sometimes gives skipped jobs inverted timestamps (e.g.
            # 05:34:23 start / 05:34:14 completion). No runner was allocated.
            duration = (0.0 if job.get("conclusion") == "skipped" else
                        minutes(job.get("started_at"), job.get("completed_at")))
            if duration == 0 and job.get("conclusion") not in ("skipped", "cancelled", None):
                if job.get("started_at") is None:
                    raise ValueError(f"Missing timing for executed job {job_id}")
            total += duration
            if job.get("conclusion") != "skipped" and job.get("started_at") and job.get("completed_at"):
                timed_starts.append(instant(job["started_at"]))
                timed_ends.append(instant(job["completed_at"]))
                if job.get("conclusion") == "failure":
                    failed_job_ends.append(instant(job["completed_at"]))
        # Unlike created_at→updated_at, this excludes queue and API update delays.
        # It is a job activity window, not a dependency-critical-path measurement.
        job_window = ((max(timed_ends) - min(timed_starts)).total_seconds() / 60
                      if timed_starts else None)
        wall = minutes(run.get("created_at"), run.get("updated_at"))
        dispatch = instant(run["created_at"]) if run.get("created_at") else None
        queue_minutes = ((min(timed_starts) - dispatch).total_seconds() / 60
                         if timed_starts and dispatch else None)
        first_failed_job_minutes = (
            (min(failed_job_ends) - dispatch).total_seconds() / 60
            if failed_job_ends and dispatch else None)
        if queue_minutes is not None and queue_minutes < 0:
            raise ValueError(f"Job starts before workflow created_at on run {run_id}")
        if first_failed_job_minutes is not None and first_failed_job_minutes < 0:
            raise ValueError(f"Failed job completes before workflow created_at on run {run_id}")
        group = by_name[name]
        group["runs"] += 1
        group["jobs"] += len(jobs)
        group["runner_minutes"] += total
        group["wall_minutes"] += wall
        if job_window is not None:
            group["job_window_minutes"] += job_window
            group["runs_with_timed_jobs"] += 1
            if queue_minutes is not None:
                group["queue_minutes"] += queue_minutes
        if outcome == "cancelled":
            group["cancelled_runs"] += 1
            group["cancelled_run_runner_minutes"] += total
        elif outcome == "failure":
            group["failed_runs"] += 1
        rows.append({
            "run_id": run_id, "workflow": name, "conclusion": outcome,
            "runner_minutes": round(total, 3), "wall_minutes": round(wall, 3),
            "job_window_minutes": round(job_window, 3) if job_window is not None else None,
            "queue_minutes": round(queue_minutes, 3) if queue_minutes is not None else None,
            "first_failed_job_completion_minutes": (
                round(first_failed_job_minutes, 3)
                if first_failed_job_minutes is not None else None),
        })
        sha = run.get("head_sha")
        if sha is None:
            unattributed_runs += 1  # Backwards-compatible with older offline fixtures.
        else:
            if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
                raise ValueError(f"Invalid source SHA on run {run_id}")
            revision = by_source.setdefault(sha.lower(), {
                "runs": 0, "cancelled_runs": 0, "failed_runs": 0,
                "zero_timed_job_runs": 0, "runner_minutes": 0.0,
                "cancelled_run_runner_minutes": 0.0, "run_ids": [],
                "workflows": [],
            })
            revision["runs"] += 1
            revision["runner_minutes"] += total
            revision["run_ids"].append(run_id)
            if name not in revision["workflows"]:
                revision["workflows"].append(name)
            if not timed_starts:
                revision["zero_timed_job_runs"] += 1
            if outcome == "cancelled":
                revision["cancelled_runs"] += 1
                revision["cancelled_run_runner_minutes"] += total
            elif outcome == "failure":
                revision["failed_runs"] += 1
    groups = {}
    for name, values in sorted(by_name.items()):
        groups[name] = {k: round(v, 3) if isinstance(v, float) else v
                        for k, v in values.items()}
    revision_groups = {
        sha: {key: (round(value, 3) if isinstance(value, float)
                    else sorted(value) if key == "workflows" else value)
              for key, value in group.items()}
        for sha, group in sorted(by_source.items())
    }
    return {
        "schema": "chunks-ci-runner-cost-v1",
        "source": "GitHub Actions job started_at/completed_at timestamps",
        "scope": "completed runs only; runner minutes are not billed-minute estimates; wall_minutes includes queue/API updates; job_window_minutes excludes them and is null without timed jobs; queue and first-failing-job completion are timing proxies, not actionable-feedback guarantees",
        "runs": len(rows),
        "runner_minutes": round(sum(x["runner_minutes"] for x in rows), 3),
        "cancelled_run_runner_minutes": round(
            sum(x["cancelled_run_runner_minutes"] for x in groups.values()), 3
        ),
        "by_workflow": groups, "by_source_sha": revision_groups,
        "runs_without_source_sha": unattributed_runs,
        "details": sorted(rows, key=lambda x: x["run_id"]),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", help="owner/repo (live mode)")
    parser.add_argument("--branch", help="exact PR branch (live mode)")
    parser.add_argument("--runs-json", type=Path, help="saved GET actions/runs JSON")
    parser.add_argument("--jobs-dir", type=Path, help="saved <run-id>.json GET jobs responses")
    parser.add_argument("--json-out", type=Path, help="write full grouped report to disk")
    args = parser.parse_args()
    if args.runs_json:
        if args.repo or args.branch or not args.jobs_dir:
            parser.error("Offline mode requires --runs-json and --jobs-dir only")
        data = json.loads(args.runs_json.read_text(encoding="utf-8"))
        runs = data["workflow_runs"]
        if data.get("total_count", len(runs)) > len(runs):
            raise ValueError("Incomplete saved workflow-run page")
        for run in runs:
            payload = json.loads(
                (args.jobs_dir / f"{int(run['id'])}.json").read_text(encoding="utf-8")
            )
            if payload.get("total_count", len(payload["jobs"])) > len(payload["jobs"]):
                raise ValueError(f"Truncated offline jobs for run {run['id']}")
            run["jobs"] = payload["jobs"]
    else:
        if not (args.repo and args.branch) or args.jobs_dir:
            parser.error("Live mode requires --repo and --branch")
        runs = get_runs(args.repo, args.branch)
    report = cost_report(runs)
    print(f"Completed runs: {report['runs']}; aggregate runner minutes: "
          f"{report['runner_minutes']:.2f}; canceled-run runner minutes: "
          f"{report['cancelled_run_runner_minutes']:.2f}")
    for name, item in report["by_workflow"].items():
        print(f"{name}: runs={item['runs']} cancelled={item['cancelled_runs']} "
              f"runner_min={item['runner_minutes']:.2f} "
              f"cancelled_min={item['cancelled_run_runner_minutes']:.2f} "
              f"job_window_min={item['job_window_minutes']:.2f} "
              f"timed_runs={item['runs_with_timed_jobs']}")
    for sha, group in report["by_source_sha"].items():
        print(f"source={sha[:12]} runs={group['runs']} "
              f"runner_min={group['runner_minutes']:.2f} "
              f"cancelled_min={group['cancelled_run_runner_minutes']:.2f} "
              f"no_timed_jobs={group['zero_timed_job_runs']}")
    if args.json_out:
        args.json_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                 encoding="utf-8")


if __name__ == "__main__":
    main()
