---
name: engineering-resource-discovery
description: Find, inspect, qualify and compare existing SDKs, libraries, APIs, models, datasets, code and infrastructure before proposing substantial custom engineering. Use for unfamiliar implementation capabilities or make/adapt/buy choices; skip established trivial platform uses.
metadata:
  version: "0.1.0"
  status: "editorial procedure; effectiveness unmeasured"
---

# Engineering Resource Discovery: prefer evidence-backed reuse, not a guessed library

**Purpose:** convert a desired capability into an inspectable candidate set, verified prerequisites, an honest build-versus-integrate decision and the smallest technical experiment capable of disproving the plan. This skill does not authorize dependency installation, license infringement, paid services, uploads or production mutations.

## Activation

Use when a new or materially changed capability has multiple plausible implementations, especially unfamiliar ecosystems, substantial custom code, paid services, pretrained models, foreign platform adapters or inter-project reuse. Skip when an existing project component already supplies the exact required behavior with current tests and no new boundary. For discovering *what products and users do*, use [Evidence Acquisition](../evidence-acquisition/SKILL.md); for choosing among known architectures under hard constraints, invoke [Engineering Judgment](../engineering-judgment/SKILL.md).

## R1–R8 resource procedure

Use [candidate qualification protocol](references/candidate-qualification.md) and [hypothetical Retrofit case](examples/retrofit-reuse-assessment.md). Work in the existing project plan/issue.

1. **R1. Break the outcome into separable capabilities.** Record user-observable operation, data transformations, input/output types, runtime/environment, performance or offline requirements, authorization and error behavior. Draw the shortest production path. Distinguish the indispensable primitive from optional convenience features.
2. **R2. Identify search surfaces and words.** Derive platform-native API names, vendor equivalents, package registries, open-source code search, established products, academic/standards references, existing sibling projects, system tools and paid/hosted offerings. Search by operation and data format, not only the app's vocabulary. Record each executed query/source/version and inaccessible classes; use [Evidence Acquisition](../evidence-acquisition/SKILL.md) if the broader ecosystem is not adequately known.
3. **R3. Build a bounded candidate inventory.** Include existing project solution, official primitive, mature external implementation, plausible composition and custom build baseline where applicable. For each record purpose, actual evidence link, repository/SDK revision, license and use conditions, supported OS/API/ABI, documented limits, dependencies, development activity, maintenance surface and integration path. Treat unsupported claims as UNKNOWN.
4. **R4. Run knock-out checks before scoring.** Exclude or mark blocked candidates that violate unavoidable authority, licensing, client-photo privacy, offline, device, version, safety, unbudgeted costs or required interface contracts. Check license text at version, transitive redistribution or model/data rights, source trust and security. Do not execute random installer scripts to determine suitability.
5. **R5. Trace real integration.** For each surviving finalist, map host call site -> adapter -> library/API contract -> state/bytes/identity owner -> side effects -> return/error -> installed user postcondition. Check cancellation, process death, revocation, asynchronous uncertainty, version updates, permissions and portability where reachable. Compare what would be adapted versus custom built, including testing and eventual removal.
6. **R6. Estimate meaningful economics.** Make/adapt/integrate comparison must include initial engineering, integration complexity, debugging, verification, maintenance, licensing, hosting, data movement and dependency risk. Use observed figures when available; otherwise label qualitative uncertainty. Never claim a precise speedup or cost saving without measurements.
7. **R7. Choose the cheapest discriminator.** If two viable paths remain and being wrong is costly, construct a disposable spike with named hypothesis, smallest representative real input, positive and hostile case, exact environment, assertion and teardown/retention rule. A mock can test interface shape but never claim cloud/image fidelity, provider behavior or real-device compatibility.
8. **R8. Record a qualified disposition.** Assign REUSE / ADAPT / INTEGRATE / STUDY / BUILD / REJECT / INVESTIGATE with basis, alternatives, reversibility and next proof. Stop when a candidate credibly satisfies the indispensable contract at the required evidence tier, plausible rivals offer no unresolved consequential advantage, and unverified claims are explicit. Do not keep searching merely to inflate an options table.

## Exceptions

- **Library found in a search listing:** only discovered, not suitable. Inspect actual API, license/version and bounded code.
- **No search results:** revisit alternate terminology, data formats, related projects and prior art before claiming custom build necessary.
- **One candidate is paid or uploads private data:** mark blocked pending owner authority and explicit privacy design; free local alternative is not automatically equivalent.
- **Dependency obsolete or incompatible:** isolate a migration risk or reject; do not force-fit architecture.
- **Mature primitive meets need:** stop. Avoid designing a custom abstraction with no demonstrated additional user outcome.
- **Both alternatives unproven:** RESEARCH/SPIKE NEXT, not implicit build.
- **Precedent with unsafe data ownership:** retain insight, do not copy its behavior.

**Independent oracle:** inspect one candidate's original API/version/license and run a representative contract or device/provider test before claiming integration is qualified. Separately review one rejected credible candidate for false exclusion.

**Completion receipt:** capability decomposition | search surfaces/executed queries | candidate/rejection matrix | license/privacy/device checks | integration traces | cost/maintenance basis | experiment findings (or NOT RUN) | recommended disposition and reversal condition.
