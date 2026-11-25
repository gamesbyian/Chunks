#!/usr/bin/env python3
"""Preserve existing per-lane PR paths inside one shared debug APK workflow.

GitHub still applies the outer union path filter. Inside it, classify the
complete PR file set; stale, incomplete or ambiguous metadata selects both
lanes rather than skipping a required test. No API mutations.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/ci_shared_debug_routes.json"
LANES = ("native", "protocol")


def load_routes(path=MANIFEST):
    data = json.loads(Path(path).read_text())
    if data.get("schema") != "chunks-shared-debug-route-v1" or set(data["lanes"]) != set(LANES):
        raise ValueError("Unknown shared debug route contract")
    for patterns in [data["common"], *data["lanes"].values()]:
        if not isinstance(patterns, list) or not patterns or len(set(patterns)) != len(patterns):
            raise ValueError("Missing or duplicate route patterns")
        for item in patterns:
            if not isinstance(item, str):
                raise ValueError("Invalid path pattern")
            pattern = item.removeprefix("!")
            literal = pattern.removesuffix("/**")
            if (not literal or literal.startswith("/") or ".." in Path(literal).parts
                    or not re.fullmatch(r"[A-Za-z0-9_./-]+", literal)):
                raise ValueError("Unsupported shared debug route glob")
    if any(p.startswith("!") for p in data["common"]):
        raise ValueError("Shared control paths cannot be negative")
    return data


def matches(pattern, path):
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-2])
    return path == pattern


def matches_ordered(patterns, paths):
    for path in paths:
        selected = False
        for pattern in patterns:
            if matches(pattern.removeprefix("!"), path):
                selected = not pattern.startswith("!")
        if selected:
            return True
    return False


def select_paths(routes, paths):
    if matches_ordered(routes["common"], paths):
        return set(LANES)
    return {lane for lane in LANES if matches_ordered(routes["lanes"][lane], paths)}


def route_patterns(routes, lane):
    return [*routes["lanes"][lane], *routes["common"]]


def union_patterns(routes):
    # Current per-lane negative globs do not mask a positive from the other
    # lane: the offline contract explicitly tests every literal/broad witness.
    return [*routes["lanes"]["native"], *routes["lanes"]["protocol"], *routes["common"]]


def get_json(path):
    result = subprocess.run(["gh", "api", "--method", "GET", path],
                            capture_output=True, text=True, check=True, timeout=30)
    return json.loads(result.stdout)


def identity(pr):
    count = pr["changed_files"]
    if type(count) is not int or not 1 <= count <= 3000:
        raise ValueError("Incomplete/oversized changed-file set")
    head, base = pr["head"]["sha"], pr["base"]["sha"]
    if not all(isinstance(x, str) and re.fullmatch(r"[0-9a-f]{40}", x) for x in (head, base)):
        raise ValueError("Invalid PR source identity")
    return head, base, count


def pr_paths(repo, event, fetch=get_json):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository")
    number = event["number"]
    if type(number) is not int or number < 1:
        raise ValueError("Invalid PR number")
    expected = identity(event["pull_request"])
    endpoint = f"repos/{repo}/pulls/{number}"
    if identity(fetch(endpoint)) != expected:
        raise ValueError("PR moved before route observation")
    count = expected[2]
    paths, seen = set(), set()
    for page in range(1, (count + 99) // 100 + 1):
        records = fetch(f"{endpoint}/files?per_page=100&page={page}")
        if not isinstance(records, list) or len(records) != min(100, count - len(seen)):
            raise ValueError("Incomplete PR files page")
        for entry in records:
            filename = entry["filename"]
            if not isinstance(filename, str) or not filename or filename in seen:
                raise ValueError("Invalid or repeated PR file")
            seen.add(filename)
            paths.add(filename)
            previous = entry.get("previous_filename")
            if previous is not None:
                if not isinstance(previous, str) or not previous:
                    raise ValueError("Invalid renamed source")
                paths.add(previous)
    if len(seen) != count or identity(fetch(endpoint)) != expected:
        raise ValueError("PR changed during route observation")
    return paths


def choose(routes, event_name, scope, event=None, repo=None, fetch=get_json):
    if event_name == "workflow_dispatch":
        if scope not in ("native", "protocol", "both"):
            raise ValueError("Invalid manual scope")
        return (set(LANES) if scope == "both" else {scope}), "manual"
    if event_name != "pull_request":
        raise ValueError("Unexpected shared debug event")
    try:
        selected = select_paths(routes, pr_paths(repo, event, fetch))
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        return set(LANES), "conservative-incomplete-pr-metadata"
    return (selected, "exact-pr-files") if selected else (set(LANES), "conservative-unmatched-dispatch")


def main():
    routes = load_routes()
    event_name = os.environ["GITHUB_EVENT_NAME"]
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    selected, reason = choose(routes, event_name, os.environ.get("CHUNKS_SCOPE") or "native",
                              event, os.environ["GITHUB_REPOSITORY"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for lane in LANES:
            output.write(f"{lane}={'true' if lane in selected else 'false'}\n")
    print("CHUNKS_SHARED_DEBUG_ROUTE:", reason, ",".join(sorted(selected)))


if __name__ == "__main__":
    main()
