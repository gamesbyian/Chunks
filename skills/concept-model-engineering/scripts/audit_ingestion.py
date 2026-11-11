#!/usr/bin/env python3
"""Audit a staged concept-model source ledger without merging or proving claims.

All labels, inspection statuses and source lineages are caller-supplied.
The report contains *review candidates* only, never inferred truth or novelty.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import sys
import unicodedata

SCHEMA = "concept-model/source-ingestion-v1"
OUTPUT = "concept-model/source-ingestion-audit-v1"
KINDS = {"owner_decision", "implementation", "user_report", "primary_document",
         "competitor", "test", "other"}
STATUSES = {"inspected", "not_searched", "inaccessible"}
TRUTHS = {"documented", "observed", "inferred", "hypothesized",
          "contested", "refuted", "unknown"}
MENTION_KINDS = {"actor", "goal", "action", "object", "state", "outcome",
                 "permission", "system", "other"}
IDENT = re.compile(r"[A-Za-z][A-Za-z0-9_:-]*\Z")
PREDICATE = re.compile(r"[a-z][a-z0-9_]*\Z")


def require_string(value, label, minimum=1):
    if not isinstance(value, str) or len(value.strip()) < minimum:
        raise ValueError("Missing or invalid " + label)
    return value.strip()


def records(items, fields, name):
    if not isinstance(items, list):
        raise ValueError(name + " must be a list")
    result = {}
    for entry in items:
        if not isinstance(entry, dict) or set(entry) != set(fields):
            raise ValueError("Malformed " + name + " record")
        key = require_string(entry["id"], name + " ID")
        if not IDENT.fullmatch(key) or key in result:
            raise ValueError("Duplicate or malformed " + name + " ID: " + key)
        result[key] = entry
    return result


def normalize_label(value):
    """Conservative comparison only. Never remove accents or punctuation."""
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def audit(data):
    if not isinstance(data, dict) or set(data) != {
            "schema", "focus_question", "scope", "questions", "sources",
            "mentions", "relations"} or data["schema"] != SCHEMA:
        raise ValueError("Unknown source-ingestion schema/fields")
    require_string(data["focus_question"], "focus question", 8)
    require_string(data["scope"], "bounded research scope", 8)
    questions = records(data["questions"],
                        ("id", "question", "required_source_kinds"), "question")
    sources = records(data["sources"],
                      ("id", "kind", "locator", "revision", "language",
                       "lineage_id", "status", "covers"), "source")
    mentions = records(data["mentions"],
                       ("id", "source_id", "label", "kind", "scope",
                        "evidence_locator", "candidate_concept_id"), "mention")
    relations = records(data["relations"],
                        ("id", "source_ids", "subject_mention_id", "predicate",
                         "object_mention_id", "truth_kind", "scope",
                         "evidence_locator"), "relation")
    if not questions:
        raise ValueError("At least one competency question is required")

    for ident, q in questions.items():
        require_string(q["question"], ident + ".question", 8)
        kinds = q["required_source_kinds"]
        if (not isinstance(kinds, list) or not kinds or len(set(kinds)) != len(kinds)
                or any(k not in KINDS for k in kinds)):
            raise ValueError("Invalid required source kinds: " + ident)

    for ident, source in sources.items():
        for field in ("locator", "revision", "language", "lineage_id"):
            require_string(source[field], ident + "." + field)
        if source["kind"] not in KINDS or source["status"] not in STATUSES:
            raise ValueError("Invalid source kind or inspection status: " + ident)
        covers = source["covers"]
        if (not isinstance(covers, list) or len(covers) != len(set(covers))
                or any(q not in questions for q in covers)):
            raise ValueError("Unknown/duplicate source-question link: " + ident)

    for ident, mention in mentions.items():
        sid = mention["source_id"]
        if sid not in sources or sources[sid]["status"] != "inspected":
            raise ValueError("Mention requires an inspected source: " + ident)
        for field in ("label", "scope", "evidence_locator"):
            require_string(mention[field], ident + "." + field)
        if mention["kind"] not in MENTION_KINDS:
            raise ValueError("Invalid mention kind: " + ident)
        cid = mention["candidate_concept_id"]
        if cid is not None and (not isinstance(cid, str) or not IDENT.fullmatch(cid)):
            raise ValueError("Invalid candidate concept ID: " + ident)

    for ident, relation in relations.items():
        ids = relation["source_ids"]
        if (not isinstance(ids, list) or not ids or len(set(ids)) != len(ids)
                or any(s not in sources or sources[s]["status"] != "inspected"
                       for s in ids)):
            raise ValueError("Relation needs inspected source citations: " + ident)
        for endpoint in ("subject_mention_id", "object_mention_id"):
            if relation[endpoint] not in mentions:
                raise ValueError("Relation references unknown mention: " + ident)
        for field in ("scope", "evidence_locator"):
            require_string(relation[field], ident + "." + field)
        if (relation["truth_kind"] not in TRUTHS
                or not isinstance(relation["predicate"], str)
                or not PREDICATE.fullmatch(relation["predicate"])):
            raise ValueError("Invalid relation predicate/truth: " + ident)

    findings = []
    def add(kind, subject_ids, operation, basis):
        findings.append({"kind": kind, "subject_ids": sorted(subject_ids),
                         "operation": operation, "basis": basis})

    coverage = []
    for qid, question in sorted(questions.items()):
        inspected = [s for s in sources.values()
                     if qid in s["covers"] and s["status"] == "inspected"]
        covered = sorted({s["kind"] for s in inspected})
        missing = sorted(set(question["required_source_kinds"]) - set(covered))
        declared = sorted({s["lineage_id"] for s in inspected
                           if s["lineage_id"] != "unknown"})
        unknown = sorted(s["id"] for s in inspected if s["lineage_id"] == "unknown")
        uninspected = {
            kind: sorted({s["status"] for s in sources.values()
                          if s["kind"] == kind and qid in s["covers"]})
                  or ["not_in_inventory"]
            for kind in missing
        }
        coverage.append({"question_id": qid,
                         "inspected_source_ids": sorted(s["id"] for s in inspected),
                         "inspected_source_kinds": covered,
                         "uninspected_required_kinds": missing,
                         "uninspected_required_statuses": uninspected,
                         "distinct_declared_lineages": len(declared),
                         "unknown_lineage_source_ids": unknown})
        if missing:
            add("source_class_gap_review", [qid],
                "Inspect one relevant missing source class or document why it cannot change the answer.",
                ", ".join(missing))
        if len(inspected) > len(declared) + len(unknown):
            add("shared_lineage_review", [qid],
                "Check whether repeated publisher lineage supplies new observations; do not count copies as independent corroboration.",
                "Inspected source count exceeds separately declared lineages plus unknown origins")

    labels = defaultdict(list)
    claimed_ids = defaultdict(list)
    for mention in mentions.values():
        labels[normalize_label(mention["label"])].append(mention)
        if mention["candidate_concept_id"] is None:
            add("unresolved_mention_review", [mention["id"]],
                "Inspect original span and ontology before proposing a concept identity.",
                "No candidate concept identifier")
        else:
            claimed_ids[mention["candidate_concept_id"]].append(mention)
    for norm, members in sorted(labels.items()):
        if len(members) < 2:
            continue
        by_signature = defaultdict(list)
        for member in members:
            by_signature[(member["kind"], member["scope"])].append(member)
        if len(by_signature) > 1:
            add("homonym_or_scope_collision_review", [m["id"] for m in members],
                "Compare roles, scope and original spans; identical labels may name distinct things.",
                "Same normalized label across different kinds or scopes")
        for group in by_signature.values():
            if len(group) > 1 and len({m["candidate_concept_id"] for m in group}) > 1:
                add("possible_identity_match_review", [m["id"] for m in group],
                    "Check stable IDs, entity type and direct alias evidence before proposing an identity link.",
                    "Same conservatively normalized label and role/scope, differing or unassigned identities")
    for cid, members in sorted(claimed_ids.items()):
        if len(members) < 2:
            continue
        if len({(m["kind"], m["scope"]) for m in members}) > 1:
            add("claimed_identity_boundary_review", [m["id"] for m in members],
                "Review apparent cross-role or cross-scope identity before retaining the shared concept ID.",
                "Shared candidate ID across different roles or scopes")
        if len({normalize_label(m["label"]) for m in members}) > 1:
            add("alias_evidence_review", [m["id"] for m in members],
                "Check primary alias or translation evidence; different labels do not independently prove sameness.",
                "One candidate concept ID has different normalized labels")

    rel_groups = defaultdict(list)
    for rel in relations.values():
        cited = set(rel["source_ids"])
        endpoint_sources = {mentions[rel[x]]["source_id"]
                            for x in ("subject_mention_id", "object_mention_id")}
        if not endpoint_sources.issubset(cited):
            add("cross_source_relation_review", [rel["id"]],
                "Verify the join between independently extracted mentions and cite the relevant original spans.",
                "Relation cites neither all endpoint source records nor a checked bridge")
        if len(endpoint_sources) > 1:
            add("cross_source_bridge_review", [rel["id"]],
                "Inspect an original statement supporting this directed link, or record it as an unresolved inference; citing both endpoint mentions is insufficient.",
                "Subject and object mentions originate in separate source records")
        def entity(mention_id):
            m = mentions[mention_id]
            return m["candidate_concept_id"] or "mention:" + mention_id
        key = (entity(rel["subject_mention_id"]), rel["predicate"],
               entity(rel["object_mention_id"]), rel["scope"])
        rel_groups[key].append(rel)
    for group in rel_groups.values():
        kinds = {r["truth_kind"] for r in group}
        if "refuted" in kinds and kinds & {"documented", "observed"}:
            add("opposed_claims_review", [r["id"] for r in group],
                "Inspect original evidence, time and meaning; preserve opposed claims until adjudicated.",
                "Same candidate relation and scope has positive and refuting claims")

    findings.sort(key=lambda f: (f["kind"], f["subject_ids"]))
    return {
        "schema": OUTPUT,
        "status": "review_candidates_only",
        "counts": {"questions": len(questions), "sources": len(sources),
                   "mentions": len(mentions), "relations": len(relations),
                   "review_candidates": len(findings)},
        "coverage": coverage, "findings": findings,
        "limits": "Declared source status, lineage, aliases and claims have NOT been externally verified. Labels are only compared, never merged. No source independence, truth, novelty, feature absence or model completeness is established."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ledger", type=Path)
    args = parser.parse_args()
    try:
        data = json.loads(args.ledger.read_text(encoding="utf-8"))
        print(json.dumps(audit(data), indent=2, ensure_ascii=False))
    except (OSError, UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        print("FAIL:", exc, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
