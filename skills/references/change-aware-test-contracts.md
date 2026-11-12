# Change-aware test contracts: reconcile executable evidence with current product authority

**Use when:** an accepted product/architecture/UX change may invalidate tests, assertions, selectors, screenshot inventories, fixtures or CI gates; a failed test appears to enforce old behavior; or an integration review finds a green check whose evidence may no longer reach the required outcome. Run this **at the change boundary where practical**, not only after a failure.

**Do not use for:** a routine reversible edit with no changed requirement, assertion or evidence dependency; a speculative redesign with no accepted authority; or a blanket relaxation of failing checks. No test may be deleted or weakened merely because it became inconvenient.

**Authority and inputs:** current owner decisions and host-project precedence rules; versioned requirement/acceptance IDs where available; actual shipping entrypoints and current code; changed files and dependency paths; relevant tests, fixtures, screenshot/selector manifests and workflow triggers; executed evidence with source/build SHA and environment. If the owner authority or intended behavior is genuinely disputed, mark the obligation **DISPUTED** and seek a scoped decision; do not let a historic test silently decide product policy.

**Output:** a bounded, bidirectional relationship trace, explicit treatment of each affected obligation and its tests/evidence/gates, the smallest adequate checks at the appropriate runtime boundary, and a truthful receipt in the existing PR/issue/queue. A new permanent manifest or graph is optional only when repeated omissions justify its upkeep.

## Pre-execution contract gate (before setup, not just before interpreting red)

**For changes to an accepted requirement, UI journey, identity, schema or evidence format, perform a proportional contract review before spending a Gradle/device/provider run.** Write down the current obligation and classify directly affected assertions, fixtures, screenshot producers, artifact inventories, collectors, test selectors and workflow triggers as KEEP / REWRITE / REPLACE-EVIDENCE / RETIRE / BLOCK. Inspect both new and removed names. A test's previous pass, workflow presence or failure is not independent proof of its authority. Treat an unresolved authority dispute as BLOCK for claims relying on it, not permission to delete a check.

Make cheap, deterministic producer/consumer parity checks run **before** expensive setup where possible. For screenshots, prefer one authoritative source of capture identifiers, with the collector deriving or validating against it; for saved artifacts verify required files are nonempty and in the expected format. Add hostile controls for renamed, missing, duplicate and unexpected evidence. This catches harness drift; it does not certify that the user journey was exercised or that the screenshot satisfies the current design. Preserve the installed and independent oracle at the actual required boundary.

**Bound the overhead:** small edits trace a few assertions; major accepted refactors inspect their affected dependency closure once and reuse that disposition across compatible validation batches. Do not run a full suite to determine whether the suite's own contract is obsolete. Record the preflight disposition in the existing PR, not a new administrative ledger. Legitimate stable security, privacy, identity, accessibility, persistence, and destructive-action oracles must not be weakened just to avoid a red run.

## A. On an accepted change, reconcile before trusting CI

1. **Pin what changed.** Cite the accepted decision, prior and current requirement wording/status, affected actor/journey/variant, exact source revision, and exclusions. Partition obligations into `PRESERVED | MODIFIED | INTRODUCED | RETIRED | DISPUTED`. A changed screen does not imply its behavioral, accessibility, privacy or recovery obligations have disappeared. Never infer owner permission merely from a merged implementation.
2. **Trace downstream and upstream.** For each impacted outcome, follow `owner decision → requirement → user-observable outcome → independent acceptance oracle → asserting test → recorded evidence → CI trigger/gate`. Then reverse-trace tests, snapshot baselines, filename lists, test IDs, locators, mocks, fixtures and gate rules back to a current requirement. A test may cover several requirements, and several tests may cover one; preserve those many-to-many links. Search both references to the removed name and the new replacement path, including workflow path filters and scripts invoked by CI.
3. **Classify every consequential assertion**, not just the test file:
   - **Enduring behavioral or compatibility protection:** exact target/state, accessibility, durable bytes, authorized operation, required text/content or protocol contract. Retain unless governing authority actually changes; adapt its observation location without weakening its meaning.
   - **Chosen presentation or implementation constraint:** a specific screen, layout, key, schema or timing. Verify whether it is an **approved, still-current** product/compatibility requirement or only one past implementation. A screenshot match can be normative when the director explicitly requires that composition.
   - **Evidence/harness convention:** artifact filename, golden inventory, locator, fixture path, test method name or CI collection rule. Verify that it still indexes meaningful evidence. Being required by CI does not independently give it product authority.
