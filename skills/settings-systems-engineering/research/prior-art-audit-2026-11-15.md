# Settings systems and agent guidance: comparative prior-art audit

**Research date:** 2026-11-15. **Disposition:** source-informed editorial procedure, not effectiveness or runtime validation. Public source URLs are evidence to check at task time, not libraries that must be installed. The app-specific inspection concerns Chunks at the then-current main revision; do not use it as later main's implementation evidence.

## Question and evidence classes

What published architecture, platform guidance, specialist agent skills and adjacent agent-instruction formats should influence modular settings menus, durable preference semantics and safe coding-agent decisions? Separate **normative platform API**, **real deployed architecture**, **tooling/library design**, **community-authored skill**, **agent-instruction authoring recommendation**, and **our own inference**. A source that advocates a practice is not an independent study proving the practice improves agents.

## Source audit: architecture and UI

| Source / status | Inspectable mechanism | Adopt / adapt / reject |
| --- | --- | --- |
| [AndroidX Settings](https://developer.android.com/develop/ui/views/components/settings) and [Preference components](https://developer.android.com/develop/ui/views/components/settings/components-and-attributes) (official platform guidance) | Preference, PreferenceScreen, PreferenceCategory and an XML/code hierarchy; built-in UI/persistence wiring | ADAPT: reuse native primitive if host stack fits; don't force a modern Compose app back to XML or deprecated platform android.preference |
| [Organizing Android settings](https://developer.android.com/develop/ui/views/components/settings/organize-your-settings) (official) | Grouping, categories and multiple screens | ADOPT: independently compose page/group hierarchy; don't equate group title with state ownership |
| [Compose state and hoisting](https://developer.android.com/develop/ui/compose/state) and [Compose architecture](https://developer.android.com/develop/ui/compose/architecture) (official) | State flows down, events up; state owner separate from rendering; shareable stateless controls | ADOPT: projection onto one observable owner; renderer should not mutate storage during recomposition |
| [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore/) (official) | Async Flow updates, transactional writes; repository -> ViewModel -> Compose; Room for large relational data | ADOPT AS AVAILABLE: use existing store appropriately; do not migrate an app's persistence just because settings move. Multi-store/remote effects are not one DataStore transaction |
| [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps) (official) and [WAI-ARIA Switch Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/switch/) (web control specification) | 48dp-class touch targets, understandable state, stable switch label, keyboard and semantics | ADOPT: accessible components/focus/feedback, with platform-specific implementation. WAI-ARIA applies directly to web, not as an Android Compose API |
| [Apple Settings HIG](https://developer.apple.com/design/human-interface-guidelines/settings) (official UX guidance) | Strong defaults, few settings, general infrequent choices in Settings; task-specific controls near task; avoid reproducing system-level controls | ADOPT as UX decision method, not an iOS layout copy. Prevent settings-page bloat |
| [VS Code configuration contribution point](https://code.visualstudio.com/api/references/contribution-points) and [Settings editor](https://code.visualstudio.com/docs/configure/settings) (deployed widely used architecture) | Typed stable keys, default, application/machine/window/resource scopes, JSON + GUI, search, direct setting links, reset, override precedence and provenance | STRONG ADOPT: separate definition, scoped value and UI; explicitly document precedence and reset. Do not copy VS Code's exact scope hierarchy into another product |
| [JSON Schema conditionals](https://json-schema.org/understanding-json-schema/reference/conditionals) (format) | Data-level if/then/else/dependent constraints | CONDITIONAL: useful for interoperable config formats; avoid mistaking data schema for a complete behavior/UI architecture |
| [react-jsonschema-form UI schema](https://rjsf-team.github.io/react-jsonschema-form/docs/api-reference/uiSchema/) and [custom widgets](https://rjsf-team.github.io/react-jsonschema-form/docs/advanced-customization/custom-widgets-fields/) (mature library design) | Distinguishes what the data is from how to render it, permits registered reusable widgets | ADAPT: two layers + typed controls; a Kotlin app does not need this React dependency or JSON data interchange |
| [VS Code settings managed policy](https://github.com/microsoft/vscode/blob/main/.github/skills/policy-and-managed-settings/SKILL.md) (first-party specialist agent skill) | Routes policy controls by governed runtime/editor and differentiates managed settings from application local UI | ADOPT boundary analysis: authority/lock/administrator scope must be part of setting design; don't merge enforced policy into user-preference storage |

## Source audit: actual skills and adjacent agent instructions

| Source / class | Finding and reuse boundary |
| --- | --- |
| [Google Android Skills catalog](https://github.com/android/skills), [Android Studio skill guidance](https://developer.android.com/studio/gemini/skills) (official skill program) | Domain-specific skills expose relevant current API tasks and load on demand. Public catalog has relevant security/Compose/testing guidance, but in this sampled review no comprehensive standalone reusable-settings-schema procedure was identified. **Do not claim the full GitHub ecosystem has none.** Prefer official API citations for mechanics; evaluate whether our Skill fills the actual gap. |
| [Community Apple HIG settings reference](https://github.com/sankalpaacharya/apple-human-interface-skills/blob/main/skills/patterns/settings.md) (community skill) | Focuses correct global/contextual placement, defaults and accessibility. Valuable UX decision input but not persistence, override resolution or migration instruction. No effectiveness evaluation established. |
| [Microsoft VS Code policy skill](https://github.com/microsoft/vscode/blob/main/.github/skills/policy-and-managed-settings/SKILL.md) (first-party targeted skill) | Concrete decision tree based on which subsystem governs the action; good model for scope/authority routing. Specialized to VS Code enterprise policy; do not vendor it as generic settings code. |
| [Community frontend-ui skill collection](https://github.com/SvaraNova/skills) (community) | Promotes components, focus/contrast, reduced motion, responsive UI and browser checks. Applicable presentation lessons, not an authoritative persistence or settings-registry specification. |
| [OpenAI GPT-6 skill and prompt design](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra) (first-party provider guidance, published 2026) | Warns against overly broad skill triggers, repetitive instruction loads and unnecessary process. ADOPT: keep description narrow, load bounded references on demand, use explicit negative cases; do not turn model-specific advice into permanent platform fact. |
| [OpenAI Codex custom code-review rules](https://developers.openai.com/blog/custom-code-review-rules-for-codex/) (provider review guidance) | Prefer a small number of high-value, non-obvious invariants and testable rules over expansive style instructions. ADOPT: scope reviews to settings ownership, scopes, explicit OFF and real effect, not cosmetics or every possible UI variation. |
| [GitHub Copilot path-specific repository instructions](https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions) (provider documentation) | Broad AGENTS/repository instructions, path-scoped instructions and on-demand Skills have different loading properties. ADOPT a short routing entrypoint for Options-related changes, and keep implementation recipe in the specialist reference. |
| [Anthropic skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices) and [research on equipping agents with skills](https://www.anthropic.com/research/equipping-agents-for-the-real-world-with-agent-skills) (provider guidance) | Clear triggers, concise entrypoint, references one level deep, concrete examples, progressive disclosure and scenario-based evaluation. ADOPT packaging and evaluate actual agent choices. They are claims about recommended practice, not a measurement of our new Skill. |
| [GitHub Copilot customization comparison](https://docs.github.com/en/copilot/concepts/customizations/comparing-customizations) and [skill instructions](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills) (provider guidance) | Always-on AGENTS/custom instructions for short broad local standards; on-demand skills for task-specific workflow; tools for capabilities. ADOPT minimal routing; avoid dumping every settings procedure into AGENTS. |
| [Google Android testing skill index](https://github.com/skydoves/android-testing-skills/blob/main/INDEX.md) (community) | Platform-focused test mechanics are useful for actual UI/device evidence, but not a replacement for requirements authority, test-contract review or provider proof. |

**Audit limitation:** Search covered first-party Android, Apple interface guidance, VS Code configuration and first-party specialist skill, mature schema form frameworks, a sample of public skill catalogs, and provider prompt/skills/review guidance (OpenAI, Anthropic, GitHub). Public code/skills are mutable and online search is not exhaustive; no controlled agent outcome experiment was conducted. Do not describe the audit as a full systematic survey or source popularity as validation.

## Existing private Skills package: gap analysis

**Inspected source:** GBIDEVELOPMENT/general-purpose/skills at the audited branch base. Audit focused on the actual entrypoints and references relevant to this problem, not every paragraph of every historical file.

- **Android App Development SKILL.md:** already has single state owner, Compose side-effect prohibition, smallest conventional fix, changed-test preflight, batched validation, observable real user journey and experience impact. **Gap:** no sequence for settings inventory, scoped effective resolution, declarative page placement, reset, or migration for an explicit OFF.
- **Android platform-playbooks.md:** discusses Compose, Room, lifecycle and accessibility; **gap:** treats settings only generically.
- **Product Specification Engineering SKILL.md and contract-construction.md:** defines per-control ID, visibility, state holder, default/override, user outcome. **Gap:** no setting-specific distinction of semantic definition / scoped instance / effective value / UI placement / remote effect.
- **Experience Conformity:** already enforces placement, actual pixels and interaction in affected UI, avoiding mere backend success. **Integration:** a settings page uses this existing process; no new universal screenshot ritual.
- **Mechanised Expertise:** source-to-procedure conversion, trigger/exclusion/oracle/stop and consequential question filtering. **Integration:** new Skill uses a narrow S0–S8 procedure instead of duplicating that generic framework.
- **Validation Economics / change-aware test contracts:** already cover batched expensive setups and obsolete tests. **Integration:** reuse; do not create independent settings-only CI bureaucracy.
- **Operational Documentation Integrity:** keep plans and active specs instructional and current. **Integration:** settings inventory lives in the host's current authoritative spec/work item; history and source audits remain separate.
- **Capability Architecture and PORTABILITY:** single primary skill, relative local links, no private source-repo runtime requirement. **Integration:** new skill is an independent task-matched capability, Android routes to it only if task warrants.

**Chunks-specific observed distinction:** Chunks already declares an eight-category navigation taxonomy and persistent settings owners, but Options taxonomy is not generally a schema of reusable independent setting definitions; existing authoritative Options contract requires stable IDs, one write path, scope/provenance, search, immediate-apply behavior and preservation of explicit choices. Product owner has approved moving original .torrent source-file handling from per-Add selection to global Options. Specific implementation must be investigated afresh on current main and must not assume this plan is shipped.

## Concrete judgments for our own Skills

### Adopt now

1. **Classification gate first**: preference vs contextual control vs action vs complex editor.
2. **Traceable inventory** of UI, keys, writers, consumers, scopes, old values and test contracts.
3. **Typed definition and exact scoped instance separated** from one or more visual placements.
4. **Effective value resolution with provenance** and reset as removing the intended override, not writing a magic default everywhere.
5. **Single authoritative persistence owner**, reactive UI, validated commit and truthful side-effect state.
6. **Default and migration semantics** distinct for existing explicit choices and fresh installs.
7. **Page manifests and interchangeable controls** only at a scale where they beat explicit UI.
8. **Visibility, access and capabilities** with readable reasons and predictable accessibility/search.
9. **Focused proof** from real UI through stored effective value to actual consumer, with sensible batching.
10. **Agent activation and counterexamples** avoiding unnecessary architecture and unnecessary testing.

### Conditional / require measured justification

- JSON Schema, runtime-loaded menus, visual form builders and user-defined UI layout: use only when necessary for interoperability/runtime configuration, not for static Kotlin preferences.
- General cross-platform storage libraries: first compare existing host framework/store, migration cost and performance.
- Automatic settings indexing and deep linking: valuable for many pages; not mandatory for a tiny app.
- Automatic source-code generation from a settings manifest: justified only if duplication is already creating significant error.
- Strong generic restrictions on remote state: each real endpoint and operation has its own postcondition and uncertainty model.

### Reject as general mandates

- A Kotlin class/file for every individual boolean.
- A fully generic visual renderer that removes special-purpose editors.
- Treating each UI placement as a separate persistent preference.
- Treating a default value as proof of user consent, or an explicit OFF as equivalent to unset.
- Moving all controls into Options regardless of task frequency.
- Declaring all settings changes to need a Save button, or all settings to apply immediately regardless of multi-field validity.
- Testing every setting relocation with a full app rebuild before coherent implementation batches.
- Automatic upgrades of storage framework or broad architecture rewrites without an observed user benefit.

## Evaluation plan (not yet executed)

Use [decision cases](../references/decision-cases.md) as blind prompts with three agent variants where economical: (A) no new settings skill, (B) concise entrypoint only, (C) full targeted references. Measure (1) accepted owner-intent coverage, (2) incorrect state/override/write boundaries, (3) lost explicit OFF, (4) unnecessary framework/CI work, (5) correctly chosen real UI and consumer verification. Include positive and negative cases and independently inspect diffs, not agent confidence. The result can justify trimming or strengthening instructions.

## Maintainer notes

All internal skill links must remain relative; source URLs above are references, not instructions to install or execute third-party code. Read source release/target-SDK prerequisites before implementation. Re-check live repo state before any Chunks migration or claim of completed UX. No local project gains the new skill until a reviewed canonical version is actually copied and routed into that project's agent runtime.
