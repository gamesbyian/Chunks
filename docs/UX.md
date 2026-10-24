# Chunks interaction and accessibility contract

Updated 2026-10-24. Status: design intent. No high-fidelity interface family, real handset captures or uncoached-user evidence has been accepted.

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
