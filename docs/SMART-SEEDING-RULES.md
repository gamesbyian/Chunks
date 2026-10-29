# Smart Seeding Rules and Seeding Groups

> **Authority:** binding feature-level product/interaction contract for ratio, duration and upload speed policy. [PRODUCT](PRODUCT.md) owns the user promise; [UX](UX.md) owns common screen behavior; [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) owns scope. This is a planned feature, not proof of implementation.

## Intent and user model

A user can express ordinary intentions without learning rTorrent ratio group syntax: "All torrents from IPTorrents seed indefinitely; everything else seeds to ratio 1.00 and uploads at no more than 24 KiB/s." Settings are understandable, previewable and accessible at Add, as well as automatic intake and transfer details. A **Seeding Group** is a named Chunks policy preset plus optional matching rules, not a Collection (semantic organization), Place (filesystem location), tracker category or rTorrent-internal group. Groups never move or rename backing data.

Every configured environment (phone or named rTorrent profile) offers the same vocabulary and flow. Policy definitions can be reusable across environments, but assignment and execution are **per exact environment** with capability checks and independently scoped limits. A user may opt into sharing a template with several environments after preview, not silently.

## Policy semantics

- **Ratio goal** is uploaded bytes divided by downloaded bytes using explicitly identified engine counters. Choose never stop due to ratio, stop once ratio reaches N (e.g., 1.00), or inherit an environment default. Show current ratio and attribution. Counter resets, imported partially completed data, differing engines, zero denominators and unknown historical upload must not masquerade as verified ratio compliance.
- **Seeding duration** is independent: unlimited, at least/at most an explicit duration, or until a date where supported. Define start as completed/download-to-seed transition and preserve timestamp provenance across restarts when available.
- **Upload speed limit** is independent: unlimited or a maximum expressed in readable KiB/s or MiB/s, with optional advanced units. A speed cap NEVER means stop at a ratio. Clarify whether 24 kbps (kilobits/s, ~3 kB/s) or 24 kB/s (~192 kbps) is intended; present units explicitly. Do not automatically reinterpret or silently round.
- **Conditions combine visibly:** "stop when any condition is met", "stop only when all conditions are met", or "seed for at least X, then stop at ratio Y" with accessible natural-language preview. Duration/ratio combinations may be logically impossible or unachievable; warn and keep a predictable fallback. Never equate completed download with stop-sharing permission.
- **What stop means:** stop or pause seeding job only, without removing the job or deleting files. Distinguish end-of-sharing stop from queue/scheduler pauses, tracker errors and disconnected remote clients. Don't promise upload traffic if no peers request pieces.
- **Rate limit granularity:** transfer-level and genuine engine-supported shared-group budget must be distinct; "24 KiB/s per transfer" differs from "24 KiB/s total across group". Don't implement a shared group cap as independent per-torrent caps without explaining the difference.
- **Defaults and overrides:** an environment's default seeding group applies in absence of a matching rule. A transfer can have one effective seeding policy, derived from deterministic matching or explicit override. Individual settings can override group fields only with clear inherited/custom indications. Manual overrides survive later tracker updates and background rescans unless the user expressly chooses to resume automation.
- **Safe defaults:** do not delete files or auto-remove jobs on ratio attainment; no silent policy changes on existing transfers. Consider private tracker rules and minimum seed times: clearly warn where a selected policy might conflict with tracker obligations, but never infer or promise satisfaction of site requirements.

## Matching and rule engine

- Rules support tracker hostname/domain and optional verified announce URL patterns, source/import channel, private metadata flag, tags selected by the user, and environment/profile; expert matching can grow later. Tracker identity is established from parsed tracker metadata and normalized hosts, never guessed from torrent name. Avoid generic substring matching (e.g., deceptive hosts containing a known brand). Support multiple trackers and magnet tracker updates; show every matched criterion and reason.
- A user can name a rule "IPTorrents" while configuring its **exact match conditions**; do not presume that a human label alone authenticates tracker identity. Do not expose full private announce URLs, passkeys, credentials, or infohashes in logs, analytics, summaries or diagnostics.
- Ordering: explicit per-transfer manual override > highest-priority matching rule > per-environment default. Tied priority is settled deterministically by saved rule order, with conflicting rules flagged and previewed. Catch-all default always exists. Matching is local/offline and never requires a tracker API, account, scraping, or third-party discovery.
- User may choose **assign suggested group**, **automatically apply verified matches**, or **always ask** in interactive Add; bulk imports can select automatic assignment separately. Unknown identity is "pending evaluation": use a safe, disclosed fallback and re-evaluate after metadata changes; don't secretly switch indefinite seeding to ratio-limited behavior for an already active transfer. Provide a rule-match preview, evaluation history and "Why this group?".
- Re-evaluation policy is configurable (add only, until complete, ongoing), but manual overrides are sticky. Any subsequent automatic change that reduces an active sharing commitment must be disclosed according to user consent and policy; no silent rule-based mass stop.
- Matching precedence must avoid treating public trackers incidentally embedded in a private torrent as authoritative origin; ambiguity gets a review-required result when safe automated classification is impossible.

