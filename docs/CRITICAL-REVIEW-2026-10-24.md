# Chunks critical design review and hardened product direction

Date: 2026-10-24 UTC. Basis: owner brief, 2026-10-23 research, main after merged #11 and #12, inspected Kotlin source/tests, rTorrent command documentation, Android platform guidance, and established practices from Glimpse, ROYGBIV, UR-Recomp and the private media organizer. This is a design-and-risk review, not an implementation or real-server acceptance claim.

## Verdict

**Proceed, but reset the dependency order and downgrade several promises to capability-qualified contracts.** The product's best differentiator is excellent organization integrated with BitTorrent, with rTorrent treated as a fully supported engine. Its most dangerous assumption is that remotely changing a path is equivalent to moving actively written torrent data. The companion is potentially useful, but managed seedboxes may prohibit its installation. A user must still get an excellent remote client without it.

No additional product feature development should be launched solely from the old queue until the blocking gates in this review have been addressed through suitable prototypes and evidence. Independent design/UX research can proceed without risking remote files.

## Severity-ranked findings

| ID | Severity | Finding | Required correction | Evidence gate |
|---|---|---|---|---|
| CR-01 | Critical | “Move without interrupting transfer” has no universal filesystem meaning. rTorrent may hold open/mmap-backed files, and cross-volume moves are copy-then-handoff. | Preserve torrent identity, peer/session state and completed bytes where supported. Disclose any brief I/O quiescence. When impossible, offer “move when safe/complete”, not a false success. | Test two filesystems, active single/multi-file torrents and restart; record observed I/O and peer behavior |
| CR-02 | Critical | rTorrent releases, builds, XML-RPC transports and hosting policies differ. Version-only feature inference is unsafe. | Method-and-behavior probing, compatibility matrix, Basic/Digest/proxy auth research, integer/i8 handling, XML/JSON encoding and per-backend limits. | Two real server families plus offline fixtures; exact server build/proxy info sanitized |
| CR-03 | Critical | Directory setter is metadata, not physical relocation; single vs multi-file path semantics differ. | A typed Place-to-engine path resolver and explicit filesystem operation proof. Read-only operations must never imply writable storage access. | Confirm actual file paths on throwaway torrents, including single/multi-file and customized root |
| CR-04 | Critical | Source deletion/cleanup could strand hashes or lose partially written data. | Server-side journal, operation lease, fence against outside writers, prepare/stage/verify/commit/cleanup, recovery before replay, no speculative delete. | Kill/restart after every transition; prove idempotence and original survival |
| CR-05 | High | Independent library copies and torrent-managed file renames were grouped into one workflow. | Distinguish *display alias*, *backing-file rename with engine acknowledgement* and *derived library copy*. Prefer safe derived copy; active incomplete files remain private until stable/verified. | Seed still hashes after rename, copy survives restart, no partially published file |
| CR-06 | High | Companion risks becoming a hidden requirement for remote use. | Direct rTorrent parity for viewing, add/start/stop/priorities/trackers as supported. Advanced remote moves offered only if a proven filesystem transport exists; companion optional. Explore separately authenticated SFTP/SSH where deploy cannot occur. | Companion-free seedbox task journey and explicit feature availability |
| CR-07 | High | The classifier conflates category confidence and destination permission. Hard-coded franchise detection can mislabel and surprise. | Independent facts/classification, collection relation, destination suggestion and execution authorization. Learning only from accepted choices; inspectable reasons, dry run, undo where physically possible. | False-positive/adversarial corpus, reversible batch, no mutation from classifier |
| CR-08 | High | Scope attempts local engine, sophisticated remote manager, companion, full protocol coverage, metadata providers and all integrations at once. | Remote-first vertical slices with local engine as a parallel later milestone; ship nothing marketed as a full local client before real lifecycle proof. | Exit criteria by slice, not document volume |
| CR-09 | High | “Bulletproof” ignores server state changing concurrently via ruTorrent/scripts/other clients. | Snapshot/lease/revalidate before commit; observed server state is authoritative; handle foreign modifications and shared file ownership. | Concurrent actor and stale snapshot tests |
| CR-10 | High | UI requirements describe mood but too few normal-person journeys and recovery states. | Add flow must foreground destination, Transfer/Place/Collection always distinguishable, named action states including unavailable and uncertain, accessibility and real device acceptance. | Uncoached add/recover/organize tasks and screen captures; no simulated-user claims |
| CR-11 | Medium | Initial XML-RPC transport is intentionally narrow and not production-ready. | Cover HTTP auth variations, malformed/huge/nested XML, partial lists, cursor/batching strategy, escaped names, server exceptions and credential lifetime; no broad generic RPC caller. | Service matrix, parser fuzz/regression and realistic list sizes |
| CR-12 | Medium | Green Gradle builds were near a misleading milestone claim. | Keep compile, JVM fixtures, emulator, real rTorrent, actual FS, actual Android device and human acceptance separate in quality ledger. | Explicit exact-SHA receipts |

