---
name: android-app-development
description: Plan, implement, debug, review and ship real native Android apps using Kotlin, Compose, JNI, Room, SAF, Photo Picker, WorkManager and remote protocols. Activate for architecture, unsafe file/data operations, authentication, lifecycle, accessibility, CI, signing, release and agent-development failures. Prioritize production user journeys, evidence fidelity and smallest conventional fixes over speculative rewrites.
metadata:
  version: "0.3.3"
  origin: "Source-linked Chunks, Glimpse and Expository Transfer retrospectives"
---

# Android app development: production-first, evidence-led

**Goal:** maximize safely working Android user journeys per unit of engineering, CI expense and agent attention. This is a portable agent *procedure*, never a substitute for the host project's AGENTS.md, verified product contracts or user authorization. The three source projects are examples, not requirements imposed on another app.

## New-product routing

For a substantial new application, architecture reset or inadequately specified multi-capability expansion, start with [Project Inception](../project-inception/SKILL.md). Establish product, ecosystem, reuse and design readiness before broad production implementation. Bounded experiments and settled routine fixes skip this route.

## First 90 seconds

1. Inspect current `main`, active PRs/issues, CI and `AGENTS.md`/queue. Identify other agents and shared files before changing anything. Previous summaries, docs or green tests can be stale.
2. Name the **shipping user journey**, actual production call path, source and destination identities, permissions, Android API/provider and observable result. Separate observed bug, plausible risk and speculative concern.
3. Record the **six-line execution card** in the existing issue/PR: **Outcome / Evidence / Authority and scope / Smallest intervention / Positive and negative proof / Exit and remaining gaps**. See [decision diagnostics](references/engineering-decisions.md).
4. Choose the existing platform/library primitive before a custom backend; for unfamiliar implementations use [Resource Discovery R1–R8](../engineering-resource-discovery/SKILL.md), then check current Android platform documentation and relevant narrowly selected upstream skill. Do not fetch/install unknown code merely because it is recommended.
5. **Plan validation boundaries before implementation.** When several changes share expensive build, device, CI or provider setup, partition compatible work into an implementation batch and a shared validation session. Retain cheap edit-local feedback and early focused gates for risky foundations or destructive effects; do not mandate a full project test for each change. See [Validation economics](references/validation-economics.md).
6. Implement the bounded batch as independently identifiable changes, with explicit per-item oracles. At the shared boundary [derive independent acceptance oracles](references/acceptance-oracle-construction.md) for unfamiliar or consequential claims and verify ordinary positive behavior through real UI/platform/adapter and relevant hostile, cancellation, crash, stale identity and recovery variants. Share setup rather than diluting proof; bound the matrix by actual risk.
7. Verify focused and integration checks on the **exact PR head**; classify red results by phase and count executed tests. Independent review for destructive/credential/native/schema/signing changes; update current issue/ledger only when evidence moves.
8. On integration, state precisely **merged vs merely proposed**, exercised tier and exact SHA/run, persisted postcondition, unsupported device/provider/owner gates and next runnable work. When waiting on external CI, choose an independent safe task, never duplicate speculative branches.

On consequential integration or accepted product-contract changes, check whether affected plan, spec, AGENTS/onboarding, work queue or handoff instructions have become stale. Use the bounded [Operational Documentation Integrity D1–D4 procedure](../references/operational-documentation-integrity.md) to update **only** affected current instructions and preserve material evidence. Routine code edits with no changed operational obligations require no documentation sweep; merged code and green CI do not imply owner/device acceptance.

## Load only the necessary specialist reference

