# Human task outcomes and continuity (MF02 + MF04)

> **Authority:** planned binding feature contract for outcome receipts (MF02) and resumable human intentions (MF04). [PRODUCT](PRODUCT.md) owns promises, [UX](UX.md) owns navigation, [ARCHITECTURE](ARCHITECTURE.md) owns domain orchestration, and [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) owns environment scope. **No implementation is claimed.**

## Purpose and boundaries

A **Task** is a person-recognizable, optional goal associated with one or more operations. A **Transfer** is an engine/profile-scoped BitTorrent job. An **Operation** is a bounded command or file operation with its own verifiable effect. A **Task receipt** summarizes progress toward explicitly chosen end conditions. The same transfer can belong to several tasks; a task can span multiple transfers or external steps. Task membership, display title and checkpoints are device-local by default, not data transmitted to trackers or remote daemons.

**Ordinary torrent use stays frictionless.** Adding a magnet or .torrent does not require the user to name a goal, configure a project, or interact with a wizard. Chunks can offer a lightweight task inferred from the explicit action (“Download on phone”, “Copy transfer to remote”, “Retrieve remote files”), but a suggested task is not a fact about intent until accepted; the user can ignore, dismiss or disable such suggestions. Existing transfer status remains visible independently of Task.

**MF02** tracks whether the *specified* end condition has been observed; **MF04** preserves selected human context for later continuation. The model must never infer that download completion implies import, playback, recipient delivery, privacy, or payload relocation. These separate outcomes require independent witnesses. A user may declare a subjective goal done; record this as **Marked done by user**, never **Verified by Chunks**.

## Task identity and scope

- Stable opaque local Task ID, versioned schema, creation/updated timestamps and user-editable title. A Task comprises one or more explicit **milestones**, optional notes/next step, zero or more associated exact transfer identities (engine/profile ID + verified infohash), optional verified authorized Place/file references, and scoped operation IDs.
- Exact environment and storage target per milestone; task-level scope is a *set of explicitly named environments*, not a mutable global “current environment”. Reconnecting, disabling an environment, switching filters, or changing remote profiles must not retarget or execute a task. Job copies across environments form separate transfer identities and data acquisitions, not implicit payload moves.
- Allowed milestone kinds: **Job added**, **Policy enforced**, **Torrent/wanted bytes verified**, **File ready for intended use** (only evidence Chunks can actually check), **Remote payload retrieved to an authorized Place**, **Published to recipient channel**, **Recipient delivery confirmed** (only when explicitly supported and consented), **Manual next step**, or **User-defined outcome**. Initially only offer kinds for which the selected environment has a valid evidence source; unsupported milestones may be noted as **Needs external confirmation**. Do not silently record remote library imports or successful playback on behalf of another app.
- A Task never becomes a remote torrent job or persistent remote authority. Do not store raw passkeys, credentials, sensitive URLs, unredacted private tracker hosts or media paths in notifications, debug exports, shortcut labels or background logs. Protect Task detail with existing transfer privacy lock and environment restrictions. Export/sharing requires deliberate scope review and redaction.

## Milestone state, evidence and receipt contract

Milestone state is **Not started**, **In progress**, **Waiting for input**, **Interrupted**, **Uncertain**, **Verified complete**, **Marked done by user**, **Not applicable**, or **Failed**; keep last evidence and transition history separately. Task aggregate is **Complete** only if all required milestones are **Verified complete** or explicitly **Marked done by user** with the qualification visible. Optional milestones cannot block completion. A failure does not automatically mean the task is impossible; task may be partially complete with a safe next step.

A typed receipt records task/milestone ID, declared target/criterion, exact environment, transfer/job/file identity when applicable, source kind (local engine, authenticated RPC, authorized filesystem read, manual declaration, external app acknowledgement), requested/acknowledged/observed timestamps, observed predicate, readback freshness, capability/scope and provenance, redaction class, and **certainty**. Distinguish **Requested**, **Acknowledged**, **Observed but unverified**, **Verified**, **Failed**, **Unknown because stale/disconnected**, and **User attested**. Never manufacture success from stale cache, command dispatch, an unavailable API, or an unverified filepath.

