# Chunks test architecture

**Use this file as instructions.** It says what a test must assert, what it must not be coupled to, and how to change a test when the product deliberately changes. Run-level results belong in [QUALITY-LEDGER](QUALITY-LEDGER.md); CI selection and acceptance in [CI-POLICY](CI-POLICY.md); the audit that produced this standard and its history in [the 2026-11-11 audit](archive/TEST-ARCHITECTURE-AUDIT-2026-11-11.md).

**Principle:** a test enforces an enduring product requirement ([PRODUCT](PRODUCT.md), [UX](UX.md), [SECURITY](SECURITY.md)). It is coupled to a literal label, screen count, layout position or screenshot filename **only when that presentation detail is itself the requirement**. A redesign that keeps every requirement must not break a behavioral test; a change that drops a requirement must break something loudly.

## 1. Assertion categories

Name the category of every new assertion in its test's KDoc. One test may hold several categories; one assertion holds one.

| Category | Asserts | Must not depend on | Where it runs |
| --- | --- | --- | --- |
| **Behavioral** | A user-visible outcome and the verified application state behind it: exact source, selected environment/profile, destination, Start preference, consent, operation outcome (requested/acknowledged/verified/refused/uncertain). | Copy, hierarchy, number of screens or taps, control type. | JVM, then installed |
| **Data-integrity** | Identity and persistence: infohash/profile identity, no blind retry, no write on refusal, restore/process-death state, exact-job scope. | Anything visual. | JVM, then real disposable engines |
| **Navigation** | Reaching a capability and returning: one canonical Transfers list, footer roots, Back, deep links, restore. | Screen *names* and titles beyond the stable root ids. | JVM + installed |
| **Accessibility** | Named controls, 48dp-class targets, 200% font, IME, TalkBack semantics, non-colour status, contrast, reduced motion. | Exact wording (assert a non-empty accessible name, not its text). | JVM + installed |
| **Presentation-conformity** | Rendered screen follows owner-approved hierarchy, density, shared components, typography/colour, action grouping, state clarity and visual direction. Reviewer records which actual image and reference were inspected and any deviations. | Capturing an image alone, unapproved pixel-perfect golden, obsolete screen copy or specific coordinates. | Proportionate human/agent review of native/Robolectric captures; targeted structural checks |
| **Visual-regression** | An *approved baseline* image compared pixel-wise. **None exists today.** See section 4. | n/a | n/a until baselines are approved |
| **Evidence-inventory** | That reviewable artifacts (screenshots, receipts, JUnit counts) were produced and are classified. | Meaning of the pixels. It proves a producer exists, not that the screen is right. | Offline contracts |

Literal copy **is** the requirement for: destructive-action and consent wording, uncertain/failed-outcome explanations, privacy and third-party-lookup disclosures, and error text a user must act on. Assert those by the exact message constant or string resource, and say so in the test.

## 2. Writing assertions

1. Find controls with the stable identifiers in `ChunksSemantics` (section 3), then check **state through Compose semantics** (`assertIsEnabled`, `assertIsSelected`, state description). Do not read selected/disabled from wording.
2. Assert the **outcome and state**, not the route: after an Add, assert the verified job identity, destination, Start state and no duplicate write; do not assert that a particular confirmation screen appeared.
3. Never assert screen coordinates or counts of screens/taps. Layout checks are accessibility checks (bounds, overlap, one footer row at 320dp/200%), expressed as relationships.
4. Preserve the strong evidence for source identity, selected environment/profile, destination, Start preference, consent and actual operation outcome. Moving where the UI shows them is allowed; dropping the check is not.
5. A mock, fake or static contract is a fast iteration tool. It never substitutes for the installed or real-engine run the change requires (section 6).

## 3. Stable semantic identifiers

`app/src/main/java/io/github/gamesbyian/chunks/ChunksSemantics.kt` is the only vocabulary. An id names an **enduring capability** (footer roots, Add source, Add submit, Add Start preference, Add-to device/remote choice, Welcome heading and actions).

- Add an id only for a capability a specification requires. Never encode copy, order, environment or a screen name.
- Surfaces that offer the same capability share one id (the local device and server Add forms share `ADD_SOURCE_FIELD` and `ADD_SUBMIT`).
- Removing a capability removes its id **and** records its replacement verification (section 5) in the same change. The UI never has to keep an obsolete control for a test's sake.
- `EnduringCapabilityAccessibilityTest` proves each id is reachable, named, touch-sized and, for the Add-to choice, selected through semantics. Extend it when you add an id.

