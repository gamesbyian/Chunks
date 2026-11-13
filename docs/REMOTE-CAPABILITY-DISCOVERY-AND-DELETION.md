# Remote capability discovery and deletion contract

**Owner decision, 2026-11-13. Status: specification / acceptance-pending, not an implementation or a claim about the owner's seedbox.** Applies first to rTorrent, and later to backend-neutral remote adapters. Work is staged within existing R1 owner-host acceptance and R3 remote-integrity cohorts, **not** a competing P0 subsystem. See [REMOTE-BACKEND-ADAPTERS](REMOTE-BACKEND-ADAPTERS.md), [ARCHITECTURE](ARCHITECTURE.md), [SPEC-COVERAGE](SPEC-COVERAGE.md) and [WORK-QUEUE](WORK-QUEUE.md).

## Observable behavior

- **Remove transfer only:** use the backend's native session removal with exact profile/hash/review, readback, uncertain-write fencing and truthful feedback. For rTorrent this is `d.erase`; do not imply payload deletion.
- **Remove transfer and data:** require separate explicit destructive consent, a positive, safe and appropriately authorized server-side *file deletion capability*, complete current-session/file-overlap/path verification, one non-idempotent submission and independently distinguished session-versus-physical-file outcomes. Never substitute session-only removal after refusal, timeout, malformed response or unknown delivery.
- **Standalone rTorrent:** standard XML-RPC does not provide a generally safe portable file-deletion API. Native `d.erase` leaves payload. Do not assume that `d.set_custom5`, `d.delete_tied`, `execute.*`, event hooks or mere ruTorrent naming automatically delete payload. In particular, Transdroid's historical custom5/ruTorrent convention is not evidence of support on a vanilla rTorrent host. Do not enable arbitrary remote shell-command execution or install hooks as an automatic workaround.
- **ruTorrent:** `erasedata` is one optional integration, not a universal rTorrent feature. Preserve exact authenticated scheme/host/tenant/numbered mount, and use only an explicitly validated same-instance route. Queue acknowledgement does not prove physical file deletion. A failed ruTorrent endpoint does not imply all other backend deletion capabilities are absent.
- **Other backends later:** normalize the user intent and outcome while delegating authoritative mechanisms to each engine adapter; never emulate destructive semantics with unreliable command sequences.

## Discovery and evidence model

Each exact authenticated environment maintains an operation-specific, evidence-carrying capability snapshot: backend and version (hints only), transport/endpoint and principal binding, adapter/method, observation timestamp/generation, reason and one of `supported`, `unsupported`, `restricted`, `unknown`. Keep **method exposure**, **actual authorization**, **prerequisite inventory/file coverage** and **operation outcome** distinct. Unknown, method-listed, route-shaped and HTTP 2xx on a harmless probe are **not** verified destructive authority.

At connect, reconnect, account switch and relevant configuration changes, perform only bounded, non-destructive discovery. Prefer read-only method introspection, version and session getters, narrowly scoped safe HTTP metadata checks and provider-documented capabilities. Never trigger a delete merely to prove that delete works; do not scan arbitrary URLs, try alternative destructive endpoints, send credentials across hosts, execute commands or replay non-idempotent writes. Where permissions cannot be proven without mutation, record `unknown` and require a guarded explicitly consented attempt; only trustworthy outcomes can promote confidence.

Cache only under exact profile/identity/endpoint/auth scope, with expiry and generation invalidation; never borrow results across accounts or aliases. Refresh when stale and immediately before mutation validate the exact target, identity, current job/file inventory, overlapping references, route and authorization. Capability evaluation must not introduce long connection delays or prevent basic list/Start/Stop/Remove when optional probing fails. Optional server-side directory browse/create and future capabilities can consume the same typed discovery vocabulary but require separate operation-specific rights.

## UI and outcome semantics

- The Connections profile may expose a concise **Server capabilities** read-only detail, with verified/unknown/restricted/unsupported and a short reason; no protocol jargon in the ordinary Transfers flow.
- In canonical Details, offer session-only Remove independently. Offer **Remove transfer and data** only with safe actionable adapter support and explicit consent; if unknown, restricted or unsupported, show a truthful disabled/explanatory state or a safe read-only recheck, not an optimistic enabled action.
- Distinguish before-wire refusal/no request sent, explicit HTTP denial (401/403 vs 404 vs 405), transport ambiguity after possible write, accepted/queued cleanup, confirmed session absence and independently verified payload absence. Preserve only privacy-safe, nonsecret diagnostics. A 404/405 on one erasedata URL must not be rewritten as proof that the whole server lacks deletion.
- A physical-delete claim requires independent evidence at actual files; confirmed session absence or a queue response alone is insufficient. Never auto retry uncertain destructive writes.

## Existing Chunks implementation and owner-host gap

As inspected 2026-11-13, `RuTorrentDeleteData.endpoint` checks a specific URL shape and `supportsRuTorrentDeleteData()` treats that shape as support, without proving plugin reachability or authorization. `removeWithDataViaRuTorrent` POSTs `mode=removewithdata&hash=…&v=1` once, mapping HTTP 404 and 405 together to `PLUGIN_UNAVAILABLE`; the canonical UI reports `No deletion was accepted`. This can present an actionable button for an absent/wrong/blocked endpoint. The user's original hosted-seedbox report is **not resolved** by passing disposable ruTorrent integration tests. Preserve #1679's owner-host reproducer and exact account/route observations, sanitized of URL path, token, password and payload name in public artifacts.

Address this incrementally: (1) distinguish the two HTTP statuses and safe diagnostic phases, and avoid false initial support claims; (2) inspect actual hosted mount/plugin/auth configuration without destructive probing; (3) implement typed capability freshness/binding and UI states within current Connections/Details adapters; (4) only add an independently justified constrained alternative deletion adapter if the server actually offers one. Do not add remote command execution.

## Acceptance and testing

1. Standalone rTorrent (legacy and modern), no ruTorrent: session-only remove works; data-removal capability isn't falsely advertised; payload stays intact, including when a custom5 convention is present.
2. Real disposable ruTorrent with genuine erasedata: exact same-instance route, authenticated consent, complete-session and shared-file/path guards, exactly one POST, queued/session-absence state followed by external collector and independently verified target-byte absence; foreign canaries unchanged.
3. Wrong tenant/numbered mount, absent plugin, 404, 405, 401/403, read-only host, filtered/unavailable session, network timeout and post-submit disconnect: correct per-operation and per-phase states, no silent d.erase, no blind second POST, no cross-profile credential or authority leakage.
4. Connect/reconnect/account changes, stale snapshot, concurrent transfer changes, profile renaming, duplicate display labels and offline: invalidate or re-evaluate without carrying deletion authority between profiles; keep other operations usable.
5. Installed canonical Welcome → Connections → Transfers → Details → reviewed Remove actions, including disabled/unknown explanatory presentation at 320/412dp, TalkBack and recovery; qualify function and experience separately.
6. Exact owner-host reproduction only with explicit owner authorization for mutation and controlled files; sanitized read-only capability/route evidence first. Passing simulated/disposable tests alone never closes the owner-host gap.

**CI:** A docs-only update uses docs validation only. Subsequent code changes choose targeted JVM/localhost and disposable real-engine/installed tests per [CI-POLICY](CI-POLICY.md); never automatically mutate protected seedboxes.