| Symptom / responsibility | Load |
|---|---|
| Recognize hidden complexity or choose a level of investigation | [Engineering Judgment](../engineering-judgment/SKILL.md) |
| Deep competitor/source search, original user testimony, coverage and counterexamples | [Evidence Acquisition](../evidence-acquisition/SKILL.md) (only when external evidence could alter a consequential UX/product choice) |
| Unfamiliar libraries, APIs, datasets, other-project code, build vs adapt | [Engineering Resource Discovery](../engineering-resource-discovery/SKILL.md) |
| Interacting feature journeys, explicit product/data/adapter specification | [Product Specification Engineering](../product-specification-engineering/SKILL.md) |
| UX jobs, original-language prior art, discoverability, concise user research and expert question selection | [Mechanised Expertise: user-needs investigation](../mechanised-expertise/references/user-needs-and-prior-art.md) (only for consequential UX choices) |
| Requirements, priorities, architecture bloat, branches and gates | [Engineering decisions](references/engineering-decisions.md) |
| SAF, app-private files, Room, remote auth, identity, retry or interruption | [Operation integrity](references/operation-integrity.md) |
| Concrete Android implementation patterns for pickers, lifecycle, UI, storage and releases | [Platform playbooks](references/platform-playbooks.md) |
| Deriving actual test fixtures, positive/negative observations and evidence tiers from product requirements | [Acceptance oracle construction](references/acceptance-oracle-construction.md) |
| UI reachability, testing tiers, CI cost, signing and integration | [Verification and CI](references/verification-and-ci.md) |
| Multiple queued changes, repeated full runs, expensive device/provider setup, or human-review bottlenecks | [Validation economics](references/validation-economics.md) |
| Security/data-risk independent PR review | [Adversarial PR review](references/adversarial-review.md) |
| Product-discovery graphs, incomplete user-workflow models, or suspected feature frontiers | [Concept Model Engineering](../concept-model-engineering/SKILL.md) |
| A documented historical success, failure or reversed premise | [Historical casebook](references/historical-casebook.md) |
| Which published expert skill to use and how to trust it | [Upstream catalog](references/upstream-catalog.md) |
| Offline checks, case evaluations and limits of the historical audit | [Skill evaluation](references/skill-evaluation.md), [audit scope](references/audit-coverage.md) |

## Stop or redirect when one of these patterns appears

- **Invented universal invariant:** Does “never overwrite” forbid a user-authorized normal write, while doing nothing for a genuine unrelated-file risk? Differentiate payload reuse, user-reviewed Replace, app-owned immutable originals, and unknown provider output. [Chunks #568 vs #809](references/historical-casebook.md).
- **Success without observation:** A click, RPC acknowledgment, system-picker launch, report summary, emitted file name, build or green *skipped* check is not postcondition evidence. Require exact target, bytes/rows/status and independent readback when material.
- **Uncertain side effect:** A possibly applied remote write, file creation or wallpaper change must not automatically replay after timeout/401/cancellation/process loss. Preserve truthful `UNCERTAIN`, then read-only reconcile or ask for fresh authorization.
- **Mutable identity across suspension:** Freeze full exact account/server/grant/item/operation generation at approval, not display labels, current screen state or normalized credentials. One user intent must map to exactly one owned command.
- **Test-only cure:** Is the test harness fixed but production still missing the guard, or is the production behavior correct but UI automation cannot reach the OS picker? Trace both endpoints and last executed assertion.
- **Unbounded architectural expansion:** After two revisions without *new real positive-path proof*, recheck user intent, existing call site and standard API. Do not abandon genuine bugs or quietly lower safety.
- **False release closure:** Physical device, owner signing/design acceptance, provider interop and installed-data upgrade have separate gates. A capability-local refusal must remain visible; do not call disabled functionality shipped.
- **Parser automation hazard:** Use “Issue #123 remains open” on partial PRs; avoid GitHub issue-closing verbs adjacent to issue numbers unless closure is intended and verified.

## Completion receipt

`Outcome | source and head SHA | actual shipping entrypoint | device/API/provider and evidence tier | executed test counts and CI links | positive and recovery outcomes | independent review/merge state | remaining unverified claims | next task`.

Never claim the skill or an unrun test is evidence of an app working.
