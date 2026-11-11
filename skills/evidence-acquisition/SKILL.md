---
name: evidence-acquisition
description: Mechanically acquire, sample, inspect, compare and challenge evidence for unfamiliar user jobs, competitor capabilities, platform constraints and prior art. Use when a consequential decision depends on external information or search completeness, not when project primary evidence already answers a bounded fact.
metadata:
  version: "0.1.0"
  status: "editorial procedure; effectiveness unmeasured"
---

# Evidence Acquisition: research that another agent can reproduce

**Job:** answer a scoped evidence question without assuming the agent already knows the relevant products, people, languages, source classes or terms. A source search does not confer authority to share private data, install unknown code or manufacture user consensus. The host project remains authoritative.

## Trigger and exclusion

Activate for a consequential prior-art landscape, user preference/criticism investigation, competitor behavior comparison, or evidence-coverage challenge. Start from an unanswered claim and an action it could change, not a generic keyword. Skip when current code or a specific primary contract answers the bounded question. For candidate libraries and build-versus-reuse use [Engineering Resource Discovery](../engineering-resource-discovery/SKILL.md); for typed concept relations use [Concept Model Engineering](../concept-model-engineering/SKILL.md).

## E1–E8 mechanical investigation

Use the [detailed retrieval protocol](references/retrieval-protocol.md) and [teaching case](examples/retrofit-competitor-research.md). Record evidence in the existing project research authority.

1. **E1. Specify an actionable question.** Record decision ID, present claim/status, two plausible opposite answers, how each changes a design/implementation/acceptance, actor/product/version/time scope and essential evidence. If both answers lead to the same action, reject the investigation or label it optional background.
2. **E2. Build a source-class matrix.** For each question classify first-party manuals, running implementation/source code, actual installed behavior, user accounts, practitioner reports, tests/standards, and independent contrary accounts as needed, optional or irrelevant. Identify which source class could disprove the favored position. Status: inspected / not searched / inaccessible / missing from inspected inventory.
3. **E3. Generate independent discovery routes.** Derive queries from user job, failure symptom, technical synonyms, competitor neighborhoods, adjacent industries, alternative platform, and relevant original-language terms. Use at least two materially distinct routes for a critical class when feasible; record exact executed query, date, locale, index, results and exclusions. An unexecuted search is never an observed negative.
4. **E4. Sample and inspect candidates.** Choose mature, small, alternative-platform, OSS and plausible counterexample candidates when relevant, not only top-ranked sites. Record original publisher/thread, duplicate/translation lineage, version and source locator. A marketing screenshot proves a published claim, not actual behavior.
5. **E5. Extract attributed claims.** For each assertion record source ID, exact locator, described product/version/actor, evidence tier, uncertainty, and contradicting claim. Preserve original-language context. Individual complaints do not prove population prevalence, even if reposted widely.
6. **E6. Compare behaviors rather than labels.** Build product/version-by-outcome cells with controls, defaults, success, error, cancellation, permission, recovery, accessibility and cost where material. Every cell cites its record and status. Keep not inspected, not documented, reported absent and tested absent distinct.
7. **E7. Challenge search coverage.** Run at least one reverse search from an unexpected user journey and one independent disconfirmation route when they could alter the decision. Sample a held-out source; check whether it adds a material feature, user group, contradiction or reuse option. Audit synonymous names and source lineage before calling anything new.
8. **E8. Bound the conclusion and stop.** Stop when material source classes are covered or explicitly unavailable, credible alternative patterns and counterexamples have been sampled, and another affordable route lacks a plausible decision-changing outcome. Record source limits and fresh deltas. A source plateau is relative to the search strategy, never proof that the world was exhaustively examined.

## Failure branches

- **No source access:** deliver a concrete search plan and status INACCESSIBLE; do not fabricate findings.
- **One origin copied 20 times:** group lineage and seek different evidence; do not count 20 independent accounts.
- **Only marketing available:** describe documented claims, not verified interactions.
- **Version disagreement:** split by release/provider/period before treating testimony as contradictory.
- **No reliable preference evidence:** present observed implementation choices without claiming what most users prefer.
- **Research cannot discriminate:** retain bounded hypotheses and propose a safe test or owner decision without arbitrary choice.

**Independent oracle:** reverse-trace one important matrix cell back to original source and the decision, then challenge one settled conclusion using an independently chosen source. A structurally complete ledger cannot prove source truth.

**Completion receipt:** scoped questions | executed search routes and skipped classes | original evidence and lineage | behavior comparison | disconfirming observations | sampling limitations | decision changed or still unknown | cheapest next discriminator.
