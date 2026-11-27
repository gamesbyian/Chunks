# User-facing client terminology and Transfers scope count (owner approved, 2026-11-15)

**Binding correction** to Chunks interface copy, accessibility labels and user-facing help. Internal domain concepts such as `environmentId`, `UnifiedTransferKey`, engine routing, durable persistence, RPC account scoping and already-existing code symbols may **continue to use environment**; do not mass-rename stable IDs, migrations or protocol contracts for this display change.

## Display vocabulary

- In visible Chunks UI, use **client** instead of **environment** where referring to a local or remote BitTorrent client. Examples: **All clients**, **Client colour**, **Show client colours**, **Choose client**, **2 clients included**. Do not use awkward `Environment: Server · Wood · <endpoint>` wording.
- Preserve established **Local** for the on-device transfer-scope chip, **Local Device** when its fuller name is specifically needed, and user-assigned short names such as **Wood** for remote scope chips, transfer cards, Details and Add destinations. Prefer the short custom name alone where source context is clear; no need to prefix `Remote client:` or `Server:`. Distinct duplicate display names are still disambiguated contextually without leaking URLs or changing stable IDs.
- Use **Connections** for the footer destination and the unified remote profile roster, **Manage** for advanced remote settings, and **Transfers** for the one canonical inventory screen. A connection/profile is persistent saved configuration; a live connected session, read-only accessible inventory and the selected client scope are separate states. Do not call a disconnected source 'connected' merely because cached or read-only rows are available.
- Update user-visible strings in Compose, Android string resources, screen-reader/TalkBack descriptions, notifications, widgets, menus, labels, help, confirmations and screenshots/tests **where they refer to clients**. Preserve **environment** in historical documents, source-level architecture discussion, external protocol terms, logs/debugging where needed, and any truly different domain meaning. Avoid blind global replacement that alters code APIs or changes meaning.

## Clients, not servers (owner directive, 2026-11-27)

**Chunks manages BitTorrent clients, some of which happen to expose server interfaces.** Local and remote clients are first-class participants in one application, so a remote BitTorrent client is a **remote client**, never generically a "server". One client can simultaneously be a BitTorrent client (peer-to-peer participant that downloads and seeds), an RPC/HTTP server (accepting remote-control requests from Chunks) and a daemon (a background process without its own interface). Which description is right depends on the relationship being described. The host computer, the client software and the interface Chunks talks to are three different things.

| Term | Meaning |
| --- | --- |
| Client | Any BitTorrent client managed by Chunks. |
| Local client | Chunks' integrated BitTorrent client (its UI name stays **Local Device** / **Local**, code `Local`, ID `local`). |
| Remote client | An external BitTorrent client controlled by Chunks. |
| Client type | rTorrent, Deluge, Transmission, qBittorrent. |
| Client instance | One particular running instance of client software (for example a numbered Seedhost instance). |
| Connection | A configured relationship between Chunks and a remote client (the saved profile). |
| Host | The machine or network host running the remote client. |
| Endpoint | The network address through which Chunks communicates with the client. |
| Server | A component accepting network requests, only where that role is specifically relevant. |
| Daemon | A background service, where applicable. |
| Engine | The underlying torrent-processing implementation. |

| Avoid | Prefer |
| --- | --- |
| Add server | Add remote client |
| Deluge server | Deluge client |
| Server settings | Connection settings |
| Server type | Client type |
| Server name | Client name |
| Server unavailable | Remote client unreachable |
| Server-side seeding | Remote client seeding |
| Server download directory | Remote download directory |
| Server reported ratio | Client-reported ratio |

**Legitimate uses of server** describe actual server functionality: an HTTP status a web server returned, a TLS server certificate, the ruTorrent/web server that fronts rTorrent, a test `MockWebServer`, or code such as `RpcServer`, `serverCertificate` and `serverResponse`. Keep them. Placeholders such as `server.example` name a **host** and may stay as host examples.

**Scope and method.** Apply this to UI strings, accessibility descriptions, help, notifications, documentation, domain models, identifiers and tests, as a **contextual review, never a blanket search-and-replace**. Keep distinctions that belong only to networking and transport in that layer; do not encode "server" into domain concepts such as transfers, inventory, Places, seeding or Add destinations. Rename code identifiers incrementally by ownership, one qualified change at a time, and **never change persisted keys, stable IDs, migrations or protocol contracts** merely to rename them. Tests that assert visible text are rewritten with the copy (REWRITE), preserving the behavior they witness. Dated historical documents keep their wording.

## Transfers summary: included, not excluded

In the compact Transfers summary immediately above rows, a scope count representing the sources **selected to participate in the current transfer-view scope** is labelled **`N clients included`**, never `N sources excluded` or `N environments excluded`. Example: **`48 transfers · No live rates · 2 clients included`**.

- Inspect the current summary's actual count derivation before changing copy. Ensure **N is computed from the selected client scope**, not from an internal excluded-source variable accidentally relabelled as included. If the old implementation counts excluded sources, update its derivation and tests to correctly count included clients.
- A selected client counts as included even when currently disconnected, locked, unavailable or presenting stale cached inventory, provided it is genuinely in the selected view's source scope. Included **does not imply Connected**, current speeds or valid inventory count. Distinguish known/stale/unknown outcomes independently. Do not count sources omitted by the user-selected scope. Do not conflate client count with the number of visible matching rows or deduplicate jobs between clients.
- If the scope count is not meaningful or not reliably known, use a truthful unavailable/omitted state rather than fabricating a number; preserve existing live-rate and excluded/unknown *diagnostic* distinctions elsewhere. If a separate legitimate diagnostic counts excluded sources, label it accurately in its own context; do not silently flip its semantic meaning.
- For user-selectable scopes (All/Local/Wood/other clients), test 0/1/2/N configured and selected clients, offline/locked clients, stale cached rows, search/status filters, disconnected sources, 4+ All chip threshold, duplicate hashes across clients, and rate availability changes. Keep the status sentence compact at 320/412dp and 200% font.

## Implementation and acceptance

Audit hard-coded UI strings and Compose-derived messages with references to **environment**, **server**, **source**, **excluded** and **included**, distinguishing real client semantics from technical terms. Adjust the shared UI model and tests once, across All/Local/remote scopes, instead of patching text separately. Ensure existing client short-name and colour parity from the Transfer Details, Add and compact-list contracts. New testing must reject outdated assertions expecting `sources excluded`, `environment colours` or verbose `Server · Wood · endpoint` on primary UI, but must **not** rewrite protocol security assertions or persistence IDs.

The final installed Pixel acceptance must show the correct `clients included` count and new terminology on Transfers, Options, Connections, Add and Details without creating unsafe routing or misleading live-session claims. Documentation changes alone are not implementation or test evidence.
