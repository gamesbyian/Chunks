# External destination delivery for local torrents

**Status:** owner-approved planned secondary workflow; no claim of implemented automatic cloud/FTP delivery. **Decision:** D-EXTERNAL-DELIVERY-20261030. Authority: [PRODUCT](PRODUCT.md), [UX](UX.md), [ARCHITECTURE](ARCHITECTURE.md), [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md), [WORK-QUEUE](WORK-QUEUE.md).

## Purpose and scope

A person running the **phone-local** torrent engine MAY choose a final destination outside phone-native download storage, such as an authorized Google Drive folder or an FTP/FTPS/SFTP server. Chunks SHOULD make the ordinary download-then-deliver journey feel seamless while preserving explicit, independently verifiable lifecycle states. The torrent execution environment stays **On phone**; the final destination is an independent **Place**. This is a secondary workflow, not the default destination or a reason to delay native download, rTorrent, safety or unified-UI release gates.

**First implementation:** stage payload bytes in a proven owned phone download location; after verified torrent completion and stable source readback, publish to the selected independent Place using a supported adapter. Do not assume that a SAF cloud provider permits direct torrent random writes or that remote RPC credentials imply filesystem access. Follow-up support for progressive per-file export may be considered only with proof of retention and full-piece dependencies. Direct cloud-backed torrent storage or cloud-backed seeding is **out of scope** unless separately authorized.

## Terminology and identities

- **Transfer job:** exact local environment ID + infohash, independent of data delivery.
- **Source:** owned, verified local torrent payload paths; may still be needed by the running client to seed.
- **Destination Place:** explicit provider/connection ID, stable authorized root and child path, tenant, permission generation and relevant capabilities. An FTP endpoint is a separately authenticated storage connection, not an rTorrent profile.
- **Delivery:** independent copy operation and journal keyed to source identity, destination identity, file manifest and revision; never the existing job-only Copy/Move Transfer command.
- **States:** torrent downloading, torrent complete, source stable/verified, delivery pending/in progress, destination verified, awaiting action, failed recoverably, verification uncertain, and cancelled. Torrent completion never implies destination delivery.

## User journey and UI states

1. **Add:** show the current per-environment default destination with optional **Change**. A secondary **Deliver files to…** action allows selection of a configured/authorized Drive or FTP-family Place; show the phone staging location and estimated capacity requirements in the review. Ordinary Add remains one step with no compulsory picker, account connection or new top-level tab.
2. **Preflight:** validate chosen destination capability and write permission, connection security, available local staging capacity and known destination collision policy. If a provider cannot be verified, offer a clear safe fallback (keep local/choose another destination) rather than promise direct delivery. Never silently downgrade FTPS/SFTP to unencrypted FTP.
3. **Transfer:** download and verify locally using the existing protected native path rules. Display **Downloaded** separately from **Delivered to [Place]**. Seed according to the existing named rule while the source is retained; never advertise that cloud storage itself is seeding.
4. **Delivery:** after verified stable source readback, copy through an adapter with resumable transport if supported; use a uniquely owned temporary destination object/name or provider-supported staging. Do not expose a partially written final file as verified. Report independent byte and file progress and clear user-action requirements.
5. **Completion:** verify destination length plus cryptographic content proof (hash or equivalent complete re-read) appropriate to provider capability; upload acknowledgement alone is insufficient. On unsupported independent readback, show **Delivered; verification unavailable** and retain source until a separately explicit user decision, never silently assert verification.
6. **Cleanup:** default **retain local source and existing seeding behavior**. Offer optional user-controlled **Stop seeding and remove local staged data after verified delivery**, with a separate explicit consent and native ownership/path revalidation. Never automatically remove torrent registrations, shared content, unrelated files, archives, or the only proven full copy. If local data is removed, seeding stops; cloud-backed seeding is not implied.
7. **Failure and restart:** offline, revoked Drive grant, expired login, disconnected FTP, full quota, conflict, cancelled upload and Android process death must preserve the journal and source. Reobserve destination state before resuming uncertain operations; do not blindly restart an upload or overwrite existing files. Offer **Retry delivery**, **Change destination**, **Keep files on phone** and **Cancel delivery**; cancellation leaves torrent and owned source intact.

## Settings and defaults

External delivery is opt-in per transfer and off by default. Remember recent Places only with the user's consent and do not migrate the local engine's default Place to cloud. No auto-delete default. Clearly separate delivery retry/connection preferences from seeding group policies. Credentials use secure scoped storage; no password/passkey in filenames, logs, analytics, error text or backup exports. FTP without transport security requires an exceptional explicit per-connection risk review if supported at all.

## Capability/security and architecture

Build on existing independent SAF publication and storage adapters, not on the libtorrent backing-store write path. Adapter contracts MUST declare read/write, resumability, atomic publish, readback/hash verification, size/quota visibility and permission/credential freshness individually; capability labels are not proof of success. A Google Drive SAF document provider may behave differently from the Drive REST API. Provider-specific API work requires its own permission/privacy review; never treat successful SAF picker selection as random-access write support.

Use a durable versioned DeliveryPlan/DeliveryJournal with exact file path mapping, source immutable manifest, current verified offsets, remote temp identifier/ETag where supplied, idempotency and crash recovery, and no secrets. Resolve destination collisions with explicit fail/rename/verified-reuse decisions, never silent replacement. Concurrency and IO budgets must avoid starvation of active torrent/Android foreground service work. No deletion on partial completion, uncertain command, unverified final copy or in-flight seeding references. Privacy lock and offline state must protect destination details and credentials.

## Acceptance matrix

| Scenario | Required proof |
| --- | --- |
| Normal multi-file local torrent → SAF Drive-compatible provider | Download/hash proof, stable native readback, complete independent publication and final reread/checksum |
| FTP/FTPS/SFTP destination | Correct isolated connection authority, secure transport policy, remote directory scope and upload/readback validation |
| Disconnected network, expired credential, provider revocation, quota full | Durable pending/uncertain state; exact-place reauthorization and resume without data loss |
| Android kill/restart/reboot mid-upload | Journal recovery and destination reconciliation; no blind overwrite or lost source |
| Pre-existing destination, path traversal, links, duplicate names | Explicit collision handling, no foreign byte mutation |
| Upload success but missing/truncated/corrupt remote content | Never mark verified; retain local copy; actionable retry |
| User cancels delivery or changes destination | No torrent/job mutation; no unauthorized remote deletion |
| Optional source cleanup | Separate consent, source ownership recheck, verified destination, seeding stop and no shared-file loss |
| 200% font, TalkBack, offline, simultaneous local+remote environments | Clear destination vs execution labels, distinct statuses, accessible controls |
| Capability unavailable | Honest Keep local/Choose another Place fallback; no pretend cloud-backed torrent storage |

**Evidence:** all new states and behaviors are **planned**, and need JVM adapter tests, fault injection, Android API29/API35 SAF checks, real provider/host interoperability, and physical handset recovery/accessibility proof before being called verified.

## Dependencies and related artifacts

Preconditions: native collision and owned-file safety; reliable Add/Start/Stop/resume; verified stable local source; existing independent SAF publication provenance; clear local/remote environment identity; remote credential security; journal/retry infrastructure. These outrank optional provider expansion. Related: [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md), [EXISTING-SERVICE-INTEGRATIONS](EXISTING-SERVICE-INTEGRATIONS.md), [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md), [SPEC-COVERAGE](SPEC-COVERAGE.md), [WORK-QUEUE](WORK-QUEUE.md).
