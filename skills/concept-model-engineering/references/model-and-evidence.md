# Evidence-grounded model construction inside a larger workflow

## Five layers of representation

1. **Raw source:** unchanged URL, issue, file, commit, user quote or actual run, with publication date, version, language and observed scope.
2. **Atomic claim:** one subject–predicate–object proposition, provenance, confidence *basis*, direct/inferred status, contradiction, valid time and operating scope.
3. **Concept ontology:** stable concept IDs, labels and aliases, entity types, roles/relations, lifecycle states and relation/cardinality constraints. Keep class vs instance distinct.
4. **Human and system journeys:** user goal, trigger, decision point, authorized actor, operation, side effect, successful observable outcome, exception/recovery and abandonment. Classify `intended`, `implemented`, `observed`, `user_expected` independently.
5. **Gaps and interventions:** evidence of mapping omission, contradiction or qualified frontier; cheapest falsifier, priority and handoff owner. Graph appearance is a visualization of the data, not the source of record.

## Source-collection discipline

For user comments, code *explicit praise, criticism, workarounds, nonuse, neutral experience, implied expectations and competing explanations*. Count authors/threads carefully, avoid repeated syndications, and do not infer prevalence from votes or result rankings. Use native-language search phrases adapted to local terminology, plus searches for existing implementations contradicting the tentative feature gap.

For comparable apps, distinguish advertised functionality, current/version-specific implementation, API-only support, plugin and actual user-observed outcome. Keep an evidence status ladder, not a binary checkbox.

For technical models, read existing native source, tests, release/CI evidence and current authority docs before drafting new types. A matching named symbol is insufficient proof of behavior; a screenshot does not prove native operation.

## Build and audit loops

Begin with a focus question and 3–6 competency questions. Prepare a concept parking lot from multiple independently chosen sources. Create typed directed links with verb phrases. Add cross-links from goals to features, features to operations, operations to verified outcomes and error states, and research hypotheses to falsification sources. Revisit the focus question after each round.

Perform *reverse coverage*:
- For every major user goal, what journey and capability could achieve it?
- For every claimed capability, which actor, precondition, postcondition and failure are defined?
- For every original user observation, is its meaning represented without distortion?
- For every documented competitor implementation, does the map contain the capability and its scope?
- For every pending owner decision, is the ambiguity explicitly represented?
- For every inferred absence, did the agent search for already-present evidence?

The graph may be tiny for a single issue, or hundreds of nodes for a long research study. Use Markdown or JSON first; render a concept diagram when visually useful. Never require a graph database or LLM-driven ingestion for small tasks.

## Parent-workflow handoff

Return a *delta*, not another large product spec: changed source refs and concepts, old/new relations, unresolved contradictions and scope, qualification status of each gap, exact user-outcome effect, suggested next experiment and whether project authority permits a requirement change. Maintain versioned stable IDs; never silently rewrite old source evidence.

An Android agent can invoke this discipline briefly before a substantial navigation/default/feature relationship design, then use Android's normal engineering and acceptance process. The modeling step should not delay unambiguous crash fixes or routine CI repairs.
