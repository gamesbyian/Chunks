# Chunks Options: canonical information architecture and UX contract

> **BINDING VISUAL/TAXONOMY UPDATE · 2026-11-11:** The eight top-level Options destinations and their ordering are now **Connections; Downloads & storage; Seeding & limits; Artwork & metadata; Notifications; Appearance & accessibility; Privacy & network; About Chunks**. Display them as compact **solid-colour filled category cards** on the settings index, each with a circular icon, bold label, short truthful subtitle, and navigation chevron unless an explicitly defined independent quick toggle applies. See [solid-colour Options visual contract](design/OPTIONS-SOLID-COLOUR-INDEX.md). All former underlying settings, widget/integration controls, persistent scopes, search aliases and deep links remain accessible within the new groups. The eight older names and conceptual wireframe below are **superseded only as top-level visual/taxonomy guidance**, not as removal of their functional requirements. Do not implement the mockup's Seeding master switch without a real, coherent rule. Footer Connections and Options > Connections share one configuration authority.

> **BINDING OPTIONS RECONCILIATION · 2026-11-09:** [binding 2026-11-09 next-push UX reconciliation](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md). **Artwork & media information** is the canonical global control; automatic recognition/collection/display preference defaults **enabled**, while outbound provider lookup is **enabled on fresh installations, separately revocable with persistent explicit OFF**, subject to provider, network, lock and data-minimization controls. With global feature off, Universal Add hides its one-time per-transfer artwork checkbox and schedules no automatic lookup; with feature on, Add shows it initially checked. Active Details has **no per-job automatic-enrichment toggle**, only correction/artwork choice. Options is one of **four permanent same-row footer destinations** (Transfers, + Add, Connections, Options), not a stacked row or a scattered duplicate settings home. Preserve one persistent global settings authority and existing explicit choices. This supersedes conflicting older placement text; no implementation acceptance is implied.


**Owner direction: 2026-11-05. Status: approved organization of existing/spec'd capabilities, not authorization for new scope.** This focused contract extends [UX](UX.md) and [PRODUCT](PRODUCT.md). [WORK-QUEUE](WORK-QUEUE.md) alone orders implementation; existing rTorrent daily-use reliability and cohesive UX work outrank future backend adapters and advanced controls. Related contracts remain authoritative for functional semantics.

## Goal and boundary

Options is the central, discoverable home for persistent user preferences, connection administration and configuration of existing rule systems. Transfers stays the ordinary landing screen; the footer retains Add / Transfers / Options. No parallel wizard, settings stack, or duplicate property storage. An individual transfer may override permitted defaults through Add or Details and link to the single authoritative global or environment-scoped configuration. Never require a settings visit before ordinary Add/Start/Stop when a verified default exists.

Organize Options as a scrollable, searchable **index of eight destinations** (not eight always-expanded panels). Each destination can present collapsed subordinate groups and concise effective-value summaries. Search must match semantic names/synonyms, open the correct nested destination and focus the setting; returning must preserve originating context, scroll, search, keyboard focus and unfinished edits. Search is already partly implemented: extend/refactor existing search rather than rebuilding it. Require touch, TalkBack, keyboard, focus and primary Pixel 6 Pro ergonomics.

