# Composing existing skill strengths into mechanised expertise

**Status:** editorial method under development. The [10 candidate compositions](../assets/compositions-v1.json) link to 22 pinned original skill *prescriptions* in [the existing source table](../assets/source-to-expertise-v09.csv). They have passed structural checks, not trials of agent effectiveness. A composition is not a new original upstream source.

## Retrieval and decision process

1. **Identify the current user outcome** and the actual shipping entrypoint. Distinguish owner-given product constraints, observed source behavior, hypotheses, and independent external evidence. Never ask the owner to repeat constraints that already exist.
2. **Retrieve candidate compositions by causal cue**, not by the presence of words in the prompt. Boundary crossings, conflicting user outcomes, asynchronous state transitions, identity changes, and reversible versus destructive actions are meaningful cues. A superficial mention of 'storage', 'accessibility' or 'security' is insufficient.
3. **Apply the applicability counterexample** (`skip_if`) before triggering deeper work. Exclude recipes whose negative condition matches. Keep only recipes where two plausible answers to the consequential question would lead to different implementations, UX states, hard constraints, or test oracles. If none qualify, choose the routine local path.
4. **Find answers from the cheapest trustworthy source**. Inspect code, tests, current project decisions, real platform contracts, original-language accounts, measured prior art and device observations as needed. An unanswered question is not automatically a request to the owner. Ask a human only about a genuinely unrecorded preference, product authority or permission.
5. **Use a concept model only if a missing relationship could reverse the decision**. Frame competency questions, construct relevant typed directed edges, reverse-traverse user goals and system operations, then audit acquisition, alias, schema and known-edge omissions before identifying a frontier. Use an ordinary trace for single-path tasks.
6. **Make a conditional choice**. Prefer an existing functioning and conventional mechanism where it satisfies current constraints. For consequential choices, compare a credible alternative and name a reversal condition. Treat framework-specific implementation recommendations as scoped examples, not portable API mandates.
7. **Choose an independent oracle** that proves the *claimed user outcome*, not just the existence of code, a green mock or an accepted RPC. Test the normal journey and the smallest disconfirming exception. Record unexecuted or unreachable conditions explicitly.
8. **Stop and retain a conditional lesson**. Stop research when remaining plausible answers cannot alter the decision or verification claim. Preserve the observed cue, mistaken assumption, tested exception and actual outcome; keep the source claim and editorial extension separate.

## Common operator pairs that are stronger together

| Paired operators | Combination and benefit | Avoid |
|---|---|---|
| User-job discovery + requirements inquiry | Extract user tasks, unmet information and observed friction from original accounts, then generate only still-unanswered product questions | Turning every source checklist into a long owner interview |
| Existing-code precedent + strong alternative | Reuse the project's proven ownership contracts, but compare the strongest real challenger when architecture is expensive to reverse | Either blindly preserving legacy architecture or reflexively replacing it |
| UX state modelling + accessibility observation | Distinguish loading, stale, failed, empty, disabled and recoverable content; require equivalent perception and action across input modes | Assuming visual design covers accessible meaning |
| Offline operation semantics + lifecycle ownership | Link immutable target identity, journal/outbox, cancellation, restored state and read-only reconciliation | Retrying an uncertain non-idempotent mutation merely because the app restarted |
| Concept-map reverse traversal + held-out prior art | Reveal omitted links between user goals, actions, permissions, outcomes and recovery, then challenge novelty with sources outside the initial map | Treating any disconnected node or missing edge as evidence of a new unmet need |
| Causal debugging + performance profiling | Prove the suspected code path executes, isolate one cause, and measure comparable behavior before and after | Cycling speculative fixes or optimizing an unmeasured metric |
| Test-tier selection + runtime dogfooding | Select the cheapest test that actually crosses the indispensable boundary; record which hardware/provider/OS behavior remains untested | Reporting emulator mocks as physical-device proof |
| Independent review + provenance auditing | Ask a second perspective to find missed counterexamples; verify each claim at exact source head and preserve rejected findings | Counting agent votes, citations or attractive prose as evidence |

## Example application: compact transfer list

**Cue:** dense list request changes not just padding but environment identification, immediate controls, semantic accessibility and status truth. Candidate compositions: `UX-01`, `UX-02`, `A11Y-01`, and perhaps `NET-01` if rows contain remote mutation controls.

**First inquiry:** Which user job is most frequent, and which information must be visible before an action? Answer by inspecting current product requirements, list code, real competitors and user feedback. Don't ask the owner what can already be read.

**Model if material:** `person -> sees transfer(environment, immutable ID) -> selects control -> approves scope -> receives operation result -> sees truthful feedback`. A missing edge is a research question until code or device observation establishes it is actually absent.

**Decision:** compare a compact visible-primary-action row, a minimal text-only row and larger cards. Preserve current task semantics; choose by observed steps, glanceability, accessible reach and user priorities. Reversal cues include truncated identity or unavailable controls under required text scaling.

**Proof:** installed interaction and accessibility at supported viewports, an explicit error/recovery state and correct target after a list reorder. Static previews do not prove remote mutation correctness.

## Counterexample application: typo correction

**Cue:** single static label has an unambiguous replacement and no state impact. `SMALL-01` applies and the more complex discovery/lifecycle mechanisms do not. Inspect the label, make the minimal edit, run focused checks, stop. Treat extra UX research, modelling, owner interviews and new architecture as unnecessary effort.

## Uncertainty and source lineage

This reference supplies procedures **proposed by our research**. The source table contains traceable paraphrases of third-party prescriptions; several may share ancestry. A GitHub link verifies what was published at a pinned revision, not that a procedure is safe, correct, independent, or effective. Future controlled evaluations should record negative transfer as carefully as successes. Never mechanically concatenate entire original skills into context.
