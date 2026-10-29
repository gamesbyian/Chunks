# Event automation, saved selection rules and external handoffs

> **Authority:** owner-approved niche advanced automation (D-NICHE-AUTOMATION-20261029). This contract owns optional `When → If → Then` rules driven by observed Chunks events and safe actions. [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md) remains canonical for ratio, time, bandwidth, tracker-based assignment, rule precedence and seeding-group runtime. [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md) owns Discovery and source auto-add. [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) owns the existing media-manager handoff boundary. **Status:** planned; no claim of a persistent server agent or working webhook delivery.

## Purpose, niche scope and exclusions

Enable a capable user to express practical automation without learning Lua or shell scripts: **When this torrent completes on My Seedbox, notify me and offer a Plex refresh**; **When seeding stops after its existing rule, call my authorized webhook**; **Once a week, show all paused transfers that are safe to resume**. Chunks should make rules legible, previewable, reversible where possible and honest about Android/background limitations.

Rules MAY trigger notifications, local Chunks labels/notes, optional authorized application intents, a bounded documented webhook, prefilled existing operations requiring review, and narrow safe actions explicitly supported by the selected engine. No arbitrary shell, unrestricted scripts, remotely installed plugins, generic privileged RPC, automatic source-data deletion, private tracker publish or blanket server management. An advanced filter expression builder MAY support richer conditions, but must be data-only, bounded and sandboxed; it is not a programming-language execution environment. An external plugin or REST API may be explored later as a separately permissioned integration, not implicitly authorized here.

## Relationship to Task outcomes and effective policy

[Task outcomes and continuity](TASK-OUTCOMES-AND-CONTINUITY.md) is the **only** owner of human-intention milestones and resumable task checkpoints (MF02/MF04). The per-rule trigger and action receipts here are operation-level evidence and MAY support a user-selected Task milestone, but never silently mark the person's larger goal complete, delivered, imported or usable. [Effective policy explanations](EFFECTIVE-POLICY-EXPLANATIONS.md) (MF31) owns rule conflict/readback/why-this-setting computation; event automation MUST reuse that evaluator when suggesting or attempting overlapping engine state changes, not create its own precedence source. Rule history is not a substitute for independent engine observation or a Task outcome receipt.

## Identity, inputs and explicit scopes

An **AutomationRule** has immutable ruleId, version, generated editable friendly name, active/disabled state, triggers, predicates, ordered actions, exact named environment IDs, permission scopes, consent version, rate/limit policy, retention and audit receipt. A **TriggerReceipt** includes source `(environmentId, engineId, transferId, eventType, observedGeneration, observationTimestamp, sourceVersion)` with quality `observed / inferred / user-requested / stale`. An **ActionReceipt** includes target adapter, idempotency key, requested effect, outcome and verified postcondition. A rule's name never determines execution identity.

Triggers are verified Chunks-observed transitions such as transfer added, completed, stalled, errored, seeding target met, file availability or explicit user schedule. An engine-reported `completed` event is distinguished from independently hash-verified completion, a duplicate poll, resumed historical state and a requested stop. A completion-triggered external action defaults to **independently verified complete** when feasible; where not possible, the user explicitly chooses and sees the weaker engine-reported basis. Cross-profile triggers cannot silently act on a different profile. Rule owners choose exact named environments and whether future profiles may be included; default is **existing selected only**.

## Niche UX and rule editor

**Entry:** `Settings → Automation` with **New rule**, **My rules**, **History**, **Integrations**. From a transfer's secondary More menu: **Make a rule from this…** opens the editor prefilled with this environment and transfer context; never adds a new button to every transfer card or the main tab bar. Users uninterested in rules see no automation badges, persistent recommendation banners or setup demands.

The rule editor uses a simple three-part sentence:

1. **When…** choose a plain-language event, time or verified state transition, with precise scope and event reliability.
2. **Only if…** select optional filters by tracker/source, exact environment, label/Collection, file type, size, status, ratio and time, with **All/Any/None** grouping. Advanced condition view may support nested groups and a bounded expression/DSL; show an understandable natural-language translation and reject unsupported fields, expensive regex, infinite recursion and ambiguous unknown values.
3. **Then…** choose one or more actions, including **Notify me**, **Tag/annotate in Chunks**, **Prepare an existing action for review**, **Send approved webhook**, or explicitly supported engine operations. Display permission, environment, network/expense and reversibility beside each action.

Show an editable generated rule name, **Applies to**, **Test against current data**, and **Preview last N matches** before Enable. The preview includes the exact matching job identifiers, predicted outcomes, unsupported/unavailable states, conflicts with Smart Seeding Rules and would-be network disclosures. `Test rule` is a **dry run** and never sends a real webhook or mutates torrents unless the user chooses a separate explicit supervised delivery test. Editing a rule creates a new version; existing queued actions retain their original version and are cancelled/re-reviewed when permission or target changes.

