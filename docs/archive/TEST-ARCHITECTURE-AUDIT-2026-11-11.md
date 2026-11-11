# Test architecture audit (2026-11-11 UTC)

**Evidence tier:** repository inspection plus **local** JVM/Robolectric and Python runs in a cloud sandbox. No emulator, installed-device, physical or protected-host run, and no CI result for the changes below at the time of writing. The instructional standard is [TEST-ARCHITECTURE](../TEST-ARCHITECTURE.md); this file is the historical record behind it.

## Measurements

| Surface | Count |
| --- | --- |
| JVM test files / `@Test` | 446 / 2,237 |
| Instrumented test files / `@Test` | 160 / 430 |
| Literal-text node lookups (`onNodeWithText`, `hasText`, installed `click("…")`) | about 1,300 JVM, 1,300 installed |
| `testTag(` uses in product code | 4 before; 19 after this change (15 new `ChunksSemantics` sites) |
| JVM screenshot captures (`captureRoboImage`) | 85, recorded (Roborazzi `record=true`), no committed baselines |
| Installed atlas `save()` producers | 42 (24 required, 18 evidence-only) |

## Obsolete or brittle assertions found, traced to their requirement

| Assertion (where) | Why it was brittle | Requirement it enforced | Disposition |
| --- | --- | --- | --- |
| `Or paste a magnet or torrent link` field lookups (3 JVM files) | Wording changed by source-first Add | A phone Add source field exists and accepts input | Converted to `ADD_SOURCE_FIELD` |
| `Review Add`, `Confirm: add then start`, `Confirm one server operation` (JVM + installed) | Two-step review removed by one-click Add | Exactly one explicit submission; no second write path | Converted to one `ADD_SUBMIT` click; mapping recorded in TEST-ARCHITECTURE section 5 |
| `On the connected server`, `On this device`, `Add a download to this server` | Destination gateway and server intro card removed | Add target explicit and non-mutating | Converted to `ADD_TARGET_DEVICE`/`ADD_TARGET_REMOTE` and the Add heading |
| `+ Add`, `Options`, `Transfers` by text (about 25 JVM files; 29 files in total with the rows above) | Footer controls override their spoken description per environment, so text lookups were unreliable | Persistent single-row footer roots | Converted to `FOOTER_*` ids |
| `Welcome to Chunks`, `Add a torrent` by text | Copy | First-install Welcome and its working actions | Converted to `WELCOME_*` ids |
| `add-confirmation-real-compose.png` required (`native_add_review.sh`) | Screen removed; required PNG list was a hand-edited literal tuple | A capture of the Add form | Moved to manifest `retired` with replacement `add-source-and-destination-real-compose.png`; list now manifest-driven |
| `Source: Magnet link` (installed journey) | Text no longer exists in the product | A fetched publisher `.torrent` is not retained as submitted source | **Dropped**; rests on post-Add tracker/BEP9 witnesses. Recorded as a gap |
| First-launch atlas text lists (`native_visual_atlas.py`) | Absence of chrome asserted by copy | Welcome shows no empty-list management chrome | **Not changed** (needs emulator); gap |

Not audited line by line: the remaining ~2,600 literal lookups. They were counted and sampled, not each traced.

## Converted and added

- `ChunksSemantics.kt` (12 stable ids) and tags on the footer, Add source/submit/Start preference, Add-to choices and Welcome.
- 29 existing JVM test files converted to the ids (footer, Add source/submit/target, Welcome); the previously reconciled Add/Combined/footer tests now use them.
- New `EnduringCapabilityAccessibilityTest` (2 tests): ids reachable, named, at least 48dp, one footer row at 320dp/200%, device target selected through semantics.
- `tools/native_atlas_manifest.json`, contract rewrite (unclassified producer, required-without-producer, retired-entry validation, requirement document must exist), 9 new contract tests; `verify_native_add_shards.py` and `native_add_review.sh` read the manifest; workflow path filters now include the manifest and contract.
- `tools/acceptance_gates.json`, `tools/pr_acceptance_report.py`, 9 offline tests.

## Verification actually executed

- JVM (local, Robolectric SDK 35): all 31 affected classes, 139 tests, 0 failures, 1 pre-existing skip.
- Python: `test_native_atlas_contract` (21), `test_native_add_shards` (20), `test_ci_trigger_contract` (20), `test_pr_acceptance_report` (9), all green; `check_native_atlas_contract.py` OK (24 required). A full `tests/` run has 16 errors that are all missing sandbox libraries (`fontTools`, `cv2`) in the separate font lane.
- Live use of `pr_acceptance_report.py` against [#1644](https://github.com/gamesbyian/Chunks/pull/1644) head `82524e4`: foundation `build`, `check`, API29, two-server and remote-delete **passed**; native shards, atlas, R8 and `build-once` **not executed** (draft); verdict NOT ACCEPTABLE, as intended.
- **Not executed:** any emulator/installed journey, the converted instrumented helpers (compiled only), CI for these commits, physical or protected-host runs.

## CI acceptance implications

- A draft PR is never integration-qualified; skipped is not passed. The report tool makes that checkable per exact SHA.
- Adding a screenshot producer or removing a required one now fails the offline `check` job until the manifest is updated, which forces the reconciliation the policy requires. Other open PRs that add captures will need a manifest line.
- The manifest/contract path filters mean a manifest-only change runs the gate; previously it would not.
- Rolling the new product ids into the installed journeys needs an emulator run and was deliberately left.
