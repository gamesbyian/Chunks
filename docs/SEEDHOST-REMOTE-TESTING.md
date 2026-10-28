# Seedhost rTorrent remote testing

The Chunks Android client derives a candidate XML-RPC plugin endpoint from an HTTPS ruTorrent web URL. **The owner's designated development instance subsequently passed authenticated Kotlin inventory of 437 torrents and scoped exact-target Add, file-priority, Start/Stop and session-only Remove, plus magnet and ruTorrent upload journeys**; see QUALITY-LEDGER. Android handset-to-host acceptance is still pending. Subsequent dated sections supersede older candidate-only status.

**Owner-confirmed development URL:** `https://ianseeds.wood.seedhost.eu/rutorrent/` (credential-free web address). This is the default workflow-dispatch input for `seedhost-kotlin-adapter-smoke.yml`, `seedhost-readonly-probe.yml` and the protected `seedhost-android-readonly.yml` API35 installed-app journey; manual overrides remain available. Its corresponding candidate HTTP-RPC plugin path is `/rutorrent/plugins/httprpc/action.php`. Credentials must remain in GitHub Secrets `rtorrent_user` and `rtorrent_pass`, never appended to this URL. The previously successful authenticated version-only probe was [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095); do not infer that the full Kotlin adapter smoke has passed.

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

These two instances are configured by the owner for testing but Chunks connectivity, inventory, and restore have **not yet been verified**. Read-only probes, exports, and snapshot previews are suitable initial tests. Do not infer blanket permission for destructive operations on their existing inventory or payload files. Future restore acceptance should use explicitly reviewed disposable public test torrents, target-scoped preflight, clear opt-in, exact-hash verification, and failure journaling. Neither snapshot import nor a remote Add implies payload bytes were migrated.
