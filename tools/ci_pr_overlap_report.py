#!/usr/bin/env python3
"""Read-only GitHub PR patch-overlap audit for human/agent reconciliation.

Reports exact-patch *candidates* only, never auto-closes or merges PRs.
Unlike filename-only overlap, a fully subsumed PR must have every changed
file/status/previous-path and its nonempty GitHub patch byte-identical in a
larger open PR. GitHub may omit patch data, which remains UNKNOWN, not equal.
Use exact-head review and CI before any integration/closure decision.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess

REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
MAX_OPEN = 40


def gh_get(path):
    result = subprocess.run(
        ["gh", "api", "--method", "GET", path],
        text=True, capture_output=True, check=True, timeout=60,
    )
    return json.loads(result.stdout)


def live_pr_files(repo, fetch=gh_get):
    if not REPO.fullmatch(repo):
        raise ValueError("Invalid owner/repository")
    prs = fetch(f"repos/{repo}/pulls?state=open&per_page=100")
    if not isinstance(prs, list):
        raise ValueError("Unexpected open PR list")
    if len(prs) >= 100 or len(prs) > MAX_OPEN:
        raise ValueError("Open PR list exceeds safe complete sample; narrow scope")
    records = []
    seen = set()
    for pr in prs:
        number = pr.get("number") if isinstance(pr, dict) else None
        if type(number) is not int or number < 1 or number in seen:
            raise ValueError("Invalid or duplicate pull request number")
        seen.add(number)
        files = fetch(f"repos/{repo}/pulls/{number}/files?per_page=100")
        if not isinstance(files, list) or len(files) >= 100:
            raise ValueError(f"Missing or possibly truncated files for PR {number}")
        entries = []
        for file in files:
            if not isinstance(file, dict):
                raise ValueError(f"Invalid file record for PR {number}")
            entries.append(file)
        records.append({"number": number, "files": entries})
    return records


def file_identity(file):
    """Opaque fingerprint of a complete patch; no code or paths enter output."""
    name = file.get("filename")
    status = file.get("status")
    previous = file.get("previous_filename")
    patch = file.get("patch")
    if not isinstance(name, str) or not name or name.startswith("/"):
        raise ValueError("Invalid repository filename")
    if status not in ("added", "modified", "removed", "renamed", "copied", "changed"):
        raise ValueError("Unsupported GitHub file status")
    if previous is not None and (not isinstance(previous, str) or not previous):
        raise ValueError("Invalid previous filename")
    if patch is not None and not isinstance(patch, str):
        raise ValueError("Invalid patch type")
    return name, (status, previous, hashlib.sha256(patch.encode("utf-8")).hexdigest()
                  if patch else None)


def overlap_report(pr_records):
    seen = set()
    maps = {}
    for pr in pr_records:
        number = pr.get("number")
        files = pr.get("files")
        if type(number) is not int or number < 1 or number in seen:
            raise ValueError("Duplicate/invalid PR identity")
        if not isinstance(files, list):
            raise ValueError(f"Missing file records for PR {number}")
        seen.add(number)
        current = {}
        for file in files:
            if not isinstance(file, dict):
                raise ValueError("Invalid file")
            name, identity = file_identity(file)
            if name in current:
                raise ValueError(f"Duplicate filename within PR {number}")
            current[name] = identity
        maps[number] = current
    rows = []
    for a, first in sorted(maps.items()):
        for b, second in sorted(maps.items()):
            if a >= b:
                continue
            common = set(first) & set(second)
            if not common:
                continue
            equal = {path for path in common
                     if first[path][2] is not None and first[path] == second[path]}
            unknown = {path for path in common
                       if first[path][2] is None or second[path][2] is None}
            different = common - equal - unknown
            subset = []
            for small, large, smallmap, largemap in (
                (a, b, first, second), (b, a, second, first)):
                if smallmap and set(smallmap) <= set(largemap) and all(
                    smallmap[path][2] is not None and smallmap[path] == largemap[path]
                    for path in smallmap
                ):
                    subset.append({"contained_pr": small, "covering_pr": large})
            rows.append({
                "pr_a": a, "pr_b": b,
                "shared_files": len(common),
                "exact_patch_files": len(equal),
                "different_patch_files": len(different),
                "unknown_patch_files": len(unknown),
                "only_a_files": len(set(first) - common),
                "only_b_files": len(set(second) - common),
                "fully_subsumed_candidates": subset,
            })
    return {
        "schema": "chunks-pr-overlap-v1",
        "qualification": ("Static GitHub PR patch evidence only. Equal patch text is "
                          "not an integrated or tested source claim. Do not auto-close, "
                          "merge, discard unique work or re-use old CI acceptance."),
        "open_prs": len(maps),
        "overlapping_pairs": len(rows),
        "fully_subsumed_candidates": sum(len(r["fully_subsumed_candidates"]) for r in rows),
        "pairs": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--json-out", help="optional JSON report path")
    args = parser.parse_args()
    report = overlap_report(live_pr_files(args.repo))
    print(f"Open PRs: {report['open_prs']}; overlapping pairs: "
          f"{report['overlapping_pairs']}; fully subsumed candidates: "
          f"{report['fully_subsumed_candidates']}")
    for pair in report["pairs"]:
        print(f"PR #{pair['pr_a']} / #{pair['pr_b']}: "
              f"common={pair['shared_files']} identical={pair['exact_patch_files']} "
              f"different={pair['different_patch_files']} "
              f"unknown={pair['unknown_patch_files']}")
        for item in pair["fully_subsumed_candidates"]:
            print(f"  review candidate: #{item['contained_pr']} patch fully covered "
                  f"by #{item['covering_pr']}")
    if args.json_out:
        from pathlib import Path
        Path(args.json_out).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                       encoding="utf-8")


if __name__ == "__main__":
    main()