## 4. Screenshots: evidence vs approved baselines

- **Evidence collection** is automatic: JVM `captureRoboImage` (recorded into `app/build/chunks-visual`) and installed `save()/capture()` PNGs are reviewable artifacts. They are not compared against anything.
- **Approved visual-regression baselines** do not exist: no golden images are committed and Roborazzi runs in record mode. Do not describe any capture as a regression guard. Introducing baselines requires a reviewed approval step and a separate verify task; it is not implied by capturing.
- [`tools/native_atlas_manifest.json`](../tools/native_atlas_manifest.json) classifies **every** installed screenshot producer as `required` (names the capability and the requirement it evidences), `evidence_only` (kept, never gating), or `retired` (records date, reason, requirement and replacement verification). `tools/check_native_atlas_contract.py` fails on an unclassified producer, a required image with no producer, or a retired entry missing its reason or replacement.
- Retire a screen by moving its entry to `retired`; never delete the entry or its requirement. A removed screen needs no screenshot, but its requirement still needs a verifier.

### Presentation acceptance without golden images

For any UI-facing feature, follow [FEATURE-EXPERIENCE-CONTRACT](FEATURE-EXPERIENCE-CONTRACT.md). Select representative actual-render state(s), inspect them against the intended design and report findings in the PR: screenshot source and viewport, checked hierarchy/density/feedback, observed deviations, and whether physical-owner approval is still pending. Use semantic/layout relationships as automated guards where stable. An image whose producer exists is **evidence inventory**; only review of the contents provides presentation-conformity evidence. Do not fail unrelated engine-only PRs for lack of UI screenshots, and do not claim a visual-review gate runs in CI unless it actually does.

## 5. Deliberate UX change: reconcile, never bypass

When a change intentionally alters the UX and a test fails:

1. Identify the requirement the test enforced (trace it before touching the test).
2. Decide: still required, restated, or genuinely retired by a recorded product decision ([DECISIONS](DECISIONS.md), [UX](UX.md)).
3. Re-express a still-required behavior against the new UI with stable ids/semantics. Do not delete, loosen or `@Ignore` an assertion to turn CI green.
4. Record the mapping in the PR and, for a retired screen or assertion, in the manifest or the table below.