## Concrete code findings from merged baseline

The read-only rTorrent form uses HTTPS (loopback HTTP allowed in JVM fixtures), transient credentials, Basic authentication, fixed network timeouts and a bounded 4 MiB XML response. It invokes the version query and main-view multicall. Current behavior has **not** been tested on a real rTorrent installation or device. No credential persistence, long-list paging, Digest/session authentication, retry/backoff, multiple servers, real SSL proxy matrix or actual JSON-RPC transport is implemented. A method listing is optional in source as of #12, not proof of a modern transport. The source test uses a synthetic loopback gateway, not native Android network behavior. Do not mistake the CI artifact for a functional production remote.

Transport risks to resolve before broadening mutations:
- 4 MiB fixed responses cannot automatically satisfy 10,000-item fleets; design bounded enumeration with explicit partial/incomplete state and server load budget.
- The chosen "main" view may be customized; do not assume it enumerates all torrents without a confirmed view contract.
- Historical XML-RPC builds can lack 64-bit i8 support; method existence and integer response encoding are not guaranteed by the rTorrent version string.
- Harden XML parsing with bounded nesting/item counts and parser-on-device testing. DTD/XXE denial tests on JVM do not establish Android provider behavior.
- User-generated server paths and torrent titles must never be copied into public diagnostics.

## Scope and ordering correction

**R0: Contract freeze for tests, not a specification of every feature.** Resolve error taxonomy, Transfer/Place/Collection identity, command state machine, capability model, display-vs-data rename semantics and first-hand UX journeys.

**R1: Real rTorrent read-only daily use.** Protected connection and authentications, trustworthy complete list, grouping/filter/sort, per-torrent details, diagnostics, resilience/reconnect. Demonstrate at least one legacy XML-RPC installation and one recent compatible installation.

**R2: Direct remote control without Companion.** Add with visible destination, start/stop, priority, queue/rates/trackers where supported. Make commands idempotent where possible and reconcile after ambiguous responses. This is the first useful remote beta.

**R3: Safe organization.** Deliver local non-mutating classifier/rules, dry-run plans, Place picker and preview. Then implement file operations on disposable test volumes in a separate narrowly privileged server-side provider (Companion if deployable). Do not ship active remote moves until faults are independently accepted.

**R4: Native local BitTorrent engine.** Complete the local torrent protocol journey, integrity, Android SAF handling and long-running service acceptance. Reuse command semantics, not false feature equivalence.

**R5: Polish, integration and release.** High-fidelity Compose flows, platform accessibility/ergonomics, privacy, signed APK, threat review, stress and recovery matrices. Device and real server acceptance before calling any release bulletproof.

R2 and R3 non-destructive UX work may overlap once command contracts are stable; no advanced remote file mutation is a prerequisite for ordinary direct remote control.

## Acceptance definition for the four signature promises

1. **Easy Place selection:** starting a transfer always visibly names the target server/device and concrete selected Place; recently-used/favorite/new-folder (if supported) is at hand, without Settings. Unsupported folder creation is explicit.
2. **Move during transfer:** first show current destination, proposed destination, expected strategy and effect on transfer, then keep data safe under crash/reconnect. The app must state when it cannot preserve continuous throughput, and must provide safe defer/fallback when allowed.
3. **Rename without breaking seeding:** clearly separate UI title alias, engine-backed rename and a renamed library copy; user can verify which one occurred. No file mutation is inferred from alias changes.
4. **Smart suggestions:** user can inspect why files were grouped, preview paths and conflicts, accept or dismiss, set narrowly scoped approved rules, and recover from mistakes. No classification score alone causes a move.

## Source checkpoints

- rTorrent command/path distinctions: https://rtorrent-docs.readthedocs.io/en/latest/cmd-ref.html
- rTorrent RPC security/setup: https://github.com/rakshasa/rtorrent-doc/blob/master/RPC-Setup-XMLRPC.md
- rTorrent scripts illustrate distinct move + directory operations, not transactions: https://github.com/rakshasa/rtorrent/wiki/Common-Tasks-in-rTorrent
- Reported compatibility anomalies: https://github.com/Novik/ruTorrent/issues/2983
- Android FGS time limits: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android special-use declaration/review: https://developer.android.com/develop/background-work/services/fgs/service-types

## Safe stopping point

Merged PRs #11 and #12 form the known passing foundation; no production-server operation has been accepted. This review is docs-only. Next permitted implementation should be the **read-only compatibility proof**, with a test plan and sanitized evidence first. A failure to access a real rTorrent instance is an explicit blocker, not an invitation to fabricate success. Decisions not supported by experiment remain Proposed/Open in DECISIONS.md.
