#!/usr/bin/env python3
"""One-off read-only CI audit. No runtime or PR acceptance decisions."""
from collections import defaultdict
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import quote

from tools.ci_runner_cost_report import cost_report, instant, paginated_items

repo = os.environ["GITHUB_REPOSITORY"]
output = Path(os.environ["RUNNER_TEMP"]) / "recent-ci-cost"
output.mkdir(exist_ok=True)
# Sorted by recently updated; report the exact sampled PR numbers and limits.
closed = json.loads(subprocess.check_output([
    "gh", "api", "--method", "GET",
    f"repos/{repo}/pulls?state=closed&base=main&sort=updated&direction=desc&per_page=100",
], text=True, timeout=60))
sample = sorted((p for p in closed if p.get("merged_at")),
                key=lambda p: p["merged_at"], reverse=True)[:20]
if not sample:
    raise SystemExit("No merged PR sample")
summaries = []
errors = []
totals = defaultdict(float)
seen = set()
for pr in sample:
    n = pr["number"]
    try:
        branch = pr["head"]["ref"]
        first, last = instant(pr["created_at"]), instant(pr["merged_at"])
        window = f"{first.date()}..{last.date()}"
        path = (f"repos/{repo}/actions/runs?event=pull_request"
                f"&branch={quote(branch, safe='')}&created={quote(window, safe='')}")
        candidates = paginated_items(path, "workflow_runs", f"PR {n} branch runs")
        runs = []
        inferred = 0
        unknown = 0
        for run in candidates:
            if not first <= instant(run["created_at"]) <= last:
                continue
            associated = run.get("pull_requests") or []
            if associated:
                if n not in {p.get("number") for p in associated}:
                    continue
            else:
                # Branch/time fallback only for same-repository runs. Explicitly
                # distinguish inference from a REST PR association.
                if (run.get("head_repository") or {}).get("full_name") != repo:
                    unknown += 1
                    continue
                if run.get("head_branch") != branch:
                    unknown += 1
                    continue
                inferred += 1
            if run["id"] in seen:
                raise ValueError("Run shared by sample PRs; refusing double count")
            if run.get("status") != "completed":
                raise ValueError("Unsettled PR run")
            run["jobs"] = paginated_items(
                f"repos/{repo}/actions/runs/{run['id']}/jobs?filter=all",
                "jobs", f"PR {n} run {run['id']} jobs")
            runs.append(run)
        report = cost_report(runs)
        seen.update(run["id"] for run in runs)
        summary = dict(pr=n, runs=report["runs"],
                       runner_minutes=report["runner_minutes"],
                       cancelled_minutes=report["cancelled_run_runner_minutes"],
                       prior_attempt_minutes=report["prior_attempt_runner_minutes"],
                       unobserved_attempt_runs=report["runs_with_unobserved_job_attempts"],
                       inferred_branch_time_runs=inferred,
                       unattributed_excluded=unknown,
                       final_head=pr["head"]["sha"],
                       nonfinal_head_minutes=round(sum(
                           x["runner_minutes"] for sha, x in report["by_head_sha"].items()
                           if sha != pr["head"]["sha"]), 3))
        summaries.append(summary)
        report["sample_pr"] = summary
        (output / f"pr-{n}.json").write_text(json.dumps(report, indent=2) + "\n")
        for workflow, value in report["by_workflow"].items():
            totals[workflow] += value["runner_minutes"]
        print("CHUNKS_CI_PR_COST", json.dumps(summary, sort_keys=True), flush=True)
    except (ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        errors.append(dict(pr=n, error_type=type(exc).__name__))
        print(f"CHUNKS_CI_PR_COST_UNAVAILABLE pr={n} type={type(exc).__name__}", flush=True)
result = dict(sample_method="20 latest merged among 100 recently updated closed main PRs",
              selected=[p["number"] for p in sample], observed=summaries, unavailable=errors,
              runner_minutes=round(sum(x["runner_minutes"] for x in summaries), 3),
              cancelled_minutes=round(sum(x["cancelled_minutes"] for x in summaries), 3),
              by_workflow_runner_minutes={k: round(v, 3) for k, v in sorted(totals.items())})
(output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print("CHUNKS_CI_SAMPLE", json.dumps(result, sort_keys=True), flush=True)
if errors:
    raise SystemExit("Incomplete sample; preserve available evidence")