- **Engine completion:** may verify torrent/wanted pieces under documented engine checks, not existence at another Place or player support.
- **Cross-engine job copy/move:** source and destination registrations are separate; only independently verified destination job-add can satisfy that milestone. Original data remains where it was unless a separate authorized payload operation occurs.
- **Remote retrieval:** use verified authorized filesystem protocol and destination readback/checksum where available; partial sync, path mismatch, unobservable server bytes and uncertain checksum remain incomplete/uncertain.
- **Move/rename and extraction:** must respect safe file-identity, hardlink and seeding continuity contracts; a changed label is not filesystem proof.
- **External media/library/recipient:** without a trustworthy documented acknowledgement plus any necessary content verification, show “Ready for you to check” or “Marked done by you,” not “Verified imported/played/delivered.”
- **Privacy/network claims:** an app status icon cannot prove absence of external exposure; state exact test boundaries.

## MF04: checkpoint and return

- Save a compact **Task checkpoint** on meaningful user actions: selected goal, last viewed milestone, pending decision (as non-executable draft), what changed, next safe action, and evidence revision. Persist atomically and recover after process death, device restart and remote disconnect. Never persist a queued destructive command as automatically resumable action.
- On return, reconcile read-only against the selected environment, permission and capability before offering **Continue**. Show **What you were doing**, **What changed since**, **What still needs you**, and **Continue / Review / Dismiss**. If state is stale, disconnected, disabled, or credential-locked, retain the checkpoint and provide reconnect/permission action instead of claiming a loss or re-running writes.
- User may **Pause task tracking**, **Dismiss**, **Archive**, **Delete task notes**, or **Mark done**, each separate from pause/remove/delete **transfer job or payload**. Removing a task never deletes, stops or moves bytes or torrent jobs. Disabling the optional Task surfaces does not discard transfers, credentials or underlying operations.
- Manual notes are private, editable, optional and can be removed. Do not infer intent from media metadata, auto-add behavior or private tracker identity without consent.
- Automatic intake is allowed to remain task-free. If optional automatic receipt grouping is enabled, it must not create unbounded noisy items, trigger provider access, or imply user acknowledgement.

## UX states and acceptance

Use shared **Tasks** / **Continue** entry only when worthwhile and enabled; otherwise quiet contextual **View outcome** / **Continue task** affordances from transfer details and the existing activity/operation view. Do not displace transfer list controls or primary Add. Single-env settings avoid gratuitous environment pickers; combined mode always shows precise profile indicators on task card, milestone and mutating affordance.

1. An ordinary phone Add without task opt-in behaves exactly as before; optional lightweight receipt is passive.
2. Remote Add verifies registration but explicitly does **not** claim payload on phone. When a user explicitly requests retrieval, its milestone remains incomplete until separate destination data verification.
3. A task with a verified torrent and an unobserved library import remains **Partially complete / Needs confirmation**.
4. Closing app during pending remote mutation and returning does not repeat command; show **Uncertain**, perform read-only reconciliation and preserve task draft and last safe action.
5. Switching from rTorrent profile A to profile B never changes the task's A-bound target or executes in B.
6. Manually marking playback successful produces **Marked done by user**, not a fabricated app proof.
7. Removing/archiving a task leaves all jobs, files and tracker activity intact; task privacy lock and redacted export hold.
8. Unit/property tests cover stable identity, monotonic verified receipts until explicit invalidation, clock/staleness, concurrent edits, duplicate events, schema upgrade and cancellation; Android tests cover process death, rotation, offline and TalkBack/200% text.

## Non-goals and capability limits

Not a full workflow scripting engine, automatic Plex/Sonarr integration, cross-device task sync, telemetry, proof that a recipient actually consumed a file, or ability to watch other Android apps without explicit integration. Neither Task tracking nor “Continue” authorizes a new remote file action. Exact current implementation coverage remains **planned** until separately evidenced.
