# Smart Connect: provider-aware rTorrent remote setup

**Owner requirement (2026-10-28):** Connecting Chunks to an existing rTorrent
instance should require as little expertise as possible. Pasting a ruTorrent
URL/hostname and entering the applicable login should normally be enough.
Advanced/manual configuration must remain available for any self-hosted or
custom reverse proxy installation. Local Android torrent downloads are
independent and never depend on a server connection.

## User experience contract

1. First screen: **Server address or ruTorrent URL**, **Username**,
   **Password**, and a prominent **Connect**. Trim whitespace; automatically
   prepend `https://` to a hostname-only input. A browser URL and a fully
   qualified API address should both work. Never tell users they must know
   what XML-RPC, SCGI, or HTTP-RPC means.
2. As the read-only attempt runs, show **Finding a secure connection** and
   the number of checked endpoint patterns, without printing credential or
   challenge details. Make cancellation available with no persisted partial
   profile. Verify `system.client_version` **and** a valid, fully decoded
   `main` list, even when empty. No mutation at Connect.
3. Save an independently verified endpoint to the existing Android Keystore
   profile by default, with an explicit opt-out and Forget controls. Preserve
   explicit user-pasted URLs/instance identity; remember the discovered
   path for hostname-only users. No cloud account or central pairing server.
4. Only then offer an optional **Connection details** card with endpoint
   type, authenticated identity, server version, verified read/list
   capabilities and unsupported features. Default view remains transfers.
   Reusing a saved profile should take one tap.
5. On failure, show a *specific*, safe explanation: hostname/DNS, firewall,
   timeout, TLS name/chain/expiry, 401 credentials, 403 permission, login
   proxy/unsupported challenge, non-RPC HTML, RPC method restriction, empty
   vs failed inventory, or uncertain result. Offer contextual manual fields.
6. For multiple instances, keep each complete URL, password, capability set,
   remembered UI preferences and transfer identity scope separate. Never
   substitute primary `/rutorrent/` for `/rutorrent2/` or `/rutorrent3/`.
   Credential retries must not be sprayed across instances.

## Provider pattern catalog (documented, never exclusive)

| Family | Reference | Candidate(s) (same HTTPS host supplied by user) |
|---|---|---|
| SeedHost.eu | https://www.seedhost.eu/client-area/knowledgebase/299/Transdrone-basic-configuration.html and https://www.seedhost.eu/client-area/knowledgebase/281/Prowlarr-basic-configuration.html | `/username/RPC1`, `/username/rutorrent/plugins/httprpc/action.php`, deployment-specific ruTorrent path |
| Ultra.cc | https://docs.ultra.cc/misc-guides/mobile-apps | `/RPC2` |
| Whatbox | https://whatbox.ca/wiki/editing_rtorrentrc | `/xmlrpc` |
| Swizzin | https://docs.swizzin.net/applications/rtorrent/ | `/username`, `/rutorrent/plugins/httprpc/action.php` |
| QuickBox | https://quickbox.io/docs/applications/download-clients/rtorrent | `/USERNAME` nginx SCGI (preferred where HTTP-RPC is untrusted) |
| GigaDrive | https://docs.giga-rapid.com/en/guides/beginners/how-to-control-your-bittorrent-client-using-mobile-apps | `/rt/plugins/httprpc/action.php` |
| Appbox | https://billing.appbox.co/knowledgebase/166/ruTorrent-and-Transdroid.html?language=english | `/RPC2` |
| Feral Hosting | https://www.feralhosting.com/wiki/software/transdrone | `/slotusername/rtorrent/rpc`, with HTTP auth username often literally `rutorrent`; slot name must come from the pasted URL, never the auth username |
| RapidSeedbox | https://help.rapidseedbox.com/en/articles/8504545-connecting-your-home-sonarr-radarr-to-rsb-s-deluge-and-rutorrent | `/plugins/rpc/rpc.php` on documented `rt-*.seedbox.vip` HTTPS host |
| Bytesized Hosting | https://bytesized-hosting.com/pages/remote-torrent-adder | hosted `/rutorrent` browser route on `*.bysh.me`; HTTP-RPC route requires separate proof |
| Seedbox.io | https://docs.seedbox.io/shared-storage/applications/transdroid/ | provider-assigned `/RPC/<digits>`; do not guess a different tenant from a nonnumeric/unknown account |

| Generic nginx/Apache/rTorrent | https://github.com/rakshasa/rtorrent-doc/blob/master/RPC-Setup-XMLRPC.md | `/RPC2`, `/xmlrpc`, ruTorrent plugin, explicit custom path |

