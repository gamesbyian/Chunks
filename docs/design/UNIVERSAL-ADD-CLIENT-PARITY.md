# Universal Add: interchangeable clients, one user experience

**Owner decision:** 2026-11-16, binding. **Status:** implementation in progress. This is the canonical cross-client Add behavior contract and supersedes any implication in older per-engine notes that local Add and remote Add may use different user journeys. Refer to [Add destination and outcome](ADD-DESTINATION-AND-OUTCOME.md) for the compact layout, [source intake](../ADD-SOURCE-INTAKE.md) for Android entry points, [architecture](../ARCHITECTURE.md) for engine adapters, and [real-transfer acceptance](../REAL-TRANSFER-ACCEPTANCE.md) for evidence.

## Principle and boundaries

A **client is a destination with capabilities**, not a separate Add UX. A person must not need to learn different steps, form arrangements, completion requirements, or navigation paths for Local and rTorrent. The same rule will cover future qBittorrent/Transmission/etc. adapters. Engine-specific differences are permitted **beneath** the common contract for storage permissions, XML-RPC transport, metadata availability, exact hash identity, file-priority support, true Start semantics and verification cost. Never simulate a remote success because local can return faster.

The **single Add surface** owns source intake, client selection, destination, optional file selections, Start when added, media enrichment preference, one Add submit button and one stable-position outcome region. The same exact client identifier and expected torrent hash accompany the entire attempt. Source/Place/Start changes after review invalidate prior authorization. An Add action happens only from the explicit person's tap and is never retried automatically after the write boundary.

Client labels are **Local** and saved connection names (Wood, etc). Avoid protocol and server endpoint strings in ordinary UI. A source can come from a magnet, HTTPS link, document picker, Android Share/Open With or clipboard. A client-specific unavailable capability should be explained in place rather than rerouting to a different form.

## Canonical visual sequence

1. **Review**: source (or a validated .torrent), destination client, exact Save to location, Start, files to download and shared optional controls. Changing the client changes available destination choices, not the screen or submission semantics.
2. **Submit**: tap **Add transfer** once. Preserve exact client and hash, issue one authorized native/RPC mutation, and render the same **Adding to [client]** status. Disable duplicate submission for that ticket.
3. **Observation**: render an inline **Checking [client]** or **Added to [client]** receipt according to the common evidence states. Registration, reviewed Place, requested Start, payload progress and media identification are separate facts. A native positive registration receipt may be surfaced immediately; RPC acceptance alone is not authoritative success.
4. **Continuation**: the user can reach the canonical Transfers page without an extra remote-only confirmation ceremony. Safe read-only verification may continue beyond the Add screen using an app-owned, process-continuous attempt coordinator. Never cancel a request in flight just to improve navigation; never background-retry a write. Keep an unresolved attempt visible and recoverable in the correct client's transfer context.
5. **Completion**: if exact hash/Place/requested Start is verified, show **Added to [client]** and **View transfer** bound to that exact client+hash. In any ambiguous result, show a truthful receipt and **Check status** read-only action. Proven no-write refusals allow deliberate re-review; uncertain writes never enable a second submission until the existing ticket is explicitly resolved.

### Shared receipt states

| Stage | Heading | Truthful meaning | Available user action |
|---|---|---|---|
| Reviewed | Add transfer | No mutation has occurred | Edit and submit once |
| Submitting | Adding to [client] | This ticket may be entering the write boundary | Navigate safely where supported; no duplicate Add |
| Registered, final checks pending | Added to [client] | Only this submission's new registration has reliable positive provenance | View exact job when independently locatable; await/check Place and Start |
| Verified | Added to [client] | This Add's exact hash, reviewed Place and requested Start state were independently verified | View transfer; start another reviewed Add |
| Session confirmed, origin unknown | Confirmed on [client] | Exact job/Place/Start exists, but cannot attribute original Add after lost ACK | Explicitly acknowledge then start another Add |
| Registered but Start unverified | Transfer registered on [client] | Exact hash and Place exist; requested Start remains unknown | Explicitly acknowledge; inspect before independently starting |
| Unconfirmed/ambiguous | Couldn't confirm Add on [client] | Write may have applied and the result cannot safely be inferred | Read-only status check, no automatic resubmission |
| Known no-write | No new Add on [client] | Refused before the write boundary or already present | Explain reason, correct and deliberately review again |

**Do not conflate:** remote RPC acknowledgement with verified admission; local native token with a fully verified Start; registered magnet placeholder with metadata/payload availability; exact job existence with attribution to a specific submitted command; no entry in rTorrent's main view with absence from its session; completed hash checks with positive downloaded peer bytes. Read-only getters must use the original principal, numbered route, environment and expected hash. Stale readback must never override newer evidence.