**Remote per-profile download defaults (planned directory-picker reuse):** The existing single Connections/profile-setting authority may offer a **Default download folder** action using the same exact-server folder browser as Universal Add. In settings mode, commit only after explicit **Set as default on this server**; a one-off Add folder never changes this preference. Where browse/create is unavailable, retain editable manual absolute path and truthful verification states. No new top-level Options category, filesystem-management tool or payload Move. [Remote directory browser](REMOTE-DIRECTORY-BROWSER.md), [visual design](design/REMOTE-DIRECTORY-BROWSER.md), [#1680](https://github.com/gamesbyian/Chunks/issues/1680).

## Eight destinations and ownership

1. **Clients & environments**: local-only, remote-only or combined; add/edit/disable individual remote profiles; session identities, auto-reconnect/remembered sessions, credential/transport access, local engine enablement and local-client protection behind biometric or password/credential. Default offer remains local only. Distinguish authenticate-to-enable, authenticate-to-open/control, and background transfer continuation. A lock must not silently stop active jobs. Mode disable must not erase jobs/payloads/profiles; environment colour/name remains discernible in Combined browsing. Scope: global mode, per-environment connection, local access gate. Reference [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [SECURITY](SECURITY.md), [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md), [REMOTE-BACKEND-ADAPTERS](REMOTE-BACKEND-ADAPTERS.md).
2. **Appearance & display**: app theme (including system-following as available), owner-approved typeface/font selection and any existing font controls, list density and expanded-row default, presentation/accessibility choices, and show/hide **session colour indicators**. Hiding colours must preserve visible text environment labels and accessibility names, especially for Combined browsing. Artwork *presentation* belongs here; metadata collection policy belongs under Media intelligence. Reference [UX](UX.md) and [UX-COHESION-AND-AUTO-ARTWORK-2026-11-05](UX-COHESION-AND-AUTO-ARTWORK-2026-11-05.md).
3. **Artwork & media intelligence**: independent control of media identification/classification, network metadata retrieval, artwork retrieval, recommendations/related transfers and provider/privacy preferences, plus permitted exclusions. Present a coarse top-level off switch and granular subordinate toggles, but preserve useful classification for smart routing when artwork is disabled. Enrichment is automatic when enabled/authorized and available; manual activity is primarily per-transfer correction and custom poster choice. Global off must stop relevant new work, not erase existing customized choices without consent. Reference [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [MEDIA-INSPECTION](MEDIA-INSPECTION.md), [SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS](SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md).
4. **Automation & rules**: one settings entry into the existing shared rule/policy conceptual system, organized by speed/bandwidth, ratio/time seeding, prioritization, smart Add routing, post-identification rerouting and **job-only** Move eligibility. Rule rows show enabled state, environment scope, priority, conditions and actions; provide effective-rule explanations, conflict precedence and dry-run/simulation where specified. Never automatically copy payload bytes; Chunks-specific presentation metadata and rename *intents* can accompany a job, while carrying speed/ratio/seeding rules is a separate explicit choice. No autonomous destructive moves or unauthorized session operations. Reference [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md), [SMART-ROUTING-AND-SOURCE-LIFECYCLE](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md), [EFFECTIVE-POLICY-EXPLANATIONS](EFFECTIVE-POLICY-EXPLANATIONS.md).
5. **Notifications**: existing system notification channels, completion/failure/connection and rule-outcome event choices as implemented or already specified. Route OS-channel configuration to Android correctly; avoid claiming all events are implemented. Per-transfer actions may link to global settings. Respect OS permission states, privacy and quiet/disabled settings already supported.
6. **Widgets & integrations**: global widgets enable/disable and existing per-transfer widget defaults/lifecycle; installed integrations, Android opening/sharing associations and future explicitly approved adapters if available. Removing widgets due to a missing transfer must not affect the job. Do not fabricate integrations or promise unimplemented controls.
7. **Files & storage**: local default destination (user-visible Downloads/Chunks Downloads), recent locations and optional per-transfer destination overrides; default source `.torrent` handling (keep / archive app-private copy / delete original only after verified safe Add); retained-source inventory and any existing storage preferences. Show specific backend and permission limitations instead of offering inoperative destinations. Source archive is not payload migration. Reference [ADD-SOURCE-INTAKE](ADD-SOURCE-INTAKE.md), [SMART-ROUTING-AND-SOURCE-LIFECYCLE](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md), [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md).
8. **Help & about**: help, troubleshooting, connection diagnostics, build/version, open-source credits and relevant licensing; maintain the intended free/no-ownership distribution posture while respecting third-party licenses. Reference [LICENSING-AND-ATTRIBUTION](LICENSING-AND-ATTRIBUTION.md).

### Global vs contextual controls

| Entry point | Owns | Does not own |
| --- | --- | --- |
| Options | Global defaults, environment administration, rule definitions, notification/media/widget policies | Individual torrent commands and arbitrary payload mutations |
| Add | Per-transfer source, destination, start preference, classification exclusion, source handling and routing overrides | Duplicate global preferences or mandatory review |
| Transfer Details | Per-transfer media correction, presentation, priority/rules overrides, share, move, remove | A separate global settings model |
| Environments | Contextual connection actions and status, deep-linked to Options administration | A parallel credential/settings database |

Every persistent setting must have a stable key, default, scope (global/environment/transfer), provenance (default/inherited/override), applicable capabilities, validation, and one owning write path. Settings screens must show the effective value when scope inheritance applies. Reset clears an override at the correct scope, not another environment's value. Persist atomically and survive process death/relaunch. Prefer schema migration over silently resetting settings from older builds. Credential material must not be stored alongside ordinary plaintext preferences. Avoid using one boolean for distinct security or media feature states.

## Proposed index wireframe (conceptual, not mandatory ornament)

```text
Options
[Search settings…                     ×]
Clients & environments       Local + 3 remote
Appearance & display         System theme · Font
Artwork & media intelligence Automatic where enabled
Automation & rules           Active policies
Notifications                Android + event settings
Widgets & integrations       Widget preferences
Files & storage              Downloads/Chunks Downloads
Help & about                 Version · Support
```

Each row opens its destination and shows a short truthful summary. No top-level giant switches that silently hide dependent settings; local/remote mode changes must have explicit effect descriptions. The options page is scrollable; nested sections may collapse. Avoid duplicate settings under Privacy, Artwork, Home, or per-environment screens: retain only contextual shortcuts. Search synonyms should include biometric, remote, local, ratio, seeding, speed, poster, artwork, metadata, recommendation, similar, colour/color, font, widget, source torrent, archive, storage, move, and notification.

## Dependency, safety and observability rules

- **Protect local access vs operational lifecycle**: define unlock expiry/relock, process recreation, fallback after biometric unavailable, and noninteractive service behaviour. Authentication protects Chunks controls rather than pretending to encrypt payloads or remotely authenticate servers.
- **Colours**: turning off environment colour chips must never erase text identity or allow a command to target an ambiguous server. Colours remain a supplemental cue only.
- **Media autonomy**: on launch, eligible existing transfers are discovered for automatic enrichment without a mandatory artwork workflow; respect network/consent requirements, per-transfer exclusions, overrides, provider quotas and failures. Disable toggles stop corresponding future tasks rather than deleting local data.
- **Rules**: evaluate environment capabilities and ownership before action. Explain winners and conflicts; no blind retry after uncertain remote command. Recognition can happen after magnet metadata, so a later move requires job-only migration safety checks and never relocates payload.
- **Source lifecycle**: Delete original only after confirmed safe intake and appropriate Android authorization. Archiving is an app-private copy. Moving the original source and moving job registration are distinct operations.
- **Notifications/widgets**: configuration changes do not mutate running transfers. System restrictions must be displayed truthfully.
- **Documentation honesty**: controls here are information architecture; mark each as implemented, partial or planned by source-grounded evidence. Do not place dead controls in a shippable Options screen.

## Immediate-apply interaction contract (owner-approved 2026-11-05)

**A valid user interaction is the commit.** Ordinary Chunks preferences must not have a second Save, Apply, Done, confirmation, or screen-exit gesture. A switch persists when toggled; radio/dropdown/font/theme selection persists when selected; slider changes take effect continuously or at a clearly defined release point with throttling; selecting an existing rule's enabled state persists immediately. Back simply navigates; leaving the screen does not cancel successfully made choices. This applies equally to Options, Add preference overrides, Transfer Details, and per-environment preferences where the operation is independently valid.

- UI state and persistence: commit to one canonical durable setting store atomically; surface the new effective value immediately, with honest pending/failure feedback if asynchronous persistence or an engine call is in flight. Persist across rotation, process death, navigation and relaunch. Repeated rapid interactions must converge on the last intended value without old completions overwriting newer ones. Never claim a remote setting succeeded solely because the UI control moved.
- Text inputs: persist each validated, self-contained change on focus loss or a short debounce, without a separate Save. Do not push incomplete hostnames, credentials, paths, numeric values or invalid intermediate typing to a remote engine. Show validation and maintain the draft; commit only valid complete values. Keyboard IME Done dismisses/completes editing rather than serving as an otherwise mandatory Save button.
- Composite/remote configuration exception: an explicit Connect, Test, Authorize, Create, or Commit operation is allowed **only** if a remote client/API requires a coherent multi-field transaction, incomplete settings cannot be safely applied independently, or committing causes a consequential operation. Keep edits as an explicit pending draft, show precisely what will be submitted, and avoid claiming a partially installed remote configuration is complete. Do not use these cases to impose Save on unrelated preferences.
- Consequential exceptions: confirmation remains appropriate for destructive operations, transfer-job Move/Remove, credential-sensitive authorization, or other actions governed by existing consent/safety contracts. Ordinary preference toggles and changing a *rule definition* are not themselves destructive confirmations, but activation must not silently execute irreversible work without independently required authorization.
- Revert and feedback: support a useful Undo for reversible accidental changes when feasible, plus a way to restore defaults at the correct scope. A failed write must produce a visible recoverable error, restore truthful effective state or indicate pending unsynced local intent, and offer a safe retry. Never silently discard edits on Back or portray remote RPC acknowledgment as verified execution.
- Rule editing: enable/disable, scope choice, priority, and independently valid parameters auto-apply; partial newly created rules remain drafts/inactive until valid. Distinguish *saved policy* from *policy applied to a running transfer*; re-evaluation must respect existing capability, scope and authorization gates.
- Accessibility: screen readers announce the new value and any failure, controls remain stable while writes complete, and focus does not jump on successful saves. Avoid modal confirmation/toast spam for routine settings. No settings screen should require a footer Save.
- Acceptance: test toggles, theme/font choices, colour indicator visibility, media granular switches, widget and notification preferences, rule enablement and per-transfer override, rapid repeated changes, process termination after selection, navigation/back, invalid text edit, offline/failed remote apply, and one genuine composite remote exception. Demonstrate both immediate observable UI behaviour and durable stored state.

This universal contract takes precedence over older mockups or prose depicting Save/Apply/Done for ordinary settings. Do not rename the **Save to** destination label on Add: it describes where payload will be downloaded, not an action to save preferences.

## Implementation and acceptance sequence (subordinate to WORK-QUEUE)

**A. Inventory and register.** Audit existing Options/search, appearance, connections, privacy, notification, widget, storage, media and rule screens; map each setting to a single destination with current source path, existing persistence, status, scope and owning spec. Identify duplicates and compatibility aliases. Do not introduce a competing task tracker.

**B. Consolidate with no behavioural regression.** Refactor routing/navigation to eight destinations and contextual deep links, reusing existing view models and storage. Preserve transfer-list scroll/focus and Add draft. Restore all prior settings through migration; no hidden destructive defaults. Ensure the master media switch and subordinate toggles represent independent actual behaviours.

**C. Expose advanced rules only as they mature.** Integrate shared rule models, explanation, previews, future multi-client capabilities and smart routing in existing priority order. Remote backend adapters remain deferred behind rTorrent daily-use and cohesive UX; do not invent six client-specific settings implementations.

**D. Verify installed user journeys.** Cover global/local/remote/transfer scope, offline/relaunch, password/biometric fallback, lock with continued transfer, session-colour-off identity, media master/granular policies, search/deep link/back-focus, Android channels, widget deletion, source retention and rule precedence. Use JVM/Compose/instrumented evidence for changed behaviour, real rTorrent where operations are relevant, then primary Pixel/human acceptance separately. Record actual results in QUALITY-LEDGER and SPEC-COVERAGE; never claim a wireframe proves implementation.

## Done criteria

The eight-section index is coherent on the Pixel, every already-existing setting has one canonical home, search reaches nested controls, contextual per-transfer links resolve to the right scope, migrations preserve old selections, inaccessible/unfinished controls are labeled or hidden, and the rTorrent transfer life cycle remains unaffected. Changes to rule execution, authentication or actual source removal require their independent safety/evidence gates.

**Owner request is organizational**: this contract introduces no additional release blocker, provider integration, independent backend, or requirement to build speculative features merely to populate Options.

## Current control ownership under the eight-card index

Navigation reuses the existing owners; the index never writes preferences. Keep legacy search terms as aliases. Planned settings are not implemented merely by creating a category.

| Category | Existing controls and destinations |
| --- | --- |
| Connections | Footer Connections authority: encrypted profile roster, initial name, rename, remember/forget, read-only Connect |
| Downloads & storage | Add's existing Save to/default Place, authenticated server folders/collections, local snapshot restore, ZIP review/recovery, collection names |
| Seeding & limits | Shared Seeding rules and existing local speed-limit controls |
| Artwork & metadata | Existing Artwork & info screen and media preference/provider stores |
| Notifications | Observed Android notification permission and OS channel settings; no invented master switch |
| Appearance & accessibility | Existing environment colour chooser, artwork-size/display settings, authenticated server display aliases; Android text sizing |
| Privacy & network | Existing scoped access protection, local DHT and clipboard-prefill preferences |
| About Chunks | Actual build version/help, Create torrent, authenticated inventory backup, local inventory/health/magnet exports |

Search opens or scrolls to these same controls. Back/All options restores the originating query and index scroll; footer Options returns to that index. Retain non-saveable snapshot review state while hiding other categories, excluding hidden controls from accessibility and keyboard focus. Android notification status must refresh on resume after the OS settings detour.