**Catalog rules:** Data-driven templates should include source URL, reviewed
date, host-pattern confidence, mount patterns, auth expectations and safe
fallback order. Prefer an actual pasted URL over a guessed provider.
Unknown providers are supported by bounded generic options or custom entry.
Update templates via reviewed app releases or signed/verified catalog
updates; *never* download executable provider scripts. Do not make
provider name or suffix alone proof of compatibility. Host matching must
respect registrable-domain boundaries, never substring tests that also
match attacker-controlled lookalikes. No third-party service receives the
user's address or credentials as part of discovery.

### First implementation slice: bounded discovery

`RemoteSmartConnect` plans up to six read-only candidates. A complete
`/rutorrent2/` URL maps **only** to that instance's HTTP-RPC plugin.
Explicit RPC paths remain exact. Only hostname-only inputs receive a
bounded same-origin provider-specific/generic path list, with a strict
HTTPS requirement and no redirect or cross-host guessing. Login failures,
bad TLS and unreachable network stop exploration. Read-only
`RTorrentRemote.version()` plus `listTransfers("main")` must both pass.
`MainActivity` uses this planner for Connect and persists a verified
profile. Unit tests cover tenant boundaries, provider patterns,
unsupported/insecure input, credential failure and fallback. This does
**not** claim all provider families have live acceptance.

**Provider catalog (2026-10-29):** hostname-only plans now come from
`rpc/RemoteProviderCatalog.kt`, a data-only list whose entries carry the
reference URL, review date, DNS domains matched on whole labels, optional
first-label prefix, ordered path templates (account placeholders only from
a validated username, never for Feral slots) and a `liveAccepted` flag that
stays `false` until a ledger-recorded handset journey exists. A JVM test pins
every plan exactly, so catalog edits are deliberate. The Connect form shows a
credential-free "Recognised <provider>" hint (polite live region) stating the
match is based on reviewed docs and not yet tested by Chunks. Feral and
Bytesized entries carry only a paste-your-full-address tip; self-hosted
families (Swizzin, QuickBox) and Seedbox.io's numbered RPC paths have no
host pattern and keep using an exact pasted URL.

**Known first-slice limitations:** No explicit provider selector; no live
capability grading beyond version+main view; no cancel/progress details
for discovery; no DNS-SRV, Zeroconf, QR pairing, browser extension
migration, SSH/VPN tunnel provisioning, custom certificate pinning or
proxy SSO session handling. The default catalog is intentionally small;
unknown domains may need to paste the provider's exact URL. Discovery
must not inspect torrent content, trackers or filenames.

## Next implementation stages

**P1: guided server identification.** Detect common provider URL
shapes; offer provider chooser/search when hostname alone is ambiguous.
Recognize `/rutorrent/`, numbered/custom named instances, copied
`/index.php`, full HTTP-RPC, direct SCGI mounts and arbitrary URL
prefixes. On ambiguous multi-instance URLs ask for the intended
instance, not an unreviewed cross-instance fallback. Support safe
copy/paste from a provider dashboard and optional QR/pairing deep links
with *no passwords in URL, clipboard logs, or crash telemetry*.

**P1: smarter handshake.** First perform credential-free, same-origin
endpoint preflight if possible, with small response/time budgets and
strict request limits, then authenticate only a plausible endpoint.
Negotiate HTTP Basic and Digest (including supported SHA-256), distinguish
proxy authentication 407, disallow redirects with credentials, and avoid
repeated authentication failures or account lockouts. The existing
transport does Basic/Digest challenge negotiation; this stage adds
first-class explanatory diagnostics, time limits, cancellation, HTTP
status types, and a tested stale/cancel path. Never blindly retry
mutating RPCs. Use structured redacted diagnostics rather than raw
server-supplied HTML, headers or auth tokens.

**P1: capability-based endpoint selection.** Probe
`system.client_version`, `system.listMethods` where supported,
`d.multicall2` main view (with bounded paging fallback),
`f.multicall` and representative **read-only** getters on an existing
verified hash. Some servers limit introspection, so unavailable
`system.listMethods` is unknown rather than automatic failure.
Distinguish **read-only**, **everyday control verified by prior
exact-target acceptance**, and **unsupported/forbidden**. Prefer the
fully capable endpoint; newer QuickBox direct nginx SCGI can work where
ruTorrent HTTP-RPC is treated as untrusted. Do not perform Add,
Start/Stop, file priority, upload or Delete to grade connection.

