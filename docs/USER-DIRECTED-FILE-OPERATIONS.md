# User-directed file operations and destination conflicts

**Status:** adopted engineering policy for future implementation and requirements review (2026-10-31); **not** proof that currently blocked operations work. [#809](https://github.com/gamesbyian/Chunks/issues/809) remains open. Scope: manual ZIP extraction, independent completed-torrent library copy, explicit exports, and independently authorized file publication. Local native torrent payload writes are instead governed by [LOCAL-TORRENT-COLLISION-POLICY](LOCAL-TORRENT-COLLISION-POLICY.md). Internal metadata, cleanup, unprompted rules and remote account mutations retain their stricter independent contracts.

This document distinguishes what a user knowingly asked Chunks to do from what its implementation might do accidentally. It supplements [OWNED-PUBLICATION-PROTOCOL](OWNED-PUBLICATION-PROTOCOL.md): that document's no-replace new-object protocol still governs **Create new / Keep both** and unattended publication. Explicit **Replace selected existing object** is a distinct, authorized operation, not an ownership claim inferred from a pathname. The existing production ZIP/library mutators stay disabled until their real hazards are fixed.

## Ordinary, discoverable UX

- **Choose destination once.** Display its actual environment, provider, location and what will happen; no mandatory ownership ceremony. Defaults should be sensible, with Change and recent destinations available.
- **No collision:** create normally when the provider supports new-object creation, and show the real resulting name/location, including any provider-generated suffix.
- **Collision:** offer **Keep both** (recommended default for ambiguous or sensitive results), **Replace**, **Skip** and **Ask for each** when meaningful. Persist preference only for the current explicit operation or when deliberately saved, never infer permanent consent from a different destination. On unsupported providers present supported options honestly.
- **Replace:** user authorizes replacement of *exactly the presented existing regular document(s)* at the intended root, not neighboring files, other torrent data, unrelated ZIP members or app metadata. A bulk **Replace all conflicts** confirmation is permissible when the review clearly describes the affected batch and scope, and the user has the opportunity to cancel.
- **Duplicate ZIP member paths:** not an ordinary destination collision; reject or require an explicit, unambiguous member-selection mapping before writes. Normalize and reject case/canonicalization ambiguities on the actual destination.
- No archive/seed source deletion, automatic overwrite or cleanup of previously existing files on error; deleting files is an independent, explicitly labeled and authorized operation.
- No atomicity theater: show per-file success, skipped, replaced, incomplete and uncertain outcomes. Offer retry/review after interruption, and avoid claiming all-or-nothing unless a real atomic primitive exists.

## Operation classes and APIs

| Operation | Authorization | Appropriate implementation boundary |
|---|---|---|
| New app-private temporary/journal/resume object | Internal operation, **no user permission to overwrite arbitrary files** | Exclusive create, no-follow, pinned identity and owned cleanup. Predictable-name truncation and deletion by naked path are defects. |
| New visible file (Save new, Keep both, ZIP entry with no conflict) | Chosen destination + intended name, not an existing-target replacement grant | Use platform-backed genuinely new-object creation (POSIX `CREATE_NEW`; MediaStore new pending row; SAF `DocumentsContract.createDocument` on a qualified provider). Observe final name/URI/permissions; no fallback to truncating arbitrary existing object. |
| Existing visible file the user explicitly chose to replace | Specific existing destination or reviewed conflicts + Replace approval | Revalidate exact scope, type and ability to write; prefer staging + atomic publication where the provider supports it. Otherwise explicitly describe non-atomic partial-failure behavior. No replacement if final target identity changes to an unrelated object outside the approved contract. |
| Existing file returned unexpectedly by a Create new operation | **No replacement consent** | Refuse/offer user-visible reconciliation; do not truncate or 'adopt' because it has a URI or matching name. |
| Automatic rules, recovery and rollback | No fresh replacement consent | Do not overwrite/delete uncertain or unowned targets; reconcile read-only first, require user action when ambiguous. |
| Local torrent payload under chosen root | Chosen torrent destination | Conventional libtorrent piece-hash check/reuse/replacement of declared regular files; separate contract, no proprietary ownership backend prerequisite. |

### Android contract distinctions (do not conflate)

1. The **`DocumentsProvider.createDocument(parent, mime, name)` provider interface** promises a *newly generated* document ID; the provider may adjust the display name to avoid collisions. This is the Android API contract and a valid starting point for supported providers, not a mathematical proof against buggy or hostile implementations. [Android reference](https://developer.android.com/reference/android/provider/DocumentsProvider).
2. The **`ACTION_CREATE_DOCUMENT` picker intent** is a different interface. Android's [storage guide](https://developer.android.com/training/data-storage/shared/documents-files) describes non-overwrite and auto-suffixed names, while its [Intent API reference](https://developer.android.com/reference/android/content/Intent) explicitly says the returned URI *may* refer to an existing document. Treat this discrepancy as a reason to inspect returned identity, provider behavior and operation intent, not as permission to truncate blindly nor as reason to ban all SAF exports. The picker is not an implicit confirmation to replace an existing object.
3. **`ACTION_OPEN_DOCUMENT`** can intentionally select an existing document and grant scoped write capability, but selection alone doesn't grant Chunks permission to replace it as part of a separately described **Create new** operation. Label the proposed replacement and obtain deliberate consent.
4. `DocumentsContract.createDocument` on a tree, MediaStore `insert`, local filesystem creation, and the Save picker have **different** identity, mutation and durability guarantees. Do not impose one provider's exact receipt schema on every adapter; instead document per-adapter capability and tested behavior. Validate source and destination, bounded I/O, returned identity, grants and failures.
5. A provider that violates its advertised contract may be refused, or limited to read/share, **for that provider or operation**. Never claim compatibility with every arbitrary cloud provider. Do not silently switch the user's destination.

## Non-negotiable data integrity

- Reject ZIP traversal, absolute entries, symlink/junction/device targets, unsafe canonicalization, duplicate/colliding members, archive bombs, exceeding storage budgets, wrong destination/environment and source changes. All writes stay under the authorized logical root; provider aliases and revoked grants fail without expanding scope.
- Keep old contents available until the intended explicit replacement can be committed where a safe staging/replace primitive exists. If the provider cannot offer transactional replacement, identify that limitation to the user before replacement; accurately report interruption and partial output afterward.
- A destination survey is advisory: prevent accidental overwrite via actual API semantics and appropriate final-target validation. Do not attempt impossible universal guarantees against a provider lying about objects or third-party applications concurrently modifying user-authorized files; test the relevant race boundaries and scope claims.
- Resolve current #809 concrete bugs: old `ZipExtractor.finish` could delete a substituted foreign filename; legacy publication could mistake a same-name directory for success; SAF `createDocument` could unexpectedly yield an existing URI opened with truncating mode; plain-file reopening and cleanup could affect a swapped object. A corrected implementation must make these adversarial fixtures fail safely **and** execute ordinary positive cases.
- Never treat filename, one-off readback digest, journal intent or earlier successful provider listing alone as authorization for automatic deletion of a target. In-progress/unknown results remain visible for recovery, not silently cleaned up.
- Source bytes of completed/seeding transfers are retained; library copy verifies independently readable source and destination bytes without stopping or deleting seeding data unnecessarily.
- Credentials, privacy lock, tracker secrets, remote environment scope and external provider authorization remain unchanged.

## Required test matrix, by operation rather than universal platform certification

**Positive:** new file into empty authorized folder; collision Keep both; exact user-reviewed Replace; Skip; multi-file ZIP with nested folders; independent library copy from a stable completed transfer; cancellation and retry; valid digest readback; partial-progress report; source remains unchanged; returned name may differ without silently misreporting destination.

**Negative:** traversal, links and special types, internal archive duplicate names, source changing mid-stream, returned existing URI where *Create new* was requested, wrong provider/volume/root, revoked permission, truncate/short write, disk full, competing target mutation, substituted cleanup/path, interrupted publication journal, malicious provider metadata and inconsistent readback. Assert unrelated files/entries are unchanged and no false 'Saved' status.

**Evidence tiers:** host filesystem, Android API29/API35 emulator, installed handset, chosen provider/version, removable volume and actual remote service are separately named. A passed tier enables only its proven operation/capability. A real G0 destructive bug blocks the affected implementation even if other test tiers are green; absence of OEM testing by itself cannot justify mislabeling an already functioning independent feature as broken.

## Immediate work against #809

1. Inventory each *actual production* entry point, staging helper, returned URI path, recovery/cleanup path and active capability flags. Retain current refuse-before-write containment for unsafe operations.
2. Split the planned positive implementation by adapter: (a) app-owned normal filesystem directory, (b) Android MediaStore new file, (c) provider-qualified SAF tree create, and (d) explicit existing-document replacement. A pass in one class never implies the others.
3. Implement first positive vertical slice using documented platform APIs and operation-scoped user consent, then the negative tests above. Reuse standard library APIs before considering new JNI, custom virtual filesystems or elaborate universal journals.
4. Reconcile the active queue and all contradictory historical clauses by dated supersession, not deletion of evidence; avoid issuing a second global work queue.
5. Retire the feature's specific temporary refusal only after exact-head automated positive and negative acceptance, with truthful per-provider limitations. Preserve independent approval/review for genuinely destructive operations.

## Background reference

libtorrent's [storage-move API](https://libtorrent.org/reference-Torrent_Handle.html) exposes `always_replace_files`, `fail_if_exist` and `dont_replace` as distinct operations, even documenting the race in the probe-first alternative. This demonstrates why explicit collision policy belongs in the product and each filesystem adapter, rather than asserting universal no-overwrite as a fundamental property. It is **not** authority to overwrite outside a selected torrent root or to delete arbitrary files during rollback.