4. **Make an explicit disposition** for each affected relation:
   - `KEEP`: current invariant and observation remain valid.
   - `REWRITE`: keep the invariant, update its interaction, locator or expected result to the approved implementation.
   - `REPLACE-EVIDENCE`: preserve the acceptance claim while changing the screenshot, runtime assertion, manifest entry, or evidence tier that establishes it.
   - `RETIRE`: only when the governing obligation or purely incidental convention is demonstrably superseded, and no surviving requirement depends solely on this test.
   - `BLOCK / ESCALATE`: missing authority, harmful loss of proof, or incompatible contracts. Do not encode an unresolved product decision as a test-edit shortcut.
   Record the before/after obligation, affected dependants, reasons and owner of each required change. A selected `RETIRE` must not erase another requirement's last witness.
5. **Design the replacement oracle before changing enforcement.** Write GIVEN initial exact identity/state, WHEN the shipping journey runs, THEN independently observe the preserved/new outcome at the furthest necessary boundary. Test the decision-changing negative and accessibility/recovery variants when reachable. Check fixtures cannot already satisfy success; an RPC acknowledgment, output filename, screenshot count or mock green may be insufficient. Use [Acceptance Oracle Construction](../android-app-development/references/acceptance-oracle-construction.md) to resolve evidence-boundary uncertainty.
6. **Coordinate edits and checkpoints.** Update the affected implementation, tests, locators, screenshot producers/consumers, CI triggers and project instructions as one coherent change where possible; coordinate shared files with active agents. Use [Operational Documentation Integrity](operational-documentation-integrity.md) for authoritative instructions. Run cheap selector/link/manifest checks at the editing boundary; share expensive installed/provider setup across compatible changes per [Validation Economics](../android-app-development/references/validation-economics.md). A docs/manifest-only change does **not** automatically justify a whole-device suite; an altered security/data boundary may require an early focused check regardless of redesign.
7. **Verify and reverse-audit.** On the exact candidate head, prove that each retained/new acceptance claim is reached by the selected test or explicitly mark it unproven. Confirm old tests/artifacts no longer enforce retired policy, while stable requirements still have meaningful evidence. Preserve pre-fix red results and distinguish `EXECUTED-PASS / EXECUTED-FAIL / NOT-REACHED / SKIPPED / ENVIRONMENT-BLOCKED`. Release gates still apply when relevant; an earlier head or a green job with zero executed checks cannot close a claim.

**Compact trace (existing PR body is usually enough):**

```text
Change/authority + before/after scope + source/head:
Requirement ID/status -> user outcome -> oracle -> test/assertion -> evidence/artifact -> gate
Assertion role: behavioral | authoritative presentation/compatibility | incidental harness
Disposition: KEEP | REWRITE | REPLACE-EVIDENCE | RETIRE | BLOCK
Why/current authority; dependent obligations retained; old/new evidence boundary
Executed: check, exact head/environment, last reached assertion, status, residual proof gap
```

For simple changes, one or two traced lines are sufficient. When many tests/gates repeatedly drift, a small machine-readable `requirement_id / assertion_id / evidence_id / gate_id / authority_revision` manifest may pay for itself, but its links must be independently sampled and challenged. Do not build a graph database or require universal IDs as a prerequisite.

## B. On a red or suspicious green, diagnose *before* editing the assertion

1. Capture the first meaningful error, last executed assertion, exact revision, invoked workflow, setup/fixture, executed/skipped counts and actual observable product state. Separate primary failure from teardown.
2. Trace the failing assertion backward to current authority, then the requirement forward to shipping behavior. Consider competing explanations rather than the easiest way to green:
   - **PRODUCT REGRESSION:** a current requirement is violated. Fix implementation; preserve or strengthen its oracle.
   - **TEST-CONTRACT DRIFT:** a test/screenshot/selector enforces a retired requirement or incidental old design. Reconcile it while retaining independent proof for surviving claims.
   - **HARNESS DEFECT:** valid product behavior cannot be observed because of a broken locator, teardown, capture script, unstable timing or wrong fixture. Repair the observer and retain the behavioral protection.
   - **ENVIRONMENT FAILURE:** missing libraries, credentials, devices or provider prevents required execution. Repair or provision the environment, or explicitly report the missing evidence; do not mark acceptance passed.
   - **UNRESOLVED:** evidence cannot yet discriminate among these. Perform the smallest experiment that can; don't label an unknown red as obsolete.
   Several classes can coexist in one campaign. `NOT-REACHED` and `SKIPPED` describe **evidence status**, not a diagnosis or a pass.