## Add and intake: equal prominence

On every Add flow (magnet, .torrent open/share, clipboard, file picker, create/share wizard, Android resolver, cross-engine Copy/Move Transfer), show **Destination environment**, **Save to**, **Seeding group** and **Start after Add** in the primary review area. A default group is preselected; "Auto-detect" is one tap; manual group selection and "Customize this transfer" are adjacent options. Preview the resolved behavior in plain language. Advanced control of individual limits is one step away; no forced group-creation wizard to add an ordinary torrent.

Magnets often lack complete metadata: label matching "checking trackers" or "needs metadata"; preserve explicit Add intent, only activate verified/safe policy, and revisit matching when data arrives. Applying a policy should be staged before auto-start when supported: exact environment/hash assignment, resolved destination and capability validation, successful add readback, policy verification, then start. If guaranteed pre-start enforcement is unavailable, hold stopped and offer a clear retry/explicit continue-without-policy choice. Unknown command results require read-only reconciliation, never blind retries.

## Unattended and automatic intake

Provide an **Apply Smart Seeding Rules to automatically added transfers** opt-in, independent from **auto-add** and **auto-start** toggles. Explicitly explain that Chunks must be able to evaluate and enforce policy for unattended operation. For each auto-add source, record actual environment, source, matched rule or fallback, effective limits, verification state and decision to start/hold. When matching is pending or policy cannot be enforced, use a user-selected safe fallback: hold stopped and notify (recommended), or start with an explicitly accepted documented fallback; never silently start an unlimited seed because the intended cap failed. Allow dry-run on sample torrents and a "test rules" preview before enabling unattended automation.

Changes to rules default to future transfers only. An explicit "Apply to existing transfers" batch action must show matching jobs, environments, old/new effective policies, possible seeding interruptions and per-profile capabilities before consent. Provide Undo/reversal where engine state allows, recording partial/uncertain results truthfully.

## Rule names and explicit environment scope

- Each new rule receives an **automatically generated, human-friendly, editable name**, derived from its intent, such as "IPTorrents · Seed forever", "Other trackers · Ratio 1.00", or "Low-speed sharing · 24 KiB/s". The name is a presentation convenience, **never** a condition used to match torrents. Show a live suggested name while authoring, allow a one-tap rename, and let users keep or restore the generated suggestion. Once manually edited, later rule-condition changes must not silently overwrite the custom name. Duplicate names are permitted if identifiable via scope and details; warn if ambiguity would be confusing. Avoid including tracker passkeys, full private announce URLs, secrets or sensitive paths in generated names. If detection is uncertain, use neutral, accurate wording instead of inventing a tracker identity.
- Every rule list row, editor, preview and confirmation shows **Applies to** with named environments: **On phone**, each individually named rTorrent profile, and an explicit **All selected environments** summary when multiple are chosen. An accessible multi-select lets users choose one, several, or all configured environments, with a clear default at creation. Never let a rule silently affect every profile just because it is stored at the application level. Show the selected scope even for a single enabled environment where future profile additions could change expectations.
- "All current environments" and "All current and future environments" are different choices. New remote profiles must not inherit potentially disruptive rules unless the user expressly opts into future-environment inclusion. Disabling a profile preserves its selected membership without remotely mutating it, and the editor identifies it as unavailable/disabled. Deleting a profile asks whether to remove its orphaned rule membership while retaining rules used elsewhere.
- The group/rule editor shows a read-only **effective policy per selected environment** preview, including which engine features are verified, unsupported, pending, inherited or overridden. Selection changes never instantly mutate live transfers; **Save rule** updates its future-add definition, while **Apply to existing transfers** is a separate scoped, reviewed operation. A shared rule may therefore be applicable in one environment but not enforceable in another; show that distinction and provide per-environment exclusions instead of silently claiming parity.
- Persist a stable rule ID independent of the editable display name. Environment membership is keyed by immutable environment/profile IDs, never by user-editable profile names. Renaming a rule or server never changes rule precedence, past assignment, matching or existing transfer identity.
- In Add, display the effective rule and group only for the currently selected destination environment, recomputing the matching preview if the user changes environment. If the manually selected rule does not apply to the newly selected environment, visibly request a compatible selection or auto-detection rather than carrying forward an invalid policy.

**Policy boundary:** Seeding-group ratio, duration and upload cap meaning/precedence belong to this contract. Optional **download** scheduling, network/battery prerequisites, queue slots, stall/watchdog behavior, quotas and source retrieval belong to [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md). The shared Add review combines both summaries for the exact selected environment without silently applying either to unrelated profiles.

