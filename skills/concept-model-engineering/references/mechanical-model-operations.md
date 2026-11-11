# Mechanical operations for constructing and using concept models

These are **operations within Concept Model Engineering**, usable directly without first loading Mechanised Expertise. They apply the same observable input → transformation → output → falsifier → stop discipline. The map is an independently useful research artifact, not merely a tool for choosing code. Do not manufacture claims or pretend a graph validator establishes external truth.

## Select the minimum operation sequence

| Trigger | Operations to load | Skip when |
|---|---|---|
| Starting a map from mixed sources | M1 → M2 → M3 | A scoped, versioned model already answers the focus questions |
| Integrating new source material | M2 → M3 → M4 | Text has no new or disputable domain facts |
| Auditing an apparently incomplete map | M4 → M5 → M6 | The relevant question is already supported at required evidence tier |
| Exploring suspected frontier / new feature relation | M4 → M5 → M6 → M7 | A source omission, alias or version difference already resolves it |
| Using a model for another task | M4 → M7 | Task needs only a direct source/code check; model adds no useful relation |

## M1. Frame competency questions
**Input:** current task, authorized scope, known decisions.
**Transform:** (a) write one focus question; (b) specify actors, domain, time/version, products and exclusions; (c) write 3–6 questions with answerable forms (which actor? which relation? under which condition?); (d) mark each required evidence tier. 
**Output:** focus/scope and numbered competency questions.
**Reject:** an uncomplicated local question already answered by a direct lookup.
**Stop:** every question is either scoped or explicitly excluded, without inventing unanswered product requirements.

## M2. Build source coverage inventory
**Input:** competency questions and access to current source corpus.
**Transform:** (a) enumerate available source classes: user testimony including nonusers, owner requirements, running implementation, primary manuals/standards, competitor behavior, tests and counterexamples; (b) record source ID, revision/time, language, observed domain and collection method; (c) mark classes absent/inaccessible; (d) retrieve an independent class for every consequential uncorroborated proposition when feasible.
**Output:** source ledger with covered, missing, derived/possibly duplicated, inaccessible statuses. For mixed or multilingual sources, apply the [staged ingestion and lineage procedure](source-ingestion-reconciliation.md); the audit never establishes independence.
**Reject:** demanding every imaginable source class; enumerate only those capable of changing the focus question.
**Stop:** further attainable source classes have no credible way to change a competency answer or evidence boundary; disclose exclusions.

## M3. Extract entities and typed propositions
**Input:** recorded sources, existing ontology, focus question.
**Transform:** (a) extract explicit actor, goal, action, object, state and outcome spans; (b) create stable IDs and aliases; (c) for each relationship write subject ID, specific verb predicate, object ID, scope, truth kind and source IDs; (d) keep observed, documented, inferred and contested statements distinct; (e) record conflicting claims separately instead of overwriting them.
**Output:** nodes, alias ledger and cited directed edges. For disputed names, homonyms or conflicting source claims, use the [identity reconciliation procedure](source-ingestion-reconciliation.md) and retain unresolved candidates rather than merging automatically. To incorporate checked evidence into an **existing** graph, use the [optional reviewed delta procedure](reviewed-model-deltas.md); it proposes additions without silently rewriting earlier claims or status.
**Reject:** adding a proposition with no source or clearly labeled hypothesis, or merging names with unproven identity.
**Stop:** each consequential proposition is grounded and expressible without creating needless relation types.

## M4. Run bidirectional competency coverage
**Input:** model, competency questions and original source set.
**Transform:** (a) locate supporting claim IDs for each question; (b) traverse from user goal to actions, authority, states, feedback and recovery, then backward from claimed capabilities to user goals; (c) detect isolated concepts, dangling aliases, unreferenced sources, contradictory or unknown truth kinds; (d) compare graph structure with a held-out source or independent representation; (e) record which discovered issues affect a target question.
**Output:** answer status per question and review candidates. Use `python3 skills/concept-model-engineering/scripts/audit_coverage.py <model.json>` for *structural leads*, not semantic conclusions.
**Reject:** calling any disconnected node automatically a missing feature or defect.
**Stop:** every material question has supported answer, bounded partial answer, or an explicit next evidence probe.

## M5. Qualify blank spaces before novelty
**Input:** unanswered competency question, candidate missing directed edge and source inventory.
**Transform:** check in order: (1) omitted source or extraction; (2) synonyms/multilingual aliases; (3) ontology lacks an edge/entity type; (4) already known concepts are not linked; (5) source contradiction; (6) provider/version boundary; (7) deliberate exclusion; only then (8) candidate frontier. Record the earliest adequate explanation and cheapest falsifier.
**Output:** typed gap with its evidence and next check.
**Reject:** claiming nonexistence or unmet user need merely from absent edge or unsuccessful search.
**Stop:** one explanation is supported sufficiently for the current task, or a bounded unknown remains.

## M6. Adversarial source challenge
**Input:** suspected frontier and its bounded novelty claim.
**Transform:** (a) search primary code/manuals for names and aliases; (b) search at least one different language or adjacent domain when relevant; (c) inspect an independently selected/held-out source; (d) attempt one plausible already-implemented counterexample; (e) record what was searched and what remains inaccessible; (f) narrow the novelty claim to the inspected universe.
**Output:** disconfirmation attempts, scope, confidence basis, and testable frontier hypothesis if still unresolved.
**Reject:** a novelty label without inspected sources, and duplicate syndicated testimony counted as independent.
**Stop:** a counterexample refutes novelty, or further search no longer changes the qualified bounded claim within available evidence.

## M7. Handoff into use, or deliver the map itself
**Input:** completed/partial graph, question statuses, gaps and authority scope.
**Transform:** (a) return map delta (new IDs, typed edges, changed statuses); (b) list what remains unknown, by cause; (c) state relevant implications for research, UX, implementation or no downstream task; (d) name the next cheap discriminator; (e) retain provenance and never silently replace source claims.
**Output:** independently useful map or bounded handoff, not an invented requirement.
**Reject:** conversion of an editorial hypothesis into a feature request without authority, or claiming tests ran when they did not.
**Stop:** recipient can answer its scoped question, or identify exactly which missing evidence prevents an answer.

## Tool limitations

The optional audit script surfaces *structural review candidates* (unanswered questions, weak evidence, isolated nodes, unused sources, gap classification). It cannot judge user needs, semantic entailment, source independence, novel discovery, usability, authorization or implementation correctness. The existing `check_model.py` checks structure; it remains the authoritative format validator.
