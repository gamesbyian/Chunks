# Android release-candidate acceptance checklist

**Forensic release gates (pending):** Resolve and qualify applicable #2025/#2026/#2027 identity, uncertainty/deletion and source-peer safety before release. Inspect actual signed/minified candidate and packaged native ABIs, compare measured size/runtime and retain device/real-client proof; QA minified smoke alone does not qualify release configuration. FE-17 is investigation, not proof of removable dependencies/ABIs. Preserve existing physical/privacy gates; atlas deferred.

**Purpose:** one stable **go/no-go gate**, not an alternative feature backlog. Use [WORK-QUEUE](WORK-QUEUE.md) to dispatch remaining work, [TECHNICAL-COMPLETENESS-LEDGER](TECHNICAL-COMPLETENESS-LEDGER.md) for implemented-versus-missing status, [SPEC-COVERAGE](SPEC-COVERAGE.md) for requirement-to-test obligations and [QUALITY-LEDGER](QUALITY-LEDGER.md) for immutable executed receipts. This checklist does not itself declare any test passed.

**Repository governance verification:** On the 2026-11-21 read, the public branch endpoint reported `main.protected=false` and an active ruleset exposed deletion/non-fast-forward restrictions, but no visible required-status rule. Classic branch-protection inspection was permission-denied. This is an **unverified release governance gate**, tracked in [#10](https://github.com/gamesbyian/Chunks/issues/10), not permission to presume no other rules exist or to silently waive source-exact checks. An authorized administrator must verify configured effective rules, PR-only merges and required status contexts. Required checks must be always-emitted or otherwise explicitly resolved for docs-only/path-filtered PRs; do not require absent optional jobs.

## Release identity

Capture in the final release receipt, before publishing:

- [ ] Exact frozen `main` commit SHA, Git tree SHA, release version/application ID, Gradle/Android plugin/JDK, native engine/libtorrent version, signed signing-certificate fingerprint and intended compatible Android API range.
- [ ] Build-produced APK bytes, SHA-256 and install-provenance; independently pull and hash the **actual installed** APK from the same exact candidate (or record a physical/device-specific gap). Never substitute a test APK, artifact ZIP or historical SHA.
- [ ] Main branch review/protection rules and applicable exact-source required checks are verified with authorized administrative access. No release based only on a PR head's green suite or on branch `protected` boolean.
- [ ] Current open PRs are integrated or explicitly excluded without unmerged release dependencies. Re-run applicable checks invalidated by integration. Freeze new features for the release candidate.

## Stop-ship correctness and safety

All apply regardless of cosmetic readiness:

- [ ] Add uses one reviewed source, selected Local or exact remote profile, destination, Start choice and one write. Duplicate/timeout/401/hidden/stale/out-of-order reads cannot cause blind retry, cross-client Add or invented success. Both stopped and started requests observed on real peers.
- [ ] Local engine passes stopped-magnet/payload verification, partial-file/foreign-content collision guards, interrupted process/resume, actual byte/piece rehash, and completed/download transitions without unsafe resume or indefinite check.
- [ ] Four remote engines (rTorrent, Transmission, qBittorrent, Deluge) pass supported read/write and exact-profile separation across authentications/daemon variants. Unsupported operations fail closed with visible reasons, not approximate emulation.
- [ ] Move/Copy transfers **jobs only**; data deletion and source retirement require separate explicit permission and independent exact-file coverage. Wrong-target, shared-file, denied-account, denied-path and lost-ACK controls assert **no destructive write**.
- [ ] Network/privacy/credential invariants: private trackers/magnet URLs and authenticators absent from CI/logs, default-online artwork behavior gated by user consent and persisted OFF, encrypted saved credentials, no background provider calls without authorization.
- [ ] Source-file Keep/Archive/Delete, remote folder browsing, default folder and verified-typed creation tested with grants, malformed and restricted providers; disabled unsafe ZIP/library writers remain disabled unless independently proven safe.

## Required functional integration

- [ ] Local and each remote client: Connect where applicable → Transfers → source-first Add file/magnet/HTTPS/Share/Open With → Place/Start choice → observed transfer → Details → file-priority/Finish in Order where supported → Stop/Start → session-only Remove and separate opt-in data removal.
- [ ] Exact-client scoped and All Transfers share row/details/colour/name semantics and preserve search, list position, navigation and saved sessions; offline/locked accounts never mutate silently.
- [ ] Smart Seeding priority, ratio, upload and tracker rules have tested effective outcomes, supported engine-specific actions and authoritative handling of disconnected/unknown/uncertain states.
- [ ] Smart Routing ordered rules and opt-in behavior are verified on different client types, including later metadata, disabled rules, unavailable targets and job-only Move scope; unsupported automation is explicitly limited, not silently claimed.
- [ ] Portable snapshots export/restore only known safe definitions; interrupted journal recovery and provider errors do not replay a possibly accepted Add. Mark remote/physical provider limits explicitly.
- [ ] Settings, artwork identity/manual overrides and cache maintain their correct global/per-client/per-job ownership, persistence and privacy boundaries after process death and source replacement.

## Testing and experience tiers

- [ ] Affected JVM/Gradle checks, Android foundation, R8 minified smoke and native Add API29/API35 shards/verifier pass on the release source. Keep original red/flaky receipts and exact test counts; no empty or skipped acceptance.
- [ ] Real disposable peers/demons verify bytes and piece hashes, not only RPC ACK or a row flag. Protected Seedhost tests use approved scoped credentials and safe redacted receipts.
- [ ] Owner's actual Pixel tests external .torrent via Files/Downloads/Drive, cold/warm/rotation/process death, navigation, unreadable/offline states, 320/412dp or representative screen widths, 200% font and TalkBack. Emulator != physical acceptance.
- [ ] FGS/Doze/VPN/wifi-to-cellular and API29/35 restore/storage permissions checked where relevant; distinguish unavailable OEM endurance observations as explicit release holds.
- [ ] Coordinated final approved Transfers/Add/Connections/Details/Options UI inspected from **actual installed captures** for correct hierarchy, artwork aspect ratio, 48dp-class targets, focus/IME/footer; a screenshot artifact alone is not visual approval.

## Distribution and exit decision

- [ ] Manual release-signing workflow succeeds with a persistent authorized key and pinned certificate; signature, package/version, artifact hash, repeatable provenance, changelog and immutable release assets verified.
- [ ] Critical failed/missing gates: **HOLD**, assign a concrete repro and source-exact owner. A capability-limited operation may be explicitly scoped out only if product contract allows a safe visible refusal, and owner accepts the release boundary.
- [ ] Publish one final [quality ledger](QUALITY-LEDGER.md) receipt recording accepted, pending, blocked, deferred and physical-owner-acceptance items separately; do not replace evidence with this checklist's unchecked boxes.
