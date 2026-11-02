#!/usr/bin/env python3
"""Offline audit for documentation-validation event path filters.

The docs lane intentionally watches its own tooling and CI contract files.
Check PR/push symmetry and targets so a future agent cannot silently drop a
necessary path or add a dead/misleading filter.
"""
from __future__ import annotations

import glob
from pathlib import Path
import re


EVENT = re.compile(r"^  (push|pull_request|workflow_dispatch):(?:\s*#.*)?$")
PATHS = re.compile(r"^    (paths|paths-ignore):$")
ITEM = re.compile(r"""^      - ['"]([^'"]+)['"]\s*$""")


def event_filters(text: str, event: str) -> list[str]:
    """Return just the selected event's paths and paths-ignore, if any."""
    active_event = None
    active_paths = False
    entries: list[str] = []
    for line in text.splitlines():
        event_match = EVENT.fullmatch(line)
        if event_match:
            active_event = event_match.group(1)
            active_paths = False
            continue
        if line and not line.startswith((" ", "#")):
            active_event = None
            active_paths = False
        if active_event != event:
            continue
        scope_match = PATHS.fullmatch(line)
        if scope_match:
            active_paths = True
            continue
        if line.startswith("    ") and not line.startswith("      ") and line.strip() and not line.lstrip().startswith("#"):
            active_paths = False
        if active_paths:
            item = ITEM.fullmatch(line)
            if item:
                entries.append(item.group(1))
    return entries


def problems(text: str, root: Path) -> list[str]:
    errors: list[str] = []
    lists = {event: event_filters(text, event) for event in ("push", "pull_request")}
    for event, entries in lists.items():
        if not entries:
            errors.append(f"{event}: no documentation validation path filters")
            continue
        duplicates = sorted({p for p in entries if entries.count(p) > 1})
        if duplicates:
            errors.append(f"{event}: {len(duplicates)} duplicated path filter(s)")
        for path in entries:
            pattern = path.removeprefix("!")
            if not pattern or pattern.startswith("/") or ".." in Path(pattern).parts:
                errors.append(f"{event}: invalid path filter")
                continue
            if not glob.glob(str(root / pattern), recursive=True):
                # Emit only a generic count, not arbitrary user-controlled
                # filenames or paths, into a public CI summary.
                errors.append(f"{event}: unmatched path filter")
    if lists["push"] != lists["pull_request"]:
        errors.append("push and pull_request docs filters differ")
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    workflow = root / ".github/workflows/documentation-validation.yml"
    try:
        errors = problems(workflow.read_text(encoding="utf-8"), root)
    except OSError:
        print("Documentation dispatch contract: cannot read workflow")
        return 2
    if errors:
        for item in sorted(set(errors)):
            print("Documentation dispatch contract:", item)
        return 1
    print("Documentation dispatch contract: symmetric, unique, valid paths")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
