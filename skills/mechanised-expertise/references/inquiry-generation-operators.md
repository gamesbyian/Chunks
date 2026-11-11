# Inquiry-generation operators: systematic, bounded expert question discovery

**Status:** editorial synthesis. This method is *informed by* pinned agent-skill prescriptions and research in goal-oriented requirements engineering and design rationale. None of the nine operators is asserted to be a quotation from those sources or empirically proven to improve agent performance. The companion machine-readable [operator catalog](../assets/inquiry-operators-v1.json) records the original links and explicit disqualifiers.

## The operational problem

A competent agent can follow a checklist yet overlook the *question that would change its decision*. One can add more checklists indefinitely and still miss a cross-feature relationship, an overlooked user's mental model, or an untested assumption. This reference teaches a small set of **transformations** that turn concrete, observed circumstances into falsifiable questions. Apply them only as needed.

First reconstruct `actor → desired observable goal → current product journey → system state/identity → constraints → available evidence`. The visible proposed UI solution can be an important user clue without settling the user's underlying job. Separate owner goals, already-adopted specifications, observed behavior, hypotheses and unresearched source classes.

## Nine optional operators

| Operator | Transform the starting evidence into | When it is useful |
|---|---|---|
| `GOAL-INVERT` | A successful goal → plausible, observable obstacles to that goal | Requirements omit feasible failure/recovery states |
| `MENTAL-MODEL` | A named control → contrasting user expectations and jobs | UX advice relies on “obvious”, “intuitive”, or “simpler” |
| `BOUNDARY-CROSS` | An action → possible drift across scope, process, permission, account or time | Work crosses mutable/uncertain state boundaries |
| `DESIGN-QOC` | An important question → real options → hard constraints and evidence-bearing criteria | Viable alternatives have nontrivial consequences |
| `MAP-REVERSE` | User goal → implementation and back; missing link → qualified gap | Knowledge graph or feature map seems incomplete |
| `SCOPE-CHALLENGE` | Popular advice → test its hidden prerequisites and nearest exception | Transferring techniques between frameworks/projects |
| `SOURCE-OPPOSITION` | Provisional claim → contradicting evidence and competing source classes | One source family, weak coverage or novelty claim dominates |
| `ORACLE-INVERT` | Passing test → realistic situation in which the user still fails | A narrow check is being inflated into a shipping claim |
| `COMPOSE-JOBS` | Two complete subsystems → possible valuable joint user journey | Cross-feature opportunities, missed relationships, manual workarounds |

### How to generate the next question

1. **Choose the operator by its causal cue**, not a word match. For example, “file” in an agent-instruction filename is not evidence that the task is a storage operation.
2. **Apply the reject condition first.** If the task is a deterministic, reversible label edit, stop after the local fix and test.
3. **Name two plausible opposite answers.** If both lead to the same implementation, user-visible state, policy or test, drop the question.
4. **Name the cheapest trustworthy answering source.** Read actual product instructions and code before asking the owner. Use real user observations to answer experiential questions; vendor docs to answer platform behavior; an experiment to decide a contested causal claim.
5. **Decide whether a map is worth the cost.** Use a short trace for one path. Use a typed model and [Concept Model Engineering](../../concept-model-engineering/SKILL.md) when missing relationships between roles, goals, objects, life cycles or sources may change the decision.
6. **Audit gaps before calling them new.** Check unsearched source, aliases, absent relation type, already-known unlinked fact, version boundary, conflict and deliberate exclusion. A graph blank never by itself proves missing product functionality or novel user demand.
7. **State a reversal observation and an oracle** before implementing a consequential choice. Record remaining untested or user-dependent conditions.

## Deepening research without making it endless

Use **goal obstacles** when a specification contains idealized successful behavior. KAOS, a goal-oriented requirements method, explicitly models goals, agents, obstacles and assignment of responsibility. Negating a leaf-level user goal helps suggest *feasible* failure causes, but an app should not model every imaginable hazard. [KAOS research](https://webperso.info.ucl.ac.be/~avl/gore.php); [obstacle analysis](https://research.dial.uclouvain.be/entities/publication/de49727c-3abf-4113-8b8d-8b24334e4f7e).

Use **Questions–Options–Criteria (QOC)** when several credible designs or architectures have important tradeoffs. Represent the consequential question, feasible options, hard constraints and criteria, then seek an observation that distinguishes the contenders. This is a design-space aid, not a requirement for small fixes. Research has found QOC more helpful for poorly understood design spaces than for tightly constrained ones. [Original QOC framework](https://www.tandfonline.com/doi/abs/10.1080/07370024.1991.9667168); [cognitive-compatibility analysis](https://www.tandfonline.com/doi/full/10.1207/s15327051hci1203_2).

Use **cross-system compositional search** to discover opportunities, not generate requirements. Find recurring user goals, shared entities, manual workarounds, and places where one app/service should explain another's state. A proposed relation must still survive product intent, source provenance, authority boundaries, prior-art searches and a cheap falsifying test. An *unconnected node* is a reason to inspect the model, not proof that the feature is missing.

## Three short examples

- **Compact transfer list:** Apply `MENTAL-MODEL` to ask which data must be immediately recognizable, `BOUNDARY-CROSS` only if mutable rows can retarget actions, and `ORACLE-INVERT` if a Compose preview is being claimed as installed usability evidence. Skip speculative architecture. A supported-device installed journey is the oracle.
- **Movie ↔ album discovery:** Apply `COMPOSE-JOBS` to hypothesize useful user relationships, then `MAP-REVERSE` to distinguish source metadata absence from an unimplemented relation. Explicitly type `person performed-on recording`, `actor portrayed person`, `film inspired-by biography`, and inference confidence; do not treat a six-hop thematic connection as the same kind of match as identical media ID. Do not automatically mutate transfers based on an inferred link.
- **One-character settings typo:** All nine operators normally reject. Respect the user-approved label, change one string, and run the narrow test. Elaborate QOC and full concept mapping would be negative-value overhead.

## Preserve multiple kinds of evidence

Each generated question is **a proposal**. A pinned source passage supports *what its author advised*, not that the new question is correct or useful. A plausible counterexample is not an observed defect. A mocked test is not an actual provider effect. If an apparent issue is disproved, record it as a false positive and narrow the rule.

**Compact output:** `goal | triggered cue | operator(s) applied/skipped | choice-changing question | two plausible answers | cheapest source | model decision (if any) | option/reversal | independent oracle | known unknowns`. A complex case may use the existing [inquiry card](../schema/inquiry-card-v1.schema.json); a routine edit needs no new artifact.