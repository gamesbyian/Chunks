# Portable transfer snapshots (owner requirement, 2026-10-27)

## Portable settings

The optional settings export reads Chunks display aliases from the exact
source scope and file priorities from the live Local engine or connected
rTorrent account. An alias may contain private user-entered text. File
priorities require an original verified v1 definition and a complete current
file observation that matches its index, path and size. Padding is excluded.
Unsupported native levels, renamed paths, missing metadata, partial reads,
and settings beyond the archive budget leave the priorities absent. rTorrent
supplies Skip, Normal and High. Local also supplies Low. The export reports
how many records have verified priorities.

The ZIP layout and checksum members are unchanged. A settings-free export
still writes the original strict manifest version 1. Manifest version 2 adds
an optional per-item `settings` object with `displayAlias` and/or
`filePriorities`. The latter is one code per non-padding file in verified
metainfo order, using `s` (Skip), `l` (Low), `n` (Normal), and `h` (High).
It is limited to 1,024 file choices per transfer and 16,384 per archive.
rTorrent source records cannot claim Low. Unknown properties, empty settings,
unsafe aliases, and priority lists that lack or disagree with verified
metainfo are rejected before comparison. Older app versions can still read
new exports when the optional settings are absent.

Comparison shows the original file index, path, size and requested priority
and flags Low for an rTorrent destination. A separate Local action can apply
a saved display alias after an exact-hash/original-name check. It preserves
an existing alias and reads back any new setting.

