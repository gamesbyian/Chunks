# Agent 5: parallel Universal Add integration contract (2026-11-06)

> **REACTIVATED BY LATEST OWNER INSTRUCTION · 2026-11-07 UTC:** Four development lanes and one integration lead are currently authorized, superseding the brief sole-integrator scheduling override merged in #1385. This document resumes as the *file lease/interface handshake* contract, subordinate to the single [WORK-QUEUE](WORK-QUEUE.md). Starting observed main `22cf5663358ddab8d9b35d116b52b2e61b638ea2`: Agent 1 shared coordinator already merged; Agent 3 authenticated rTorrent registered-job/Place + deferred-Start result already merged via #1372 (10/10 PR-head checks); Agent 2 #1363 remains unmerged and installed-red for actual phone HTTPS piece-0 bytes; Agent 5 #1370 is stacked and must follow #1363 with new integrated evidence. Agent 4 should focus on real installed first-piece and exact-head/byte identity qualification; no blanket test waivers. Keep each lane within existing agreed contract before expanding. Historical baseline and issue map below remain valid as dated evidence, not current SHA. 

**Authority:** This is a coordination handoff under [WORK-QUEUE](WORK-QUEUE.md), not a second executable queue or a new normative product specification. Live starting main: `7a1ee8a62fc3bd39983b7ec5434768bd52182110` (2026-11-06 21:49 UTC). At inspection: zero open PRs; #1349, #1350 and #1351 merged. Prior PR-head `1a8a940988c5f0435994bfe64997e3df9c76ea7a` completed all nine workflows; no installed merged-main APK or physical-device acceptance follows from that. Always refresh GitHub immediately before working or merging.

## Parallel leases and interface handshake

| Lane | Owns | Does not independently edit |
| --- | --- | --- |
| Agent 1 | Shared Universal Add draft, capability contract, authorization, attempt lifecycle, observation/reconciliation; focused pure/shared tests | Phone Activity/intent handlers, rTorrent network/RPC implementation, central docs |
| Agent 2 | Android Share/Open With, clipboard, magnet, local URI and HTTPS .torrent ingestion, cancellable source replacement, phone adapter and phone installed tests | Shared coordinator/model lifecycle without Agent 1 interface agreement; rTorrent RPC; central docs |
| Agent 3 | rTorrent Add adapter/review/session identity, transport and read-only reconciliation, error/auth handling, remote tests | Shared coordinator/model lifecycle without Agent 1 interface agreement; phone Activity source intake; central docs |
| Agent 4 | Exact-head installed qualification, scoped test fixtures/workflows and current-source APK provenance, run artifacts and precise failure diagnosis | Production lifecycle implementation, protected write dispatch without authorization, central docs |
| Agent 5 | Integration decisions, PR reviews, merge order, conflict resolution, cross-lane seams, central WORK-QUEUE, QUALITY-LEDGER, SPEC-COVERAGE, AGENT-LANES, AGENTS.md, this handoff and issue reconciliation | Independent duplicate Add implementation or unsupported acceptance claims |

**Interfaces to settle before cross-lane changes:** Agent 1 proposes a small source/draft/attempt contract in its PR description or a comment on issue #8, naming types, fields, call sites and commit SHA. Agents 2 and 3 acknowledge or propose specific adjustments on the same GitHub thread before wiring their adapters; Agent 5 arbitrates divergent fields. No agent depends on another's live session or an unmerged path as if it were on main. Shared file overlap requires explicit handoff and single writer. Agent 4 qualifies immutable PR heads and the eventual merged main, not whichever branch happens to be checked out later.

## One lifecycle and minimum data contract

