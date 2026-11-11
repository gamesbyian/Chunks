# Reviewed model deltas (M3 to M4): repair without erasing evidence

This optional, independently usable Concept Model Engineering procedure repairs an existing scoped concept-model/v1 after [staged source ingestion](source-ingestion-reconciliation.md). It produces a proposed, reviewable addition to a graph, never a wholesale rewrite, automatic merger, or declaration of novelty.

## Trigger and sequence

Use when a source audit exposes a consequential omitted claim, ambiguous identity, contradiction or unrepresented relation that affects a competency question. Skip if the model already answers the question, or when only speculative evidence exists.

1. **Pin the base.** Run the existing model checker and ingestion auditor, note the exact base model revision, and name the affected competency question.
2. **Inspect original spans.** Verify the direct source material, not merely the auditor's candidate findings. Register a staged source as a new record or link to an existing record only when its locator and role match. A shared publisher or translated document is not independent corroboration.
3. **Resolve identities explicitly.** For each relevant mention, record the exact existing/new concept ID and the inspected supporting locator. Different roles or scopes mapping to one concept require a specific boundary explanation. Identical labels never authorize automatic merging.
4. **Review directed claims.** Bind both endpoints and cited sources before acceptance. A relationship assembled from different source records requires a checked original bridge statement, with source ID and exact locator. Keep the original truth kind: review cannot promote an inference into an observation.
5. **Preserve disagreement.** Refuting evidence creates a separate claim. To challenge an existing claim, identify the exact same scoped proposition and retain both pending independent adjudication.
6. **Generate the proposal and preview.** Inspect precise additions, pending reviews, original-source audit findings, and the base-model digest. Compare that digest with the current target before any separate authorized integration. The preview is not a canonical write or evidence of semantic accuracy.
7. **Integrate in a separate reviewed project change.** Verify real evidence and competency answers against the combined head. Only a distinct review can amend existing claims, question statuses or gaps.

**Output:** additive proposed source records, concepts and claims; challenged-claim annotations; unresolved record IDs; a snapshot digest; optional candidate model. **Stop:** a consequential new proposition is supported by checked source spans and explicit identity decisions, or remains pending with a bounded next inquiry.

## Offline synthetic example

From repository root:

~~~sh
python3 skills/concept-model-engineering/scripts/propose_model_delta.py \
  skills/concept-model-engineering/examples/cross-system-journey.json \
  skills/concept-model-engineering/examples/multilingual-ingestion.json \
  skills/concept-model-engineering/examples/reviewed-delta-decisions.json \
  --output /tmp/concept-delta-proposal.json \
  --preview /tmp/concept-delta-preview.json
python3 skills/concept-model-engineering/scripts/check_model.py /tmp/concept-delta-preview.json
~~~

The fixture accepts one same-source statement and explicitly defers a cross-source relationship. The command refuses to overwrite any of its three input files.

Review format: concept-model/reviewed-delta-v1. The four required collections are source_links (stage ID, model ID, add/link_existing, reviewed as-of date where relevant, source locator and basis); new_concepts (ID, kind, label, evidence mention IDs, checked locator and basis); identity_links (mention ID, concept ID, checked locator, basis, optional explicit boundary_basis); and relation_reviews (relation ID, accept/defer, claim ID for accept, checked locator and basis, optional cross-source bridge source/locator, and challenge claim IDs).

All IDs must be explicit. Existing claims, source records, concept records, competency-question statuses and gaps remain unchanged. The proposal includes the declared review trace so an independent reviewer can challenge each exact source span; it does not authenticate those declarations. A new concept requires a separately reviewed in-model source, and incompatible existing concept kinds require an explicit boundary explanation. Output and preview paths must be fresh: the CLI refuses to overwrite unrelated files. The projected model is checked with the existing structural checker. Neither the checker nor the generator authenticates inspection, translation equivalence, independent authorship, semantic entailment, or an actual runtime outcome. A fabricated checked locator can satisfy structural validation; direct source inspection remains essential. No generator operation grants deployment, merge, feature authorization or novelty status.
