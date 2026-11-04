# Seedhost rTorrent remote testing

## Proposed manual, exact-infohash hosted received-byte proof (2026-11-01, not accepted)

The [owner-authorized synthetic peer-byte workflow](../.github/workflows/seedhost-exact-peer-bytes-once.yml)
is **manual-only**, on **reviewed `main`**, with a required `apply=true`
confirmation and `GITHUB_RUN_ATTEMPT=1` fence. It is never invoked by a
pull request, push, scheduler, or an automatic retry. Protected credentials
remain exclusively in trusted GitHub Actions secrets. This is a separate
host/JVM peer test, not the installed Android Connect, Add, or physical phone
gate. The workflow must not be advertised as passing until its real,
non-skipped, exact-head result is inspected.

The test first authenticates the **one specifically authorized primary HTTPS
ruTorrent instance**, checks its exact read challenge and current queue, and
selects a server parent corroborated by pre-existing complete single-file
jobs through `SeedhostDestinationPreflight`. It reads the same host's peer
listener and verifies a TCP connection **before any remote write**. It
generates a unique 512 KiB public/untracked synthetic V1 payload using the
GitHub run identity. Only its fresh exact infohash may receive a single
stopped `load.raw_verbose`, Start, Stop, and session-only `d.erase`.
All authoritative postconditions are re-read by exact infohash. A runner-side
libtorrent seeder transfers the actual payload to rTorrent through its
known TCP peer port; `d.down.total` and `d.completed_bytes` must both
grow **strictly from zero** to received and hash-verified positive values,
respectively. The runner also requires positive independent upload bytes
and a local payload SHA-256 re-read. A global rTorrent counter, successful
RPC acknowledgement, or stopped Add by itself is insufficient.

Cleanup is one-shot, only for the same freshly observed hash, unique name and
corroborated parent. It verifies Stop/inactivity before session-only Erase,
leaves payload data untouched and compares unrelated inventory identities.
A 401, timeout, lost ACK, unexpected directory or changed identity can leave
the synthetic transfer behind rather than risk a blind repeat or deletion.
The runner's peer preflight excludes reserved/non-global addresses,
including shared-space CGNAT, benchmarking and documentation ranges, instead
of relying only on Java's narrower private-address classification. It further
pins the historical, independently observed rTorrent peer endpoint address
`5.79.112.219` for this **one-time** host test. A provider-side readdress
must trigger a new explicit review, not fallback to another returned public
address or credential-bearing network sweep. Server-reported peer candidates
are compared to that **literal** IP before any DNS resolution, so arbitrary
hostnames cannot trigger resolver queries or DNS-rebinding effects from the
trusted GitHub runner. A transient
or malformed post-Add receipt must print `manual-reconciliation-required`
once a synthetic Add could have been submitted, even when the job was never
positively verified. A `PendingRemoval` result still has an exact-session
hash and **cannot** be called session removed merely because it disappeared
from the `main` view; the test requires exact-session `VerifiedRemoved`
plus the original unrelated inventory identity set before success.

The workflow emits only a strict stage allowlist and the **new synthetic
infohash** to enable owner-approved manual reconciliation. Full Gradle output,
headers, credentials, directory paths and tracker data are withheld.

This acceptance is intentionally **not** a promise that Internet swarm peers
will be available or that the installed phone can download in the background.
It also does not verify bytes by reopening the server filesystem independently:
host-side piece-hash verification and exact-hash counters are paired with the
runner's original payload and seeder upload observations. The synthetic
payload may remain in the approved parent after session removal, as is
conventional for rTorrent and required by Chunks' Remove semantics.

The Chunks Android client derives a candidate XML-RPC plugin endpoint from an HTTPS ruTorrent web URL. **The owner's designated development instance subsequently passed authenticated Kotlin inventory of 437 torrents and scoped exact-target Add, file-priority, Start/Stop and session-only Remove, plus magnet and ruTorrent upload journeys**; see QUALITY-LEDGER. Android handset-to-host acceptance is still pending. Subsequent dated sections supersede older candidate-only status.

