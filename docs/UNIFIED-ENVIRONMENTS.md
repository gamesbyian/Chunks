# Unified local and remote experience (owner contract, 2026-10-28)

**Normative principle:** Chunks is one torrent manager with interchangeable local-phone and rTorrent-backed environments. User-facing workflows, layout, terminology, information hierarchy, navigation and control placement MUST be shared wherever the verified capabilities allow. A divergence requires a concrete engine/server/security/storage capability reason; different implementation technologies alone are not an excuse to create separate applications.

## Environment colours and combined Transfers views (owner contract, 2026-10-29)

Each stable local or remote environment identity MUST own an automatically assigned, user-editable colour chosen from Chunks' existing application palette, excluding black and white. Automatic assignment prefers unused, readily distinguishable colours among currently configured environments; user choice may deliberately reuse a colour. Renaming, reordering, disabling, reconnecting or restoring the same environment retains its colour; deletion/re-creation with a new identity does not imply reuse of the former identity. Provide palette selection and Restore automatic colour in environment settings. Colour is applied consistently to each occurrence of an environment's display name in rules, **Applies to** summaries, transfer rows/details, Add destination, settings scopes, confirmations, filters, notifications and other applicable surfaces. Names/labels and icons remain readable independently of colour, including TalkBack, high contrast and colour-vision differences. Use palette-appropriate accessible foreground tokens or non-text indicators when raw palette hues fail contrast; never rely on colour alone or recolour semantic warning/error/progress status indiscriminately.

The main Transfers view MUST support a combined scope of **any nonempty subset of enabled environments**, including All, phone plus selected remotes, multiple remotes without phone, and a single environment. The environment picker is a multi-select view filter with an efficient single-environment choice, select-all and clear/reset controls, not a one-at-a-time engine switcher only. Remote-only and phone-only users receive the same core experience without redundant controls. The chosen subset is a **view scope**, never an Add destination, rule target, bulk command target or execution identity by inference. Persist named subset and list sort/filter/search/view density through navigation and restart; newly configured profiles are not silently included in a custom saved subset (the explicit dynamic All choice includes newly enabled profiles). If a profile is disabled, locked or removed, visibly resolve its membership rather than redirecting operations.

Combined sorting/filtering/search are calculated over **one logical union** of transfer jobs from the selected environments, not separately sorted per-server lists concatenated together. Sorting supports the shared transfer fields, documented null/unknown ordering and deterministic tie-breaking by stable (environment ID, torrent identity), and remains stable as remote snapshots refresh. A job is keyed by its immutable environment identity plus verified torrent identity; identical hashes on two engines are separate entries, and no cross-profile deduplication is implicit. Reconciliation/pagination must not suggest an exact global rank from incomplete or stale datasets; show loading, partial results, offline status, last-known snapshots and unavailable counts plainly. Aggregate counts, progress and speeds include only eligible selected snapshots, with staleness and missing data made explicit rather than silently misrepresented as live totals.

Every transfer row MUST retain a compact, consistent environment-colour identifier (for example, slim coloured leading rail plus coloured source label), including combined lists, lists of search results, Collections and selection summaries; retain the textual origin on detail screens and action previews. Environment colour must not replace semantic status, urgency or accessibility signals. Cross-profile multi-select is allowed; each batch action shows its target environments, verifies per-environment capabilities and scopes, separates individual results and asks for appropriately explicit approval for destructive/cross-engine effects. Filter changes must never redirect actions or silently preserve hidden selected jobs without a clear selection count/review. Add continues using its own explicit/default destination independent of which environments are visible in Transfers.



**Universal sort reversal:** Every user-selectable transfer sort criterion MUST support both ascending and descending directions, in grouped and ungrouped views. This includes names, dates/age, file size, progress, speeds/activity, and future sort keys. One consistent direction control reverses the chosen comparator across all visible environments; grouping on/off retains direction. Date/age labels must make oldest/newest semantics explicit. If a proposed criterion cannot be reversed meaningfully, it must not be presented as a standard sort option. Tests must cover both directions for each supported key, including ties, unknowns and partially stale sources.

### Optional environment grouping and common secondary sort

