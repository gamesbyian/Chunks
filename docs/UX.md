# Chunks interaction and accessibility contract

Updated 2026-10-24. Status: a preliminary Android Compose interface is implemented in #14. Its Transfers, Places, Organize, Server and Add-preview surfaces have source/build/JVM evidence but **no high-fidelity approved reference family, real handset captures or uncoached-user evidence**. Advanced Move and Rename interactions are still only design contracts.

## Mental model, first-run and navigation

- **Transfers**: what is downloading, completed, uploading, stuck and awaiting a safe move.
- **Places**: where the actual files reside, grouped by device/server, with recent/favorite folders and clear capability.
- **Collections**: semantic organization, optionally spanning folders/media/franchises without changing file placement.
- **Servers**: local engine plus separate rTorrent connections, secure status, diagnostics and explicit offline state.
- **Settings**: secondary controls, not a hidden destination picker.

First run should offer a clear choice between **Connect an rTorrent server** and **Download on this device** once the local engine actually exists. Before then, label the app as an rTorrent remote prototype rather than implying the local engine is available.

## Task journeys and their failures

### Add torrent
From Android Share, magnet paste or torrent file: choose actual engine and Place, browse recent/favorite destinations, optionally create a folder only if capability allows, review file selection and Add. Display exact destination and whether the request has been acknowledged and observed on server. If unreachable, invalid magnet, destination rejected or auth expired, preserve draft intent and explain recovery. Avoid silently using an old default server/path.

### Active move
On transfer details, choose Move → inspect current vs proposed Place, capacity, collisions and expected effect on live download → confirm → observe planned/copying/quiescing/verifying/complete or deferred/uncertain states. If the host can't do it safely, offer Move after completion or Make library copy rather than a dead control. Never show “Done” until verified. When disconnected mid-move, show last confirmed stage and a safe Check progress action; do not duplicate the command.

### Rename
Present three concrete actions with outcomes: **Change label in Chunks**, **Rename torrent's backing file** (only where available), **Save a renamed library copy**. Show which preserves the seeding source, and never imply changing a UI label is a physical rename. For incomplete files, queue publication until stable or explicitly state that a separate incomplete copy isn't media-ready.

### Smart organization
Offer *Why this suggestion?* with observed filename facts and uncertain inference. Show category/collection membership and separate candidate destination; allow review by group, review conflicts, apply one, apply selected, teach an approved limited rule, ignore and cancel. A Star Trek TV episode and Star Trek comic can share a Collection but have different labels and Places. User's mixed-media root is legitimate. Never auto-create sprawling nested folders merely because an external catalog supplies a hierarchy.

### Remote failure and reconnect
Clearly distinguish Remote server offline, Auth failed, RPC unsupported, Filesystem unavailable, Permission denied, Operation pending and Operation uncertain. State whether this app, rTorrent, or file storage is still functioning. A server-side download may continue when the phone is offline.

## Information density and expressiveness

Generous default controls (at least 48dp), expressive colour with text/icons redundant status, readable progress, activity, per-server identity and search. An opt-in denser list mode can display hundreds of transfers without sacrificing navigation; deep peer/tracker/file details progressively disclosed. Distinct loading, empty, offline, access-denied, confirmation, destructive, deferred and uncertain states need designs. Irreverent copy is welcome on delight surfaces but should never replace factual errors.

## Accessibility acceptance

Verify on actual Android handset and where appropriate tablet: TalkBack traversal and actions, large font 200%, reflow, minimum targets, touch reach, gesture alternatives, keyboard focus, reduce motion, colour-vision differentiation and screen rotation/cutouts. Automated semantics audit helps but cannot substitute for physical device or comprehension testing. Prototypes and reference illustrations must retain an explicit unapproved status until reviewed.

## Early Add preview history versus current confirmed Add

The historical #14 dashboard initially supported read-only server views and a **non-submitting** Add preview. This was superseded by the merged two-step stopped Add implementation described below (#44 and following). Current native Compose also has exact-hash confirmed Start/Stop, session-only Remove, selected file priority and paged file listings. These require protected hosted Seedhost and physical handset journey acceptance; there is no filesystem Move or backed-file Rename executor.

## Android share-entry state

As of merged #17, Chunks registers for Android magnet VIEW and text/plain SEND, prepopulating the Add draft. Incoming links while the app is running use singleTop intent routing. Before a server is connected, the drafted magnet remains in ephemeral app state; it is shown after connection. Hash/path validation is local syntax only, not torrent authenticity or remote folder existence. A real Android Sharesheet, reopen/process death, browser magnet link and large-text test remains required before release.

## Local .torrent intake acceptance

Use standard Android OpenDocument rather than requiring a custom filesystem browser. Read at most 2 MiB; reject noncanonical bencode, v2/hybrid until supported, path traversal and malformed UTF-8; show verified v1 SHA-1 infohash, torrent name, file count and announced size. No automatic Add and no hidden path mutation. Exercise file pick, cancel, bad extensions with valid bytes, wrong file type, oversized metainfo, accessibility labels and state retention in screenshot QA. First implementation candidate #40 is a preview only and must not imply submission.

