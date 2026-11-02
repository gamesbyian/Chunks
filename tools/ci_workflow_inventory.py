#!/usr/bin/env python3
"""Secret-free, dependency-free inventory of every GitHub Actions YAML file."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

EVENTS = ("pull_request", "pull_request_target", "push", "merge_group",
          "workflow_dispatch", "workflow_call", "workflow_run", "schedule",
          "release", "repository_dispatch", "issues", "issue_comment")


def block(text, key):
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if re.match("^" + re.escape(key) + r":(?:\s|$)", line):
            out = []
            for child in lines[i + 1:]:
                if child and not child[0].isspace() and not child.startswith("#"):
                    break
                out.append(child)
            return "\n".join(out)
    return ""


def event_block(on, event):
    lines = on.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("  " + event + ":"):
            out = []
            for child in lines[i + 1:]:
                if re.match(r"^  [a-zA-Z_-]+:", child):
                    break
                out.append(child)
            return "\n".join(out)
    return ""


def explicitly_targets_main(push_scope):
    """Recognize explicit main in inline and multiline push branch filters.

    Wildcards and negative filters remain unclassified for manual review;
    this is an inventory of possible duplication, not a dispatch simulator.
    """
    match = re.search(
        r"(?m)^    branches:[ \t]*(?:\[([^\]\n]*)\])?[ \t]*(?:#.*)?$",
        push_scope,
    )
    if not match:
        return False
    if match.group(1) is not None:
        names = [value.strip().strip("\"'") for value in match.group(1).split(",")]
        return "main" in names
    for line in push_scope[match.end():].splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= 4:
            break
        item = re.match(
            r"^      -[ \t]+(['\"]?)([^'\"\s#]+)\1(?:[ \t]*(?:#.*)?)?$",
            line,
        )
        if item and item.group(2) == "main":
            return True
    return False


def inspect(path, root):
    source = path.read_text(encoding="utf-8")
    clean = "\n".join(line for line in source.splitlines()
                      if not line.lstrip().startswith("#"))
    on = block(source, "on")
    events = [e for e in EVENTS if re.search(
        r"(?m)^  " + re.escape(e) + r":", on)]
    bracket = re.search(r"(?m)^on:\s*\[([^\]]+)\]", source)
    if bracket:
        events += [x.strip().strip(chr(34) + chr(39))
                   for x in bracket.group(1).split(",")]
    single = re.search(r"(?m)^on:\s*([a-z_-]+)\s*$", source)
    if single:
        events.append(single.group(1))
    events = sorted(set(events))
    self_path = path.relative_to(root).as_posix()
    push_scope = event_block(on, "push")
    pr_scope = event_block(on, "pull_request")
    def path_entries(block):
        return set(re.findall(r"(?m)^\s+-\s+['\"]?(\.github/workflows/[^'\"\s]+)", block))
    self_on_push = self_path in path_entries(push_scope)
    self_on_pr = self_path in path_entries(pr_scope)
    # A workflow-only edit is then tested at PR head and re-dispatched after
    # the merge solely because the workflow file itself is in main push paths.
    main_only_push = explicitly_targets_main(push_scope)
    duplicate_self_candidate = self_on_push and self_on_pr and main_only_push
    jobs = sorted(set(re.findall(
        r"(?m)^  ([A-Za-z_][\w-]*):\s*$", block(source, "jobs"))))
    concurrent = ("workflow" if re.search(r"(?m)^concurrency:", clean) else
                  "job" if re.search(r"(?m)^    concurrency:", clean) else "none")
    uses = sorted(set(re.findall(r"(?m)^\s*(?:-\s+)?uses:\s*([^\s#]+)", clean)))
    timeouts = [int(x) for x in re.findall(r"timeout-minutes:\s*(\d+)", clean)]
    flags = {
        "gradle": bool(re.search(r"setup-gradle@|gradle --no-daemon|\./gradlew", clean)),
        "emulator": "android-emulator-runner" in clean or "emulator -avd" in clean,
        "native": bool(re.search(r"\bcmake\b|\bninja\b|libtorrent4j|\bJNI\b|ndk-build", clean, re.I)),
        "secrets_reference": bool(re.search(r"secrets\.[A-Za-z_]", clean)),
        "write_permission": bool(re.search(
            r"(?m)^\s+(?:contents|actions|id-token|pull-requests|packages|"
            r"deployments|checks|issues):\s*write\b", clean)),
        "summary_reference": "GITHUB_STEP_SUMMARY" in clean,
        "matrix": bool(re.search(r"(?m)^\s+matrix:", clean)),
    }
    issues = []
    if not re.search(r"(?m)^name:\s*\S", clean):
        issues.append("missing_name")
    if not events:
        issues.append("missing_events")
    if not jobs:
        issues.append("missing_jobs")
    if any(e in events for e in ("pull_request", "pull_request_target")) and concurrent == "none":
        issues.append("pr_without_concurrency")
    return {
        "path": path.relative_to(root).as_posix(),
        "self_trigger_on_main_push": duplicate_self_candidate,
        "self_trigger_on_pr": self_on_pr,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "events": events, "jobs": jobs,
        "pr_paths_filter": bool(re.search(r"(?m)^    paths(?:-ignore)?:", on)),
        "concurrency": concurrent,
        "cancel_in_progress": bool(re.search(
            r"(?m)^\s*cancel-in-progress:\s*(?:true|[$])", clean)),
        "max_timeout_minutes": max(timeouts) if timeouts else None,
        "actions_count": len(uses),
        "mutable_action_tags": sum(bool(re.search(r"@v\d+(?:\.\d+)*$", x))
                                   for x in uses),
        "flags": flags, "review_flags": issues,
    }


def collect(root):
    root = root.resolve()
    directory = root / ".github" / "workflows"
    paths = sorted((*directory.glob("*.yml"), *directory.glob("*.yaml")))
    if not paths:
        raise ValueError("No workflow definitions found")
    workflows = [inspect(path, root) for path in paths]
    missing = [(w["path"], flag) for w in workflows for flag in w["review_flags"]
               if flag.startswith("missing_")]
    if missing:
        raise ValueError("Invalid workflow definitions: " + repr(missing[:10]))
    return {
        "schema": 1,
        "workflow_count": len(workflows),
        "event_counts": {e: sum(e in w["events"] for w in workflows)
                         for e in EVENTS if any(e in w["events"] for w in workflows)},
        "main_self_trigger_candidates": [w["path"] for w in workflows
                                         if w["self_trigger_on_main_push"]],
        "missing_concurrency": [w["path"] for w in workflows
                                if "pr_without_concurrency" in w["review_flags"]],
        "privilege_review": [w["path"] for w in workflows if
                             w["flags"]["secrets_reference"] or
                             w["flags"]["write_permission"]],
        "workflows": workflows,
        "qualification": "Static definition index, not executed verification",
    }


def markdown(report):
    def names(items):
        return ", ".join(Path(x).name for x in items[:12]) or "none"
    return "\n".join([
        "### Workflow topology inventory", "",
        f"- {report['workflow_count']} workflow files enumerated.",
        "- Declared events: " + ", ".join(
            f"{k}: {v}" for k, v in sorted(report["event_counts"].items())),
        f"- PR workflows without concurrency: {len(report['missing_concurrency'])} "
        f"({names(report['missing_concurrency'])})",
        f"- Main-push self-trigger candidates: {len(report['main_self_trigger_candidates'])} "
        f"({names(report['main_self_trigger_candidates'])})",
        f"- Privilege review candidates: {len(report['privilege_review'])} "
        f"({names(report['privilege_review'])})",
        "- Full JSON in workflow-inventory artifact; flags are not product failures.", "",
    ]) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    report = collect(args.root)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")
    if args.summary:
        destination = os.getenv("GITHUB_STEP_SUMMARY")
        if destination:
            with open(destination, "a", encoding="utf-8") as fh:
                fh.write(markdown(report))
        else:
            print(markdown(report))
    else:
        print(f"Inventoried {report['workflow_count']} workflow definitions")


if __name__ == "__main__":
    main()
