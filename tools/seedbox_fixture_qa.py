#!/usr/bin/env python3
"""Bounded read-only analysis of owner-consented rTorrent main-view QA fixtures."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import unicodedata

FIELDS = ["d.hash=", "d.name=", "d.size_bytes=", "d.completed_bytes=", "d.state=", "d.directory="]
HASH = re.compile(r"^[0-9a-fA-F]{40}$")
CASES = ("long_name", "non_ascii", "rtl", "large_transfer", "zero_bytes",
         "completed", "incomplete", "started", "stopped", "nested_directory",
         "long_path", "punctuation_name")

def analyze(document):
    if not isinstance(document, dict) or document.get("source") != "owner-approved-seedhost-dev":
        raise ValueError("Not an owner-authorized development-server fixture")
    if document.get("view") != "main" or document.get("fields") != FIELDS:
        raise ValueError("Unexpected or incompatible RPC field layout")
    data = document.get("transfers")
    if not isinstance(data, list) or len(data) > 12000:
        raise ValueError("Invalid or oversized transfer list")
    normalized, seen = [], set()
    hits = {name: [] for name in CASES}
    for idx, row in enumerate(data):
        if not isinstance(row, list) or len(row) != len(FIELDS):
            raise ValueError("Incompatible transfer row shape")
        h, name, size, completed, started, directory = row
        if not isinstance(h, str) or not HASH.fullmatch(h) or h.lower() in seen:
            raise ValueError("Invalid or duplicate torrent infohash")
        seen.add(h.lower())
        if not isinstance(name, str) or len(name) > 4096 or any(ord(c) < 32 for c in name):
            raise ValueError("Unexpected or unsafe torrent display name")
        if not isinstance(directory, str) or len(directory) > 8192 or any(ord(c) < 32 for c in directory):
            raise ValueError("Invalid filesystem path")
        if (not isinstance(size, int) or isinstance(size, bool) or size < 0 or
            not isinstance(completed, int) or isinstance(completed, bool) or completed < 0 or completed > size or
            not isinstance(started, int) or started not in (0, 1)):
            raise ValueError("Inconsistent torrent numeric state")
        item = {"hash": h.lower(), "name": name, "size_bytes": size,
                "completed_bytes": completed, "started": bool(started),
                "directory": directory,
                "percent": round(100 * completed / size, 1) if size else 0.0}
        normalized.append(item)
        props = {
            "long_name": len(name) >= 60,
            "non_ascii": not name.isascii(),
            "rtl": any(unicodedata.bidirectional(c) in ("R", "AL") for c in name),
            "large_transfer": size >= 1_000_000_000,
            "zero_bytes": size == 0,
            "completed": size > 0 and completed == size,
            "incomplete": size > 0 and completed < size,
            "started": bool(started),
            "stopped": not bool(started),
            "nested_directory": directory.count("/") >= 4,
            "long_path": len(directory) >= 90,
            "punctuation_name": bool(re.search(r"[()[\]{}!&;]", name)),
        }
        for key, matches in props.items():
            if matches:
                hits[key].append(idx)
    # Deterministic selection, bounded to avoid gigantic QA bundles.
    cases = []
    selected = set()
    for key in CASES:
        if hits[key]:
            index = max(hits[key], key=lambda i: (len(normalized[i]["name"]),
                          normalized[i]["size_bytes"], -i))
            cases.append({"case": key, "transfer": normalized[index]})
            selected.add(index)
    if normalized and not cases:
        cases.append({"case": "ordinary", "transfer": normalized[0]})
    return {
        "schema": "chunks.rTorrent-visual-qa-catalog.v1",
        "source": "owner-approved-seedhost-dev",
        "view": "main",
        "completeness": "view-scoped-unverified",
        "total": len(normalized),
        "counts": {k: len(v) for k, v in hits.items()},
        "cases": cases,
        "coverage": {"selected_unique": len(selected),
                     "missing_cases": [k for k in CASES if not hits[k]]},
        "notes": [
            "All cases are from actual supplied read-only snapshot, not generated transfers.",
            "View main is not guaranteed to include every rTorrent transfer.",
            "Visual candidate selection is not evidence of Android rendering.",
            "No mutations or file-system actions were performed."
        ],
    }

def markdown(data):
    out = [
        "# Chunks dev rTorrent real-data visual QA catalogue", "",
        f"View: **{data['view']}**. Transfers observed: **{data['total']}**.",
        f"Unique representative transfers: **{data['coverage']['selected_unique']}**.",
        "Snapshot completeness: **view-scoped, unverified**.", "",
        "| Visual case | Torrent name | Size bytes | State | Path |",
        "|---|---|---:|---|---|",
    ]
    def safe(text):
        return str(text).replace("|", "\\|").replace("\n", " ")[:240]
    for row in data["cases"]:
        tr = row["transfer"]
        state = "started" if tr["started"] else "stopped"
        out.append(f"| {safe(row['case'])} | {safe(tr['name'])} | {tr['size_bytes']} | {state} | {safe(tr['directory'])} |")
    out += ["", "## Missing cases (do not manufacture them)", "",
            ", ".join(data["coverage"]["missing_cases"]) or "None", "",
            "Use this case set to seed emulator/handset screenshot state assertions;",
            "this report itself is **not** a screenshot atlas or UI acceptance."]
    return "\n".join(out) + "\n"

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("snapshot", type=Path)
    p.add_argument("--output-dir", type=Path, required=True)
    args = p.parse_args()
    if args.snapshot.stat().st_size > 4_194_304:
        p.error("Snapshot too large")
    result = analyze(json.loads(args.snapshot.read_text(encoding="utf-8")))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "qa-cases.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (args.output_dir / "qa-cases.md").write_text(markdown(result), encoding="utf-8")
    print(f"CHUNKS_FIXTURE_QA: {result['total']} transfers; "
          f"{result['coverage']['selected_unique']} real representative UI cases")

if __name__ == "__main__":
    main()
