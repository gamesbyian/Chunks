#!/usr/bin/env python3
"""Answer-free prompt export and transparent grading of bounded decision probes.

Correct option selection is not an evaluation of actual engineering competence.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]

def cases(root=ROOT):
    root=Path(root)
    prompts=json.loads((root/"assets/transfer-probes.json").read_text(encoding="utf-8"))
    key=json.loads((root/"assets/transfer-probe-key.json").read_text(encoding="utf-8"))
    return prompts["probes"],{x["id"]:x for x in key["answers"]}

def public_prompts(probes):
    # Only the three literal public fields are exported; no hidden answer or pattern.
    return [{"id":p["id"],"prompt":p["prompt"],"options":p["options"]} for p in probes]

def grade(probes,key,responses):
    if not isinstance(responses,list):
        raise ValueError("Responses must be a JSON list")
    ids={p["id"] for p in probes}
    got={}
    for r in responses:
        if not isinstance(r,dict) or r.get("id") not in ids:
            raise ValueError("Unknown/malformed response")
        ident=r["id"]
        if ident in got:
            raise ValueError(f"Duplicate response {ident}")
        if r.get("choice") not in ("A","B","C") or not isinstance(r.get("rationale"),str) or not r["rationale"].strip():
            raise ValueError(f"Response {ident}: expected A/B/C and nonempty rationale")
        got[ident]=r
    results=[{"id":p["id"],"answered":p["id"] in got,
              "correct":p["id"] in got and got[p["id"]]["choice"]==key[p["id"]]["answer"],
              "rationale_present":p["id"] in got} for p in probes]
    return {"total":len(probes),"answered":len(got),
            "correct":sum(bool(r["correct"]) for r in results),
            "results":results,
            "qualification":"Closed-book option choice only; not proof of source review, shipping code or genuine professional expertise."}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    modes=parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--emit-prompts",action="store_true")
    modes.add_argument("--grade",type=Path,metavar="RESPONSES.json")
    parser.add_argument("--strict",action="store_true",help="Fail if any answer missing or incorrect")
    args=parser.parse_args()
    prompts,key=cases()
    if args.emit_prompts:
        print(json.dumps(public_prompts(prompts),indent=2,ensure_ascii=False))
        return 0
    try:
        result=grade(prompts,key,json.loads(args.grade.read_text(encoding="utf-8")))
    except (OSError,ValueError,json.JSONDecodeError) as exc:
        print("FAIL:",exc,file=sys.stderr)
        return 2
    print(json.dumps(result,indent=2,ensure_ascii=False))
    return 1 if args.strict and result["correct"]<result["total"] else 0

if __name__=="__main__":
    sys.exit(main())
