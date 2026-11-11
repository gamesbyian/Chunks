# Candidate qualification: executable steps for build, adapt and integrate

## 1. Define the indispensable contract

Start with actual owner intent and production entrypoint. Specify user action, accepted input formats, output, target API/device and data ownership, offline/network/privacy/latency, cancellation/failure/repair, safety and user-visible acceptance. Mark each requirement REQUIRED, PREFERRED or UNKNOWN; label source and approval state. If important UNKNOWN values are owner choices, use the decision-handoff procedure, not an invented default.

**Reject** a search for "best image library" when the actual required transformation has not been identified. Split segmentation, annotation, image warping, reference-image lookup, image export and cross-app attachment handoff into separately testable primitives.

## 2. Enumerate origins for candidates

Start with existing project modules and related internal repositories, then official Android/JDK or protocol APIs, package catalogs, well-maintained open-source implementations, authoritative vendor services, standards and reproducible academic designs. Search for both task words and interface/data-format terms. Find at least one conventional baseline and one credible rival for an expensive decision; exceptions can be justified when the platform has a unique supported primitive.

**Do not equate search ranking, stars, release age or publisher claims with technical suitability.** Record all investigated sources and qualified versions. Keep unrelated proprietary licensed content out of the repository.

## 3. Candidate record

| Field | Mechanically inspect |
|---|---|
| ID / source / immutable version | Actual repository release, artifact coordinate, tag/commit, vendor API version |
| Feature contract | Which required operations and data types are demonstrably present? |
| Runtime fit | OS/API/ABI/JVM/Kotlin/Compose, process model, offline, min SDK, CPU/GPU/memory |
| Ownership and failure | Data residence, external calls, grants, retries, cancellation, recovery, concurrency |
| License and rights | Actual LICENSE at pinned revision, dependencies, required notices, assets/models and redistribution |
| Security/maintenance | Release activity, issue history, advisories, transitive deps and abandoned code |
| Integration cost | Adapter boundaries, test harness, upgrade paths, fallback/removal, required custom work |
| Evidence tier | Indexed claim / docs / inspected API-code / locally executed / installed provider |
| Disposition | REUSE, ADAPT, INTEGRATE, STUDY, BUILD, REJECT, INVESTIGATE, with reason and reversal |

A missing license or unclear redistribution permission is UNKNOWN/BLOCKED, not silently permissive. The agent is not legal counsel; seek appropriate review when rights are genuinely uncertain.

## 4. Comparison without bogus rankings

For each REQUIRED condition, record pass, fail or unverified with a source. Knock out known failures first. For remaining candidates, compare development and lifelong ownership cost, cognitive complexity, performance, portability, reproducibility and security. Hard constraints must not be traded for a weighted popularity score. When costs are not measured, provide a conditional recommendation ("reuse A if its process-death behavior survives the representative probe") and an explicit falsifier.

## 5. Discriminating integration spike

Write a hypothesis and falsifying observation *before* implementation:

1. Input fixture representative of actual data and a negative/failure fixture, with provenance and privacy treatment.
2. Smallest code path exercising the actual upstream library/API, with pinned versions.
3. One positive postcondition and one failure boundary (denied grant, cancel, corrupted format, large image, timeout or process death as relevant).
4. Exact proof tier: local unit, installed Android, external provider, device or human image fidelity.
5. Captured raw evidence (checksums, pixel comparison, elapsed time, log signatures, test counts) and limitations.
6. Retention plan: disposable experiment deleted, promoted after review, or quarantined as a fixture.
7. Stop if unrelated architecture or infrastructure must be added merely to answer the narrow question.

Never infer runtime behavior from a README or that a compiled demo proves the target user's journey. Do not assume permission to send customer-site images to third parties.

## 6. Reuse handoff to specification

Record chosen/conditional implementation, alternative rejected and reason, license and data obligations, exact adapter contract and state authority, evidence tier, dependency risks, acceptance test and trigger to revisit. Send the result to [Product Specification Engineering](../../product-specification-engineering/SKILL.md); it remains a proposal until owner authority and the product contract permit implementation.
