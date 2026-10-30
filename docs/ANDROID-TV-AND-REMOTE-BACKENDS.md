# Android TV and multi-backend remote control: binding future product contract

**Owner direction, 2026-10-29. Status: planned and gated, not implemented or accepted.** This contract is normative for future behavior, not an executable queue or proof of readiness. Active priorities remain in [WORK-QUEUE](WORK-QUEUE.md), feature coverage in [SPEC-COVERAGE](SPEC-COVERAGE.md), and actual test evidence in [QUALITY-LEDGER](QUALITY-LEDGER.md). [Future backend umbrella #660](https://github.com/gamesbyian/Chunks/issues/660).

## Two distinct expansion tracks, after foundations

1. **Multi-backend remote control**: once rTorrent control is independently shown dependable under real hosts, authentication, TLS, reconnects, partial/stale results, duplicate hashes, Add, Start, Stop, file priorities, session-only Remove, and verified/uncertain outcomes, expand remote adapters to all *practical maintainable* BitTorrent control backends. Start with Transmission and qBittorrent to prove interoperability, then Deluge, BiglyBT/Vuze and other actively supported APIs according to demand and compatibility. “All protocols” is a coverage ambition, not a promise that every undocumented, obsolete, proprietary or inaccessible protocol works. Publish supported server versions/features in a capability matrix.
2. **Android TV / Google TV**: offer an installable, genuinely remote-operable TV experience with both **local client on TV** where hardware/storage/service restrictions permit, and **remote-only control** for phone/NAS/seedbox/server downloads. TV is explicitly a future supported form factor, not a requirement to delay current phone P0 acceptance. Device availability, packaging/Play listing and TV compatibility require separate proof.

The tracks are separable. Do not hold TV design hostage to every remote adapter, but **do not implement TV as a workaround for incomplete phone engine integrity or remote correctness**.

## Shared architecture for protocol adapters

- Share normalized transfer observations, environment IDs, exact (environment, hash) job identity, freshness, completeness, errors, capabilities, safe operation requests and outcome states. **Never** infer that the display’s selected environments authorize mutation. The backend adapter remains sole executor for its exact target/profile.
- Keep backend-specific authentication, network transport, endpoint discovery, session semantics, storage paths, file-priority capabilities, deletion semantics, reconnect and server-version quirks in isolated adapters, with explicit feature detection. Never silently downgrade TLS, expose credentials or tracker passkeys, or map session-only Remove into payload deletion.
- Preserve universal views for All/any selected environments: search, filter, group, reverse sort, stable selection and progress with honest missing data. Mixed-backend batch actions are capability-gated per exact target; partial success and uncertain outcomes remain visible and independently reconcilable.
- Reuse a shared Add, transfer details, storage-place and rules vocabulary only where semantics genuinely match. Explain unsupported actions; advanced backend-specific settings are optional scoped panels rather than fake universal controls.
- Each adapter needs protocol fixtures including malformed/fault/race/timeout responses, deterministic negative tests, repeatable real-server receipts, and compatibility/regression checks against rTorrent and local-only modes. Simulated ACK never proves successful mutation.
- No assumption that remote RPC access grants remote file-byte streaming or device storage rights.

## Android TV product UX

- Provide distinct **phone Compose** and **TV Compose** presentations over the same tested core/engine adapters. Do not stretch mobile touch navigation or duplicate transfer business logic. TV-first layout uses large readable text, generous focus targets, high contrast and clear environment/status labels; no touch, mouse, phone or keyboard required for main flows.
- D-pad Left/Right/Up/Down, Select, Back and long-press where supported must be predictable. Preserve focused transfer, row and scroll position across details, dialogs, sort changes, reconnections and returning Back. Define explicit focus restoration and sane fallback for missing/disappearing rows, long filenames and virtualized lists. No focus traps, invisible controls or offscreen activation.
- Minimum television routes: setup (local, remote or both), server/profile selection and safe credentials entry, Transfers (including combined views), Add via TV-suitable magnet/link/torrent intake and remote handoff where available, transfer details/files/priorities, scoped Start/Stop/session Remove, destinations/storage permissions, settings and accessible error/recovery states. Display source engine and server clearly at every risky command.
- Optional large-screen/media enhancements: artwork/poster/banner layouts for recognized content, browse files and **Open with** an installed compatible TV player. Show availability/transport/seekability and explicitly obtain authorization. Never promise playback from XML-RPC alone or silently download an entire remote video.
- Local TV downloads require device-specific storage paths, write safety, service lifecycle, reboot/Doze/foreground restrictions, external USB/SAF provider testing and no overwrite. Remote-only TV must work without enabling a local engine or requesting irrelevant storage permission.
- Accessibility acceptance: TalkBack or appropriate Android TV screen-reader support, D-pad-only flows, focus visibility, high contrast/colour independence, large font scaling, text truncation disclosure, 720p/1080p/4K layouts, overscan/safe areas where relevant, and slow/offline conditions. Test on TV emulator and at least one physical Google TV/Android TV device before claiming supported.
- Ensure TV launcher intent, manifest/device feature requirements and distribution/listing do not accidentally remove phone compatibility. Shared account-free experience remains; no Chunks cloud account required.

## Open-source reuse investigation and legal gate

[BiglyBT-Android](https://github.com/BiglySoftware/BiglyBT-Android) is an **explicit reference implementation** for TV-specific torrent workflows, Leanback navigation, focus/dialog behavior, remote-friendly file selection, and device packaging. Compare with current [Android Compose focus guidance](https://developer.android.com/develop/ui/compose/touch-input/focus) and official Compose for TV samples; prefer modern Compose for TV presentation rather than importing an old Leanback-based UI wholesale.

**Mandatory audited reuse before copying**: inventory exact BiglyBT TV source files/classes, dependency graph, obsolete APIs, tests, source commit, authorship and SPDX/license metadata including submodules and bundled dependencies. Confirm Chunks' own licensing/distribution decision and legal compatibility, including GPL source/distribution obligations. The absence of a root Chunks LICENSE file is not itself a legal conclusion. Do not copy third-party source/assets without compatibility review and attribution; general UX ideas and independently implemented behaviors can be used without copying protected code. BiglyBT core and BitTorrent engine should not replace Chunks' libtorrent implementation simply to gain TV UI.

The prior informal **30–50% TV infrastructure / 15–30% complete TV app** savings figures are speculative planning hypotheses only. Do not treat as validated estimates, schedule commitments or justification for direct copying. An actual file-level audit and prototype must establish reusable components and integration cost.

## Available future real-server test fixtures (owner confirmation, 2026-10-29)

The owner confirms that GitHub Actions secrets already exist for three further Seedhost protocol endpoints. They are **future opt-in integration fixtures**, not proof of implemented adapters, supported RPC endpoint format, credentials validity, or successful connections:

| Backend | Base/service URL supplied | Password secret |
| --- | --- | --- |
| Transmission | `https://wood.seedhost.eu/ianseeds/transmission` | `Transmission_pass` |
| qBittorrent | `https://wood.seedhost.eu/ianseeds/qbittorrent/` | `Qbittorrent_pass` |
| Deluge | `https://wood.seedhost.eu/ianseeds/deluge` | `Deluge_pass` |

All three use username secret `rtorrent_user`. Password **values** are not documented or fetched here. Exact service URLs may be UI entry points rather than API endpoints; discover API/session/CSRF/URL routing per protocol instead of assuming direct RPC. Keep all authenticated checks in protected, manually triggered workflows; prevent logs/artifacts from exposing tokens, passkeys, credentials or sensitive transfer inventories. Use licensed disposable torrents, safe Add/Start/Stop/session-only Remove after explicit compatibility checks, and never delete payloads or mutate the user's pre-existing torrents. Continue prioritizing current rTorrent P0 before expansion.

## Staging / exit gates

- **G0/G1 (current):** native data safety, rTorrent correctness, physical Android phone acceptance, shared local/remote capabilities, stable CI evidence.
- **G2:** universal core screens and action semantics genuinely shared; preserve future extension seams without premature backend/TV refactors.
- **G3:** rTorrent release-quality gate met, then independent Transmission and qBittorrent adapter proof; additional protocols only after per-engine fixtures and security/scope review.
- **G4:** source/license audit and TV architecture spike; D-pad routes, independent phone-vs-TV module boundary and emulator proof.
- **G5:** physical TV, external-storage and player-handoff acceptance, then packaging/release claim.

Future implementation agents must claim ownership and respect AGENTS.md/AGENT-LANES.md before modifying shared navigation, engines or CI. Spec agreement and a new issue are not proof of feature delivery.