### Surface requirements

- **One status location**, no duplicated remote "Adding" and "operation outcome" cards. Headline and supporting text should be supplied by shared, engine-neutral status projection. Engine-specific diagnostics are subordinate expandable details.
- **No blocking success ceremony**: a routine remote Add shouldn't force the user to wait on the Add screen for repeated RPC polls while Local immediately navigates. Observations can continue in a safe lifecycle owner. An incomplete attempt never masquerades as a finished transfer.
- The current stable footer remains **Transfers | Add | Connections | Options**. Client selection must not modify the Transfers visibility filter. The Add screen should not remain selected after returning to Transfers.
- 48dp-minimum primary touch targets, TalkBack live-announcement on state changes, accessible exact target names, and visible actions on a 412dp-wide handset. Respect reduced motion.
- A failed/unavailable location, authentication or client capability should be explained in the shared form. A verified destination remains independently scoped and must never be guessed from another client's last-used Place.
- Source-file Keep/Archive/Delete follows the global policy and is applied only after verified *new* registration; do not silently delete after an ambiguous result.
- Keep all private source bytes, magnet tracker tokens, endpoint secrets and credentials out of logs, saved state, status text and documents.

## Implementation ownership: converge incrementally

**Current baseline at adoption:** `UniversalAddCoordinator` in `model/UniversalAddCoordinator.kt` already owns source/target/Place intent, review, one-shot submission ticket, readback fence, exact-session reconciliation and acknowledgment states. `LocalUniversalAddAdapter` and `RemoteUniversalAddAdapter` bridge real different engines correctly. However, `LocalEngineScreen` and `VerifiedAddScreen` still independently own large Add orchestration, outcome presentation and navigation paths. This separation is the owner-observed UX discrepancy.

1. **Shared presentation and regression model (first slice).** Introduce a pure mapping of existing coordinator phases plus *positive* registration evidence to canonical receipt stages, headings and explanatory text. Use it from both Local and remote screens. Unit-test Local/rTorrent equivalence and lost ACK vs positive provenance. Keep existing engine execution and retry fences untouched.
2. **Shared Add journey state owner (next slice).** Move the Add operation/lifecycle out of individual Compose screen coroutine scopes into one app-owned `UniversalAddAttemptController` or equivalent. It owns a stable client+hash ticket, attempt events, cancellable read-only observations, bounded scheduling and the shared outcome stream. Local and remote adapters implement the same submission/result interface; no new engine writer. Preserve process-death uncertainty and never serialize private metainfo.
3. **Shared composable and navigation (after controller).** Replace independent source/form/status implementations with one Universal Add route and scoped client destination subcomponents. Submit once, navigate uniformly, and keep readback running where safe. Respect per-client capabilities without separate client-specific user journeys. Remove obsolete standalone local/remote Add screens only after their real input/edge behavior is proven ported.
4. **Contract and evidence consolidation.** Replace duplicate local/remote screen assertions and stale screenshots *before* running the final batch. Exercise the same test matrix against local libtorrent, a real authenticated rTorrent profile and at least one additional profile identity. Prove positive paths and negative/timeout/no-blind-retry handling on PR-head and installed APK; report physical device acceptance separately.

### Required equivalence/acceptance matrix

- Magnet and `.torrent` input, including Android Open With, Share and clipboard, with exact client and original-source continuity across navigation and Activity recreation.
- Local and remote default/custom Places, remote browse/new folder when supported, safe local folder checks, and correct global original-file policy.
- Start selected and unselected, metadata-pending magnets, a multi-file torrent with skipped files, and optional Finish in Order.
- Immediate positive native registration, ordinary rTorrent registration after ACK, delayed server appearance, and server hidden-view job.
- Duplicate existing hash, stale inventory, changed target/Place, expired login, no-write refusal, lost ACK, timeout during mutation, out-of-order readbacks, and process death.
- Consistent UI receipt stages and navigation for both engines, including TalkBack and smaller handsets, without creating unauthorized duplicate Adds.
- No false positive downloads/peers, no attribution of an existing job after a lost ACK, no automatic Start to repair an uncertain state, no leak of credentials/magnet private tokens, and no clearing an unresolved ticket merely because the user leaves Add.

**Acceptance rule:** pure/unit/Compose mock tests can qualify status wiring but do **not** establish end-to-end parity. Complete only after installed local and actual protected/disposable rTorrent user journeys prove the same experience. Record partial implementation honestly in [quality ledger](../QUALITY-LEDGER.md) and [spec coverage](../SPEC-COVERAGE.md); do not report the overall refactor as shipped merely because projection tests pass.
