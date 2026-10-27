# Chunks interaction and accessibility contract

Reconciled 2026-10-26. Native Compose remote, experimental local engine, finished-file SAF library-copy and bounded manual ZIP-to-SAF extraction journeys exist with targeted JVM/API35 evidence. **The owner-selected six-image reference family has a first limited native implementation (#222: accessible geometric wordmark/header and high-contrast remote cards with Android/JVM, API35 and screenshot-atlas checks). Full reference-accurate layout, independent human screenshot review, physical Android handset, actual phone-to-Seedhost task, uncoached usability and 200% TalkBack approval remain open.** Advanced backing-file Move/Rename and cleanup remain design contracts.

## Privacy lock journeys (specified, not shipped)

During onboarding, explain and offer an optional transfer privacy lock; when enabled, authenticate once per app process session by default. Provide separate local-transfer and each rTorrent-profile policies: never, per session, on foreground return, after five minutes away, or each entry. The settings surface must give clear Android biometric/device credential selection and device capability/error explanation. Add **Lock now** to protected local and remote screens. Show a neutral locked screen with no title, alias, tracker, file path, collection, server identity, status detail or transfer controls leaking before authentication; guard actions as well as visuals, including back stack restoration, share/deep links, recents thumbnails, notifications and TalkBack. After unlock restore the user's context without forcing transfers to restart. Sensitive deletion, credential and policy-downgrade operations can require fresh verification without superseding precise confirmation. Remote login credential remembering remains opt-out by default and independent from this privacy gate. Full owner requirement: [TRANSFER-PRIVACY-LOCK.md](TRANSFER-PRIVACY-LOCK.md).

## Add destination: clear, never compulsory (owner requirement, 2026-10-27)

In magnet, .torrent, Android Share, and local/remote Add flows, show **Save to: [resolved default Place]** prominently, beside **Change** and a compact **Recent locations** picker. The primary Add action must work without opening a location picker when the default is valid. Changing location applies to that transfer only; separate explicit settings change the default. The most recently **successfully used** destinations are accessible in one tap with readable volume/server and full-parent-path labels; maintain separate recent lists for local and each remote profile, deduplicate and cap history, and remove or clearly mark stale/unauthorized locations. Favorite destinations may remain distinct. Do not preselect a recent custom folder instead of the configured default without the user's explicit preference.

For the local engine, display **Downloads / Chunks Downloads** as the proposed default only when the folder can actually be created and used for ongoing torrent downloads/seeding on that Android version/provider. Create it lazily and idempotently; if Android storage policy or engine requirements prevent it, explain and offer a supported directory without falsely showing a public Downloads location. For a remote server, show its own existing default directory; Chunks must never automatically create a Chunks-branded directory remotely. A server with an unknown or unusable default needs clear recovery rather than an invented path. This updates older Add wording below that implied obligatory folder selection. Test narrow and large fonts, TalkBack, first-run, two remotes with different defaults, stale recents and interrupted Adds.

## Mental model, first-run and navigation

- **Transfers**: what is downloading, completed, uploading, stuck and awaiting a safe move.
- **Places**: where the actual files reside, grouped by device/server, with recent/favorite folders and clear capability.
- **Collections**: semantic organization, optionally spanning folders/media/franchises without changing file placement.
- **Servers**: local engine plus separate rTorrent connections, secure status, diagnostics and explicit offline state.
- **Settings**: secondary controls, not a hidden destination picker.

First run should distinguish **Connect an rTorrent server** from **Download on this device (experimental)**. The local engine exists, but downloads to its app-private save folder and has not passed physical device lifecycle or arbitrary SAF destination acceptance.

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

The [Chunks visual design references](design/reference-images/README.md) set the aspirational in-app direction: high-contrast colour, bold geometric surfaces, chunky segmented progress and expressive lettering/icons. Implement accessible native components, preserve the actual Transfers / Places / Collections / Servers model and do not treat mockup actions as delivered functionality.

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

## Storage and archive journeys (partially implemented, with open acceptance gates)

**Storage entry:** From Transfers/Places and low-space alerts, show per-volume cap, measured versus unknown usage, free/headroom, active seeding backing bytes, independent library copies, extracted files and staging, with adjustment and warnings suggested at 70/85/95 percent of a chosen cap. Explain that a remote server's storage is independent of the phone. At cap, local downloading may pause while seeding remains available; never pretend remote quota enforcement is supported without proof.

**Cleanup wizard:** A default 'Free space without affecting sharing' path and an explicit 'Include files needed for sharing' path. Inventory scoped to actual authorized Place and refreshed before mutation; inspect eligible objects and estimated freed bytes, select/batch review, then confirm exact targets and see verified results. Separate Remove transfer (keep files) from Delete backing data. Before deleting an extracted archive's original, show: 'This archive is part of a torrent. Deleting it will make affected pieces unavailable for sharing. Your extracted files will remain. Restoring or re-downloading the original bytes is required to seed the full torrent again.' Shared files, remote unsupported capability, stale state and interrupted deletion require distinct blockers/recovery UI.

**Extraction:** A per-transfer 'Extract now' action and 'Auto-extract when ready' off-by-default toggle. Show destination picker, estimated output size and headroom, content/collision preview and progress. Explain extraction keeps the original torrent data intact for sharing; show password, unsupported format, multipart-incomplete and safe cancellation states. No silent overwrite or source archive deletion. Actual local/remote capability and storage safety gates live in [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md).

## Friendly queue-name editing and acceptance

Each transfer card offers Change/Edit list name, editable text, conservative explained suggestions, Save, Cancel and Use original. A suggestion only fills the draft; editing locally never changes torrent metainfo, backing files or seeding. The original name remains visible and searchable, alias-aware sorting uses the displayed name. Device-local scoped persistence and isolated native editor initially merged in [#74](https://github.com/gamesbyian/Chunks/pull/74). Actual populated installed MainActivity localhost acceptance [#82](https://github.com/gamesbyian/Chunks/pull/82) **merged** at `6dd07cb36df1df674b2a9a210c8ab6c0b7e22b27`, with 17/17 native API35 [40155244582](https://github.com/gamesbyian/Chunks/actions/runs/40155244582). Inspected real emulator PNGs verify scrolling a suggestion into view before tapping, a filled draft, Save and original title. App-owned edit sessions survive offscreen recycling. **Physical handset, TalkBack, 200% type, independently verified account isolation and process-death behaviour remain separate acceptance gates.**

## Native transfer confirmation survives scrolling (merged #89)

A transfer row inside a Compose `LazyColumn` may be recycled while users inspect a long name, file list or confirmation. Start/Stop, session Remove and exact-file-priority review must **not** silently reset to an apparently fresh action when scrolled out of view. Preserve the confirmation/selection state and the `attempted` latch with saveable item-keyed state; keep the in-flight flag ephemeral so an interrupted operation returns to an uncertain, non-repeatable state instead of an endless spinner. New server snapshot/target identity changes must invalidate an obsolete review. The selected file page and exact server file index must remain stable, not become a different physical target because of a list-position shift. Native scroll-away/back regressions may prove preserved review states; no actual server mutation is performed by those tests. [#89](https://github.com/gamesbyian/Chunks/pull/89) merged at `3e11d502dca11513e7b272676aab49079280e10d` after Android/JVM 40143850496 and native API35 40143850653 passed at exact source `cdea90ce1e62cd7d82c6013a24a1dc17846e5389` (15 instrumentation tests). Physical handset, process-death recovery and hosted-server operations remain separate unproven gates.

## Queue-wide local alias review (merged #98)

Transfers offers **Review suggested queue names** to inspect at most 100 eligible proposals from the **currently visible server view**. Existing user aliases and unknown/noisy filenames are skipped. Every suggestion shows its original torrent name and a filename-derived explanation. User explicitly chooses rows, optionally selects an alternative or enters custom text, then reviews old/new pairs and confirms a single atomic local batch. Nothing saves automatically; no RPC command, storage path rename or torrent metainfo mutation can result. **Undo last batch** clears only values unchanged since that batch was applied; if a user subsequently edits an alias, Undo refuses the entire batch rather than destroying their edits. Verified in merged [#98](https://github.com/gamesbyian/Chunks/pull/98) with exact-head Android/JVM [40158980304](https://github.com/gamesbyian/Chunks/actions/runs/40158980304) and native API35 [40158980145](https://github.com/gamesbyian/Chunks/actions/runs/40158980145), including real Compose PNG inspection. No physical TalkBack/200% type validation, large host benchmarking or media enrichment claims.

## Batch-name native accessibility and concurrent edit safety

[#100](https://github.com/gamesbyian/Chunks/pull/100) is merged at `6c8631b9d41808710badf4547541f0f750223e77`. Proposal checkboxes now have a source-torrent-specific semantics label, title columns wrap within the row and every alternative explains its filename provenance. Native API35 [40159540831](https://github.com/gamesbyian/Chunks/actions/runs/40159540831) passed 20/20 with a semantics assertion and a stale-batch all-or-nothing refusal test; source `2386cab18d5fb439f29de6ba16ffcb99bd3ecf79`. Actual 1080×2400 proposal PNG from artifact `12586130971` was visually inspected. This is automated accessibility and emulator visual evidence, **not** a real TalkBack or 200%/RTL device walkthrough.

## Phone-storage Places preview (merged #104)

The native Places screen now includes a separate read-only **Phone storage** card with OS-reported total/free bytes for Chunks' internal app volume, occupied-or-reserved progress, optional active Android low-space alert and a **Refresh phone space** control. The text explicitly distinguishes other apps/system reservations and remote rTorrent paths, and refuses to invent either a numerical Android low-space floor or Chunks torrent allocation. This card neither offers deletion nor enforces a cap. [#104](https://github.com/gamesbyian/Chunks/pull/104) passed [Android/JVM 40162903014](https://github.com/gamesbyian/Chunks/actions/runs/40162903014), [API35 40162903028](https://github.com/gamesbyian/Chunks/actions/runs/40162903028) and baseline [40162903074](https://github.com/gamesbyian/Chunks/actions/runs/40162903074), with inspected real low/normal Compose PNGs from artifact `12586059962`. This does not establish physical Android behavior, screen-reader speech or measured torrent usage.

## Local ZIP review task (merged #110; extraction intentionally absent)

From the disconnected connection form, **Inspect a local ZIP without connecting** opens a separate, read-only ZIP inspection page; connected navigation also exposes **ZIP review**. A large **Choose local ZIP** action invokes Android's document picker. The report names the locally selected document, shows safe path previews with bounded output, distinguishes integrity-verified from blocked/unsupported results, and expressly states that successful review is not permission to extract or delete a torrent-backed archive. **Back** returns to the connection form or connected transfer queue. This is a separate phone-local document journey, never a disguised rTorrent remote file browser.

[#110](https://github.com/gamesbyian/Chunks/pull/110) passed Android/JVM and API35 instrumentation at source `4279a5ba4c5b5a89944652ae8adabc310e79202a` ([40229146122](https://github.com/gamesbyian/Chunks/actions/runs/40229146122), [40229146145](https://github.com/gamesbyian/Chunks/actions/runs/40229146145)). Tests exercise real document-provider staging and the Compose result text, but not a complete picker-driven screenshot, 200% typography, screen-reader spoken comprehension, permission revocation or physical handset. Distinguish this **read-only view** from future manual/opt-in archive extraction and cleanup wizard task journeys.

## Current SAF UX capability boundary (2026-10-26)

Verified finished local data may be **copied**, never silently moved, to a selected SAF library destination while the original remains seedable. A verified ZIP can be manually extracted into a **new** SAF folder, with unknown-capacity acknowledgement, journal recovery and no source deletion. These are API35 fixture-provider paths rather than blanket SD/cloud support. Disclose actual source/derivative identities and ongoing seeding consequences. The user's auto-extract preference remains disabled until independent device safety proof.
