#!/usr/bin/env python3
"""Return bounded structural *review candidates* for concept-model/v1 documents.

Does not infer feature absence, ontology truth, source independence or novelty.
"""
import argparse
import json
from pathlib import Path
import sys

from check_model import check

WEAK = {"hypothesized", "unknown", "refuted", "contested"}
GAP_ACTION = {
    "acquisition_omission": "Inspect an omitted original source and extract relevant propositions.",
    "alias_mismatch": "Compare original names, translations and stable identifiers before merging.",
    "schema_blind_spot": "Check whether an existing statement requires a distinct typed edge.",
    "underlinked_fact": "Inspect cited claims for an existing relation before proposing a new one.",
    "contradiction": "Retain conflicting scoped claims and seek a discriminator.",
    "unknown_fact": "Seek one independent primary source or reproducible observation.",
    "version_boundary": "Split the claim by provider, platform or version.",
    "deliberate_exclusion": "Preserve exclusion; do not score as missing knowledge.",
    "candidate_frontier": "Challenge with held-out sources and an already-implemented counterexample.",
}


def audit(model):
    errors = check(model)
    if errors:
        raise ValueError("Invalid model: " + "; ".join(errors))
    claims = {c["id"]: c for c in model["claims"]}
    sources = {s["id"] for s in model["source_records"]}
    nodes = {c["id"] for c in model["concepts"]}
    connected = set()
    used_sources = set()
    findings = []
    for claim in model["claims"]:
        connected.update((claim["subject"], claim["object"]))
        used_sources.update(claim["source_ids"])
    for q in model["competency_questions"]:
        linked = [claims[id_] for id_ in q["claim_ids"]]
        if q["status"] in {"unknown", "partial"}:
            findings.append({
                "kind": "question_needs_evidence", "subject_id": q["id"],
                "operation": "Inspect original sources for a direct answer and record unresolved conditions.",
                "basis": q["status"]})
        if q["status"] == "answered" and linked and all(c["truth_kind"] in WEAK for c in linked):
            findings.append({
                "kind": "answer_evidence_review", "subject_id": q["id"],
                "operation": "Inspect whether cited claims entail an answer; seek direct evidence before asserting certainty.",
                "basis": "All cited propositions have weak or disputed truth kind"})
    for node in sorted(nodes - connected):
        findings.append({
            "kind": "isolated_concept_review", "subject_id": node,
            "operation": "Check for unextracted sourced relations and deliberate exclusions; do not infer an absent feature.",
            "basis": "No recorded incoming or outgoing claim"})
    for source_id in sorted(sources - used_sources):
        findings.append({
            "kind": "source_coverage_review", "subject_id": source_id,
            "operation": "Check whether this source contains relevant unextracted facts or was intentionally outside scope.",
            "basis": "Source appears in ledger but no cited proposition"})
    for gap in model["gaps"]:
        findings.append({
            "kind": "gap_followup", "subject_id": gap["id"],
            "operation": GAP_ACTION[gap["kind"]],
            "basis": gap["kind"], "qualification": gap["qualification"],
            "next_action": gap["next_action"]})
    findings.sort(key=lambda x: (x["kind"], x["subject_id"]))
    return {
        "schema": "concept-model/coverage-audit-v1",
        "status": "review_candidates_only",
        "counts": {
            "source_records": len(sources), "concepts": len(nodes),
            "claims": len(claims),
            "competency_questions": len(model["competency_questions"]),
            "gaps": len(model["gaps"]), "review_candidates": len(findings)
        },
        "findings": findings,
        "limits": "Structural leads only. No feature absence, novelty, semantic completeness or effectiveness claim."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    try:
        model = json.loads(args.model.read_text(encoding="utf-8"))
        print(json.dumps(audit(model), indent=2, ensure_ascii=False))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print("FAIL:", exc, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
