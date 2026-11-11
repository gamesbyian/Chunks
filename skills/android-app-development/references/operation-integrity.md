# Operation integrity across Android and remote systems

## Authority matrix: what must be stable

| Operation | Durable identity and permission | Required positive witness |
|---|---|---|
| Local torrent payload download | Explicit selected Place + exact torrent paths/infohash; regular-file semantics and path containment | Real peer bytes, independent file readback, stop/restart/seed |
| Publish immutable private photo | Content digest/length, app-created staging identity, final exclusive create, Room membership | Verified bytes + Room rows across restart |
| SAF Create new/Keep both | Actual DocumentProvider authority, fresh write grant and returned URI; no silent returned-existing URI overwrite | Different created object, exact bytes and original preservation |
| SAF reviewed Replace | Explicit selected existing target, fresh target identity/capability, collision consent | Target readback; partial/uncertain policy if provider cannot guarantee atomicity |
| Remote transfer/job or file operation | Exact endpoint/server instance/account/session + infohash/item ID + operation | Per-item read-only reconciliation and post-state; byte verification when claiming transfer |
| OS wallpaper/widget action | Preview ID vs actual wallpaper-command pending/ack/unknown; Android host state | Observed OS/Room/widget action, not an on-screen click |
| Installed APK upgrade | Stable certificate identity, applicationId, installed schema and private files | adb install -r without uninstall; every real populated row and byte retained |

## Files and persistence

SAF URI is a **provider capability**, not a POSIX path. Do not infer local paths from content URI, trust blind provider write modes, assume atomic rename, or persist a grant that Android never issued. A picker opening is not successful selection. Validate returned authority/membership, scope, URI access and exact item. Read-only operations cannot silently become upload/delete/rename.

For private originals, staged bytes must be checked before Room admission. File plus database is a two-resource operation: list process-death windows before/during creation, after force, after readback, before/after DB transaction and during recovery. CREATE_NEW prevents existing-name replacement, but does not ensure a partially streamed file is never visible. Recover by proven provenance/ownership and quarantine, never by guessing from filename alone. Preserve unknown orphans, registered originals and complete sources.

For archive extraction: validate traversal, directory ancestry, duplicates, large expansion, disk failures, special files, target identity and collision policy. Source ZIP, seeding payload and unrelated sibling files remain intact. Split provider-specific supported actions rather than inventing one universal SAF transaction engine.

Room migrations must use actual generated schemas and populated historical fixtures, verify membership/favorites/unknown jobs, and never use destructive fallback just to make a CI upgrade green. Signer continuity is separate from schema correctness.

## Network and lifecycle

Freeze authenticated principal **exactly**, not trimmed/formatted display alias; freeze endpoint, scheme, certificate/pin, path and session at action start. Public RPC replies do not authenticate invented supplied credentials. Do not assume a 2xx or server-wide byte rate establishes a per-item effect. Require matching hash/ID and observed readback.

Use explicit state: PROPOSED -> REVIEWED -> AUTHORIZED -> STARTED -> ACKNOWLEDGED -> VERIFIED, or FAILED/CANCELLED/UNCERTAIN. Non-idempotent writes after 401, timeout or connection loss may have happened; an uncertain record stays uncertain until independent read-only reconciliation or an explicit safe new user decision. Prevent duplicated writes and unsafe auto cleanup.

On Android, test actual foreground service/WorkManager constraints for the app's target/API, Doze, network transition, permissions revoked after grant, background restrictions, process death and reboot. Correct cancellation requires ownership token/Job/transport cancellation plus post-return checks for blocking APIs; hiding progress is insufficient. Test a user-visible recovery route for abandoned work.

## UI state

One selected source and one command pipeline should serve Single, Dual, Share, drag, widget and picker pathways where the underlying operation is the same. Freeze scope before asynchronous callbacks; navigation and filter changes cannot retarget an already approved mutation. State owners must be explicit; unknown/offline/partial status remains truthful across restoration.