**P2: authentication/security configurations.** Standard publicly trusted
TLS first. For user-managed self-signed CAs, support explicit local CA
import or *out-of-band verified* certificate/SPKI fingerprint pinning,
with visible warning and rotation/recovery workflow; never silently
trust-all or downgrade to HTTP. Support local HTTP only via secure
loopback/tunnel when appropriate, and explain how to use an already
configured WireGuard/Tailscale/SSH forward; do not expose raw unauthenticated
SCGI ports on the Internet. Reverse-proxy SSO, client TLS certificates,
VPN-only hostnames, custom HTTPS ports, and OAuth/OIDC browser flows
need separate credential-isolation designs and real test environments.
If an SSO gateway cannot expose an authenticated RPC route, explain
that limitation instead of looping through guesses.

**P2: optional ruTorrent gateway features.** Keep direct XML-RPC and
the ruTorrent HTTP-RPC plugin as separate capabilities. HTTP-RPC
inventory working does not guarantee ruTorrent's upload form, token
or cookie negotiation, response compression, permission to run all
commands or parity with direct nginx SCGI. Use the exact settled
endpoint for ordinary commands; require fresh readback on mutations.

**P2: resilience.** Detect redirect/proxy changes, stale saved
configuration and expiring certificates. Show last-known-good endpoint,
last verified connection time and a user-invoked **Repair connection**
action that safely reruns the read-only planner. Repair must never
replace the intended instance or silently change profiles. Present
offline status without claiming server-side downloads stopped.

## Security and test invariants

- **HTTPS only** for remote; no auto downgrade from TLS to plaintext.
  Loopback HTTP remains allowed for controlled fixtures.
- **Same origin** for all auto-candidates; never discover a sibling
  hostname, LAN address or RFC1918 target from untrusted server output.
  No automatic redirect following; verify host, scheme, path and port.
- **Bounded attempts**: max six candidates, one authenticated read-only
  check per path, fail closed on auth errors/TLS/unreachable host; no
  credential attacks or path scanning.
- **No secret leakage** into Git/CI, telemetry, HTTP URLs, status error
  text, server logs, screenshot tests or clipboard pairings.
- **Fresh instance identity**: main-view success, even empty, is proof
  of one *specified* endpoint, not of a particular human-intended
  instance unless its path/identity are confirmed.
- **Provider-agnostic support**: provider rules are convenience hints,
  not mandatory accounts, cloud servers or approved-vendor lists.
- **Real-host acceptance**: SeedHost primary plus independent
  `/rutorrent2/` and `/rutorrent3/` read-only profiles; Ultra/Whatbox/
  Swizzin/QuickBox/other provider real or faithful gateway tests
  required before advertising one-tap compatibility. Fixture mocks
  must include 200 HTML, 401/403/407, malicious redirects,
  self-signed/wrong-host TLS, empty view, untrusted RPC method,
  duplicate hashes, large list, path escaping, cancellation and
  absent/rate-limited credentials.
- **Peer vs control**: HTTP/443 API access is independent of incoming
  peer-wire listener (e.g. SeedHost TCP/53281). A successful RPC
  connection alone never proves that the seedbox accepts peer traffic.

See [SEEDHOST-REMOTE-TESTING](SEEDHOST-REMOTE-TESTING.md),
[SECURITY](SECURITY.md), [QUALITY-LEDGER](QUALITY-LEDGER.md)
and [WORK-QUEUE](WORK-QUEUE.md) for live acceptance boundaries.

## Implementation note: progress, cancel and repair (2026-10-28)

`RemoteSmartConnect.connect` reports each candidate before probing (`onAttempt`) and, on failure, returns per-candidate `Attempt(description, path, outcome)`. Only the description, the URL **path** and a short classified outcome (404, refused read-only check, login refused, redirect refused, non-XML-RPC reply) are ever shown: no hostnames with credentials, passwords or raw server replies. `repairAdvice` gives category-specific next steps (paste the browser's ruTorrent address, provider XML-RPC path, web-UI vs SSH password, certificate check, VPN/home network) and states that Chunks stops after one refused login to avoid account lockout. Cancel abandons the read-only discovery coroutine and fences any late result. Credentials are still only sent in response to a same-endpoint 401 challenge, so candidates answering 404 never receive the password.

## Implementation note: saved-profile instance identity (2026-10-29)

Each saved server on Connect now shows its exact address (host, non-default port and RPC path, never the scheme or credentials) and when it last authenticated ("Last connected 2 hours ago"). Two profiles for one account on one host, such as `/rutorrent/` and `/rutorrent2/`, previously had identical labels. The time is written only by the existing post-authentication save and lives inside the same Keystore-encrypted roster document. Records saved before this change still load and show "Last connection time not recorded". Evidence: a JVM address test and Android instrumented roster tests (legacy record, distinct addresses, times); no handset review.
