# Eight additional source-to-mechanism transformations

These eight **source-pinned publisher recommendations** extend the prior 22 curated patterns. They were read at the specified Git revisions. Their advice is evidence of what was published, not independent proof of effectiveness or correctness. The resulting procedure definitions in the companion source-mechanisms catalog are **our editorial transformation**, not claims that these authors developed mechanised intuition.

| Pattern | Original recommendation | Conditional expert-cognition mechanism |
|---|---|---|
| PRX-023 | [Room migration upgrade evidence](https://github.com/revanthkumarJ/android-skills/blob/55c939c299e83d3cd4b7b75fa5a9abc89f3758a3/skills/android-room-offline-cache/SKILL.md#L71) | Convert the difference between successful compilation and persisted installed upgrade into an exact data-retention test. |
| PRX-024 | [Android work scheduling choice](https://github.com/GuillemRoca/agent-skills-android/blob/68fd141eec5ad2029ae8393e510dbe46dea48c36/skills/android-background-work/SKILL.md#L29) | Derive scheduling questions from user-required lifetime and supported API restrictions; test actual interruption. |
| PRX-025 | [Nextcloud event migration inventory](https://github.com/nextcloud/android/blob/3b0f7bb0d448ba0c748226936050c06b415b1727/.claude/skills/android-eventbus-to-workmanager-flow/SKILL.md#L47) | Reconstruct publisher, subscriber, identity and terminal-state ownership before replacing a UI update channel. |
| PRX-026 | [Compose test concern](https://github.com/chrisbanes/skills/blob/d059de165cfba3aee36d5e7bd901a75d41a3dfd7/skills/compose-ui-testing-patterns/SKILL.md#L14) | Convert a behavior claim into the narrowest sufficient observable assertion, not a prescribed new harness. |
| PRX-027 | [Composable external state ownership](https://github.com/siifii/android-agent-engineering/blob/1dc314ad0ebd3815926b0f24de89716033c78d69/skills/design-system/design-system-component-authoring/SKILL.md#L72) | Audit duplicate state ownership and controlled component behavior without copying a project-specific component API. |
| PRX-028 | [Home Assistant observable errors](https://github.com/home-assistant/android/blob/d1763fc20efdd3cb4f2f50e798f5bdf47269bf81/.agents/skills/ha-android-logging-errors/SKILL.md#L27) | Trace reachable failure and cancellation paths; make the outcome visible while separating diagnostic context from private user data. |
| PRX-029 | [Official Android cached permission audit](https://github.com/android/skills/blob/6daa53ef27fa2aa87827d183a85c06cedceca833/security/android-permissions-security/SKILL.md#L72) | Ask whether an actual permission still applies at invocation after a prior grant or changed state. |
| PRX-030 | [Official Android nested intent trust checks](https://github.com/android/skills/blob/6daa53ef27fa2aa87827d183a85c06cedceca833/security/android-intent-security/SKILL.md#L89) | Map external caller, untrusted fields, approved destination and observable rejection; preserve accepted Share workflows. |

## What transfers and what remains scoped

- The requirements to preserve data on upgrade, bind events to exact identity, maintain truthful feedback, and test user-visible outcomes are broadly transferable **problem distinctions**. Room, WorkManager, Compose, Timber, IntentSanitizer and specific Android permission APIs are version- and platform-scoped mechanisms.
- An Android source can be correct about the condition it covers and still be inappropriate for a project whose shipping architecture or minimum SDK is different. Each new mechanism contains a trigger and an exclusion, and requires an independent oracle at the actual user or platform boundary.
- The existing corpus still contains 1,073 recorded recommendations including potentially repeated and editorial syntheses. Thirty curated source-derived procedures do not establish full semantic coverage of that corpus or an experimentally demonstrated improvement.
- The original 22-source table is preserved under its historical filename. These eight are a separate addendum to avoid silently rewriting source history.

## Conversion invariant

Source claim -> observable cue -> named input facts -> ordered typed operations -> opposite decision branches -> cheapest distinguishing observation -> independent oracle -> stop. A vague phrase such as 'apply senior engineering judgment' cannot substitute for any step.
