#!/usr/bin/env python3
"""Read-only GitHub Actions runner-cost report; no new CI dispatch or telemetry service.

Run: python3 tools/ci_runner_cost_report.py --repo gamesbyian/Chunks --branch BRANCH
For long branches: add --created YYYY-MM-DD..YYYY-MM-DD; live pages are exhaustive up to GitHub's 1,000-result search bound.
Requires authenticated gh CLI only for live mode. Offline mode:
  --runs-json runs.json --jobs-dir downloaded-job-json/
The REST data is the source; durations are measured, not predicted or billed minutes.
GitHub head_sha groups may include draft, ready and retry events; not one dispatch.
On pull_request, head_sha need not be the actual GITHUB_SHA merge checkout.
Queue and first-failing-job times are observed proxies, not proof of human-actionable feedback.
Aggregate job_window_minutes intentionally includes idle gaps between workflow rerun attempts.
Attempt-window and retry-gap metrics separate these, without changing runner-minute totals.
Step hotspots use GitHub step numbers rather than names to avoid leaking dynamic/secret labels.
ALL workflow rerun attempts are fetched; latest-only jobs silently undercount failed attempts.
Missing/incomplete step data is reported as unknown; it is never interpreted as zero time.
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


MAX_PAGE_RESULTS = 1000  # GitHub caps filtered workflow-run searches at 1,000.


def created_range(value: str | None) -> str | None:
    """Require a bounded, real calendar-date range accepted by GitHub REST."""
    if value is None:
        return None
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})\.\.(\d{4}-\d{2}-\d{2})", value)
    if not match:
        raise ValueError("created must be YYYY-MM-DD..YYYY-MM-DD")
    try:
        first, last = (datetime.strptime(part, "%Y-%m-%d").date()
                       for part in match.groups())
    except ValueError as exc:
        raise ValueError("Invalid created calendar date") from exc
    if last < first:
        raise ValueError("created date range is reversed")
    return value


def paginated_items(path: str, key: str, label: str) -> list[dict]:
    """Fetch complete stable pages, never a plausible-looking partial total."""
    collected: list[dict] = []
    seen_ids: set[int] = set()
    expected = None
    for page in range(1, MAX_PAGE_RESULTS // 100 + 1):
        payload = gh_json(f"{path}&per_page=100&page={page}")
        count = payload.get("total_count")
        if type(count) is not int or count < 0:
            raise ValueError(f"Invalid {label} total_count")
        if count == MAX_PAGE_RESULTS and key == "workflow_runs":
            # GitHub may cap a filtered search at exactly 1,000 results
            # even if the underlying match set is larger. Never certify
            # the boundary value as a complete sample.
            raise ValueError(
                "At the 1000-workflow search ceiling; narrow with --created "
                "before claiming an exhaustive report"
            )
        if count > MAX_PAGE_RESULTS:
            raise ValueError(
                f"More than {MAX_PAGE_RESULTS} {label}; narrow with --created "
                "or a smaller branch; do not present a partial total"
            )
        if expected is None:
            expected = count
        elif count != expected:
            raise ValueError(f"{label} total_count changed during pagination")
        items = payload.get(key)
        if not isinstance(items, list):
            raise ValueError(f"Invalid {label} page")
        wanted = min(100, expected - len(collected))
        if len(items) != wanted:
            raise ValueError(f"Incomplete {label} page {page}")
        for item in items:
            if not isinstance(item, dict) or type(item.get("id")) is not int:
                raise ValueError(f"Invalid {label} item")
            identity = item["id"]
            if identity in seen_ids:
                raise ValueError(f"Duplicate {label} item across pages: {identity}")
            seen_ids.add(identity)
        collected.extend(items)
        if len(collected) == expected:
            return collected
    raise ValueError(f"Incomplete {label} pagination")


def get_runs(repo: str, branch: str, created: str | None = None) -> list[dict]:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid owner/repository")
    if not branch or len(branch) > 200 or any(x in branch for x in ("\n", "\r")):
        raise ValueError("Invalid branch name")
    window = created_range(created)
    path = f"repos/{repo}/actions/runs?branch={quote(branch, safe='')}"
    if window is not None:
        path += f"&created={quote(window, safe='')}"
    runs = paginated_items(path, "workflow_runs", "workflow runs")
    # Stop before N job-list requests if the branch is still changing. A
    # completed-only report cannot represent an in-progress PR revision.
    for run in runs:
        if run.get("status") != "completed":
            raise ValueError(f"Run {run['id']} is not complete; use a settled window")
    for run in runs:
        run_id = int(run["id"])
        run["jobs"] = paginated_items(
            f"repos/{repo}/actions/runs/{run_id}/jobs?filter=all",
            "jobs", f"jobs on run {run_id}",
        )
    return runs


def cost_report(runs: list[dict]) -> dict:
    """Completed runs only: sum each job's actual timestamps, including matrix jobs."""
    seen_runs = set()
    by_name = defaultdict(lambda: {
        "runs": 0, "cancelled_runs": 0, "failed_runs": 0,
        "runner_minutes": 0.0, "cancelled_run_runner_minutes": 0.0,
        "wall_minutes": 0.0, "job_window_minutes": 0.0, "queue_minutes": 0.0,
        "runs_with_timed_jobs": 0, "jobs": 0,
        "runs_with_reruns": 0, "prior_attempt_runner_minutes": 0.0,
        "failed_jobs_across_attempts": 0,
        "complete_attempt_window_minutes": 0.0, "retry_idle_gap_minutes": 0.0,
        "runs_with_complete_attempt_windows": 0,
    })
    rows = []
    step_by_workflow = defaultdict(lambda: {
        "jobs_with_steps": 0, "jobs_without_steps": 0,
        "timed_steps": 0, "incomplete_steps": 0, "skipped_steps": 0,
        "invalid_step_timestamps": 0,
        "timed_step_minutes": 0.0, "hotspots": [],
    })
    by_head_sha = {}
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
        run_attempt = run.get("run_attempt", 1)
        if type(run_attempt) is not int or run_attempt < 1:
            raise ValueError(f"Invalid run attempt on workflow {run_id}")
        observed_attempts = set()
        prior_attempt_minutes = 0.0
        failed_jobs_across_attempts = 0
        timed_starts = []
        timed_ends = []
        attempt_job_intervals = defaultdict(list)
        failed_job_ends = []
        for job in jobs:
            job_id = int(job["id"])
            if job_id in seen_jobs:
                raise ValueError(f"Duplicate job {job_id} on run {run_id}")
            seen_jobs.add(job_id)
            attempt = job.get("run_attempt", 1)
            if type(attempt) is not int or attempt < 1 or attempt > run_attempt:
                raise ValueError(f"Invalid job attempt on job {job_id}")
            observed_attempts.add(attempt)
            if job.get("conclusion") == "failure":
                failed_jobs_across_attempts += 1
            # GitHub sometimes gives skipped jobs inverted timestamps (e.g.
            # 05:34:23 start / 05:34:14 completion). No runner was allocated.
            duration = (0.0 if job.get("conclusion") == "skipped" else
                        minutes(job.get("started_at"), job.get("completed_at")))
            if duration == 0 and job.get("conclusion") not in ("skipped", "cancelled", None):
                if job.get("started_at") is None:
                    raise ValueError(f"Missing timing for executed job {job_id}")
            total += duration
            if attempt < run_attempt:
                prior_attempt_minutes += duration
            # Steps are included in the existing jobs REST response. Never issue
            # an additional Actions query or emit raw step labels/commands.
            if job.get("conclusion") != "skipped" and job.get("started_at") and job.get("completed_at"):
                activity = step_by_workflow[name]
                steps = job.get("steps")
                if steps is None:
                    activity["jobs_without_steps"] += 1
                elif not isinstance(steps, list):
                    raise ValueError(f"Invalid steps on job {job_id}")
                else:
                    activity["jobs_with_steps"] += 1
                    seen_step_numbers = set()
                    for step in steps:
                        if not isinstance(step, dict):
                            raise ValueError(f"Invalid step on job {job_id}")
                        if step.get("conclusion") == "skipped":
                            # GitHub can return inverted times for skipped steps.
                            activity["skipped_steps"] += 1
                            continue
                        begin, end = step.get("started_at"), step.get("completed_at")
                        if not begin or not end:
                            activity["incomplete_steps"] += 1
                            continue
                        number = step.get("number")
                        if type(number) is not int or number < 1:
                            raise ValueError(f"Missing step number on job {job_id}")
                        if number in seen_step_numbers:
                            raise ValueError(f"Duplicate step number on job {job_id}")
                        seen_step_numbers.add(number)
                        try:
                            elapsed = minutes(begin, end)
                        except ValueError:
                            # Step metadata can be unreliable on canceled jobs.
                            # Preserve complete job runner cost; mark this step
                            # as unusable timing instead of losing the entire audit.
                            activity["invalid_step_timestamps"] += 1
                            continue
                        activity["timed_step_minutes"] += elapsed
                        activity["timed_steps"] += 1
                        activity["hotspots"].append({
                            "run_id": run_id, "job_id": job_id,
                            "step_number": number, "minutes": round(elapsed, 3),
                        })
            if job.get("conclusion") != "skipped" and job.get("started_at") and job.get("completed_at"):
                timed_starts.append(instant(job["started_at"]))
                timed_ends.append(instant(job["completed_at"]))
                attempt_job_intervals[attempt].append((instant(job["started_at"]), instant(job["completed_at"])))
                if job.get("conclusion") == "failure":
                    failed_job_ends.append(instant(job["completed_at"]))
        # Unlike created_at→updated_at, this excludes queue and API update delays.
        # It is a job activity window, not a dependency-critical-path measurement.
        job_window = ((max(timed_ends) - min(timed_starts)).total_seconds() / 60
                      if timed_starts else None)
        # GitHub's cross-attempt min(start) -> max(end) window includes
        # human/API delay between a failed attempt and its later rerun.
        # Preserve the original metric, and distinguish per-attempt windows.
        windows = {}
        for attempt in range(1, run_attempt + 1):
            intervals = attempt_job_intervals.get(attempt, [])
            windows[attempt] = (
                (min(start for start, _ in intervals), max(end for _, end in intervals))
                if intervals else None
            )
        complete_windows = all(window is not None for window in windows.values())
        summed_attempt_window = (
            sum((end - start).total_seconds() / 60
                for start, end in windows.values() if start is not None)
            if complete_windows else None
        )
        # No idle-gap claim when a rerun attempt lacks job times, or attempts
        # overlap (unusual GitHub metadata / offline fixture).
        retry_idle_gap = None
        if complete_windows:
            ordered_windows = [windows[i] for i in range(1, run_attempt + 1)]
            deltas = [
                (ordered_windows[i][0] - ordered_windows[i - 1][1]).total_seconds() / 60
                for i in range(1, len(ordered_windows))
            ]
            if all(delta >= 0 for delta in deltas):
                retry_idle_gap = sum(deltas)
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
        group["prior_attempt_runner_minutes"] += prior_attempt_minutes
        group["failed_jobs_across_attempts"] += failed_jobs_across_attempts
        if run_attempt > 1:
            group["runs_with_reruns"] += 1
        if summed_attempt_window is not None:
            group["complete_attempt_window_minutes"] += summed_attempt_window
            group["runs_with_complete_attempt_windows"] += 1
        if retry_idle_gap is not None:
            group["retry_idle_gap_minutes"] += retry_idle_gap
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
            "reported_attempts": run_attempt,
            "job_attempts_observed": sorted(observed_attempts),
            # An absent attempt can mean no runner allocated; never silently
            # certify its cost as zero from an incomplete offline jobs payload.
            "attempts_without_jobs": sorted(set(range(1, run_attempt + 1)) - observed_attempts),
            "prior_attempt_runner_minutes": round(prior_attempt_minutes, 3),
            "failed_jobs_across_attempts": failed_jobs_across_attempts,
            "job_window_minutes": round(job_window, 3) if job_window is not None else None,
            "attempt_job_window_minutes": {
                str(attempt): (
                    round((window[1] - window[0]).total_seconds() / 60, 3)
                    if window is not None else None
                ) for attempt, window in windows.items()
            },
            "complete_attempt_window_minutes": (
                round(summed_attempt_window, 3) if summed_attempt_window is not None else None
            ),
            "retry_idle_gap_minutes": (
                round(retry_idle_gap, 3) if retry_idle_gap is not None else None
            ),
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
            revision = by_head_sha.setdefault(sha.lower(), {
                "runs": 0, "cancelled_runs": 0, "failed_runs": 0,
                "zero_timed_job_runs": 0, "runner_minutes": 0.0,
                "cancelled_run_runner_minutes": 0.0,
                "prior_attempt_runner_minutes": 0.0,
                "runs_with_reruns": 0, "run_ids": [],
                "workflows": [],
            })
            revision["runs"] += 1
            revision["runner_minutes"] += total
            revision["prior_attempt_runner_minutes"] += prior_attempt_minutes
            if run_attempt > 1:
                revision["runs_with_reruns"] += 1
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
    step_activity = {}
    for workflow, values in sorted(step_by_workflow.items()):
        step_activity[workflow] = {
            **{key: (round(value, 3) if isinstance(value, float) else value)
               for key, value in values.items() if key != "hotspots"},
            # Exact step number + job ID makes this reproducible while never
            # serializing step names, shell commands, URLs, or test output.
            "hotspots": sorted(values["hotspots"], key=lambda x: (
                -x["minutes"], x["run_id"], x["job_id"], x["step_number"],
            ))[:8],
        }
    revision_groups = {
        sha: {key: (round(value, 3) if isinstance(value, float)
                    else sorted(value) if key == "workflows" else value)
              for key, value in group.items()}
        for sha, group in sorted(by_head_sha.items())
    }
    return {
        "schema": "chunks-ci-runner-cost-v1",
        "source": "GitHub Actions job started_at/completed_at timestamps, all run attempts",
        "scope": "completed runs only; head_sha is PR branch identity, not necessarily the tested merge checkout; runner minutes are not billed minutes; job_window_minutes spans all rerun attempts including idle retry gaps, whereas complete_attempt_window_minutes excludes between-attempt gaps but may include dependencies within each attempt; absent attempt jobs make these new metrics unknown; queue/failure-completion metrics are timing proxies, not actionable-feedback guarantees",
        "runs": len(rows),
        "runner_minutes": round(sum(x["runner_minutes"] for x in rows), 3),
        "prior_attempt_runner_minutes": round(
            sum(x["prior_attempt_runner_minutes"] for x in rows), 3),
        "runs_with_reruns": sum(x["reported_attempts"] > 1 for x in rows),
        "runs_with_complete_attempt_windows": sum(
            x["complete_attempt_window_minutes"] is not None for x in rows),
        "complete_attempt_window_minutes": round(sum(
            x["complete_attempt_window_minutes"]
            for x in rows if x["complete_attempt_window_minutes"] is not None), 3),
        "retry_idle_gap_minutes": round(sum(
            x["retry_idle_gap_minutes"] for x in rows
            if x["retry_idle_gap_minutes"] is not None), 3),
        "runs_with_unobserved_job_attempts": sum(
            bool(x["attempts_without_jobs"]) for x in rows),
        "cancelled_run_runner_minutes": round(
            sum(x["cancelled_run_runner_minutes"] for x in groups.values()), 3
        ),
        "by_workflow": groups, "by_workflow_step_activity": step_activity,
        "by_head_sha": revision_groups,
        "runs_without_head_sha": unattributed_runs,
        "details": sorted(rows, key=lambda x: x["run_id"]),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", help="owner/repo (live mode)")
    parser.add_argument("--branch", help="exact PR branch (live mode)")
    parser.add_argument("--created", help="inclusive YYYY-MM-DD..YYYY-MM-DD range (live mode)")
    parser.add_argument("--runs-json", type=Path, help="saved GET actions/runs JSON")
    parser.add_argument("--jobs-dir", type=Path, help="saved <run-id>.json GET jobs responses")
    parser.add_argument("--json-out", type=Path, help="write full grouped report to disk")
    args = parser.parse_args()
    if args.runs_json:
        if args.repo or args.branch or args.created or not args.jobs_dir:
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
        runs = get_runs(args.repo, args.branch, args.created)
    report = cost_report(runs)
    # Preserve the user's selection in the exported machine-readable evidence:
    # a calendar-sliced report must never masquerade as a branch lifetime total.
    if args.runs_json:
        report["selection"] = {
            "mode": "offline-snapshot",
            "source": "caller-supplied runs and jobs; preserve those source files",
        }
        print("Scope: offline supplied runs/jobs snapshot")
    else:
        report["selection"] = {
            "mode": "github-rest",
            "repository": args.repo,
            "branch": args.branch,
            "created_range": args.created,
            "job_attempt_filter": "all",
        }
        print(f"Scope: repository={args.repo} branch={args.branch} "
              f"created={args.created or 'not specified'}; all job attempts")
    print(f"Completed runs: {report['runs']}; aggregate runner minutes: "
          f"{report['runner_minutes']:.2f}; canceled-run runner minutes: "
          f"{report['cancelled_run_runner_minutes']:.2f}; "
          f"prior-attempt minutes: {report['prior_attempt_runner_minutes']:.2f}; "
          f"rerun workflows: {report['runs_with_reruns']}")
    for name, item in report["by_workflow"].items():
        print(f"{name}: runs={item['runs']} cancelled={item['cancelled_runs']} "
              f"runner_min={item['runner_minutes']:.2f} "
              f"cancelled_min={item['cancelled_run_runner_minutes']:.2f} "
              f"job_window_min={item['job_window_minutes']:.2f} "
              f"timed_runs={item['runs_with_timed_jobs']}")
    for workflow, activity in report["by_workflow_step_activity"].items():
        if activity["hotspots"]:
            top = activity["hotspots"][0]
            print(f"step_activity={workflow}: timed_jobs={activity['jobs_with_steps']} "
                  f"missing_steps_jobs={activity['jobs_without_steps']} "
                  f"timed_step_min={activity['timed_step_minutes']:.2f} "
                  f"top=run{top['run_id']}/job{top['job_id']}/step{top['step_number']} "
                  f"({top['minutes']:.2f}m)")
    print(f"Complete per-attempt span (measured runs only): "
          f"{report['complete_attempt_window_minutes']:.2f} minutes "
          f"across {report['runs_with_complete_attempt_windows']} runs; "
          f"observed retry idle: {report['retry_idle_gap_minutes']:.2f} minutes "
          "(exclude missing/overlapping attempts)")
    for sha, group in report["by_head_sha"].items():
        print(f"head_sha={sha[:12]} runs={group['runs']} "
              f"runner_min={group['runner_minutes']:.2f} "
              f"cancelled_min={group['cancelled_run_runner_minutes']:.2f} "
              f"no_timed_jobs={group['zero_timed_job_runs']}")
    if args.json_out:
        args.json_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                 encoding="utf-8")


if __name__ == "__main__":
    main()
