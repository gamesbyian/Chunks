#!/usr/bin/env python3
"""Offline portable-skills link/frontmatter checks. No external dependencies."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

LINK_RE = re.compile(r"!?\[[^\]\n]*\]\(\s*(<[^>]+>|[^\s)]+)")
STALE_SOURCE_RE = re.compile(
    r"https?://github\.com/GBIDEVELOPMENT/general-purpose/"
    r"(?:blob|tree)/(?:main|master|[0-9a-f]{40})/skills/",
    re.IGNORECASE,
)
NAME_RE = re.compile(r"(?m)^name:\s*['\"]?([a-z0-9-]+)")
IGNORED = {"__pycache__", ".git", ".pytest_cache", ".mypy_cache"}


def check_skills(skills: Path) -> list[str]:
    """Return actionable diagnostics for references internal to a skills copy."""
    skills = skills.resolve()
    errors: list[str] = []
    if not skills.is_dir():
        return [f"{skills}: missing skills directory"]
    if not (skills / "CAPABILITY-ARCHITECTURE.md").is_file():
        errors.append("Missing skills/CAPABILITY-ARCHITECTURE.md")
    skill_files = sorted(skills.glob("*/SKILL.md"))
    if not skill_files:
        errors.append("No skill entrypoints found")
    for entry in skill_files:
        text = entry.read_text(encoding="utf-8")
        parts = text.split("---", 2)
        if len(parts) < 3 or parts[0].strip():
            errors.append(f"{entry.relative_to(skills)}: missing YAML frontmatter")
        else:
            match = NAME_RE.search(parts[1])
            if not match or match.group(1) != entry.parent.name:
                errors.append(f"{entry.relative_to(skills)}: name must match directory")
            if "description:" not in parts[1]:
                errors.append(f"{entry.relative_to(skills)}: missing description")
    for source in sorted(skills.rglob("*.md")):
        if any(part in IGNORED for part in source.parts):
            continue
        content = source.read_text(encoding="utf-8")
        rel = source.relative_to(skills)
        for match in STALE_SOURCE_RE.finditer(content):
            line = content.count("\n", 0, match.start()) + 1
            errors.append(f"{rel}:{line}: stale source-repository skill link; use a relative local link")
        for match in LINK_RE.finditer(content):
            raw = match.group(1).strip("<>")
            parsed = urlsplit(raw)
            if parsed.scheme or raw.startswith("//") or raw.startswith("#"):
                continue  # External evidence or a local heading, not a package file.
            target_text = unquote(parsed.path)
            if not target_text:
                continue
            target = (source.parent / target_text).resolve()
            line = content.count("\n", 0, match.start()) + 1
            if not target.is_relative_to(skills):
                errors.append(f"{rel}:{line}: link escapes skills package: {raw}")
            elif not target.exists():
                errors.append(f"{rel}:{line}: missing local target: {raw}")
    for source in sorted(skills.rglob("*.json")):
        content = source.read_text(encoding="utf-8")
        rel = source.relative_to(skills)
        for match in STALE_SOURCE_RE.finditer(content):
            # A pinned source URL in an evidence record is legitimate provenance.
            # Branch-relative JSON pointers and schema identities are not portable.
            if "/blob/main/" not in match.group() and "/blob/master/" not in match.group() and "/tree/main/" not in match.group() and "/tree/master/" not in match.group():
                continue
            line = content.count("\n", 0, match.start()) + 1
            errors.append(f"{rel}:{line}: mutable source-repository JSON pointer; use portable identity")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skills-dir", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    errors = check_skills(args.skills_dir)
    for e in errors:
        print(e, file=sys.stderr)
    if errors:
        print(f"Portability FAILED: {len(errors)} issue(s)", file=sys.stderr)
        return 1
    print(f"Portability OK: {args.skills_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