For an exact **already-present rTorrent transfer**, an independently fresh,
unfiltered complete-session read before and after the exact hash's file query
first **compares** the current rTorrent priorities with saved v2 priorities
without writing anything. Original name, complete file count, sizes, order and
one consistent original versus server-relative path convention must match.
Server-relative files are recognized **only under the exact verified torrent
root**; an unrelated leading directory cannot authorize a saved mapping
(#1983). Unknown priorities, incomplete inventory, changed account/target
or a saved Low choice refuse application. Low captured from Local is reported
as unsupported and is never silently remapped.

When the target is already present and every saved priority is supported, a
**separate, paginated Apply confirmation** can show the exact account, hash,
file index/path/size and current versus saved choice for each change. The user
must approve those changes explicitly. The coordinator refreshes the complete
session and full file vector, refuses any changed review, applies only changed
file priorities with the existing exact-hash/index/path/size/expected-old
verified RPC, and reads the whole final vector independently. One ambiguous
acknowledgement, timeout, account switch or postcondition uncertainty **stops**
the remainder; no automatic retry or rollback is attempted. Report verified
partial changes and require a new read-only comparison before another attempt.
No Add, Start/Stop, removal, destination migration, payload copy or
automatic background priority application occurs. The same confirmation
can be opened after a newly restored rTorrent job appears in a fresh snapshot
comparison.

The separately confirmed saved display-alias action for rTorrent first
re-reads the exact authenticated account's complete current session, and
refuses a missing/differently named/hash-changed transfer. It writes only a
Chunks-owned, device-local, account-isolated alias, preserves any existing
manual alias and independently reads back a successful save. It cannot
rename a server-side torrent or any payload filename, and neither that alias action nor a read-only comparison by itself
authorizes remote priority writes. Only the new independent, explicit
file-priority confirmation described above can do so.

For an exact already-present stopped Local transfer, **Check Local files for
priority restore** reads its current file priorities and folder. The
confirmation shows the current and requested priorities for each file.
The full file layout must match by index, path and size. On confirmation the
engine rechecks the same registration, stopped state, folder, paths and old
priorities before submitting one libtorrent priority vector. It verifies
the complete live result and separate saved resume data before reporting
success. A lost or ambiguous outcome calls for a fresh read, with no
automatic repeat. The action leaves the transfer stopped.

Libtorrent can export bytes from a native partfile when a skipped file is
reselected. The snapshot action preserves existing skipped files and
requires an absent native partfile and the default backing layout. It
refuses alternative partfile locations and renamed backing paths. A
Finish in Order policy also blocks snapshot priority application so it
retains ownership of that transfer’s scheduling. See the
[pinned libtorrent storage implementation](https://github.com/aldenml/libtorrent/blob/24a3adf35d3e689bde700371337aad61b160bafa/src/posix_storage.cpp).

Definition Add, stopped restore and journal reconciliation apply no
settings. A change to settings alone preserves the definition-only journal
identity, including journals created from a v1 snapshot. Collections, tracker
edits and broader profile settings remain open.

## Restore one stopped rTorrent definition

In a connected rTorrent account, compare a verified snapshot against the
complete session and choose **Review stopped server restore** on a missing
original. This action requires a successful authenticated challenge, advertised
stopped raw Add, and a current absolute server default Place. The confirmation
shows the exact account, original name and infohash, and the current server
default. Historical paths are advisory. The existing manual Add route remains
available for separately reviewed destinations.

The adapter rechecks complete inventory and Place before dispatch, commits a
private hash-only journal intent at the RPC write boundary, and sends one
ordinary stopped Add. Success requires a complete-session identity read and
separate exact stopped, inactive and directory readback. **Check previous server
restore** reopens the journal for that archive, item and account without
repeating a write. If a reply was lost, the observed stopped registration may
be verified without attributing who added it. The accepted first slice uses the current server default for both restore and
recovery. A changed Place leaves a previous restore unresolved. Nothing starts,
moves or copies downloaded payload. For saved priorities, compare the archive
again and use the existing per-file review and explicit Apply confirmation.

**Selected Place (merged #2004):** The user can choose a
nondefault folder in the exact authenticated account's existing ruTorrent
browser. A picked or typed path is only a draft: before the restore confirmation
and again immediately before the one stopped Add, Chunks must read the mount's
current root and the selected directory independently through the same tenant's
read-only `_getdir` endpoint, with strict canonical path and root-containment
checks. No browsing support, failed authentication, another tenant's root or
changed folder must refuse the write. The nondefault Add sets the exact
`d.directory.set` parent; the default Add continues to omit that setter. The
confirmation identifies which of those two modes is being used. Selected and
default Places receive distinct hash-only journal fingerprints, so an uncertain
old default Add cannot be recast as authorization for a newly selected folder.
An explicit selected-Place previous-restore check may inspect the existing
journal and stopped session read-only even when the folder plugin later becomes
unavailable. The source-exact installed two-server campaign
[#2004 acceptance](https://github.com/gamesbyian/Chunks/actions/runs/45279072543)
passed both the authenticated folder picker and one stopped selected-folder
restore, including independent target/hash/Place readback, wrong-tenant
refusal, unchanged second server and no second Add. Unsupported hosts retain
the default-only flow. No arbitrary remote filesystem mutation, Start, payload
copy or automatic retry is added.

**Read-only interrupted server group (new candidate):** After comparing a
verified `.chunksnap` with the current whole rTorrent session, the user may
ask to check all previously attempted metainfo definitions **at the currently
reviewed server default or explicitly selected Place**. This is a new,
bounded, opt-in **observation and Chunks-owned journal reconciliation**, not
remote batch restore and not authorization to replay even an absent or
unverified Add. The coordinator opens only pre-existing, item-scoped journals
for that exact archive/account, independently verifies the current stopped
hash, original name and destination for each submitted item, then compares
the complete session again before publishing an aggregate result. It never
creates a journal for an absent item, changes a server job, sends an Add,
starts a transfer, copies data or retries a previous uncertain operation.
A missing or damaged journal is explicitly **unknown**, and a journal
fingerprint for another reviewed Place is distinctly **different Place**,
not a successful recovery. A REVIEW, READY or REFUSED record can say only
that **this exact journal** recorded no submission. Valid independently
verified previous attempts may advance their hash-only Chunks-owned receipt
to VERIFIED. The UI shows counts and a bounded group of actionable item
statuses; the individual read-only journal check remains available for
details. Large snapshots are bounded to 512 metainfo records per check.
This candidate requires dedicated real installed exact-account and native
acceptance before being called complete.

**Explicit stopped rTorrent batch restore (integration candidate):** Following a
fresh complete-session comparison of a verified archive, the user may review
up to 32 missing original metainfo definitions (24 MiB total) at one
independently verified current server default or explicitly browsed same-tenant
Place. This creates a process-only batch proposal; Cancel and preparation
perform zero server Adds. A second dialog names the exact account, destination
and initial original definitions. After confirmation, every item-scoped journal
must first be opened and checked against the original archive/account/Place;
a blocked **later** journal prevents **all** remote Adds. One guarded app-level
mutation lease spans the group. Each stopped Add then revalidates the complete
expected current session, exact account, Place and authenticated transport,
durably enters IN_FLIGHT before its single non-idempotent request, and
independently verifies the stopped hash/name/Place with the original rTorrent
readback. Only a fully VERIFIED result permits the next item; changed session,
lost acknowledgement, uncertainty or cancellation stops the remainder with
partial counts and a read-only previous-batch review path. The lease settles
once after success or stop so an ordinary post-write refresh cannot invalidate
the batch between members. No Start, payload copy, data removal, automatic
replay or priority mutation occurs. This candidate is not accepted until the
source-exact Android/JVM, native UI/R8 and installed disposable two-server
campaign pass, including the new two-item observed-registration proof; physical
Pixel/provider/TalkBack and signed release remain separate gates.

## Current implementation checkpoint (2026-11-17)

Chunks now supports more than the original inventory-only 2026 prototype, but this remains a **partial** snapshot feature. The current local engine can opt-in to export actual verified v1 torrent metainfo into bounded, checksummed `.chunksnap` archives. A user can select an archive in the read-only comparison flow, see already-present/missing identities, prepare a missing original torrent for manual Add, explicitly restore one stopped Local definition, or confirm a bounded group of verified missing originals as stopped at one newly reviewed Local destination (merged #1840). The restoration journal records an Add intent before submission; an unverified native reply is not permission to issue another Add. **No payload files are transferred.**

[PR #1830](https://github.com/gamesbyian/Chunks/pull/1830) tightened journal verification: the target's complete exact-hash list is insufficient by itself; independent observation that the exact transfer is stopped and saved at the original reviewed Place is mandatory. [PR #1831](https://github.com/gamesbyian/Chunks/pull/1831) **merged** a distinct user-initiated **read-only recovery** route for interrupted Local restores, with existing-only journals, exact stopped-state/Place readback and a separate destination confirmation. Its final head `eee60355` passed nine source-exact workflows, including installed real-peer magnet, two-server rTorrent, native Add verifier, API29, R8 and documentation. This integration proof does not reproduce a killed process on the owner's Pixel. Neither capability silently retries an Add after a failed acknowledgement.

The following gaps are **not complete**: unattended interrupted-batch resume, proven real-host authenticated remote metainfo availability and remote restore, moving downloaded payloads, migration of historical state or path mappings, verified physical Android document-provider/process-kill recovery, and the full settings/priority round trip. True private-tracker definitions may contain credentials in announce URLs and must be handled as sensitive archive content.

### Merged #1840 Local stopped batch restore and next recovery slice

The Local snapshot comparison now offers a separate **review all missing complete
originals** action when at least two verified v1 `.torrent` definitions are
absent from a current, complete Local inventory. The user must review one newly
selected writable Local Place and explicitly confirm up to 512 individual
**stopped** Adds. No transfer is started, no payload is moved, and no old path
is reused. The batch consumes a bounded maximum of 24 MiB of raw original
metainfo, with every mutable source buffer frozen and all identities/names
revalidated **before the first Add**.

Each attempted item uses the **existing per-item, source-and-target-scoped
durable restore journal**, shared with the one-at-a-time review. This makes a
different batch size or process relaunch incapable of resetting an earlier
IN_FLIGHT/ACK_UNVERIFIED entry into a new first attempt. After each Add,
Chunks independently verifies the exact infohash is present and **stopped at
the reviewed canonical Place**; only then may the next native Add begin. A
stale complete inventory, failed journal, uncertain acknowledgement or
unverified Place halts the remainder immediately. A new explicit comparison
is required; no automatic retry or next-item continuation follows ambiguity.
A grouped result reports verified-stopped count separately from the
uncertain/not-attempted remainder.

This is an explicit Local definition-restore capability, **not** general
portable queue migration. In-progress batches are not resumed automatically
after process death, there is no remote stopped bulk-import adapter or
historical file-priority restore, and no physical Pixel kill/provider acceptance
is claimed until exercised on actual hardware. An unsupported or missing
metainfo entry remains outside the batch, not guessed from an infohash.

### Candidate read-only previous-batch recovery (2026-11-17)

After a stopped Local batch is interrupted, the user can re-open a verified
`.chunksnap`, compare against one **fresh complete** Local inventory, and
choose **Check previous restores (read-only)**. This review includes both
already-present and missing original metainfo identities; already-present
jobs must never be submitted again. Select the **same original Local Place**
for independently verified status, even if the archive's historical folder
suggestion says something else.

The recovery probe uses only the existing exact
source-archive/item/target journals via `openExisting`; it **never creates a
missing journal** or calls an engine mutation. A journal in REVIEW/READY/
REFUSED is classified as not submitted **from that exact journal**, but an
absent/corrupt journal is **unavailable**, never proof that the engine did
not receive an earlier Add. IN_FLIGHT/ACK_UNVERIFIED/VERIFIED entries are
rechecked against the current complete inventory plus the current STOPPED
state at the reviewed canonical Place. The existing journal may be advanced
to VERIFIED only after this independent evidence. A different Place, absent
job, active job, stale inventory, cancellation or corrupt journal remains
unresolved or blocks a coherent result; no Add, Start, Stop, retry, Remove,
payload write or historical directory remap occurs. The receipt gives
verified, unresolved/unavailable and review-only counts.

This is explicit user-initiated **read-only reconciliation**, not automatic
resume after process death. Any future retry requires a separate reviewed
absence protocol; the new group check does not grant mutation authority.
Physical Pixel process-death and external SAF/provider acceptance remain
separate proof gates even after source-exact Android/JVM/native CI passes.

### 2026-10-27 historical milestones

The older checkpoint paragraphs below preserve dated evidence from the early library-only/inventory-only era. Statements such as "no mutation adapter" or "not yet connected to UI" describe **that date**, not current repository capability. Do not overwrite later observed source and source-exact CI with these historical descriptions.


**Recovery journal foundation, PR #356 MERGED (2026-10-27):** A hash-only, target-scoped Android import mutation journal now has a source implementation and tests. It persists reviewed Place fingerprints, commits an IN_FLIGHT intent synchronously **before** any future Add, distinguishes acknowledgement from independently verified exact-hash presence, preserves uncertainty across process death, and refuses concurrent stale overwrites via atomic compare-and-commit. Retries need complete fresh target absence **plus explicit user approval**; neither absence nor reopening triggers an Add. This journal is **not yet connected to the UI or any engine mutation adapter**, and must not be described as working restore. Exact-head Android/JVM 40621529085 and API35 native 40621528947 passed at `90e6c57b20058cf464f7847e63b248659f21a463`; squash merge `d318a3099fc4d0d7b8ab6acf46142b1548faa725`. This is a journal library only and does not enable any engine Add. Next slices: verified complete target inventory, reviewed Place remap, UI wiring, exact-hash post-Add reconciliation and process-kill/end-to-end testing. No payload bytes are transferred.

**Dated Android inventory-only export checkpoint (2026-10-27; verify against newer evidence):** Both local-engine and rTorrent screens now offer inventory-only .chunksnap SAF export. Local #331 passed exact-head Android/JVM run 40610549425 and API35 native run 40610549506 at 9e285b760cacfbd1c551346dec427bea8aaa44bd, merged ed6370f3971f8b8243c24c2ea9e4217775979d03. Remote #302 and offline read-only archive inspection #313 were already merged. No exported definitions are restorable: names and hashes alone cannot reconstruct a torrent; payloads, metadata, resume state and server destination are absent. Still needed: raw v1 metainfo recovery, validated manifests with true recovery counts, target Place mapping, durable per-item import journal, exact-hash Add readback, and physical local/remote acceptance. Do not claim full backup/restore.

**Status (2026-10-27): backend foundations, remote #302 and local #331 inventory-only SAF export, and offline read-only Android archive inspection #313 merged. Actual restorable metainfo-backed snapshots and destination-mutating restore remain planned.** Transport #295 provides bounded deterministic ZIP members with SHA-256 receipts (Android/JVM [40594814560](https://github.com/gamesbyian/Chunks/actions/runs/40594814560)). Strict typed manifest, canonical field and duplicate-key rejection, Unicode safety, complete v1 `.torrent` metainfo identity and original-name verification, and sensitive export warning flag are merged in #298 (exact head `65cd7cc52ada43bf84df052223e5ddabae2a19f6`, Android/JVM [40596795904](https://github.com/gamesbyian/Chunks/actions/runs/40596795904), merge `54c932c62d4bed67708a33afa2bac2ad31f6d684`). Read-only capture from remote rTorrent/local transfer status is merged in #299 (head `af0ba199777cc7aba7715eb0afcc66849868333e`, [40597315315](https://github.com/gamesbyian/Chunks/actions/runs/40597315315), merge `a7b6a8e9c1321cb2a4dcb47db9e835989da38883`); it currently creates **inventory-only** entries, marks zero verified restorable torrents, omits potentially sensitive source-directory hints by default, reports unsupported identities and refuses oversized inventories. Pure, missing-only target-inventory preview merged #300 (head `425f6d56a84eecc30bbc47d6276d172152557196`, [40597489194](https://github.com/gamesbyian/Chunks/actions/runs/40597489194), merge `1ecbe30ebe381258d2052e83be7ee9e0858ea451`); it skips known exact hashes and refuses partial/duplicate target inventories. Those three backend slices are JVM-level source proofs only; subsequent #302 adds a tested remote inventory-only CreateDocument and readback UI. **Still no source raw metainfo retrieval, no document import, no actual restore mutations, no payload-copy feature, and no physical-device or real-host snapshot acceptance.** Canonical product requirement for Chunks inventory portability; priority belongs exclusively to WORK-QUEUE.

**User-facing remote inventory export status (#302):** A connected remote queue now offers **Save inventory-only snapshot**, explicitly scoped to the loaded rTorrent view and disabled on stale/loading inventory. It counts omissions, states zero restorable items, optionally includes historical server paths only after explicit toggle (off by default), and uses Android's standard CreateDocument provider then reopens and SHA-256-verifies the exact saved Uri with bounded readback. An unsuccessful write/readback is labelled **unverified**, and no torrent or server operation is mutated. Exact tested head `9de6043091d31c3ae5db5c3491a3779d41305c18` passed Android/JVM [40598453276](https://github.com/gamesbyian/Chunks/actions/runs/40598453276), native visual baseline [40598453406](https://github.com/gamesbyian/Chunks/actions/runs/40598453406), and installed API35 instrumentation with a disposable debug DocumentsProvider [40598453290](https://github.com/gamesbyian/Chunks/actions/runs/40598453290); merged as `44225802bf479dd2ca2d230c6baacce0e2b28f18`. **Not yet delivered:** local screen export, obtaining raw recoverable torrent definitions, opening/previewing user snapshots, restorable counts, restore operations, private tracker fidelity, real external providers and physical handset acceptance. Do not describe the exported inventory-only archive as a transferable torrent set.

## Proposed 2026-11-17 remote original Source-plugin export slice

A user can opt into collecting **original v1 .torrent definitions** when the exact
authenticated ruTorrent account supports its standard read-only Source plugin.
The Source request uses the uppercase infohash spelling of rTorrent's saved
session filename. Returned definitions still need independent v1 identity
verification before they enter the archive.
The default export still records **inventory only**. This is an additional
capability, never a replacement for the existing inventory and manual restore
paths. A missing/disabled plugin or a record whose name, version or infohash
cannot be independently verified stays inventory-only. A renamed rTorrent
display name may therefore prevent an otherwise valid original from qualifying;
the system must not mislabel that entry as restorable.

The plugin endpoint is derived solely from the authenticated, numbered
`/plugins/httprpc/action.php` mount; there is no fallback to a sibling
account, `execute.*`, payload filesystem read, remote mutation or direct
HTTP credential-in-URL. It is queried only after a successfully challenged
RPC read on the same connected transport and through its own constrained
Basic/Digest read challenge. Every response is independently bounded to 2 MiB
and checked by the existing strict v1 metainfo reader. The resulting archive
is validated using the unchanged v1 schema/ZIP/SHA-256 pipeline. The owner
explicitly opts into potentially sensitive private-tracker announce URLs
before any Source reads, and that opt-in resets whenever the connected
server session changes. The connected account, loaded view, and frozen exact hash/name/Place
inventory are fenced throughout collection and before sharing. A routine
remote refresh that changes only speeds or counters must not abort a long
Source collection; a changed identity, name or Place must. A failed
Source read **does not retry a write** and does not erase other recoverable
entries. Never imply a share chooser saved the file.

This slice requires actual Source-plugin availability and accepted
authentication on an authorized real rTorrent instance, plus installed
Android and owner's Pixel document-provider acceptance, before claiming
that a particular host's torrents are recoverable. It does **not** implement
bulk restore, profile Place remap, payload copy or historical priorities.

## Outcome and scope

From a local Chunks engine or a selected rTorrent profile, **Export transfer snapshot** creates one portable, user-owned `.chunksnap` file. Importing it into a selected local engine or different rTorrent profile reconstructs the chosen torrent **definitions** and supported per-transfer settings, **not downloaded payload bytes**. Intended example: preserve a seedbox's 250-torrent inventory before shutdown, connect a replacement seedbox, preview the snapshot, remap download destinations, and selectively restore without requiring the old server to remain online.

Snapshots are for manual and optionally repeatable inventory backup. Never automatically migrate, delete, stop, reannounce, or alter source transfers. The exporting server is read-only. A successful import means the target engine has accepted and independently listed matching identities, **not** that it has received or verified any payload bytes or established peer connectivity.

## Archive structure and versioning

- Single ZIP-format container with extension `.chunksnap`, containing `manifest.json` (UTF-8), optional `torrents/<stable-id>.torrent` raw metainfo, and `checksums.json` with SHA-256 digest and size for each stored member. No payload files or rTorrent session files. Manifest v1 permits producer, UTC creation timestamp, source kind, and item identity/name/recovery/magnet/historical-directory fields. Manifest v2 adds the narrowly scoped settings above.
- Top-level `schemaVersion` (1 or 2), `producer`, creation timestamp (UTC), source kind (local/rTorrent), **redacted user-facing source label** (default omit server URL/username), counts by recovery capability, and items. An item's scoped identity is original v1/v2 infohash identity where verifiable; content path is never identity. Preserve original name and original infohash, plus the source's optional directory suggestion, current complete percentage and state **only as historical context**.
- Prefer exact complete `.torrent` metadata with verified hash and appropriate announce information. A valid magnet with complete infohash and required retrieval parameters is a separately labelled fallback. **Hash-only entries are inventory records, not automatically restorable torrents.** Never fabricate a magnet that is guaranteed to retrieve metadata, trackers, peers, or content from an infohash alone.
- Preserve file selection/priorities, selected trackers, tags/collections, local-only display aliases and start-paused preference when retrievable and translatable; capability-report any unrepresentable setting. Do not copy credentials, passkeys in connection URLs, cookies, authorization headers, private device tokens, rTorrent scripts, XML-RPC commands or arbitrary session state.
- Explicitly distinguish public tracker URLs from private tracker announce URLs. Private-tracker passkeys are often embedded in legitimate `.torrent` announce URLs, so **a full-fidelity archive can contain secrets**. Default export must show a clear sensitivity warning and require deliberate selection of full-fidelity tracker preservation; a sanitized export must mark torrents whose tracker removal may prevent their restoration/use. Never describe an exported snapshot as categorically free of secrets.
- Fixed size/member limits, bounded JSON/parser depth and string lengths, duplicate-name rejection, normalized paths/no traversal/no links, exact member allowlist, manifest checksum validation, and archive-version rejection before any writes. Verify metainfo hashes and identity congruence, including mixed v1/v2 where supported. Avoid ZIP bombs and giant torrent lists; stream into bounded temporary storage and atomically publish through Android document picker.

## Extraction of exportable definitions

1. Snapshot inventory into an immutable, source-revision-stamped read-only view; fetch full metainfo via supported **local engine APIs** or a verified, restricted remote metainfo export capability (for example, independently authorized read-only filesystem access). Ordinary rTorrent XML-RPC inventory methods do **not** universally expose raw `.torrent` bytes.
2. For a remote profile without verified metainfo retrieval, derive only what the RPC actually supplies, e.g. verifiable infohash and tracker data. A magnet is valid as a fallback only when the necessary identity and usable magnet context really exist. Explicitly classify per entry: `complete-metainfo`, `magnet-fallback`, `inventory-only`, `failed`.
3. Show an export review **before** file creation with total items, restorable items by strategy, omissions/failures and privacy warning. Provide a share/save flow using Android's document provider, working offline after successful generation. Do not falsely mark 100% recoverable because 100% of names/hashes were listed.
4. Individual entry failures do not erase the rest. Export report lists exact failures and mitigation without exposing sensitive URLs.

## Import and restoration workflow

1. Choose `Restore snapshot` from transfer-list menu or profile tools; open a user-selected `.chunksnap` (or explicitly supported plain `.torrent`/magnet list later). Perform all parsing, hash verification, version checking and inventory matching **before** contacting the target with mutations.
2. Show destination engine/profile, counts for already-present exact identities, restorable/new entries, unsupported/unsafe entries, and capacity limits. Default selection **missing transfers only**; do not duplicate entries or unexpectedly modify existing target torrents.
3. Offer per-entry and bulk selection, default target directory or reviewed mapping from old directory roots to verified target Places, never auto-create a Chunks server folder. Old absolute paths are **suggestions only** and never silently sent to a new host. Respect scoped recent destinations and custom parent selector. Unknown or absent destination defaults block only affected items, with an actionable prompt.
4. Default to **add paused/stopped**, unless the user explicitly selects start-after-restore. Existing target transfers remain unchanged unless separately and explicitly confirmed. Apply supported file priorities after creation and recheck every exact hash and saved settings. Distinguish imported, duplicate/skipped, unsupported, failed, and uncertain remote acknowledgements.
5. Persist a journal of pending imports and target-scoped completed hashes to survive process death. On timeout, reconcile by fresh inventory before deciding whether to retry; never blindly reissue an uncertain remote Add. Support safe resume and export of results.
6. The result summary clearly states `torrent definitions imported; payload data not transferred`, warns that old completion percentages are historical, and explains the user may re-download or independently transfer existing payloads and recheck them on the new server.

## Skip-safe snapshot defaults and return path (owner UX, 2026-10-30)

Preselect a plainly labelled, privacy-preserving set of transfer **definitions only** and a clearly identified export location; expose optional profiles, transfer subset and sensitive field handling as compact, reachable choices. Never imply an export contains file bytes or account secrets, and do not infer that importing definitions grants access to saved content. When imported definitions do not resolve to a safe destination or authorized exact environment, retain the preview and request one bounded choice instead of silently rebinding jobs or starting them. Later edit/retry/rebind must be discoverable without recreating the export, and the user should understand the next action after import. [Default and continuity UX](UX.md#visible-options-automatic-defaults-and-nondecision-continuity-binding-owner-direction-2026-10-30).

## UX/accessibility

- Local and remote transfer list overflow: **Export snapshot**, **Restore snapshot**. Also expose Export for selected transfers from bulk selection and profile settings. Import destination must always be explicit.
- Export progress: gathering inventory, resolving metadata, checking entries, writing archive, done/partial. Clear distinction between unreachable source, missing metainfo, weak magnet and file save error.
- Preview shows a concise restorable count, source date and privacy warning; expandable per-item diagnostics and a default missing-only selection. Long lists support search, type/status filters and accessible bulk actions; 48dp touch targets, TalkBack status, 200% font scaling and no decorative reliance on color.
- Optionally provide a human-readable manifest/report, but the full snapshot remains one archive; no external cloud service is required.
- Never force an archive passphrase as a prerequisite. Explore optional password-encrypted export using independently reviewed authenticated encryption, not ZIP's legacy weak encryption. A normal unencrypted archive must remain clearly labeled sensitive.

## Architecture and acceptance slices

A. **Backend partially accepted:** portable bounded `SnapshotManifestV1`, strict JSON parser, deterministic fixtures, v1 metainfo identity checks, checksum/ZIP defenses and missing/invalid claims tested in #295/#298. Not yet accepted: real private-passkey redaction strategy, v2/hybrid metainfo, interrupted Android document writes and physical-device proof.

B. **Inventory-only adapter accepted (#299); rest remains:** acquire actual raw metainfo from the live local engine, prove hash and storage durability, and implement alias/collection schema extensions under a separately versioned compatibility contract. Test process restart and Android SAF CreateDocument/readback with no loss of torrent payload or seeding.

C. **Read-only inventory adapter accepted (#299); rest remains:** wire the UI and implement rTorrent capability-aware metainfo acquisition. Verify at least two server configurations: RPC-only limited coverage and a fully authorized metainfo read adapter; no assumption that `d.get`/XML-RPC can return session `.torrent` data. Explicit coverage counts are release gates.

D. **Pure missing-only preview/dedup accepted (#300); rest remains:** fresh target inventory, destination remapping, local/remote import adapters and post-Add exact-hash confirmation with timeout fencing. Test against disposable rTorrent, native Android API35, interrupted batch, partial success, duplicate import and two profile defaults. Physical Android/real Seedhost acceptance separately gated. No downloaded-payload claim until actual download/recheck verification.

E. **Remote inventory-only export entry point accepted (#302), rest remains:** wire complete export/import review and completion reports with actual recoverable definitions and local screen entry; add `.chunksnap` sample fixtures with synthetic/public torrents only, no embedded credentials or private tracker tokens. Incorporate into SPEC-COVERAGE, PRODUCT, ARCHITECTURE, UX, SECURITY and WORK-QUEUE when those shared files are next safely reconciled.

## Non-goals for v1

No downloaded data copy, no replacement server provisioning, no arbitrary remote filesystem access, no restoring active peers/ratio or exact historical completion, no general rTorrent session-state transplant, no implicit tracker passkey stripping that breaks a private torrent, and no guarantee that magnets can retrieve metadata. This feature composes with, but is independent of, local↔remote verified payload copy/move.

## Multi-instance Seedhost acceptance targets (2026-10-27)

Two owner-provisioned rTorrent test instances are available for eventual snapshot portability acceptance: https://ianseeds.wood.seedhost.eu/rutorrent2/ (username `ianseeds`, password secret `rTorrent_pass2`) and https://ianseeds.wood.seedhost.eu/rutorrent3/ (username `ianseeds`, password secret `rtorrent_pass3`). Credential names are case-sensitive. The canonical [Seedhost remote testing document](SEEDHOST-REMOTE-TESTING.md) owns configuration and safety instructions. No passwords belong in source, logs, exports or test fixtures.

Cross-instance QA checklist: obtain fresh complete source and target inventories; export snapshot and verify the saved archive; inspect counts of genuinely recoverable definitions; preview missing-only target matches and explicit destination remaps; when an actual restore adapter is ready, import only explicitly authorized disposable test definitions as stopped, validate each exact infohash, duplicates, partial failures and crash-safe uncertain outcomes; repeat with instances reversed to detect per-host differences. Preserve source torrents and never claim payload migration. These instances have not yet passed Chunks connection or restoration acceptance, and read-only snapshot work must remain non-mutating.