**Owner-confirmed development URL:** `https://ianseeds.wood.seedhost.eu/rutorrent/` (credential-free web address). This is the default workflow-dispatch input for `seedhost-kotlin-adapter-smoke.yml`, `seedhost-readonly-probe.yml` and the protected `seedhost-android-readonly.yml` API35 installed-app journey; manual overrides remain available. Its corresponding candidate HTTP-RPC plugin path is `/rutorrent/plugins/httprpc/action.php`. Credentials must remain in GitHub Secrets `rtorrent_user` and `rtorrent_pass`, never appended to this URL. The previously successful authenticated version-only probe was [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095); do not infer that the full Kotlin adapter smoke has passed.

## Read-only authentication evidence correction (2026-11-01)

Earlier green read-only workflows validated RPC replies but did not require
`READ_CHALLENGE_ACCEPTED`. Their success markers alone cannot establish that
the server checked the supplied credentials. Preserve their version/inventory
decoder evidence; qualify historical authentication claims accordingly.

Merged #891 makes the actual `SeedhostAdapterSmokeTest`
use the same gate as localhost public-read, accepted-challenge and rejected-login
counterexamples. It requires valid version/main-view data **and** an accepted
HTTP authentication challenge at the exact endpoint before printing its success
marker. An anonymous readable endpoint remains unverified. This is bounded HTTP
credential evidence, not human account ownership or write permission.

From reviewed `main` containing #891, use the existing
`seedhost-endpoint-compatibility.yml` with exactly `primary-rutorrent-https`.
No new secret, arbitrary URL, numbered-instance fallback or mutation is needed.
Record its exact SHA/run/result; ordinary fixture success or a missing-environment
skip is not live-host acceptance.

## Exact credential contract

The owner confirmed these repository-secret names (case-sensitive):
- `rtorrent_user`
- `rtorrent_pass`

The manual-only `seedhost-readonly-probe.yml` workflow now consumes exactly those secrets. It accepts a **credential-free HTTPS ruTorrent URL as a Run workflow input**; there is no required URL secret. Use the ruTorrent web login URL already supplied to the project when dispatching.

Its only request is `system.client_version`. The workflow refuses off-provider hostnames, URLs embedding credentials or query/fragment data, and redirects. It does not log the server URL, password, returned version, XML response, info hashes or file inventory. Do not put real credentials into GitHub workflow inputs or code.

The credential values cannot be read via available GitHub connector tools and were not retrieved. A passing Kotlin/Android build does not prove the credentialed probe succeeded; record the workflow run and sanitized result separately when it runs.

## Next gates

After the protected version probe succeeds, test method listing, view/list completeness and browser/Android authentication behavior, without dumping private torrent names. Only then prototype remote write methods against disposable transfers. Live relocation remains blocked pending crash-safe verification.

Seedhost general integration docs describe `/rutorrent/plugins/httprpc/action.php` and deployment-specific variants such as `/RPC1`. Never assume a setter has moved files.

## Authentication failure recovery (2026-10-24)

