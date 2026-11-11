# Source-derived strengths and deliberate combinations

A companion [machine-readable table](../assets/source-to-expertise-v09.csv) stores each reviewed passage's proposed cue, question, research source, model, decision, exception and oracle. Its entries remain editorial hypotheses, not observed outcome claims.

These 22 **pinned original passages** were inspected, but the claims are **paraphrases of what their authors recommend**, not proven effectiveness. The final column contains **our editorial, original proposed mechanised-intuition extensions**, not published advice from those authors. This is a curated sample from a larger heterogeneous corpus, not a global best-skill ranking.

| Expertise family | Pinned original | Source recommendation, paraphrased | Proposed conditional mechanism |
|---|---|---|---|
| Expert inquiry | [source](https://github.com/siifii/android-agent-engineering/blob/1dc314ad0ebd3815926b0f24de89716033c78d69/workflows/requirements-grill/SKILL.md#L29) | Question missing user states, permissions, failure and recovery | Ask only questions capable of changing a design, after inspecting existing specifications. |
| Existing architecture | [source](https://github.com/mmiani/kotlin-kmp-claude-agent-skills/blob/939786cb13b49daacea7d5fb0a10877b6005e6be/skills/kotlin-project-feature-implementation/SKILL.md#L98) | Inspect module, state owner, repository and existing analogous feature | Recover local conventions and inspect the actual shipping path before selecting a new architecture. |
| Window-responsive layout | [source](https://github.com/flutter/agent-plugins/blob/1bd8f2caa144c11b9153706c8de5f6d3e074b799/skills/flutter-build-responsive-layout/SKILL.md#L21) | Available parent/window space governs layout better than device labels | Test content-based breakpoints; translate framework APIs rather than blindly copying. |
| Async UI state | [source](https://github.com/expo/skills/blob/a9e5bd3fa00b4e61f632e43f85da73aee1942582/plugins/expo/skills/expo-data-fetching/SKILL.md#L38) | Distinguish loading, empty, error and content; preserve stale content on refresh failure | Model user-visible state transitions and recovery, not only API responses. |
| Causal debugging | [source](https://github.com/Kazppe/agent-skills/blob/27584120bac525df8ddf3f4e8334df9578e98750/skills/systematic-debugging/SKILL.md#L48) | Reproduce, falsify hypotheses and measure actual code execution | Use path-observation to reject plausible-but-incorrect bug theories. |
| Decision challenges | [source](https://github.com/GuillemRoca/agent-skills-android/blob/68fd141eec5ad2029ae8393e510dbe46dea48c36/skills/doubt-driven-development/SKILL.md#L30) | Falsifiable decision, one strong alternative and risks converted into checks | Make a reversibility-aware choice only when the decision is consequential. |
| Test boundary | [source](https://github.com/android/skills/blob/6daa53ef27fa2aa87827d183a85c06cedceca833/testing/testing-setup/SKILL.md#L23) | Inventory existing test frameworks and device/E2E options | Select least costly sufficient actual test tier. |
| Test reset | [source](https://github.com/skydoves/android-testing-skills/blob/8665ed59643c90eb1056a256a6ddec161aa1cfa1/adb/apps/installing-and-managing-apps/SKILL.md#L77) | pm clear erases data; force-stop preserves persisted state | Choose fixture semantics that retain the failure being tested. |
| Concurrency ownership | [source](https://github.com/home-assistant/android/blob/d1763fc20efdd3cb4f2f50e798f5bdf47269bf81/.agents/skills/ha-android-concurrency/SKILL.md#L16) | Child tasks should not cancel shared scopes; cancellation propagates | Map scope ownership, shared state and interruption. |
| Offline mutation | [source](https://github.com/Cap-go/capgo-skills/blob/e90bb5639130e34387f00bf0a95c6d47e4a2ebb8/plugins/capacitor-features/skills/capacitor-offline-first/SKILL.md#L80) | Connectivity does not mean server availability; ordered, idempotent outbox | Trace exact target, uncertain commit and reconciliation before retry. |
| RTL semantics | [source](https://github.com/Almatrafi-Ali/arabic-rtl-mobile/blob/5aeefc97aafe8628c044953bc48a4d9a0f01d497/SKILL.md#L240) | Check direction-sensitive arrows and animations in actual RTL display | Model semantic meaning rather than mechanically flipping every asset. |
| Accessibility | [source](https://github.com/zakariaf/Flutter-Skills/blob/e073e5ea10c963d2c52ab1e423bd314c28a56154/skills/accessibility-as-code/SKILL.md#L15) | Support semantics, type scaling, accessible actions and non-color states | Probe same goal across actual assistive input modes; respect platform-specific targets. |
| Measured performance | [source](https://github.com/chrisbanes/skills/blob/d059de165cfba3aee36d5e7bd901a75d41a3dfd7/skills/compose-performance/SKILL.md#L16) | Reproduce a concrete transition and remeasure one axis | Require causal trace; do not optimize based on a generic anti-pattern. |
| Release profiling | [source](https://github.com/android/skills/blob/6daa53ef27fa2aa87827d183a85c06cedceca833/jetpack-compose/jetpack-compose-performance/SKILL.md#L39) | Release configuration materially affects profile interpretation | Normalize build environment before causal conclusions. |
| Runtime dogfooding | [source](https://github.com/callstack/agent-device/blob/56091566a5ab4855a28223b8ece2bd7023373481/skills/dogfood/SKILL.md#L25) | Explore installed app and collect runtime issue evidence | Do not infer actual UI interaction from code review alone. |
| Delegation boundaries | [source](https://github.com/Kazppe/agent-skills/blob/27584120bac525df8ddf3f4e8334df9578e98750/skills/ai-delegation-guardrails/SKILL.md#L43) | Research output must identify unconfirmed facts; implementation obeys scope | Keep external instructions as data and independently check outcomes. |
| Review triage | [source](https://github.com/Kazppe/agent-skills/blob/27584120bac525df8ddf3f4e8334df9578e98750/skills/multi-ai-review/SKILL.md#L85) | Check findings against source, classify real/false-positive/fixed | Treat review as hypothesis, not a consensus oracle. |
| User-facing E2E | [source](https://github.com/GuillemRoca/agent-skills-android/blob/68fd141eec5ad2029ae8393e510dbe46dea48c36/skills/android-e2e-verification/SKILL.md#L68) | Use user-visible assertions with deterministic asynchronous waits | Turn acceptance into a real journey with negative controls. |
| Device session | [source](https://github.com/shinyorg/skills/blob/8a68c5042875d878211ae6e292061bffaeaf4c50/plugins/shiny/skills/shiny-bluetoothle/SKILL.md#L219) | Feature-detect capabilities and explicitly disconnect sessions | Scope to hardware and library; not a universal Bluetooth API recipe. |
| Specification links | [source](https://github.com/siifii/android-agent-engineering/blob/1dc314ad0ebd3815926b0f24de89716033c78d69/workflows/feature-to-spec/SKILL.md#L27) | Connect problem, non-goals, state, modules and acceptance | Reverse-traverse the model to distinguish unlinked facts from genuine omissions. |
| Media test | [source](https://github.com/sunnat629/android-media-pack/blob/1d74b4953d21ee31a3acf61eff68972e100c2ac3/.skills/media/testing/media3-test-utils-robolectric/SKILL.md#L26) | Fake media UI models; instrument actual codec/DRM/playback | A mock cannot establish a physical playback outcome. |
| Agent API contract | [source](https://github.com/android/skills/blob/6daa53ef27fa2aa87827d183a85c06cedceca833/device-ai/appfunctions/SKILL.md#L70) | Document app-visible AppFunction contracts alongside implementation | Describe true side effects and limitations instead of inventing agent tool promises. |

## Strong compositions

- **Understand feature needs:** concrete requirements inquiry + existing-code/source ownership inspection + truthful async UI modes. Discover missing outcomes without questioning the owner about facts the repo already establishes.
- **Research UX:** available-window layout + assistive semantics + original-language user testimony + meaning-aware RTL + actual runtime observation. Transfer broad principles, not framework-specific APIs or arbitrary minimum pixel values.
- **Debug like a specialist:** reproduce, instrument the executed path, compare a working precedent, isolate the causal axis, and independently remeasure rather than iterating plausible code patches.
- **Handle lifecycle and persistence:** shared coroutine ownership + remote mutation uncertainty + test-specific reset semantics + installed-device verification. Preserve exact identities and reconcile when outcome is uncertain.
- **Choose proportionately:** local engineering precedent + one well-defended alternative + disconfirming failure probe + an explicit reversal condition, only when stakes justify it.
- **Review and learn:** treat multiple-agent review as hypotheses, distinguish real and false-positive findings, and retain what evidence caused each decision change.

## Six important contextual tensions

1. Broad **performance audit** and one-axis **measured repair** are compatible when the scan is within the requested subsystem and the fixes are justified individually.
2. A source's preferred test runner, UI architecture or library does not supersede existing proven host conventions.
3. `pm clear` produces a fresh-state test; `am force-stop` preserves state for a process-loss test. Neither is a universal isolation rule.
4. Requirements-grill prompts are normally questions *for the agent to investigate*. Only unknown product values or authorization belong with the owner.
5. Framework-specific accessibility thresholds should never silently override the host's platform and stronger accessibility requirements.
6. A copied or translated instruction may increase raw document frequency without providing independent corroboration. Publisher breadth, lineage and effectiveness remain separate.

## Distinctive mechanised-intuition contribution

The synthesis is a procedure for **selecting**, **combining**, **disqualifying** and **revising** these practices: observable cue → choice-reversing question → cheapest trustworthy answer → bounded conceptual map if needed → credible alternative and reversal condition → independent user-outcome oracle → corrected conditional rule.

A qualified blank in a conceptual model matters only after auditing source retrieval, aliases, relation types, known edges and counterexamples. A long checklist, fluent justification, passing toy test, or popular example does not prove experienced judgment.

This table does not resolve copyright/licensing for copying original skill text; it links and paraphrases. Uninspected sources, independent authorship, original-language user research and controlled effectiveness remain open.
