#!/usr/bin/env python3
"""Validate structural integrity of a concept-model/v1 document, not its truth."""
import argparse
import json
from pathlib import Path
import re
import sys

GAP_KINDS = {"acquisition_omission","alias_mismatch","schema_blind_spot",
             "underlinked_fact","contradiction","unknown_fact","version_boundary",
             "deliberate_exclusion","candidate_frontier"}
QUAL = {"map_repair","insufficient_evidence","out_of_scope","qualified_frontier"}
TRUTH = {"documented","observed","inferred","hypothesized","contested","refuted","unknown"}

def check(model):
    errors = []
    if not isinstance(model, dict) or model.get("schema") != "concept-model/v1":
        return ["Incorrect model schema/version"]
    for field in ("focus_question","scope"):
        if not isinstance(model.get(field), str) or len(model[field]) < 10:
            errors.append(f"Missing meaningful {field}")
    groups = ("source_records","competency_questions","concepts","claims","gaps")
    if any(not isinstance(model.get(k),list) for k in groups):
        return errors + ["Missing list collection"]
    ids = {}
    for name in groups:
        found = set()
        for item in model[name]:
            if not isinstance(item,dict) or not item.get("id"):
                errors.append(f"Malformed item in {name}")
                continue
            if item["id"] in found:
                errors.append(f"Duplicate {name} ID: {item['id']}")
            found.add(item["id"])
        ids[name] = found
    for claim in model["claims"]:
        cid = claim.get("id","?")
        for end in ("subject","object"):
            if claim.get(end) not in ids["concepts"]:
                errors.append(f"Claim {cid}: unknown {end}")
        if claim.get("truth_kind") not in TRUTH:
            errors.append(f"Claim {cid}: unknown truth kind")
        if not isinstance(claim.get("predicate"),str) or not re.fullmatch(r"[a-z][a-z0-9_]*",claim["predicate"]):
            errors.append(f"Claim {cid}: invalid specific relation")
        refs = claim.get("source_ids")
        if not isinstance(refs,list) or not refs or any(x not in ids["source_records"] for x in refs):
            errors.append(f"Claim {cid}: missing or unknown provenance")
        if not claim.get("scope"):
            errors.append(f"Claim {cid}: missing scope")
    for q in model["competency_questions"]:
        if q.get("status") not in {"answered","partial","unknown","out_of_scope"}:
            errors.append(f"Question {q.get('id')}: invalid status")
        cited=q.get("claim_ids")
        if not isinstance(cited,list) or any(x not in ids["claims"] for x in cited):
            errors.append(f"Question {q.get('id')}: unknown claims")
        if q.get("status")=="answered" and not cited:
            errors.append(f"Question {q.get('id')}: no cited claims for answer")
    for gap in model["gaps"]:
        gid=gap.get("id","?")
        if gap.get("kind") not in GAP_KINDS or gap.get("qualification") not in QUAL:
            errors.append(f"Gap {gid}: unknown category/status")
        refs=gap.get("source_checks")
        if not isinstance(refs,list) or any(x not in ids["source_records"] for x in refs):
            errors.append(f"Gap {gid}: source checks reference unknown source")
            refs=[]
        for field in ("question","falsifier","next_action"):
            if not isinstance(gap.get(field),str) or not gap[field].strip():
                errors.append(f"Gap {gid}: missing {field}")
        if gap.get("qualification")=="qualified_frontier":
            if (gap.get("kind")!="candidate_frontier" or len(set(refs))<2
                or not gap.get("disconfirmation_attempts") or not gap.get("novelty_scope")):
                errors.append(f"Gap {gid}: unqualified novelty, needs two independent checks, disconfirmation and bounded scope")
    return errors

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("model",type=Path)
    args=p.parse_args()
    try:
        errors=check(json.loads(args.model.read_text(encoding="utf-8")))
    except (OSError,UnicodeError,json.JSONDecodeError) as exc:
        errors=[str(exc)]
    for e in errors:
        print("FAIL:",e,file=sys.stderr)
    if errors:
        return 1
    print("PASS: referential integrity, evidence and frontier guards")
    return 0

if __name__=="__main__":
    sys.exit(main())
