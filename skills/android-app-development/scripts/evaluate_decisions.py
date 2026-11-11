#!/usr/bin/env python3
"""Offline historical decision probes. No API calls or agent execution."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "assets" / "decision-regressions.json"


def load_cases(path=CORPUS):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, list) or len(data) < 12:
        raise ValueError("Scenario corpus missing or too small")
    seen = set()
    for case in data:
        if case["id"] in seen or not case["id"].startswith("T"):
            raise ValueError("Duplicate/malformed scenario ID")
        seen.add(case["id"])
        opts = case["choices"]
        ids = [v["id"] for v in opts]
        if len(ids) != 3 or set(ids) != {"A", "B", "C"}:
            raise ValueError("Three unique A/B/C choices are required")
        if case["answer"] not in ids or not case["reason"] or not case["scenario"]:
            raise ValueError("Missing decision answer/reason/prompt")
    return data


def prompts(cases):
    return [{"id": c["id"], "scenario": c["scenario"], "choices": c["choices"]}
            for c in cases]  # no key, explanation, project or source


def grade(cases, responses):
    if not isinstance(responses, list):
        raise ValueError("Responses must be a JSON list")
    known = {c["id"]: c for c in cases}
    by_id = {}
    for item in responses:
        if not isinstance(item, dict) or item.get("id") not in known:
            raise ValueError("Unknown/invalid response ID")
        if item["id"] in by_id:
            raise ValueError("Duplicate response ID")
        if item.get("choice") not in {"A", "B", "C"}:
            raise ValueError("Choice must be A, B or C")
        if not isinstance(item.get("rationale"), str):
            raise ValueError("Human-reviewable rationale required")
        by_id[item["id"]] = item
    rows = []
    for case in cases:
        r = by_id.get(case["id"])
        correct = bool(r and r["choice"] == case["answer"])
        rows.append({"id": case["id"], "case_id": case["case_id"],
                     "answered": r is not None, "correct": correct,
                     "rationale_supplied": bool(r and r["rationale"].strip()),
                     "reason": case["reason"] if not correct else None})
    return {"total": len(cases), "answered": len(by_id),
            "correct": sum(x["correct"] for x in rows),
            "score": round(sum(x["correct"] for x in rows) / len(cases), 4),
            "rows": rows,
            "limitations": "Multiple-choice decision screen only: not a blind agent study, actual code review or Android acceptance."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--emit-prompts", action="store_true", help="Emit only prompts and shuffled-looking choices, never key")
    modes.add_argument("--grade", metavar="RESPONSES.json", help="Grade agent replies with id/choice/rationale")
    parser.add_argument("--strict", action="store_true", help="Nonzero exit unless all decisions answered correctly")
    args = parser.parse_args()
    cases = load_cases()
    if args.emit_prompts:
        print(json.dumps(prompts(cases), indent=2, ensure_ascii=False))
        return
    result = grade(cases, json.loads(Path(args.grade).read_text(encoding="utf-8")))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if args.strict and result["correct"] < result["total"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
