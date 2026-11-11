# Frontier qualification: distinguish missing knowledge from missing modeling

## The blank-space taxonomy

| Category | Test to perform | What to do |
|---|---|---|
| Acquisition omission | Does an already-cited manual, source file, user report, competitor or existing map contain the answer? | Add the missing evidence; call the prior gap an extraction failure |
| Identity/alias mismatch | Are multiple names, translations, file IDs or normalized terms the same underlying concept, or incorrectly merged? | Repair identity and preserve aliases; do not count duplicated concepts as novelty |
| Schema blind spot | Could the sources express this fact if only a different entity/edge/status type existed? | Extend schema minimally; backfill and retest old relationships |
| Underlinked known facts | Are the needed concepts present but the proposition or cross-workflow edge absent? | Add the directed relationship with evidence; retain linkage provenance |
| Contradiction/contested interpretation | Do sources or intended/implemented/observed statuses disagree? | Keep alternative claims, time/version, and test separating hypotheses |
| Unknown fact | Neither evidence nor a reliable claim of absence is available | Record unknown with next check; do not infer false |
| Version/provider/platform boundary | Does a capability exist only for a release, API, remote server, plugin or permission? | Split scope rather than claiming universal availability |
| Deliberate exclusion | Did the research question intentionally exclude the region? | State exclusion; do not score it as a new discovery |
| Candidate frontier | Does an important user-goal or relation remain inadequately addressed **after** source audits and counterexample searches? | Propose a falsifiable exploration with explicit local novelty boundary |

## The pre-frontier qualification gate

A candidate must pass these before receiving the status `qualified_frontier`. A failure returns it to **map repair**, **insufficient evidence** or **out of scope**.

1. **Explicit question and bounded universe.** What is the unanswered competency question? Is the claim limited to a particular inventory, app version, user segment and evidence window?
2. **Corpus coverage.** Enumerate missing source classes and biased populations, including native-language comments, negative/nonuser testimony, comparable software, implementation/API references and counterexamples. Report not searched and inaccessible.
3. **Normalization and ontology integrity.** Search synonyms, abbreviations, multilingual aliases, duplicate features, overbroad categories and false conceptual mergers.
4. **Competency-question stress test.** Ask whether each core user outcome and relationship is answerable, and run **held-out source ingestion** to detect forgotten existing facts. Manually inspect relevant false negatives.
5. **Alternative model stress test.** Reverse-map from user journeys to features and separately from features to user journeys; compare source-derived and independent candidate maps. Look for disconnected subgraphs and lifecycle handoff omissions.
6. **Disconfirmation.** Search for at least one credible already-implemented counterexample, explicitly including plugins, alternate platforms and adjacent industries. Record result, even when it reduces perceived novelty. **Failure to find an example is not proof of nonexistence.**
7. **Saturation and residual risk.** Sample new independent sources in rounds; log newly discovered concepts/relations and false novelty corrections. A plateau is *relative to the sampling strategy* and cannot certify world completeness.
8. **Falsifiable handoff.** Define the next observation that would refute novelty, the user outcome that makes the frontier interesting, cheapest next investigation, and the decision it could change.

## Proof ladder for gap claims

`gap_in_this_document` → `gap_in_scoped_source_corpus` → `gap_in_documented_implementations_checked` → `plausible_user_experience_gap` → `validated unmet user need`.

Moving right requires **additional sources or tests**. None automatically means a world-first invention. A new integration of familiar capabilities may be far more useful than a new primitive.

## Independent adversarial roles

- **Retriever:** rebuilds a map from previously excluded or held-out sources and finds skipped facts.
- **Ontologist:** challenges entity boundaries, edge direction, missing types and circular definitions.
- **Skeptic:** actively searches existing implementations, old terminology, opposite user testimony and earlier unsuccessful attempts.
- **Journey auditor:** searches start-to-finish tasks, interrupted and exceptional paths, alternative products and nonuser exits.

A single agent can perform these passes sequentially; multiple agents reduce correlated blind spots only when they use independently selected source sets. Their output is evidence and corrections, not a democratic vote.

## Prioritization

Rank remaining research by **expected reduction of decision-relevant uncertainty per unit of effort**, with risk, probable user value and falsifiability as separate inputs. Do not let graph-centrality or number of missing edges become a direct proxy for user demand. A missing relationship among high-degree nodes may be a banal omission; a single poorly connected exit route may expose a serious usability failure.
