#!/usr/bin/env python3
"""Analyze matched, independently reviewed evaluation receipts.

This does NOT run agents, evaluate patches, or assert causal skill benefit.
Only matched clean pairs with actual executed tests and completed review are
eligible. Small n remains descriptive, not a statistical significance claim.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path
import sys

ARMS = {"control", "skill"}
REVIEWS = {"accepted", "rejected", "pending"}
EXPOSURES = {"isolated", "potentially_contaminated", "known_contaminated"}


def validate_and_summarize(data):
    if not isinstance(data, dict) or data.get("schema") != "engineering-judgment/trial-receipts-v1":
        raise ValueError("Invalid trial receipt schema")
    entries = data.get("runs")
    if not isinstance(entries, list):
        raise ValueError("Missing run collection")
    ids = set()
    pairs = defaultdict(dict)
    for run in entries:
        if not isinstance(run, dict):
            raise ValueError("Run must be a record")
        needed = {"run_id", "pair_id", "task_id", "arm", "model", "scaffold",
                  "tool_access", "time_budget_seconds", "task_digest", "oracle_digest",
                  "skill_loaded", "exposure", "executed_tests", "tests_passed",
                  "review", "elapsed_seconds", "unnecessary_work"}
        if set(run) != needed:
            raise ValueError(f"Missing/unexpected receipt fields: {sorted(needed ^ set(run))}")
        if any(not isinstance(run[field], str) or not run[field] for field in
               ("run_id", "pair_id", "task_id", "model", "scaffold", "tool_access", "task_digest", "oracle_digest")):
            raise ValueError("Missing key identity or environment")
        if run["run_id"] in ids:
            raise ValueError("Duplicate trial run id")
        ids.add(run["run_id"])
        if run["arm"] not in ARMS or run["review"] not in REVIEWS or run["exposure"] not in EXPOSURES:
            raise ValueError("Unknown arm, review state or exposure")
        if type(run["skill_loaded"]) is not bool or run["skill_loaded"] != (run["arm"] == "skill"):
            raise ValueError("Skill loading and declared arm disagree")
        for field in ("tests_passed", "unnecessary_work"):
            if type(run[field]) is not bool:
                raise ValueError(f"{field} must be boolean")
        if type(run["executed_tests"]) is not int or run["executed_tests"] < 0:
            raise ValueError("Bad executed-test count")
        if type(run["time_budget_seconds"]) is not int or run["time_budget_seconds"] < 1:
            raise ValueError("Invalid time budget")
        if not isinstance(run["elapsed_seconds"], (float, int)) or isinstance(run["elapsed_seconds"], bool) or run["elapsed_seconds"] < 0:
            raise ValueError("Invalid elapsed time")
        if run["tests_passed"] and run["executed_tests"] == 0:
            raise ValueError("Skipped/no-test job cannot be graded as passing")
        group = pairs[run["pair_id"]]
        if run["arm"] in group:
            raise ValueError("Duplicate arm inside pair")
        group[run["arm"]] = run

    comparisons = []
    for ident, group in sorted(pairs.items()):
        if set(group) != ARMS:
            comparisons.append({"pair_id": ident, "eligible": False, "reason": "incomplete_pair"})
            continue
        control, skill = group["control"], group["skill"]
        matched = ("task_id", "model", "scaffold", "tool_access", "time_budget_seconds", "task_digest", "oracle_digest")
        if any(control[field] != skill[field] for field in matched):
            comparisons.append({"pair_id": ident, "eligible": False, "reason": "unmatched_conditions"})
            continue
        if any(run["exposure"] != "isolated" for run in (control, skill)):
            comparisons.append({"pair_id": ident, "eligible": False, "reason": "possible_or_known_contamination"})
            continue
        if any(run["review"] == "pending" or run["executed_tests"] == 0 for run in (control, skill)):
            comparisons.append({"pair_id": ident, "eligible": False, "reason": "missing_review_or_executed_tests"})
            continue
        accepted = {arm: run["tests_passed"] and run["review"] == "accepted" for arm, run in group.items()}
        comparisons.append({
            "pair_id": ident, "task_id": skill["task_id"], "eligible": True,
            "control_accepted": accepted["control"], "skill_accepted": accepted["skill"],
            "control_unnecessary_work": control["unnecessary_work"],
            "skill_unnecessary_work": skill["unnecessary_work"],
            "control_seconds": control["elapsed_seconds"],
            "skill_seconds": skill["elapsed_seconds"]})
    eligible = [x for x in comparisons if x["eligible"]]
    improved = sum(x["skill_accepted"] and not x["control_accepted"] for x in eligible)
    worsened = sum(x["control_accepted"] and not x["skill_accepted"] for x in eligible)
    return {
        "schema": "engineering-judgment/trial-summary-v1",
        "runs": len(entries), "pairs": len(pairs), "eligible_pairs": len(eligible),
        "skill_only_successes": improved, "control_only_successes": worsened,
        "both_successes": sum(x["control_accepted"] and x["skill_accepted"] for x in eligible),
        "neither_successes": sum(not x["control_accepted"] and not x["skill_accepted"] for x in eligible),
        "control_unnecessary_work": sum(x["control_unnecessary_work"] for x in eligible),
        "skill_unnecessary_work": sum(x["skill_unnecessary_work"] for x in eligible),
        "control_total_seconds": sum(x["control_seconds"] for x in eligible),
        "skill_total_seconds": sum(x["skill_seconds"] for x in eligible),
        "comparisons": comparisons,
        "limits": "Descriptive matched-pair signals only. Independent review quality, unobserved confounders, contamination and small samples prevent unqualified causal claims."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipts", type=Path)
    args = parser.parse_args()
    try:
        data = json.loads(args.receipts.read_text(encoding="utf-8"))
        result = validate_and_summarize(data)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print("FAIL:", exc, file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
