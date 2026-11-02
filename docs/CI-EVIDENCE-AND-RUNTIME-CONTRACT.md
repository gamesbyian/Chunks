# CI evidence and runtime contract

## Required acceptance tiers

Green unit tests never prove that an installed Chunks APK transfers live data. Preserve separate evidence for offline unit tests, real disposable rTorrent and Android emulator tests, protected live Seedhost exact-infohash tests, and owner-installed physical device/OEM tests.

Every completed test run must produce exact source SHA and run ID, acceptance tier, expected and actual test count, per-test outcomes and timings, last safe checkpoint, bounded failure class, per-transfer received/verified-byte proof and exact cleanup status. Missing reports, cancelled tests, partial JUnit data and all-skipped results must fail closed. A partially positive test in an overall-red run remains valid only at the narrower proven tier.

Do not expose credentials, authentication headers, cookie tokens, tracker passkeys, user paths or unfiltered stack traces. Use hashed test-case identities, allowlisted state labels, and public/synthetic infohashes only. Publish JSON receipts for red and green runs whenever the runner remains operational.

## Android native Add timing and full coverage

Last accepted unsharded [run 41367486529](https://github.com/gamesbyian/Chunks/actions/runs/41367486529) took 29.1 minutes, with a 3.7-minute build and 22.6-minute connected test. Its baseline was 362 executed cases.

The native suite may use parallel AndroidJUnitRunner numShards and shardIndex across two independent API35 emulators, but it must keep the whole suite, not select convenient classes. Each shard uploads every case outcome and count plus real PNG captures. An always-run coordinator must reject wrong tested SHA, missing or overlapping test identities, fewer than 362 executed tests (until independently revised), failed/all-skipped tests, any missing required real Compose capture, and absent or malformed receipts. Preserve the separate 200%-font acceptance tier. The performance goal is **less than 25 minutes end-to-end** including setup and aggregation. Job timeouts are not proof of achievement; measure actual completed wall time.

## Real magnet metadata and peer evidence

The protected rTorrent path must separately verify approved host/profile, exact hash registration, original metainfo name and size, started and active flags, connected peers, real per-hash received bytes and independently verified pieces, Stop/Start, session-only Remove and unchanged unrelated inventory. A successful RPC ACK, positive placeholder size or started flag is insufficient.

For synthetic real-peer BEP9 controls, the runner sends only a magnet in the rTorrent RPC; a real libtorrent seeder must supply metadata and verified payload pieces over peer transport. Capture independent runner-ready, preflight, seed-ready, Add, metadata, bytes, cleanup and inventory-restored/uncertain stages, surviving failed JVM reporting when possible. If Add may have applied after an ambiguous ACK, preserve the synthetic hash and perform read-only reconciliation, never replay the mutation blindly.

The already accepted installed .torrent transfer result from [41363266446](https://github.com/gamesbyian/Chunks/actions/runs/41363266446) should not be needlessly repeated. The magnet APK acceptance remains blocked. Consult [REAL-TRANSFER-ACCEPTANCE.md](REAL-TRANSFER-ACCEPTANCE.md) and [issue #4](https://github.com/gamesbyian/Chunks/issues/4).

## Practical speed rules

Run cheap negative controls before SDK/emulator allocation. Avoid redundant builds on documentation-only and Python-only changes. Split full cases across runners instead of dropping tests. Use a known metadata-serving real peer for controlled magnet diagnosis when public swarm peers are absent. Every red run must preserve its first missing proof and last confirmed state; restrict the next test to unresolved hypotheses.

Candidate implementation paths: tools/ci_failure_digest.py; tools/verify_native_add_shards.py; tools/parse_real_magnet_peer_receipt.py; tools/seedhost_android_real_transfer_add_once.sh. Do not describe PR-only code as merged.