3. Pick a discriminator that changes the remedy: compare the approved requirement with the assertion; inspect the current UI/screen reader; read authoritative state outside the command; isolate failing setup from reachable code; inspect workflow dispatch and artifact production. Once classified, rerun the smallest affected check plus its invalidated dependency closure. Preserve required final branch/release gates and independent review.

## C. Select enforcement by claim stability, not one project-wide phase

| Current contract | Prefer at this boundary | Guard against |
|---|---|---|
| Deliberately changing UX composition | Semantics, state/source integrity, control reachability, accessibility and focused current screenshots | Treating every historical image/text/name as normative, or silently dropping an approved fixed visual design |
| Stabilizing integrated journeys | Real transitions among screens, persistence, cross-adapter identity, error and recovery, targeted interaction coverage | Passing individual mocks while the assembled shipping path remains unexecuted |
| Release candidate | Approved appearance, accessibility, installation/upgrade, compatibility and end-to-end evidence on exact artifact and supported environments | Treating prior-head, simulated or skipped checks as release acceptance |
| High-risk destructive/auth/credential/schema boundary **at any stage** | Early focused positive and negative proof at the real boundary | Relaxing a serious guard to save device/CI time |

Stability is a property of each obligation: mandatory wording and approved pixels may be stable during a redesign; exploratory layout composition may remain fluid near integration. Preserve critical accessibility and data protections throughout.

## Teaching case: source-first Add after an accepted UX change

**Reported retrospective, not independently reverified here:** an accepted redesign removed the compulsory Add confirmation screen. A screenshot contract still required `add-confirmation-real-compose.png`, while the new source/destination screen had a replacement capture. Separately, an agent worried the Infohash was no longer exposed, then found an inline display; missing-library errors blocked other checks and some installed tests were skipped.

Correct separation:
- **Infohash visible before submission:** enduring behavioral requirement; verify it in the current source-first Add journey. If the inline control hides it, **fix product**, not the assertion.
- **Separate confirmation screen:** intentionally retired presentation choice; don't restore it merely to pass a historical test.
- **Screenshot filename and manifest rule:** incidental evidence locator; replace the stale filename with the current capture *after checking that the image and its generating journey meaningfully witness the retained requirement*. Presence alone does not validate the pixels.
- **Missing libraries:** environment failure; repair setup or report as unexecuted. **Skipped installed tests:** no installed acceptance evidence, whatever the status of the manifest check.

This case is a teaching illustration from a user-provided account. Confirm exact PR heads/logs before using it as a historical ground-truth evaluation key. Separate practice examples from truly unseen, temporally sealed evaluation cases.

## Verified case: Chunks 200% Compose capture mismatch (2026-11-12)

At [Chunks PR #1668](https://github.com/gamesbyian/Chunks/pull/1668), the installed system-font-scale Compose test completed successfully and published eight PNG captures; the downstream shell script failed because it independently demanded seven images with legacy screen names. [The exact-head job](https://github.com/gamesbyian/Chunks/actions/runs/43312421401) reached the device test and then failed at artifact validation. The resulting evidence proves the named navigation test ran, but not that the stale capture list was an authoritative product requirement.

Correct prevention: inspect both the new screenshot producer and its collector before the Android run; eliminate the duplicated list or qualify source-derived filenames with a cheap offline preflight. Verify actual PNGs after the installed run. Keep accessibility and navigation assertions intact. Do not relabel a failing artifact check as an app regression or a passing device test as pixel-design approval. See [Chunks PR #1670](https://github.com/gamesbyian/Chunks/pull/1670) for the concrete source-derived collector/preflight.

## Adversarial controls and stop condition

- **Old screenshot obsolete, current invariant intact:** change manifest/capture, retain semantic requirement and verify replacement.
- **Red accessibility test after redesigned navigation:** preserve the requirement and repair the unreachable button, even if screenshots look attractive.
- **Exact regulatory copy or explicitly approved composition changed:** preserve the supposedly brittle literal/snapshot assertion until authority changes.
- **Mock green without an installed/current journey:** demand real execution if that is the acceptance boundary.
- **Docs-only selector repair with no code/runtime change:** do focused selector proof, not an unrelated full app matrix.
- **Uncertain schema or API compatibility:** inspect external consumers and persisted versions before retiring tests of internal names/fields.
- **Ambiguous failure with no authoritative decision:** keep status unresolved and seek discriminating evidence rather than assuming either a regression or obsolete test.

**Stop when:** each actually affected current obligation has an explicit keep/change/retire disposition and correctly scoped executed proof or a named gap; downstream gate/manifest consumers no longer contradict the accepted contract; further checks cannot change the decision without new authority/evidence. Do not certify real productivity improvement from passing these cases alone; evaluate on held-out work and cost/escape outcomes.