## Transfer details and control surfaces

Show a compact group name and effective stop/rate summary in transfer details with "Why this rule?", quick override, reassign, pause, and detailed metrics. Transfer lists can filter by seeding group, source tracker, ratio goal, paused-by-rule status and compliance/enforcement state. Batch assignment follows exact-hash and environment scope safety. Settings use a single visual rule builder (When / Then / Exceptions) with examples, a natural-language summary and a nontechnical mode; experts may inspect full criteria and order.

UI distinguishes rule **selected**, **applied to Chunks**, **acknowledged by engine**, **verified active** and **unsupported/offline/uncertain**. Never claim a stopped transfer is still actively seeding, or that a rule is enforced merely because it was saved.

## Runtime and capabilities

- Local libtorrent4j and remote rTorrent must share a policy/rule domain model, UI, serialization and matching semantics; adapters independently negotiate supported per-torrent speed, ratio/scheduler behavior, persistence and readback.
- Never assume rTorrent supports arbitrary native ratio groups, inherited settings, remote scripts, writable user configuration or per-torrent policy persistence. Where native engine supports enforcement, prefer verified native state. Chunks-managed enforcement requires an explicitly disclosed always-running/periodically connected controller; no promise that a phone app can enforce remote stopping when offline or killed.
- If per-torrent upload caps or seeding duration cannot be applied, don't claim equivalent group caps or an enforceable stop. Explain unsupported properties in Add before auto-start. Existing server rules may already act: detect where feasible, avoid overwriting without consent, and show unresolved interaction/precedence.
- Policy data must be saved as Chunks-only metadata keyed by stable environment identity and verified torrent infohash, with resilient migration/schema versions. Tracker passkeys never enter nonsecret policy serialization. No external service, central account or remote companion required for basic local rule matching.
- When copying a transfer job between engines, display the target's default or proposed inherited policy, apply only on target after review, and never assume the source's group or counters represent equivalent progress. Moving a job leaves source data intact under the separate job-handoff contract.

## Acceptance criteria

1. Set up "IPTorrents: unlimited seeding" and "Everything else: stop at 1.00, upload max 24 KiB/s", with explicit differentiation between kbit/s and KiB/s; verify expected match and precedence on fixture metadata.
2. All interactive Add routes prominently show destination, directory, seeding group, and start-on-add with no unnecessary advanced-settings navigation.
3. Trackerless, partial and multi-tracker magnets show pending/ambiguous states; no invented provenance or leaked passkeys.
4. Auto-add/auto-start with opt-in rules applies only verified supported policy, otherwise follows configured stop/notify or explicit fallback.
5. Phone-only, remote-only, and dual-mode with two named servers use identical UX but independently enforce and identify scope; disconnect does not imply remote enforcement continues.
6. Per-transfer caps and aggregate caps are not confused; ratio stop, time stop, rule-driven stop, and global/manual pause show separate reasons.
7. Changes to saved rules do not retroactively stop old jobs without an explicit reviewed batch operation; manual overrides survive rematching.
8. Unit, property, lifecycle, controller-offline, duplicate/uncertain-command, policy collision, persistence/process-death, accessibility and 200% type tests; test on approved public torrent fixtures only.
9. Rules are clearly selectable per one, several or all named environments; adding a new profile does not unexpectedly include it; disabled/offline scope and capability differences are explicit. Generated descriptive names are editable and stable, with a separate immutable rule identity and no secret leakage.
10. Native engine and Seedhost remote capabilities are reported from evidence, not from aspirational UI. Maintain SPEC-COVERAGE and QUALITY-LEDGER separately when implementation/test evidence exists.

## Implementation note: domain model (2026-10-29)

`app/src/main/java/io/github/gamesbyian/chunks/seeding/SeedingRules.kt` holds the shared, offline rule domain:
- `RateLimit.parse` with exact units, showing bits beside bytes;
- `SeedingPolicy`, with independent ratio, duration and cap, ANY/ALL combination, contradiction warnings, and a plain-language description that says stopping never removes or deletes;
- `TrackerHosts`, matching whole labels and never keeping credentials or paths;
- `SeedingRule`, with a stable ID, generated names that follow conditions until the user renames, and explicit environment membership where future environments are opt-in;
- `SeedingRuleEngine.resolve`.

Evidence: JVM tests, including acceptance case 1. `SeedingRuleStore` persists rules and per-environment defaults as versioned Chunks-only JSON with domains and numbers only: no tracker URLs, passkeys or infohashes. A URL-shaped domain, duplicate ID, unknown version or corruption never loads, and an unreadable record is reported and never silently overwritten (Robolectric tests). Not yet: Add/detail UI, or local/remote enforcement adapters with readback.

