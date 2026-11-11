#!/usr/bin/env python3
"""Dependency-free agent skill structural and casebook regression validation."""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CASE_PATTERN = re.compile(r"\| ((?:CH|GL|EX)-\d\d) \|")
LINK_PATTERN = re.compile(r"\]\(([^)]+)\)")


def validate(root=ROOT):
    errors = []
    root = Path(root).resolve()
    sk = root / "SKILL.md"
    if not sk.is_file():
        return ["Missing SKILL.md"]
    text = sk.read_text(encoding="utf-8")
    if not text.startswith("---\n") or "\n---\n" not in text[4:]:
        errors.append("SKILL.md requires YAML frontmatter delimiters")
    else:
        header = text.split("---", 2)[1]
        matches = re.findall(r"(?m)^name:\s*([a-z0-9-]+)\s*$", header)
        desc = re.findall(r"(?m)^description:\s*(.+)$", header)
        if len(matches) != 1 or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", matches[0]) or len(matches[0]) > 64:
            errors.append("Invalid or missing skill name")
        if len(desc) != 1 or not 1 <= len(desc[0].strip()) <= 1024:
            errors.append("Invalid or missing skill description")
    if len(text.splitlines()) > 500:
        errors.append("SKILL.md too long; use progressive references")
    # A complete vendored skills suite permits sibling links; an isolated
    # single-skill copy remains confined to its own root.
    package_root = root.parent if (root.parent / "CAPABILITY-ARCHITECTURE.md").is_file() else root
    for path in root.rglob("*.md"):
        doc = path.read_text(encoding="utf-8")
        if not doc.strip():
            errors.append(f"Empty Markdown file {path.relative_to(root)}")
        for target in LINK_PATTERN.findall(doc):
            if target.startswith(("http://", "https://", "#", "mailto:")):
                continue
            filepart = target.split("#", 1)[0]
            if not filepart:
                continue
            resolved = (path.parent / filepart).resolve()
            if not resolved.is_relative_to(package_root) or not resolved.exists():
                errors.append(f"Broken/outside local link {path.relative_to(root)} -> {target}")
    casepath = root / "references" / "historical-casebook.md"
    if not casepath.is_file():
        errors.append("Missing casebook")
        ids = []
    else:
        casebook = casepath.read_text(encoding="utf-8")
        ids = CASE_PATTERN.findall(casebook)
        if len(ids) < 25 or len(ids) != len(set(ids)):
            errors.append(f"Need >=25 unique source-linked case IDs (found {len(ids)})")
        for match in re.finditer(r"(?m)^\| ((?:CH|GL|EX)-\d\d) \|([^\n]+)", casebook):
            if "https://github.com/" not in match.group(2):
                errors.append(f"Case {match.group(1)} is missing a source link")
    fixture = root / "assets" / "decision-regressions.json"
    if not fixture.is_file():
        errors.append("Missing decision regression corpus")
        scenarios = []
    else:
        try:
            scenarios = json.loads(fixture.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeError) as err:
            errors.append(f"Invalid decision JSON: {err}")
            scenarios = []
    if not isinstance(scenarios, list) or len(scenarios) < 12:
        errors.append("Need >=12 evaluation cases")
        scenarios = []
    seen, unique = set(), set()
    for row in scenarios:
        try:
            sid, cid = row["id"], row["case_id"]
            if not re.fullmatch(r"T\d{2}", sid) or sid in seen:
                errors.append(f"Invalid/duplicate scenario ID {sid}")
            if cid not in ids or cid in unique:
                errors.append(f"Unknown/reused casebook ID {cid}")
            seen.add(sid)
            unique.add(cid)
            opts = row["choices"]
            if (len(opts) != 3 or {c["id"] for c in opts} != {"A", "B", "C"}
                    or row["answer"] not in {"A", "B", "C"} or not row["scenario"] or not row["reason"]):
                errors.append(f"Invalid options/answer for {sid}")
        except (KeyError, TypeError, ValueError) as err:
            errors.append(f"Malformed scenario: {err}")
    return errors


def main():
    errors = validate()
    if errors:
        for error in errors:
            print("FAIL:", error, file=sys.stderr)
        return 1
    print("PASS: skill frontmatter, progressive links, source-linked casebook, evaluation corpus")
    return 0


if __name__ == "__main__":
    sys.exit(main())