Combined Transfers SHALL offer a **Group by environment** presentation toggle, independent from which environments are included. When off, the list remains one globally sorted union. When on, show one clearly labelled, environment-coloured section per selected environment containing its own transfers. Apply **one shared secondary sort criterion and direction** (name, age/date added, size, progress, activity/speed, or any other supported transfer sort) to *every* section; do not require or introduce separate per-profile subordinate sort configuration. Expose the ordering as, for example, **Group: Environment · Sort within groups: Name A–Z**. Section order is independently predictable (selected environment order or a clear common environment-name ordering, with a stable default), and must not vary with the secondary sort. Toggling grouping preserves the chosen transfer sort and direction: it switches between global sort and per-group application of that same sort. Respect unknown values, stale remote snapshots and stable tie-breaking within each group. Group headers display the persistent environment name in its palette colour, with accessible counts/status and collapse/expand affordance where supported. Collapsing changes visibility only, never selection scope or action targeting; hidden selected items must be surfaced in selection summaries. Search/filters apply uniformly to the selected union before grouping; empty sections may be omitted with a clear indication when filtering. Remember grouping state with the view's existing preferences. Acceptance: verify two remote profiles plus phone under each supported common secondary sort and both directions; grouping on/off neither changes results, Add destination, nor mutation targets.

### Combined-view acceptance
1. With phone and two remote profiles configured, selecting any one, any pair or all three yields the exact scoped union; matching hashes remain separately identified.
2. Sorting by name, progress, activity and size uses global ordering across all selected transfer jobs; search/filter composition operates on the same union with predictable unknown-value handling.
3. In mixed online/offline/locked states, displayed totals and ordering disclose staleness or incompleteness, never imply complete live coverage, and do not silently omit an environment without notice.
4. Colours follow stable profile IDs through rename/reorder/reconnection/restart; duplicate manual selections are allowed, automatic choices avoid repeats when feasible, and black/white are absent from choices.
5. Compact coloured origin treatments remain legible on light/dark, 320×700, 412×892, 200%-text and TalkBack screens; names and statuses remain distinguishable without colour.
6. Combined batch actions cannot accidentally execute against an unselected/hidden profile, and a view-scope change cannot alter Add destination, saved rule scope or setting target.


## Configuration and first run
- Local-only is the default offer and a complete standalone path; no remote account, remote connection, seedbox, or network enrichment is required. An explicit remote-only choice is equally first class and must not force local torrent-engine setup or start the local service.
- Users may enable local only, remote only, or both; remote may include multiple independently named rTorrent profiles. Enabling, disabling and later re-enabling an environment preserves its configuration and torrent/data state unless a **separate, explicit deletion or credential-forget action** is confirmed. Disable means hidden/inactive in Chunks UI, not a destructive torrent removal or remote server shutdown. Do not stop active local downloads silently: warn of consequences and offer a separate stop/background policy.
- At least one usable environment must be offered. If none is configured, present non-destructive setup. Disconnected, unauthorized, locked, temporarily unreachable and disabled are different states. Offline remote profiles remain configured; Chunks never falls back by silently redirecting a mutation to another engine.
- Every entry route (main screen, deep link, Android Open With/Share, notification, widget, collection, search) resolves an explicit destination environment before Add or mutation. In single-environment mode, avoid gratuitous engine choosers.

## Task outcomes and explanation scope

Optional [Tasks](TASK-OUTCOMES-AND-CONTINUITY.md) attach each milestone to exact immutable local/remote profile identity; a task can span multiple explicit profiles but never follows the current view filter as an execution target. When reconnecting, reconcile only the named profile; disabling an environment preserves checkpoints without deleting or stopping its jobs. [Effective rule explanations](EFFECTIVE-POLICY-EXPLANATIONS.md) show winner, overrides, global constraints and verified/pending capability **separately per environment**. No bulk cross-profile rule application from a preview.

## Seeding rule environment scope

Smart Seeding Rules follow the same unified UI on phone and all named remote profiles. Every rule list row and rule editor exposes a visible, selectable **Applies to** summary using stable environment IDs (phone and each server profile), with one/several/all-current options and separate explicit future-environment opt-in. Generated rule names are user-editable and are not identities. A reused definition can have independently validated per-environment enforcement capabilities; a shared template does not implicitly apply to every engine. New profiles never silently inherit rule scope. When Add's destination environment changes, recompute applicable groups and explain any incompatible manually chosen group. Saving scoped settings does not silently mutate active transfers. Canonical behaviors and checks: [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md).