## Destination-first confirmed Add implementation

The merged Compose Add view (originally #44) accepts a v1 magnet or SAF-picked local .torrent bytes, switches explicitly between sources, requires an absolute server directory, then offers **Review stopped Add** followed by **Confirm: add stopped**. After any submission attempt it prevents another immediate attempt from that same screen and displays Verified, Already present, Pending verification or Uncertain readback. This is not an auto-start wizard. Android document picker, server directory existence/permissions, live Seedhost success and screenshots require independent device/real-server evidence. The temporary .torrent bytes are held in memory only, not a stored library.

## Controlled Start/Stop affordance

Transfers show one 48dp Start/Stop action per exact v1 hash, disabled when the list is stale. A confirm surface shows torrent display name and exact identity, and an explicit separate Confirm action performs one RPC. Preserve visible pending/uncertain/success state until user navigates away; never optimistic-toggle status. Refresh server view after the readback. Do not confuse lifecycle stop with deleting torrent or data; no auto-retries or background command loops.

## Storage and archive journeys (requested, not yet built)

**Storage entry:** From Transfers/Places and low-space alerts, show per-volume cap, measured versus unknown usage, free/headroom, active seeding backing bytes, independent library copies, extracted files and staging, with adjustment and warnings suggested at 70/85/95 percent of a chosen cap. Explain that a remote server's storage is independent of the phone. At cap, local downloading may pause while seeding remains available; never pretend remote quota enforcement is supported without proof.

**Cleanup wizard:** A default 'Free space without affecting sharing' path and an explicit 'Include files needed for sharing' path. Inventory scoped to actual authorized Place and refreshed before mutation; inspect eligible objects and estimated freed bytes, select/batch review, then confirm exact targets and see verified results. Separate Remove transfer (keep files) from Delete backing data. Before deleting an extracted archive's original, show: 'This archive is part of a torrent. Deleting it will make affected pieces unavailable for sharing. Your extracted files will remain. Restoring or re-downloading the original bytes is required to seed the full torrent again.' Shared files, remote unsupported capability, stale state and interrupted deletion require distinct blockers/recovery UI.

**Extraction:** A per-transfer 'Extract now' action and 'Auto-extract when ready' off-by-default toggle. Show destination picker, estimated output size and headroom, content/collision preview and progress. Explain extraction keeps the original torrent data intact for sharing; show password, unsupported format, multipart-incomplete and safe cancellation states. No silent overwrite or source archive deletion. Actual local/remote capability and storage safety gates live in [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md).

## Friendly queue-name editing and acceptance

Each transfer card offers Change/Edit list name, editable text, conservative explained suggestions, Save, Cancel and Use original. A suggestion only fills the draft; editing locally never changes torrent metainfo, backing files or seeding. The original name remains visible and searchable, alias-aware sorting uses the displayed name. Device-local scoped persistence and isolated native editor initially merged in [#74](https://github.com/gamesbyian/Chunks/pull/74). Actual populated installed MainActivity localhost acceptance [#82](https://github.com/gamesbyian/Chunks/pull/82) **merged** at `6dd07cb36df1df674b2a9a210c8ab6c0b7e22b27`, with 17/17 native API35 [40155244582](https://github.com/gamesbyian/Chunks/actions/runs/40155244582). Inspected real emulator PNGs verify scrolling a suggestion into view before tapping, a filled draft, Save and original title. App-owned edit sessions survive offscreen recycling. **Physical handset, TalkBack, 200% type, independently verified account isolation and process-death behaviour remain separate acceptance gates.**

## Native transfer confirmation survives scrolling (merged #89)

A transfer row inside a Compose `LazyColumn` may be recycled while users inspect a long name, file list or confirmation. Start/Stop, session Remove and exact-file-priority review must **not** silently reset to an apparently fresh action when scrolled out of view. Preserve the confirmation/selection state and the `attempted` latch with saveable item-keyed state; keep the in-flight flag ephemeral so an interrupted operation returns to an uncertain, non-repeatable state instead of an endless spinner. New server snapshot/target identity changes must invalidate an obsolete review. The selected file page and exact server file index must remain stable, not become a different physical target because of a list-position shift. Native scroll-away/back regressions may prove preserved review states; no actual server mutation is performed by those tests. [#89](https://github.com/gamesbyian/Chunks/pull/89) merged at `3e11d502dca11513e7b272676aab49079280e10d` after Android/JVM 40143850496 and native API35 40143850653 passed at exact source `cdea90ce1e62cd7d82c6013a24a1dc17846e5389` (15 instrumentation tests). Physical handset, process-death recovery and hosted-server operations remain separate unproven gates.
