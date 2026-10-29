# Unified local and remote experience (owner contract, 2026-10-28)

**Normative principle:** Chunks is one torrent manager with interchangeable local-phone and rTorrent-backed environments. User-facing workflows, layout, terminology, information hierarchy, navigation and control placement MUST be shared wherever the verified capabilities allow. A divergence requires a concrete engine/server/security/storage capability reason; different implementation technologies alone are not an excuse to create separate applications.

## Configuration and first run
- Local-only is the default offer and a complete standalone path; no remote account, remote connection, seedbox, or network enrichment is required. An explicit remote-only choice is equally first class and must not force local torrent-engine setup or start the local service.
- Users may enable local only, remote only, or both; remote may include multiple independently named rTorrent profiles. Enabling, disabling and later re-enabling an environment preserves its configuration and torrent/data state unless a **separate, explicit deletion or credential-forget action** is confirmed. Disable means hidden/inactive in Chunks UI, not a destructive torrent removal or remote server shutdown. Do not stop active local downloads silently: warn of consequences and offer a separate stop/background policy.
- At least one usable environment must be offered. If none is configured, present non-destructive setup. Disconnected, unauthorized, locked, temporarily unreachable and disabled are different states. Offline remote profiles remain configured; Chunks never falls back by silently redirecting a mutation to another engine.
- Every entry route (main screen, deep link, Android Open With/Share, notification, widget, collection, search) resolves an explicit destination environment before Add or mutation. In single-environment mode, avoid gratuitous engine choosers.

## Seeding rule environment scope

Smart Seeding Rules follow the same unified UI on phone and all named remote profiles. Every rule list row and rule editor exposes a visible, selectable **Applies to** summary using stable environment IDs (phone and each server profile), with one/several/all-current options and separate explicit future-environment opt-in. Generated rule names are user-editable and are not identities. A reused definition can have independently validated per-environment enforcement capabilities; a shared template does not implicitly apply to every engine. New profiles never silently inherit rule scope. When Add's destination environment changes, recompute applicable groups and explain any incompatible manually chosen group. Saving scoped settings does not silently mutate active transfers. Canonical behaviors and checks: [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md).

## Navigation and presentation parity
- One shared Transfers list/card, transfer detail, Add review, Files, priority, trackers, peers, storage, Collections, search, sorting, filtering, multi-select, activity/errors and confirmation vocabulary. Individual screens must not fork simply because the active adapter is local versus rTorrent.
- When multiple environments are enabled, a prominent compact, accessible environment selector on the main Transfers screen identifies **On phone** or the friendly rTorrent profile name. Each transfer detail has a persistent small source label/icon; destructive dialogs, batch previews, queue settings, destination pickers and status/error notices name the target environment. Never rely on colour alone. In one-environment mode, do not waste prime screen space on a useless selector; retain identity where safety benefits.
- Switching environments is immediate UI navigation, never an implicit migration, pause/resume, network mutation, credential switch, or file movement. Preserve independently per environment the list scroll position, sort, filter, search, selection (subject to identity/freshness and privacy lock), view density and relevant screen state; never accidentally act on stale selection from a previous scope. Distinguish active environment from default Add destination.
- A combined/all-environments view may be added later only if every row has explicit origin, filters and batch actions are scope-aware and cross-environment operations use separate review. It is not required by this contract.

## Advanced control and integration scope

Use the same advanced Add, queue, network, diagnostics and settings flows in phone-only, remote-only and combined modes. [Intake and queue policies](INTAKE-QUEUE-AND-POLICIES.md) must distinguish server-native schedules from phone-connected automation; [protocol and privacy controls](FILES-PROTOCOL-AND-DIAGNOSTICS.md) must distinguish local interface/VPN from remote peer ports; [remote integrations and preferences](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) must distinguish rTorrent RPC from separately authorized file/privileged services. Per-profile capabilities, readback status, selected environment and offline limits are explicit in every advanced action; no silent fan-out or accidental inheritance by newly added remote profiles.

## Settings and scope
- Classify each setting before implementation as **app-global**, **environment-specific** (phone or exact server profile), or **transfer-specific**. Show scope beside the setting or under an unambiguous scope header. Never imply a profile-scoped rate limit, download destination, privacy lock, queue behavior, or credentials applies across engines.
- A shared setting may optionally offer **Apply to all supported environments** only as explicit opt-in with preview of each target and unsupported/failed results. A partial failure cannot be reported as universal success. Persist independent overrides and distinguish Chunks-side presentation preferences from settings actually enforced by a torrent engine.
- Defaults and recent Places belong to their exact environment; per-transfer changes do not silently update defaults. All destructive and irreversible actions include current target identities and refreshed capability/authority checks.

## Discovery and secondary infrastructure navigation

The integrated [Discover experience](INTAKE-QUEUE-AND-POLICIES.md) is a **Chunks-wide opt-in interface** with an immediate global off switch; any discovery-based Add still requires choosing an exact phone/remote environment, with per-environment limits, Places and seeding rules previewed. Disabled Discovery stops source polling and unattended Add independently of which environments are connected, but never stops existing torrents or manual Add. On/Off is not silently overridden by a newly added remote profile or an imported settings file.

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
