#!/usr/bin/env python3
"""Produce a review-only, additive concept-model delta from staged evidence.

No semantic verdict is inferred from labels, citations or a reviewer's declaration.
The original concept-model/v1 is never modified by this module.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys

from audit_ingestion import audit
from check_model import check

SCHEMA = "concept-model/reviewed-delta-v1"
OUTPUT = "concept-model/delta-proposal-v1"
ID = re.compile(r"[A-Za-z][A-Za-z0-9_:-]*\Z")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}\Z")
# A role equivalence must be explicit; unknown ontology kinds need reviewer justification.
ROLE_KINDS = {"action": {"action", "operation"}, "goal": {"goal", "user_goal"},
              "state": {"state"}, "object": {"object"}, "actor": {"actor"},
              "outcome": {"outcome"}, "system": {"system"},
              "permission": {"permission"}, "other": {"other"}}


def required(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Missing or invalid " + field)
    return value


def ident(value, field):
    required(value, field)
    if not ID.fullmatch(value):
        raise ValueError("Invalid identifier for " + field)
    return value


def rows(value, keys, name, id_key):
    if not isinstance(value, list):
        raise ValueError(name + " must be a list")
    out = {}
    for item in value:
        if not isinstance(item, dict) or set(item) != set(keys):
            raise ValueError("Malformed " + name + " record")
        key = ident(item[id_key], name + "." + id_key)
        if key in out:
            raise ValueError("Duplicate " + name + ": " + key)
        out[key] = item
    return out


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False,
                         sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def propose(model, staged, reviews):
    errors = check(model)
    if errors:
        raise ValueError("Invalid base concept model: " + "; ".join(errors))
    staged_audit = audit(staged)  # validates all references; reports leads, never truth
    if not isinstance(reviews, dict) or set(reviews) != {
            "schema", "source_links", "new_concepts", "identity_links", "relation_reviews"
            } or reviews["schema"] != SCHEMA:
        raise ValueError("Unknown review schema/fields")
    sources = {s["id"]: s for s in staged["sources"]}
    mentions = {m["id"]: m for m in staged["mentions"]}
    relations = {r["id"]: r for r in staged["relations"]}
    existing_sources = {s["id"]: s for s in model["source_records"]}
    existing_concepts = {c["id"]: c for c in model["concepts"]}
    existing_claims = {c["id"]: c for c in model["claims"]}

    source_links = rows(reviews["source_links"],
        ("stage_id", "model_id", "decision", "as_of", "checked_locator", "basis"),
        "source link", "stage_id")
    new_concepts = rows(reviews["new_concepts"],
        ("id", "kind", "label", "evidence_mention_ids", "checked_locator", "basis"),
        "new concept", "id")
    identity_links = rows(reviews["identity_links"],
        ("mention_id", "concept_id", "checked_locator", "basis", "boundary_basis"),
        "identity link", "mention_id")
    relation_reviews = rows(reviews["relation_reviews"],
        ("relation_id", "decision", "claim_id", "checked_locator", "basis",
         "bridge_source_id", "bridge_locator", "challenge_claim_ids"),
        "relation review", "relation_id")

    additions = {"source_records": [], "concepts": [], "claims": []}
    source_ids = set(existing_sources)
    mapped_sources = {}
    for sid, review in sorted(source_links.items()):
        if sid not in sources or sources[sid]["status"] != "inspected":
            raise ValueError("Source must be inspected: " + sid)
        if review["decision"] not in {"add", "link_existing"}:
            raise ValueError("Unknown source decision: " + sid)
        for field in ("checked_locator", "basis"):
            required(review[field], sid + "." + field)
        target = ident(review["model_id"], sid + ".model_id")
        if target in mapped_sources.values():
            raise ValueError("Multiple staged sources sharing a model source ID: " + target)
        if review["decision"] == "link_existing":
            existing = existing_sources.get(target)
            if (not existing or review["as_of"] is not None
                    or existing["url"] != sources[sid]["locator"]
                    or existing["role"] != sources[sid]["kind"]):
                raise ValueError("Existing source identity mismatch: " + sid)
        else:
            if target in source_ids or not isinstance(review["as_of"], str) or not DATE.fullmatch(review["as_of"]):
                raise ValueError("New source ID collision or invalid as_of: " + sid)
            source_ids.add(target)
            additions["source_records"].append({
                "id": target, "url": sources[sid]["locator"],
                "role": sources[sid]["kind"], "as_of": review["as_of"]})
        mapped_sources[sid] = target

    concept_ids = set(existing_concepts)
    for cid, item in sorted(new_concepts.items()):
        if cid in concept_ids:
            raise ValueError("New concept ID collides with existing: " + cid)
        for field in ("kind", "label", "checked_locator", "basis"):
            required(item[field], cid + "." + field)
        mids = item["evidence_mention_ids"]
        if (not isinstance(mids, list) or not mids or len(mids) != len(set(mids))
                or any(m not in mentions for m in mids)):
            raise ValueError("New concept requires distinct inspected mention IDs: " + cid)
        concept_ids.add(cid)
        additions["concepts"].append({"id": cid, "kind": item["kind"], "label": item["label"]})

    mapped_mentions = {}
    used_by_concept = {}
    for mid, item in sorted(identity_links.items()):
        if mid not in mentions:
            raise ValueError("Unknown reviewed mention: " + mid)
        cid = ident(item["concept_id"], mid + ".concept_id")
        if cid not in concept_ids:
            raise ValueError("Unreviewed concept identity: " + mid)
        for field in ("checked_locator", "basis"):
            required(item[field], mid + "." + field)
        if item["boundary_basis"] is not None:
            required(item["boundary_basis"], mid + ".boundary_basis")
        if cid in new_concepts and mid not in new_concepts[cid]["evidence_mention_ids"]:
            raise ValueError("New concept not evidenced by bound mention: " + mid)
        target_kind = (existing_concepts[cid]["kind"] if cid in existing_concepts
                       else new_concepts[cid]["kind"])
        if (target_kind not in ROLE_KINDS.get(mentions[mid]["kind"], {mentions[mid]["kind"]})
                and not item["boundary_basis"]):
            raise ValueError("Incompatible concept role requires boundary review: " + mid)
        mapped_mentions[mid] = cid
        used_by_concept.setdefault(cid, []).append(mid)
    for cid, mids in used_by_concept.items():
        signatures = {(mentions[mid]["kind"], mentions[mid]["scope"]) for mid in mids}
        if len(signatures) > 1 and any(not identity_links[mid]["boundary_basis"] for mid in mids):
            raise ValueError("Cross-role/scope identity requires explicit boundary review: " + cid)

    for cid, item in new_concepts.items():
        if any(mentions[mid]["source_id"] not in mapped_sources
               for mid in item["evidence_mention_ids"]):
            raise ValueError("New concept evidence requires a reviewed source: " + cid)
        if not any(mapped_mentions.get(mid) == cid for mid in item["evidence_mention_ids"]):
            raise ValueError("Orphan new concept without an explicit identity review: " + cid)

    claimed_ids = set(existing_claims)
    challenges = []
    for rid, review in sorted(relation_reviews.items()):
        if rid not in relations:
            raise ValueError("Unknown reviewed relation: " + rid)
        if review["decision"] not in {"accept", "defer"}:
            raise ValueError("Unknown relation decision: " + rid)
        for field in ("checked_locator", "basis"):
            required(review[field], rid + "." + field)
        if not isinstance(review["challenge_claim_ids"], list) or len(set(review["challenge_claim_ids"])) != len(review["challenge_claim_ids"]):
            raise ValueError("Invalid challenge list: " + rid)
        if review["decision"] == "defer":
            if (review["claim_id"] is not None or review["bridge_source_id"] is not None
                    or review["bridge_locator"] is not None or review["challenge_claim_ids"]):
                raise ValueError("Deferred relation cannot allocate claims or bridges: " + rid)
            continue
        rel = relations[rid]
        claim_id = ident(review["claim_id"], rid + ".claim_id")
        if claim_id in claimed_ids:
            raise ValueError("Claim ID collision: " + claim_id)
        claimed_ids.add(claim_id)
        subject_mid, object_mid = rel["subject_mention_id"], rel["object_mention_id"]
        if subject_mid not in mapped_mentions or object_mid not in mapped_mentions:
            raise ValueError("Accepted relation has unreviewed endpoint identity: " + rid)
        if any(sid not in mapped_sources for sid in rel["source_ids"]):
            raise ValueError("Accepted relation cites an unreviewed source: " + rid)
        endpoints = {mentions[subject_mid]["source_id"], mentions[object_mid]["source_id"]}
        if not endpoints.issubset(rel["source_ids"]):
            raise ValueError("Relation lacks endpoint source citations: " + rid)
        if len(endpoints) > 1:
            if (review["bridge_source_id"] not in rel["source_ids"]
                    or not isinstance(review["bridge_locator"], str)
                    or not review["bridge_locator"].strip()):
                raise ValueError("Cross-source bridge requires checked original span: " + rid)
        elif review["bridge_source_id"] is not None or review["bridge_locator"] is not None:
            raise ValueError("Unneeded bridge metadata for same-source relation: " + rid)
        claim = {"id": claim_id, "subject": mapped_mentions[subject_mid],
                 "predicate": rel["predicate"], "object": mapped_mentions[object_mid],
                 "truth_kind": rel["truth_kind"],
                 "source_ids": [mapped_sources[sid] for sid in rel["source_ids"]],
                 "scope": rel["scope"]}
        for target_id in review["challenge_claim_ids"]:
            previous = existing_claims.get(target_id)
            if not previous:
                raise ValueError("Challenge references unknown existing claim: " + rid)
            if any(previous[f] != claim[f] for f in ("subject", "predicate", "object", "scope")):
                raise ValueError("Challenge target is not the same scoped proposition: " + rid)
            if previous["truth_kind"] == claim["truth_kind"]:
                raise ValueError("Challenge cannot restate the same truth kind: " + rid)
            challenges.append({"new_claim_id": claim_id, "existing_claim_id": target_id,
                               "disposition": "preserve_both_pending_adjudication"})
        additions["claims"].append(claim)

    projected = copy.deepcopy(model)
    for name in additions:
        projected[name].extend(additions[name])
    errors = check(projected)
    if errors:
        raise ValueError("Proposed graph fails structural validation: " + "; ".join(errors))
    return {
        "schema": OUTPUT, "status": "proposal_only_no_original_mutated",
        "base_model_digest": digest(model), "staged_ingestion_digest": digest(staged),
        "review_decisions_digest": digest(reviews),
        "additions": additions,
        "challenges_preserved": challenges,
        "review_trace": {"source_links": reviews["source_links"],
                         "new_concepts": reviews["new_concepts"],
                         "identity_links": reviews["identity_links"],
                         "relation_reviews": reviews["relation_reviews"]},
        "pending": {"mentions": sorted(set(mentions) - set(identity_links)),
                    "relations": sorted(set(relations) - {rid for rid, r in relation_reviews.items()
                                                        if r["decision"] == "accept"}),
                    "sources": sorted(set(sources) - set(source_links))},
        "source_audit_findings": staged_audit["findings"],
        "unchanged": ["existing source records", "existing concepts", "existing claims",
                      "competency question statuses", "gaps"],
        "projection_structural_check": "passed",
        "limits": "Review declarations and source locators are not authenticated evidence. Human/agent source inspection, semantic entailment, independence and changed user outcomes remain unverified. This is an add-only proposal, not model approval, novelty or authorization."
    }, projected


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("model", type=Path)
    p.add_argument("staged", type=Path)
    p.add_argument("reviews", type=Path)
    p.add_argument("--output", type=Path, help="write review proposal JSON (default stdout)")
    p.add_argument("--preview", type=Path, help="write a separate, structurally checked candidate model")
    args = p.parse_args()
    try:
        paths = [x.resolve() for x in (args.model, args.staged, args.reviews)]
        for dest in (args.output, args.preview):
            if dest is not None and dest.resolve() in paths:
                raise ValueError("Refusing to replace original model, staged source or review file")
            if dest is not None and dest.exists():
                raise ValueError("Refusing to overwrite existing output: " + str(dest))
        if args.output and args.preview and args.output.resolve() == args.preview.resolve():
            raise ValueError("Proposal and preview must have distinct paths")
        data = [json.loads(pth.read_text(encoding="utf-8")) for pth in
                (args.model, args.staged, args.reviews)]
        result, preview = propose(*data)
        report_text = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
        if args.output:
            with args.output.open("x", encoding="utf-8") as handle:
                handle.write(report_text)
        else:
            print(report_text, end="")
        if args.preview:
            with args.preview.open("x", encoding="utf-8") as handle:
                handle.write(json.dumps(preview, indent=2, ensure_ascii=False) + "\n")
    except (OSError, UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        print("FAIL:", exc, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