[The first protected run](https://github.com/gamesbyian/Chunks/actions/runs/40063093896) returned HTTP 401 while sending preemptive Basic auth. This proves neither a bad password nor a bad RPC endpoint: the server could require a Digest challenge. The diagnostic now negotiates Basic/Digest by challenge over verified HTTPS using credentials scoped to the single endpoint and prohibits redirects. If 401 persists, it reports only the challenge scheme, never realm/nonce, host, password or returned data. App transport still requires its own Digest implementation/validation before being declared compatible.

## Verified production read-only version probe

[Actions run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095) completed successfully at source SHA `2f2660780363faf91a8d04ff8415264f0176c229` and emitted the sanitized success marker `CHUNKS_SEEDHOST_RPC: authenticated read-only response verified`. The test used the repo's `rtorrent_user`/`rtorrent_pass` secrets and a user-supplied URL. This proves authentication and one XML-RPC version response over protected HTTPS, not the Android client's credentials/transport compatibility, exhaustive torrent enumeration or any remote mutation. No torrent inventory was emitted.

## Owner designation: development/test inventory

The owner explicitly confirmed this rTorrent instance is configured and populated for Chunks development and the running torrents are **not private or confidential**. Real nonsecret torrent names, paths, sizes, statuses, file layouts and visual inspection artifacts may therefore be kept in the Chunks repository, fixtures, screenshot atlases, CI evidence and PRs. No need for automatic anonymization of this designated development inventory. Still exclude passwords, cookie/session material, auth headers, tracker passkeys or tokens potentially embedded in tracker/announce URLs. This disclosure scope does not change live mutation or deletion safety requirements, and does not apply to other servers by inference. Future visual capture should use real representative states and follow Glimpse/ROYGBIV's multi-viewport, accessibility and screenshot-regression precedent.

## Destructive operation consent and controlled rollout

The owner explicitly authorized destructive actions on torrents in this designated development rTorrent instance for testing. This includes targeted remove/delete and move/rename tests, subject to Chunks' engineering controls: exact torrent info-hash targeting, scoped approved operation, preflight state capture, server capability check, postcondition verification, no silent overwrite, recovery/journal for multi-step storage operations, and appropriately bounded test batches. The server is not production data. This permission does not apply to arbitrary FTP folders, other systems, or secrets.

The original manual seedbox workflow remains read-only. New destructive test workflows must be separate, visibly named, manual-only and default to dry-run, with an explicit arm input and exact torrent hash. No automatic deletion on a routine push or PR.

## Public-source Add acceptance

[Public fixture collector](PUBLIC-TORRENT-FIXTURES.md) (#29/#30) builds bounded test `.torrent` files and infohash-derived magnets from licensed Archive items and optional small Commons media. The separate [Seedhost public Add smoke](../.github/workflows/seedhost-public-add-smoke.yml) (#32) is manual-only and defaults to an offline preview. To arm its one stopped `load.raw_verbose` request, explicitly provide an absolute destination and re-enter the preview infohash. It reads back the main-view identity, `d.directory_base` and `d.state`. Real Add has **not** been executed or verified on the owner's server by the offline PR checks. No automatic retries after ambiguous writes; no auto-removal or filesystem moves. The Android API in #31 is also JVM-tested, not yet real-server accepted.

## Owner-authorized snapshot QA catalogue

The manual [read-only Seedhost probe](../.github/workflows/seedhost-readonly-probe.yml), when run with `inspect_inventory=true`, can save nonsecret main-view transfer metadata as `chunks-seedbox-fixture.json`. It now also invokes `tools/seedbox_fixture_qa.py` to produce `qa-cases.json` and `qa-cases.md` in the same 14-day artifact. The deterministic analysis selects **actual observed transfers**, if present, for long names, Unicode/right-to-left script, punctuation, incomplete/complete, started/stopped, enormous downloads, nested/long destination paths, and zero-size anomalies. Missing states are explicitly reported, never fabricated. All data is from the owner-approved development inventory and may be published for QA after checking token-bearing fields remain excluded. This is **candidate state selection**, not an emulator screenshot or full UI acceptance.

For offline replay: `python3 tools/seedbox_fixture_qa.py snapshot.json --output-dir /tmp/chunks-qa`. Source snapshot format, field list and hash identity are validated; out-of-range data or duplicate hashes fail closed. Then use Glimpse/ROYGBIV-style viewport and accessibility screenshot atlases with the enumerated real cases, measuring rendering against Compose instead of HTML mockups.

## Actual Kotlin adapter read-only compatibility gate (manual-only)

The [protected Kotlin adapter test](https://github.com/gamesbyian/Chunks/actions/workflows/seedhost-kotlin-adapter-smoke.yml) is separate from the earlier Python version probe. Dispatch from reviewed `main`, supply the normal ruTorrent HTTPS URL, and it consumes the already confirmed `rtorrent_user`/`rtorrent_pass` secrets. It runs `SeedhostAdapterSmokeTest` with the actual `RTorrentRemote` transport and decoder, reads version and the `main` transfer view, validates unique hashes, basic sizes and names, and prints only a success marker/count. No server mutation or torrent payload download. Public PRs run the fixture class with no live environment and deliberately skip its actual-server test; those green runs do **not** prove protected connection. Retain the resulting exact workflow run and tested SHA before marking this gate passed. Android physical TLS, certificate and accessibility UX still require handset evidence.

## Parent destination semantics for test Adds

The selected Add destination is a **parent folder**, not an instruction to rename/remove the multi-file torrent root. For new transfers, `d.directory.set` retains normal single-file behavior and appends the torrent name for multi-file items. The verified readback must explicitly inspect `d.is_multi_file`, `d.name` and `d.directory`. A different directory, flattening of a multi-file root, or an uncertain response is **not** verified. Do not re-run a write automatically, and never treat setter success as physical file movement.

## Installed Android-to-Seedhost real-UI gate (manual, read-only)

The [protected Android read-only workflow](../.github/workflows/seedhost-android-readonly.yml) compiles the actual debug APK and instrumentation APK, installs both on a **Google APIs API35 x86_64 emulator**, and drives **MainActivity's real login form** to fetch and refresh the owner's nonempty development Seedhost transfer queue over certificate-validated HTTPS. It saves two PNGs from the **connected queue only**. This route never sends Add/Remove/Start/Stop, never accesses payload files, and never attempts a real physical handset.

To run, manually dispatch on reviewed **main** using the prefilled approved credential-free `https://` development ruTorrent URL, or explicitly override it with another authorized development endpoint. The URL is public and contains no credentials. This is a one-click input default only, **not** an automated or already accepted server test. Existing protected `rtorrent_user` and `rtorrent_pass` secrets supply login. The workflow is deliberately **manual-only, main-only**: PR emulator runs see no credentials, skip the live test and establish only source compilation. The runner passes base64-encoded arguments to Android to preserve shell syntax; base64 is **not encryption**, and the script never prints arguments, persists them in the repo, publishes its raw instrumentation logs or uploads a login screenshot. The two connected-screen images are retained for seven days. The user-approved development inventory may be present, but screenshots must still be inspected for reusable tracker passkeys if present in visible names before external republication.

**Acceptance is pending until a protected run completes successfully.** An ordinary successful native Compose fixture run or an unexecuted workflow does not prove Android-to-Seedhost networking, Android platform TLS/Digest behaviour, real transferred names, responsive queue rendering or authentication. Even a successful API35 emulator run does not prove a Pixel handset, TalkBack, screen-off or physical background lifecycle.

## Additional owner-provisioned snapshot test servers (2026-10-27)

The owner created two more rTorrent/ruTorrent instances for cross-instance snapshot QA:

| Instance | Credential-free ruTorrent URL | Username | Password GitHub Actions secret |
|---|---|---|---|
| 2 | https://ianseeds.wood.seedhost.eu/rutorrent2/ | ianseeds | `rTorrent_pass2` |
| 3 | https://ianseeds.wood.seedhost.eu/rutorrent3/ | ianseeds | `rtorrent_pass3` |

**Secret names are case-sensitive**, including the capital R and T in `rTorrent_pass2`. Keep actual passwords in repository secrets only; never place them in tracked files, snapshot archives, workflow inputs or logs. Existing primary instance `/rutorrent/` uses the separate `rtorrent_user` and `rtorrent_pass` secrets. Existing CI workflows wired to that primary pair must not be pointed at the new URLs without explicitly mapping the correct credentials. Derive and verify each new instance's XML-RPC endpoint and capabilities independently.

**Owner permission update (2026-11-03):** Under [D-SECONDARY-RTORRENT-TEST-FIXTURES-20261103](DECISIONS.md), agents may **populate instances 2 and 3** with real, identifiable test torrents/magnets and operate those fixtures as needed to test **combined browsing or any other Chunks feature**. This supersedes the earlier read-only-only interpretation for the secondary instances; no request for the owner to manually populate them is necessary. Earlier authenticated read-only evidence exists (run 40630941499); actual connected multi-job acceptance and restore are **not** proven merely by this authorization.

**Practical test progression:** (1) authenticate each separately with its own case-sensitive secret over its exact numbered HTTPS route, read version and inventory, and select a dedicated known test destination; (2) add suitable known public/disposable fixtures to one or both secondary instances, deliberately including distinct and, where useful, same-hash/different-instance jobs; (3) verify exact-hash membership, downloaded/verified bytes, metadata and file priorities as appropriate; (4) conduct a **single coordinated real-engine/installed-app campaign** for phone plus two remotes: connected and disconnected inventories, selected subsets, search/sort/grouping, profile colours, settings and command targets, Stop/Start, retry/unknown states and cold restoration; (5) clean up only positively identified test-created or explicitly designated disposable jobs. Session-only Remove keeps bytes. Test **Remove and data** only with exact reviewed file mapping, provider permission and actual independent file-absence checks, including unrelated canaries; any uncertain result stays uncertain. Prefer one batched fixture provisioning and acceptance session to repeated per-feature protected-server setup.

**Boundaries:** The new authorization does not permit arbitrary deletion of pre-existing inventory or payload, unreviewed remote filesystem/FTP access, credential spill, instance fallback or scope escalation. Protected mutating workflows stay manual, trusted-main-only, with explicit arm/target and no automatic repeat after timeout or 401. If no current protected workflow supports the requested scoped operation, add and independently qualify the smallest expressly armed workflow rather than broadening an existing read-only action. Snapshot import, Copy/Move and Add never by themselves prove that payload bytes migrated. Keep per-instance server, hash, destination, action, outcome and test-source receipts in [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Owner-supplied torrent input security (2026-10-27)

The six owner-uploaded repository-root `[IPT].torrent` inputs and two additional magnet identities are inventoried in [USER-SUPPLIED-TORRENT-TEST-INPUTS.md](USER-SUPPLIED-TORRENT-TEST-INPUTS.md). Inspect private-tracker announce fields for embedded passkeys before adding these to protected Seedhost test runs or publishing artifacts. Never automatically start them during CI. Prefer licensed, disposable public fixtures for actual server Add/restore tests. The owner supplied these for app testing, not as evidence of download success or permission to redistribute payload files.

## Installed Android Add-screen journey (protected, mutating, 2026-10-28)

[`seedhost-android-add-ui-once.yml`](../.github/workflows/seedhost-android-add-ui-once.yml) is dispatch-only and main-only, and runs only with `apply_add: true` (default false).
- **What it does:** on an API35 emulator, the installed app's real Add screen adds **one** run-unique synthetic magnet (unreachable loopback tracker, so no payload or peers). It uses the server default Place or, because this host's `directory.default` is `~/downloads`, a parent corroborated from fresh reads of existing completed single-file transfers. It then requires the app's own Verified add+start outcome.
- **How it checks:** an independent adapter read checks the exact hash, the started state and the directory.
- **How it cleans up:** one exact Stop and one session-only erase of that identity only, then a check that main-view identities equal the preflight set. No step is retried after an uncertain reply.
- **What it publishes:** only fixed stage labels, an allow-listed exception class name, and post-login screenshots (never the login screen).
- **Status:** first accepted in run [40652034320](https://github.com/gamesbyian/Chunks/actions/runs/40652034320). Emulator, not a physical handset.

## Owner step: one completed public torrent for the copy probe (2026-10-28)

The read-only copy probe ([`seedhost-copy-to-phone-readonly.yml`](../.github/workflows/seedhost-copy-to-phone-readonly.yml)) needs a **complete, started, non-private** transfer on the primary instance. Every small completed transfer there today is private, so Seedhost inbound peer reachability is untested. The owner agreed on 2026-10-28 to add one public torrent. Use the official Debian netinst, which has ordinary seeders, so the server can complete it:

1. Open the primary ruTorrent at https://ianseeds.wood.seedhost.eu/rutorrent/ (not `/rutorrent2/` or `/rutorrent3/`).
2. Open **Add Torrent**. In **Torrent URL**, paste the current official file, today `https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/debian-13.7.0-amd64-netinst.iso.torrent`. If that 404s, Debian has released a newer point version: copy the `debian-…-amd64-netinst.iso.torrent` link from https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/ instead.
3. Leave **Don't start torrent** unchecked and the default directory unchanged, then click **Add**.
4. Wait until it shows 100% and is **seeding** (about 756 MiB; usually a few minutes). Leave it started. Don't add a label, private tracker or ratio rule that would stop it.
5. Tell the agent. It dispatches the probe on `main` with `max_mib` `800`, `seconds` `900` and `hash` set to the Debian infohash from the swarm runs (`7acf8fb590b2060dd9c3146ef770169d593433b0` for 13.7.0). Size order alone is not enough: run [40744688203](https://github.com/gamesbyian/Chunks/actions/runs/40744688203) found 40 smaller complete transfers, all private. The probe sends no mutation: the runner's phone engine downloads the payload with the server as its only peer and discards it.

The owner may stop or remove the transfer at any time afterwards. Removing its data is the owner's own ruTorrent action; Chunks automation never deletes it. An INCONCLUSIVE result with `maxPeers=0` means the runner could not reach the server's peer port (hosting firewall or NAT), not a Chunks Copy defect.

## Seedhost connection profiles and Transdrone cross-check (owner screenshots, 2026-10-28)

**Use the already tested Chunks HTTPS profiles first.** The three owner-provisioned
ruTorrent web roots are separate instances on the primary service and are
**not interchangeable**:

| Chunks profile | Credential-free ruTorrent root (auto-mapped to `plugins/httprpc/action.php`) | User | Password secret | Evidence |
|---|---|---|---|---|
| Primary | `https://ianseeds.wood.seedhost.eu/rutorrent/` | `rtorrent_user` secret | `rtorrent_pass` | Authenticated Kotlin read/write and API35 read-only/add; see QUALITY-LEDGER |
| Second | `https://ianseeds.wood.seedhost.eu/rutorrent2/` | `ianseeds` | `rTorrent_pass2` | Read-only version + empty main-view listing [40630941499](https://github.com/gamesbyian/Chunks/actions/runs/40630941499) |
| Third | `https://ianseeds.wood.seedhost.eu/rutorrent3/` | `ianseeds` | `rtorrent_pass3` | Read-only version + empty main-view listing [40630941499](https://github.com/gamesbyian/Chunks/actions/runs/40630941499) |

The corresponding HTTPS HTTP-RPC plugin paths are `/rutorrent/plugins/httprpc/action.php`,
`/rutorrent2/plugins/httprpc/action.php` and
`/rutorrent3/plugins/httprpc/action.php`, respectively, on
`ianseeds.wood.seedhost.eu`. All three are already supported by
`RemoteEndpointHints`; do not hardcode different username prefixes into those
successful paths. Their shared hostname does not make their inventories or
passwords interchangeable.

**Independent working-client evidence:** On the owner's Android device,
Transdrone successfully controls the primary instance with server type
*rTorrent*, hostname `wood.seedhost.eu`, username `ianseeds`, an **empty**
port-number field, SCGI mount point `/ianseeds/RPC1`, authentication **enabled**
(the "Disable authentication" checkbox is unchecked) and **Use SSL unchecked**.
This is evidence that Transdrone can use that configured path. It **does not**
prove HTTPS is available at `https://wood.seedhost.eu/ianseeds/RPC1`, that
blank port definitively means 80, that the client does not redirect, or that
`/ianseeds/RPC2` / `RPC3` exist. Those are separate hypotheses. Never
substitute `ianseeds.wood.seedhost.eu` for `wood.seedhost.eu` when testing the
Transdrone-derived alternative, and never infer that `ianseeds` is a hostname
prefix from the Transdrone screen.

**Security policy:** Chunks intentionally rejects insecure remote HTTP outside
loopback. The user-supplied Transdrone screenshot is *not* authorization to send
passwords over HTTP or loosen this protection. First test the **candidate**
`https://wood.seedhost.eu/ianseeds/RPC1` using an authenticated read-only
request with the *primary* secrets. If it fails, retain the working HTTPS
ruTorrent plugin endpoint; report the HTTP-only compatibility difference
instead of disabling TLS validation. Do not automatically redirect credentials
between hosts, silently downgrade to HTTP or attempt unverified numbered SCGI
mounts. Direct SCGI/XML-RPC might also lack ruTorrent's separate torrent-upload
fallback; capability-test it independently.

**Agent one-click protocol:** [`seedhost-endpoint-compatibility.yml`](../.github/workflows/seedhost-endpoint-compatibility.yml)
is manual-only, trusted-`main`-only and read-only; select exactly one of
`primary-rutorrent-https`, `second-rutorrent-https`,
`third-rutorrent-https` or `primary-scgi-https-candidate`.
It maps the exact corresponding URL and **correct case-sensitive secret** to
the existing Kotlin `SeedhostAdapterSmokeTest`. The test must independently
authenticate, read `system.client_version` and list `main` before marking
the selected endpoint verified. A candidate failure does **not** invalidate the
separately proven ruTorrent path. The existing
[`seedhost-instances-readonly.yml`](../.github/workflows/seedhost-instances-readonly.yml)
remains the two-instance matrix probe; the existing primary protected workflows
remain authoritative for deeper primary acceptance. Never run live probes on PR
heads, conflate a passing fixture/unit test with the dispatched live test, or
send unscoped mutations to instances 2/3 merely because credentials work.
Test-fixture mutations on instances 2/3 now have explicit owner authorization,
but require the reviewed scope and manual trusted-main execution above.

**Peer networking is separate:** HTTP(S) RPC and BitTorrent peer-wire
reachability are different. The initial TCP refusal at
`5.79.112.210:53281` was to the **ruTorrent web hostname IP**, whereas
this rTorrent daemon was actually bound to `5.79.112.219` (SeedHost
Client Area **Check IP**, owner verified). Merged #460 reads
`network.bind_address` before falling back to the web host. The
runner-to-SeedHost single-peer Copy then **verified all 792,723,456
bytes** in [run 40756284173](https://github.com/gamesbyian/Chunks/actions/runs/40756284173).
This disproves the earlier hypothesis that incoming TCP to the *correct*
rTorrent address was necessarily forbidden. It does not establish a
provider-wide permitted port range or physical-phone connectivity. Never
reuse the web host IP as the peer IP by assumption, never confuse
incoming peer port `53281` with the HTTPS management port `443`.

## Smart Connect handoff and next verification (2026-10-28)

[SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md) now defines the general
user-facing provider discovery process. The first Android implementation
is [merged #463](https://github.com/gamesbyian/Chunks/pull/463):
hostname-only inputs plan bounded, same-origin HTTPS RPC candidates;
complete `/rutorrent/`, `/rutorrent2/` and `/rutorrent3/` input must
stay on the exact selected instance. The connection is accepted only
after authenticated `system.client_version` **and** a valid complete
`main` view, with verified per-profile credentials saved to Android
Keystore if the user has not opted out. The working Transdrone direct
`/ianseeds/RPC1` HTTP profile does not establish its HTTPS availability.

After merging and verifying #463's exact Android head:
(1) use the protected, manual-only three-instance HTTPS profiles
without changing their case-sensitive GitHub secrets;
(2) test both hostname-only primary discovery and exact numbered ruTorrent
URLs with live version/list verification, including empty secondary lists;
(3) test `https://wood.seedhost.eu/ianseeds/RPC1` separately as an
**unverified HTTPS candidate**;
(4) record exact run/SHA, route, authenticated listing counts,
auth/challenge class and failure categories without credentials, private
URLs, tokens or passkeys; and (5) run the native API35 UI and physical
handset separately. Never run discovery mutations or allow a
failed `/rutorrent2/` attempt to switch to the primary instance.
Later provider capabilities and guided connection repair are tracked in
[#464](https://github.com/gamesbyian/Chunks/issues/464).

The earlier manual-workflow and test-gate wording elsewhere in this
chronological document describes the state when each section was written.
Current verified evidence in QUALITY-LEDGER and live main supersede older
pending statements.
