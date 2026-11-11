#!/usr/bin/env python3
"""Triage underspecified instructions and check anchored reviewed contracts.

Structural checks DO NOT prove that an agent can execute a method or that a
source claim is true. This tool never changes project or skill files.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

VERBS = re.compile(
    r"\b(?:research|investigate|discover|compare|specify|assess|evaluate|"
    r"audit|review|identify|recover|choose|select|verify|validate|"
    r"synthesize|reconcile|inspect|design|map|trace|prioritize)\b", re.I
)
DIRECTIVE = re.compile(r"^\s*(?:[-*]\s+|\d+[.)]\s+)")
NEGATIVE = re.compile(r"\b(?:never|do not|don't|avoid|must not|should not)\b", re.I)
PLACEHOLDER = re.compile(r"\b(?:todo|tbd|just do it|use common sense|as appropriate)\b", re.I)
REQUIRED = (
    "id", "path", "anchor", "method", "trigger", "inputs", "operation",
    "branch", "output", "evidence", "stop", "fallback"
)


def candidates(root: Path) -> list[dict]:
    """Identify review leads, not automatically adequate or inadequate directives."""
    findings = []
    root = root.resolve()
    for path in sorted(root.rglob("*.md")):
        if not path.is_file():
            continue
        fenced = False
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.strip().startswith(("~~~", chr(96) * 3)):
                fenced = not fenced
                continue
            if fenced or line.lstrip().startswith(("#", "|", ">")):
                continue
            if not DIRECTIVE.match(line) and not re.search(r"\b(?:must|should|need to)\b", line, re.I):
                continue
            verbs = list(VERBS.finditer(line))
            if not verbs or NEGATIVE.search(line[: min(verbs[0].start() + 12, len(line))]):
                continue
            has_link = "](" in line
            findings.append({
                "path": str(path.relative_to(root)), "line": number,
                "verbs": sorted(set(m.group().lower() for m in verbs)),
                "has_inline_link": has_link,
                "priority": "inspect_first" if not has_link else "check_linked_method",
                "directive": line.strip()[:450],
            })
    return findings


def check_contracts(root: Path, ledger: Path) -> list[str]:
    """Check declared fields, anchors and local links; do not infer semantic quality."""
    root = root.resolve()
    try:
        records = json.loads(ledger.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        return [f"cannot read contracts: {exc}"]
    if not isinstance(records, list) or not records:
        return ["contracts must be a non-empty JSON list"]
    errors = []
    seen = set()
    for index, rec in enumerate(records):
        where = f"record {index + 1}"
        if not isinstance(rec, dict):
            errors.append(f"{where}: must be an object")
            continue
        missing = [k for k in REQUIRED if k not in rec]
        if missing:
            errors.append(f"{where}: missing fields {missing}")
            continue
        ident = rec["id"]
        if not isinstance(ident, str) or not ident.strip() or ident in seen:
            errors.append(f"{where}: missing/duplicate id")
            continue
        seen.add(ident)
        invalid = [
            k for k in REQUIRED
            if not isinstance(rec[k], str) or len(rec[k].strip()) < (3 if k == 'id' else 10) or PLACEHOLDER.search(rec[k])
        ]
        if invalid:
            errors.append(f"{ident}: empty/placeholder contract fields {invalid}")
            continue
        for part in ("path", "method"):
            path = (root / rec[part]).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                errors.append(f"{ident}: missing/out-of-package {part}: {rec[part]}")
        source = (root / rec["path"]).resolve()
        if source.is_relative_to(root) and source.is_file():
            if rec["anchor"] not in source.read_text(encoding="utf-8"):
                errors.append(f"{ident}: anchor changed or missing; review instruction and contract")
        if rec["branch"].strip().lower() == rec["stop"].strip().lower():
            errors.append(f"{ident}: branch is same as stop criterion")
    return errors


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skills-dir", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--scan", action="store_true", help="print candidate instructions for HUMAN triage")
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--check-contracts", action="store_true", help="check only anchored reviewed contracts")
    parser.add_argument("--ledger", type=Path, help="optional alternative contract file")
    args = parser.parse_args(argv)
    if not args.scan and not args.check_contracts:
        parser.error("choose --scan and/or --check-contracts")
    if args.scan:
        findings = candidates(args.skills_dir)
        print(json.dumps({
            "candidate_count": len(findings), "reported": findings[:max(0, args.limit)],
            "caveat": "Review leads only; no automatic completeness or quality verdict."
        }, indent=2))
    if args.check_contracts:
        ledger = args.ledger or args.skills_dir / "assets" / "reviewed-instruction-contracts.json"
        errors = check_contracts(args.skills_dir, ledger)
        for problem in errors:
            print(problem, file=sys.stderr)
        print(f"Reviewed contract checks: {'FAIL' if errors else 'PASS'}; reviewed records only")
        return 1 if errors else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
