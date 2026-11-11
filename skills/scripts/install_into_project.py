#!/usr/bin/env python3
"""Copy a self-contained skills directory into another local repository safely."""
from __future__ import annotations

import argparse
import filecmp
import shutil
import sys
from pathlib import Path

from check_portability import check_skills

SKIP = {"__pycache__", ".git", ".pytest_cache", ".mypy_cache"}


def files_in(root: Path):
    return sorted(
        file.relative_to(root) for file in root.rglob("*")
        if file.is_file() and not any(x in SKIP for x in file.relative_to(root).parts)
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--source-revision", help="verified git commit SHA; not guessed")
    parser.add_argument("--replace", action="store_true", help="replace conflicting skills files only")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    source = (args.source_root / "skills").resolve()
    project = args.project_root.resolve()
    target = project / "skills"
    if not project.is_dir():
        parser.error("target project directory must exist")
    if source == target or source in target.parents or target in source.parents:
        parser.error("source and destination must be separate repositories")
    errors = check_skills(source)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        parser.error("source skills package has portability errors")
    paths = [p for p in files_in(source) if str(p) != "VENDOR-SOURCE.md"]
    conflicts = [
        p for p in paths
        if (target / p).exists() and not filecmp.cmp(source / p, target / p, shallow=False)
    ]
    if conflicts and not args.replace:
        print("Conflicting existing files (use --replace only after reviewing differences):", file=sys.stderr)
        for p in conflicts:
            print(f"  {p}", file=sys.stderr)
        return 2
    print(f"{'Would copy' if args.dry_run else 'Copying'} {len(paths)} skills files to {target}")
    if args.dry_run:
        return 0
    target.mkdir(exist_ok=True)
    for path in paths:
        destination = target / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / path, destination)
    revision = args.source_revision or "UNSPECIFIED: inspect source checkout before asserting provenance"
    (target / "VENDOR-SOURCE.md").write_text(
        "# Vendored Skills\n\nSource revision: " + revision +
        "\n\nThese are local files, not automatically updated. See PORTABILITY.md.\n",
        encoding="utf-8",
    )
    issues = check_skills(target)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("Installed and verified. Add local skill routing to the host AGENTS.md.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