1. **Review-only stage:** retain exact source type/provenance (magnet, token-bearing HTTPS, content URI or validated metainfo; do not log or persist secrets), source generation, verified hash if known, user-selected *exact* environment/profile/account, capabilities, verified Place/root/permissions, and explicit Start intent. Rejected replacement must preserve the previous valid review. Obsolete async completions cannot replace current review.
2. **Authorization:** one explicit current user approval bound to the reviewed source and exact target+Place+Start; any change invalidating its scope requires re-review. Display account and storage scope rather than guessing a default or falling back from remote auth loss to phone.
3. **Attempt:** one coordinator owns submitted/acknowledged/refused/uncertain and attempted-write latches. Existing phone and rTorrent adapters remain the only mutation executors. A transport timeout, 401, interrupted process or missing response can leave a write uncertain: **no blind re-submit**. Read-only exact-profile+verified-infohash inventory reconciliation precedes any new user-authorized attempt.
4. **Observation:** distinguish receipt/ack from verified transfer registration, metadata, actual peer/piece progression and success. A job exists only when exact observed identity matches. Never equate Add success with downloaded bytes.
5. **Restoration:** no private-source leak into persisted state, logs, Intent replay or backups. Restore only nonsecret, safely valid state unless source consent and permission permit otherwise. Pending/uncertain mutation cannot silently reset on Activity/process recreation.

No new database, another Add executor, duplicate session catalog or automatic mutation. Keep source .torrent Keep/Archive/Delete and artwork exclusion as later reviewable choices, with precise source-document ownership/permissions. Disabled ZIP/library paths under #809 remain disabled.

## Shared integration tests and gating

- Source replacement: refused/cancelled/stale picker/share/HTTPS/snapshot, navigation and Activity restore, precise one-time URI consumption and unaffected earlier valid draft.
- Targets: phone, remote account A versus B, numbered instance and combined browsing, stale/missing profile, auth failure, unavailable Place, revoked URI grant, explicit Start off/on, and exact hash collision across environments.
- Attempt fences: stop or timeout after send, 401, acknowledged-but-not-observed, observed-after-timeout, retry only with renewed user authorization and read-only reconciliation. No unapproved second request.
- Installed acceptance: both phone and real disposable rTorrent positive Add→observed job→Start/Stop where applicable; real byte/piece/peer proofs for downloading claims; one immutable source-head, run IDs, APK SHA-256 and source SHA. API29/API35, R8/Compose, navigation restoration, combined instances where impacted.
- Publish current-main APK only after matching exact-source evidence and checksum; a PR-head APK is labeled PR-head. Manual provider and Pixel/TalkBack/OEM acceptance remain **unverified** unless actually performed.
- Red runs remain recorded: historical TrackerController crash, instrumentation lifecycle/ANR, source HTTPS timeout and canceled post-shutdown run; never relabel cancellation as pass.

## Merge and reporting protocol

1. Re-fetch live main, all open PR heads/diffs, overlapping paths, checks and relevant issue comments. Prefer narrow independent adapter PRs and shared contract first, or integrate atomically if an API cannot be safely staged.
2. Check head SHA against run head, test gates and review evidence; reject outdated, insufficiently qualified or incompatible branches. Merge using expected-head SHA and re-fetch main immediately. Test the integrated result separately.
3. Agent 5 alone reconciles core queue, quality receipts, coverage and issue statuses, preserving historical red outcomes. Agents include proposed ledger/queue changes in PR description, **not direct edits** to Agent 5 files.
4. Once tests prove the real cross-lane lifecycle, integrate in this order: coordinator contract, phone and rTorrent adapters, assembled negative/positive tests, source-pinned debug APK. Avoid premature routing or payload Move; Move is **job-only**, leaving bytes in place with separate consent for source job retirement and optional speed/ratio/seeding policy carryover.
5. Record merged PRs, actual integrated main SHA, checks completed/failed/skipped, receipts, exact APK identity, physical/provider limitations and next uncovered journey. An empty PR list means no outstanding integration at that instant, not proof the campaign is complete.

**Issue map:** #8 shared UX/Add; #1 rTorrent; #5 native phone; #567 shared identity/observation; #9 testing; #10 APK/release. #6 routing follows shared Add; #2 physical relocation is deferred and must not be used to justify payload-moving Move. #809 remains a capability-scoped disabled-write risk.
