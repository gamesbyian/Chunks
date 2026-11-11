# Source acquisition, extraction and identity reconciliation (M2–M3)

This is a **direct Concept Model Engineering procedure**, not a mandatory Mechanised Expertise preflight. The optional standard-library auditor reports discrepancies to inspect. It does **not** search documents, understand languages, adjudicate identities, authenticate provenance, modify the concept model or measure user demand.

## 1. Choose evidence based on the question

**Input:** focus/scope, competency questions and existing source inventory.

1. For each question, name the source classes whose possible answers could change its truth status: product-owner decision, shipping implementation, actual user report, first-party reference, competitor, test or another bounded class. Do not blindly demand all classes.
2. Record inspected / not searched / inaccessible for each source, including a version or time, language, exact locator, collection method and potential lineage (copy, translation, reprint, common publisher). Use `unknown` for unverified origin.
3. Group multiple documents with the same **declared** lineage without pretending authorship independence is established. Different sources may still disagree or contribute distinct observations. Do not infer popularity or prevalence from sample size.
4. Ask which missing source class could change the answer; investigate that one first, if access/cost permits. Record inaccessible, stale and excluded evidence rather than silently filling it in.

**Output:** question-to-source coverage ledger and explicit next retrieval. **Stop:** additional attainable source classes are unlikely to alter the scoped answer, or a blocking evidence gap is recorded.

## 2. Extract small, attributed propositions

**Input:** inspected original material and the existing ontology.

1. For each relevant source span, record its exact locator, original-language label, source ID, applicable product/version/role, and whether the material is a stated claim, a code observation, an inference or disputed evidence.
2. Extract candidate mentions in one of the bounded roles: actor, goal, action, object, state, outcome, permission, system or other.
3. Build each proposed directed statement from **subject mention → specific predicate → object mention**, with scope, evidence status and source references. If the relationship spans two sources, record both and mark the bridge for checking.
4. A relationship combining **different source records** requires a checked bridge between subject and object; citing both extracted mentions does not prove their relationship. Keep the relation inferred/unresolved until inspected evidence supports it.
5. Record unresolved labels and interpretations as candidates. Never assign missing content, an identity equivalence, a source quotation or a relationship solely because the concept map would look better with it.
6. Keep conflicting claims separately, including version/time. Source agreement, author independence, semantic entailment and runtime truth require additional evidence.

**Output:** staged mentions/relations with source-backed provenance. **Stop:** extracted propositions are linked to original material and each consequential ambiguity is labeled for review.

## 3. Reconcile identities without an automatic merger

**Input:** staged mentions plus existing stable concept IDs and alias ledger.

1. Compare Unicode NFKC/casefold/whitespace-normalized labels only to **generate review candidates**. Preserve original spelling, diacritics, punctuation, original language and source spans.
2. For equal labels, compare role, scope, version, stable external ID and original source context. Same label with different roles or scopes can be a homonym. Same role and scope with different IDs can be an alias candidate.
3. For one proposed concept ID with different labels, require direct identity or translation evidence. False mergers hide blank spaces; false splits manufacture them.
4. Accept an identity link only after checking the actual source and recording its basis. If not settled, preserve separate IDs and `unknown` or `candidate` relation.
5. Re-run competency coverage after accepted changes. Any perceived frontier must survive omitted-source, alias and missing-edge checks before proceeding to novelty research.

**Output:** accepted concept link with evidence, rejected match with reason, or bounded unresolved candidate. **Stop:** identity changes relevant to the current question are resolved or explicitly deferred without claiming novelty.

## Optional deterministic audit

From the **repository root**:

```sh
python3 skills/concept-model-engineering/scripts/audit_ingestion.py skills/concept-model-engineering/examples/multilingual-ingestion.json
```

Input is a `concept-model/source-ingestion-v1` JSON ledger: `focus_question`, `scope`, `questions[]` (required source kinds), `sources[]` (inspection status, declared lineage, source-to-question links), `mentions[]` (scoped labels and tentative IDs), and `relations[]` (typed, sourced links). The synthetic fixture has duplicate publisher lineage, full-width Unicode variants, a possible homonym, multilingual alias, unsearched test class and opposed statements.

The coverage report distinguishes missing source classes that were never inventoried, explicitly not searched, or inaccessible, and flags cross-source relation bridges even when both endpoints are cited. The tool rejects unknown IDs, duplicate records, uninspected sources cited as inspected evidence, malformed relation endpoints and invalid source/question references. It emits a question coverage table and bounded review candidates. It never writes to `concept-model/v1`, automatically merges similar labels, or grants `qualified_frontier` status.

**Falsifier:** inspect a reported candidate in the original source and demonstrate why its apparent similarity or missing coverage is irrelevant; record the false positive. Treat a structurally clean audit as *absence of flagged patterns*, never as proof of complete or true modelling.
