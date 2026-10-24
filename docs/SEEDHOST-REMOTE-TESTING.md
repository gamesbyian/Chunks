# Seedhost rTorrent remote testing

The Chunks Android client can derive a candidate XML-RPC plugin endpoint from an HTTPS ruTorrent web URL. This is not proof that the endpoint works for the owner's instance.

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