## Navigation and presentation parity
- One shared Transfers list/card, transfer detail, Add review, Files, priority, trackers, peers, storage, Collections, search, sorting, filtering, multi-select, activity/errors and confirmation vocabulary. Individual screens must not fork simply because the active adapter is local versus rTorrent.
- When multiple environments are enabled, a prominent compact, accessible environment selector on the main Transfers screen identifies **On phone** or the friendly rTorrent profile name. Each transfer detail has a persistent small source label/icon; destructive dialogs, batch previews, queue settings, destination pickers and status/error notices name the target environment. Never rely on colour alone. In one-environment mode, do not waste prime screen space on a useless selector; retain identity where safety benefits.
- Switching environments is immediate UI navigation, never an implicit migration, pause/resume, network mutation, credential switch, or file movement. Preserve independently per environment the list scroll position, sort, filter, search, selection (subject to identity/freshness and privacy lock), view density and relevant screen state; never accidentally act on stale selection from a previous scope. Distinguish active environment from default Add destination.
- A combined/all-environments view is **required**, supporting any selected subset and globally sorted/filtered union; scope-aware rows and safe cross-environment batches follow the binding contract above.

## Advanced control and integration scope

Use the same advanced Add, queue, network, diagnostics and settings flows in phone-only, remote-only and combined modes. [Intake and queue policies](INTAKE-QUEUE-AND-POLICIES.md) must distinguish server-native schedules from phone-connected automation; [protocol and privacy controls](FILES-PROTOCOL-AND-DIAGNOSTICS.md) must distinguish local interface/VPN from remote peer ports; [remote integrations and preferences](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) must distinguish rTorrent RPC from separately authorized file/privileged services. Per-profile capabilities, readback status, selected environment and offline limits are explicit in every advanced action; no silent fan-out or accidental inheritance by newly added remote profiles.

## Settings and scope
- Classify each setting before implementation as **app-global**, **environment-specific** (phone or exact server profile), or **transfer-specific**. Show scope beside the setting or under an unambiguous scope header. Never imply a profile-scoped rate limit, download destination, privacy lock, queue behavior, or credentials applies across engines.
- A shared setting may optionally offer **Apply to all supported environments** only as explicit opt-in with preview of each target and unsupported/failed results. A partial failure cannot be reported as universal success. Persist independent overrides and distinguish Chunks-side presentation preferences from settings actually enforced by a torrent engine.
- Defaults and recent Places belong to their exact environment; per-transfer changes do not silently update defaults. All destructive and irreversible actions include current target identities and refreshed capability/authority checks.

## Discovery and secondary infrastructure navigation

The integrated [Discover experience](INTAKE-QUEUE-AND-POLICIES.md) is a **Chunks-wide opt-in interface** with an immediate global off switch; any discovery-based Add still requires choosing an exact phone/remote environment, with per-environment limits, Places and seeding rules previewed. Disabled Discovery stops **discovery-source** polling and discovery-triggered unattended Add independently of which environments are connected, but never stops existing torrents or manual Add. On/Off is not silently overridden by a newly added remote profile or an imported settings file.

[Server tools](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) are **remote-profile-specific and secondary** under Servers → selected remote → Server tools. No privileged admin actions or dashboard tiles become first-class Transfers/Add/Details controls; remote-only mode still emphasizes torrent use. Local-only mode has no Server tools screen. Multiple remote profiles never inherit one another's admin credentials/permissions, and a disconnected/unverified profile cannot present an optimistic management action.

## Capability differences
- Capabilities are negotiated per engine, server profile, authenticated principal, version/build, storage permission and snapshot freshness, not hardcoded 'remote vs local' assumptions.
- Common supported operations use identical surface and semantics. Unsupported/unknown commands are disabled or replaced by a truthful explanation and safe alternative, never optimistic simulation. Hidden controls are appropriate only for genuinely irrelevant concepts. Retain the placement/context when useful so users can find the expected operation.
- A transfer is always identified by (environment identity, verified infohash identity); same hash across environments is multiple jobs. Copy/Move Transfer transfers only jobs and **never bytes**; moving files, publishing copies or transferring payload bytes are explicitly different operations governed by their separate contracts.