**Examples:** a user can select `When transfer is verified complete on Seedbox A → notify on phone`; the editor states **Works only while Chunks can receive/observe the event** unless a separately verified server-side observer exists. This is not a promise of unattended 24/7 server-triggered execution. A notification requiring Android permission shows a consent checkpoint, not silent failure.

## Settings, defaults and interaction with existing rules

Automation is **disabled until a user explicitly enables a saved rule**. Rule templates are suggestions, never live by default. Scope is per named engine, with optional explicit cross-environment action requiring separate confirmation. Restore/import leaves potentially mutating rules **disabled** pending capability/credential verification. The global **Discovery off** switch disables Discovery-triggered searches/subscriptions/auto-intake but not a user-created rule driven by an existing transfer or manually scheduled local event. Any rule with a Discovery-dependent trigger/action is held or disabled while Discovery is off, with a reason. This never circumvents the Discovery gate.

Smart Seeding Rules determine ratio/time/bandwidth policy and tracker matching; generic automation cannot silently override these policies, or set an unlimited upload cap. If both attempt to change the same torrent operational field, surface an explicit precedence/conflict preview and require per-transfer/user consent. Never duplicate the canonical seeding-group editor or invent a second ratio policy engine. Multi-action rules stop or hold downstream effects when a prerequisite fails; they are **not** implicitly atomic across servers.

## Action adapters and security

- **Webhooks:** outgoing HTTP(S) calls require user-defined named destination, URL/domain, method limited to vetted documented options, payload preview and explicit data-field selection. Default body is minimal event type, time, scoped alias and opaque operation ID, **no** private tracker URL, passkey, full magnet, file path, host/IP, credentials or media title. Allow secret reference as an optional request-signing token, stored separately and never embedded in rule export/logs. Support receiver idempotency key and signed payload where receiver implements it. `2xx` acknowledges transport only, not the receiving system's side effect.
- SSRF controls must prevent private-address/loopback/link-local/metadata endpoint access by default, reject redirects to new unapproved origins and DNS rebinding, unsafe protocols and unbounded bodies/timeouts. Deliberately adding a named LAN/private service is a **separate per-destination advanced authorization** with explicit address/host and authorization checks; never a global `disable network protection` switch.
- Android app handoff uses explicit packages/intents and provider grants, never arbitrary broadcasts to every installed app. If no compatible recipient, offer **Copy summary** or **Open compatible app**, not fake delivery. A media manager can act only through its own separately authorized adapter and existing completion/Place rules.
- Outgoing API auth, remote RPC auth, source/indexer auth and local Android permissions are different scopes. An operation that removes data, modifies files, publishes externally or restarts server services cannot be introduced by a generic webhook rule; it goes through the owning feature's approval flow.
- Guard per-event rate, retries, retention, backoff, action concurrency and loop detection. A rule-generated event cannot endlessly retrigger itself or another rule; show cycle reason and halt. Queue semantics are at-most-once for non-idempotent effects unless receiver idempotency is proven; uncertain deliveries reconcile/read back before manual retry.
- Keep a redacted bounded activity log with `Triggered / Matched / Held / Submitted / Acknowledged / Independently verified / Uncertain / Failed`. A request that was never sent is not labelled failed delivery; network-request success is not proof that Plex scanned or a service refreshed.

## Background execution and recovery

A phone-only observer may stop working while Android kills/backgrounds Chunks; clearly distinguish **Foreground only / Opportunistic background / Provider-verified persistent server automation**. Do not show an automation as **Always active** without a proven independent always-on executor. On reconnect, detect missed intervals and historical events without silently replaying destructive, billable or duplicative actions. Any backlog replay is previewed with limit and explicit consent; auto-run on reconnect is off by default. Rule disabled/credentials revoked/Discovery off must be checked again immediately before dispatch. Device and server clocks can differ; schedule rules have timezone/DST and offline semantics. No network-dependent enforcement promise when phone is offline.

## Acceptance matrix

| Behavior | Required independent tests |
|---|---|
| Editor | Generated/editable names, scope, When/If/Then preview, unknown predicate, All/Any/None, 320×700/412×892, TalkBack/200% font |
| Trigger identity | Duplicate polling, phone restart, historical events, clock drift, verified vs inferred completion, two same-hash jobs on different profiles |
| Safety | Dry run causes zero mutations/requests, no cross-profile hidden actions, seeding rule precedence, private tracker secrets absent from logs/webhooks |
| Delivery | Receiver 2xx vs actual postcondition, timeouts, redirect/SSRF, LAN approval, payload limit, DNS change, revoked token, idempotent retry |
| Durability | Disable during queue, offline/Doze, scheduler backlog, loop prevention, conflict with Discovery off, no claimed always-on without server observer |
| Compatibility | Local/remote unsupported operations show truthful states; an rTorrent RPC login alone never grants external webhook or server admin privileges |

## Implementation/evidence status and related artifacts

Specification only; no tested automation runner or webhook proof is claimed. Record each adapter's exact evidence in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md). Execution slices in [WORK-QUEUE](WORK-QUEUE.md).

[PRODUCT](PRODUCT.md) · [UX](UX.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) · [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md) · [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md) · [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md)