Current requirement → replacement verification (source-first Universal Add, #1643):

| Former assertion | Requirement | Replacement verification |
| --- | --- | --- |
| Separate "Review Add" then "Confirm: add then start" screens | One explicit Add action is the only submission; no second write path; no blind retry | Tests click `ADD_SUBMIT` once and assert the single ticket/outcome; real-peer journey asserts the registered job |
| Full infohash on the confirmation card | The exact source identity is visible before Add | Inline `Infohash:` and `Destination:` asserted in `VerifiedAddScreenAtlasTest` and `MainActivityRealMagnetJourney` before submit |
| "On the connected server" / "On this device" destination gateway | Add target is explicit and non-mutating | `ADD_TARGET_DEVICE` selected by default; `ADD_TARGET_REMOTE` only for the authenticated profile |
| `add-confirmation-real-compose.png` required | A capture of the Add form with source and destination | `add-source-and-destination-real-compose.png` (manifest `retired` entry) |
| `Source: Magnet link` card text | A fetched publisher `.torrent` must not remain the submitted source | **Gap:** now rests on post-Add tracker-list/BEP9 witnesses; unrun on an emulator |
| Catalog search on a fresh install (`FooterImeInstalledInstrumentedTest`) | Footer stays reachable through a real soft keyboard; a typed draft survives Activity recreation | Exercised on `ADD_SOURCE_FIELD`, reached from the Welcome by `FOOTER_ADD` (the Welcome deliberately has no search, #1512) |
| "Create torrent" heading (`MainActivitySafeRouteRestoreInstrumentedTest`) | Create opens from offline tools and is not restored after recreation | Asserted on the current heading "Make it shareable." |
| "Server settings" on Connections (`ChunksMainActivityAtlasTest`) | Connections identifies the verified server | "Connected ·" on canonical Connections |
| "All environments" chip click (`LocalAddHiddenInCombinedInstrumentedTest`) | Canonical Transfers shows every environment before the hidden-Add notice is judged | Footer `FOOTER_TRANSFERS`; the scope chip is pressed only when more than one environment exists |
| "Start another Add" only (real-peer journey) | A retained result offers one acknowledgement that begins a fresh review without resending Add/Start | The exact button label for the reported server state (verified, session confirmed, or registered with Start unverified) |
| Transfers row literal name (real-peer journey) | Transfers shows the original metadata name for the exact job | The row's accessible name `Details for <name> on <environment>`; also exposed a product defect (stale pre-metadata name), fixed by re-reading on the Transfers root |
| "Payload size and progress pending metadata" (real-peer journey, legacy queue copy) | A peerless magnet is shown as metadata-pending without invented progress; a resolved job no longer is | Canonical compact row: "Fetching metadata" present and no "100% ·"/"Sharing ·" while pending; "Fetching metadata" absent after metadata (the old negative check had become vacuous) |

## 6. Installed-device and end-to-end evidence is mandatory

Compilation, JVM/Robolectric, mocks, static contracts and screenshot inventories do **not** substitute for execution of the relevant installed journey. Shared Add, navigation, persistence, remote RPC and operation-gating changes need the installed native shards, R8, API29, real disposable two-server and, for Add/source changes, the real-peer Add lifecycle on the exact commit ([CI-POLICY](CI-POLICY.md), [REAL-TRANSFER-ACCEPTANCE](REAL-TRANSFER-ACCEPTANCE.md)). Emulator results are not physical-device, OEM, Doze or protected-host evidence.

## 7. Classifying a red or missing result

Report each result as exactly one of:

| Class | Meaning | Evidence required to claim it |
| --- | --- | --- |
| **Product failure** | The product violates a requirement. | The failing behavior reproduced and the requirement named. |
| **Test-contract drift** | The product is right; the test encodes a superseded presentation. | The recorded UX decision plus the reconciled assertion (section 5). |
| **Environment/dependency** | Setup, network, registry, emulator or runner failed before the assertion ran. | The failing setup step; say the assertion did not run. |
| **Not executed** | Skipped, cancelled, path-filtered, draft or never reported. | The check name and state. It is **not a pass**. |

`python3 tools/pr_acceptance_report.py --sha <exact commit> --profile integration` prints each required gate as passed/failed/not-executed/pending/not-reported and exits non-zero unless every gate passed on that commit. It labels a failure *environment/dependency* only from a setup-looking failing step; otherwise it says "needs diagnosis" because product failure versus drift needs a human. Gates and profiles live in [`tools/acceptance_gates.json`](../tools/acceptance_gates.json). Waivers need `--waive GATE=REASON` and are echoed.

An intermittent installed failure that also reproduces on `main` is recorded as an open defect in the ledger. A single green rerun does not close it.

## 8. Draft pull requests and exact-commit acceptance

Draft PRs deliberately skip the expensive native, R8 and installed-magnet jobs; GitHub shows them as *skipped*. A draft is therefore never integration-qualified. Before integrating:

1. Mark the PR ready so the heavy gates execute on the **current head** (they re-run on every later push).
2. Run the acceptance report on that exact SHA with the right profile (`integration`; `integration-real-peer` for Add/source/remote-Add changes).
3. Treat evidence from an older head, a parent PR, or a different mode (`source=magnet` versus `source=https`) as not covering this commit.
4. A stacked child is accepted only against its own head; its parent's green does not qualify it.

## 9. Known gaps

- Roughly 2,600 JVM and installed assertions still locate controls by literal text; only the footer, Add, Welcome and Add-target lookups use stable ids. Convert opportunistically when a test is touched for a requirement-driven change, not in a mass rewrite.
- Installed helpers (`MainActivityRealMagnetJourney.click("…")`, the atlas `native_visual_atlas.py` first-launch text lists) still use literal text; they cannot be changed or verified without an emulator run. `testTagsAsResourceId` is not enabled, so UIAutomator hierarchies cannot yet carry the stable ids.
- The first-launch atlas check asserts the *absence* of chrome by its copy; absence ids for search/batch/filter chrome do not exist.
- No approved visual-regression baselines (section 4).
- The acceptance report cannot decide product failure versus test drift automatically.
