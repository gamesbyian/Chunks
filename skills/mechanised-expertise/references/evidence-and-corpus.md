# Evidence, applicability and corpus conversion

## Two linked records per recommendation

**Source claim** (what an exemplar actually says): `evidence_id; publisher/repository; exact revision/blob/file/line; language; bounded paraphrase; source_scope; provenance/evidence tier; version applicability; origin/derivation status`. If source was inspected only through a summary, label it as indirect. When multiple packs share an idea, count *observed documents*, *publisher repositories* and *verified independent origins* separately; unknown origins stay unknown. Do not infer real-world effectiveness from repetition.

**Mechanised expertise proposal** (our explicit, testable transformation): `trigger; recognition cue; inquiry; answer-source strategy; competency questions and map template; option and exception; hard constraint; decision discriminator; independent oracle; reversal trigger; estimated investigation cost; evaluation status`. A generated proposal is a *hypothesis*, not something the exemplar necessarily taught.

Maintain a typed link `source supports problem/constraint`, `source proposes solution`, `source contradicts proposal`, `source supplies test`, `source is counterexample` or `editorial extension`. Never combine all link types into one prevalence count.

## Evidence tiers and common invalid leaps

1. *Textual prescription:* an author recommends an approach. It supports a claim about the author's advice, not efficacy.
2. *Static inspection:* code, configuration or schema demonstrates a state at a particular revision; does not establish runtime success.
3. *Simulated/local tests:* concrete input-output contract under controlled stubs; may not exercise platform/provider.
4. *Installed runtime/device:* user-like journey with environment, app SHA, instrumentation and observable result.
5. *Field/human observation:* actual user experience with described sample and limitations.
6. *Comparative effectiveness:* matched tasks, controlled baseline, independent oracle, confidence/variation and costs.

Prefer source-specific, self-contained checks. A false-positive tests-only rescue, a skipped CI check and a build with no installed behavior must remain distinguishable.

## Concept-map coverage discipline

Model nodes may be actors, goals, capabilities, providers, states, assets, versions, risks and evidence. Directed edges must have meaningful predicates, scope and evidence. Run competency queries, inspect disconnected nodes, reverse-search from an unfamiliar user journey, then consult a held-out source. Classify blank as: acquisition omission; alias mismatch; schema omission; missing-known-edge; contradictory; provider/version boundary; deliberate exclusion; fact unknown; candidate frontier. Only frontier candidates progress to novelty searches, with original-language synonyms and falsifying counterexamples.

## Weighting and evaluation

Maintain separate sortable quantities for source prominence (where within a document and how strongly emphasized), observed prevalence (number of distinct documents/repositories within *sample*), provenance strength (revision+line+inspection), applicability, project-specific relevance, cost/benefit estimate, and experimental efficacy. Never count generated associations as independent corroboration. If a source is copied, translations or a common upstream excerpt, collapse to a lineage family when justified and retain the raw counts too.

For evaluation, give comparable agents the same repo, task, constraints and evidence access. Randomize whether they get the candidate skill. Blind-review an independent observable acceptance test, count missed risks, needless questions, overengineering, time/tokens, false success claims, accessibility and destructive behavior. Include near/far transfer and an adversarial variant where the normal recommendation is inapplicable. Do not infer effectiveness from a toy fixture alone.

## Integration with the current corpus

The November 2 v0.6 mobile corpus already has `instructions_v06.csv`, `unified_evidence_v06.csv`, `provenance_audit_v06.csv`, `candidate_practice_edges_v06.csv` and `practice_breadth_v06.csv`. Those five historical v06 CSV datasets are **not bundled** in this portable skills tree; this is background provenance, not a locally executable corpus-enrichment requirement. Locate and verify the original corpus under separately authorized project/research access before enrichment; if unavailable mark the activity blocked rather than fabricate records. Those earlier 1,073 recommendation records include syntheses and overlaps; do not restate them as independent validated rules. A later expertise enrichment must retain the original ID and add `proposal_state=generated|reviewed|experimented`, `source_claims`, `applicability_exclusions` and the proposed inquiry and oracle. No automatic enrichment should be counted as source evidence.