## Acceptance criteria
1. Clean local-only setup and core Add→inspect→pause/resume→remove journeys work without server configuration or remote prompts; local background torrent behavior is not silently disrupted by UI setting toggles.
2. Clean remote-only setup and equivalent supported journeys work without starting local engine/service or requiring phone download storage configuration.
3. Both enabled, switching phone ↔ each of at least two remote profiles preserves scoped UI state and clearly identifies the active list and every transfer detail; same-hash jobs never collide.
4. A user changing any global, profile or transfer setting can correctly determine its target(s) before submission; unsupported cross-profile apply and partial success are explained.
5. A disconnected, locked or disabled environment never silently redirects an Add or mutation to another environment, and disabling never deletes files/jobs or forgets credentials.
6. Cross-engine batch and destructive confirmation names every affected scope, forbids stale-scope actions and follows existing exact-target readback and safety contracts.
7. Android screenshots at 412×892 and 320×700 plus 200%-font/TalkBack/keyboard tests verify compact origin labels, selector, touch targets, setting scopes and all three configuration modes.
8. Instrumentation/domain tests demonstrate shared reusable presentation/controller flows with adapter-specific capability decisions; no separate duplicated local-versus-remote feature implementations without documented justification.

**Status:** This is a binding specification and acceptance target, not a claim that all UI/lifecycle behaviors are implemented. Refer to PRODUCT, UX, ARCHITECTURE, SPEC-COVERAGE, DECISIONS and WORK-QUEUE for integration and evidence.

## Implementation note: environment identity badges (2026-10-29)

A shared `EnvironmentBadge` (decorative phone/server icon plus text) names the environment on:
- the phone screen header ("On this phone");
- the server Transfers list header ("Server · account @ host/path");
- the server Add confirmation ("Adding to: Server · …").

The server label comes from the authenticated alias scope via the same address formatting as saved profiles. Two instances of one account on one host (`/rutorrent/` vs `/rutorrent2/`) therefore read differently, and no password is ever shown. Evidence: a JVM label test and Robolectric renders. Not yet: badges on every details card, an environment switcher, or combined-mode lists.

## Implementation note: read-only All environments view (2026-10-29)

**PARTIAL.** An **All environments…** button on the phone list and the server list opens one read-only combined list (`CombinedTransfersScreen`), built on the shared catalog:
- **Sources:** the phone engine, but only if it is already running in this process (the view never starts it); the signed-in server's latest accepted list; and every other saved profile, shown as *Not connected* with no rows. Identity is `phone` or the saved profile ID.
- **List behavior:** multi-select view scope (All, one or several), search, six reversible sort keys with unknowns last, and *Group by environment* with one shared sort. Scope, sort, direction and grouping persist across restart; search text is not saved.
- **Status:** each source shows Live, Out of date, Not connected or Locked.
- **Privacy:** each environment's own privacy lock is respected, and the window is secured while protected rows are shown.
- **Rows:** each row has a text environment label and a decorative rail.

**Cross-environment Start/Stop (PARTIAL).** *Select…* enables an explicit row selection, counting selections hidden by the current view.
- **Before running:** Start…/Stop… shows a preview naming every target environment and its count.
- **While running:** each environment runs under its own existing mutation gate: the server list's readback gate, or the process-wide `PhoneMutationFence` shared with the phone screen. Results are reported per environment.
- **Refusals:** a stale, offline, locked, busy or signed-out environment refuses its items, never redirecting them.
- **Uncertain results:** the first uncertain result halts the batch, with no retry.

**Environment colours (PARTIAL).** Each stable environment ID receives an automatic hue from Chunks' accent palette, never black or white.
- **Automatic assignment:** a hue is assigned once and persisted, avoiding hues already used until all nine are taken. Error red and warning yellow come last.
- **User choice:** *Environment colours…* in the combined view lets the user choose any hue, including a repeat, and *Restore automatic colour* returns to the assigned one.
- **Where colours appear:**
  - combined row rails and environment labels; label text uses the hue only when it reaches WCAG AA on the card surface, otherwise it uses normal text;
  - scope-chip dots;
  - group headers;
  - the phone, server and Seeding-rule environment badges.
- **Still uncoloured:** the Add-review badge and notifications.

Not yet built:
- other actions from this view;
- simultaneous connection to several servers;
- an environment settings screen.

Evidence: [QUALITY-LEDGER](QUALITY-LEDGER.md).

