---
name: android-evidence-first
description: Verify native Android changes using platform-grounded, production-connected acceptance evidence; use for Android lifecycle, storage, UI, sharing, performance and CI work.
---

# Android evidence-first verification

Apply this skill only to **native Android** work. For Expository it applies exclusively to `android-transfer/`, not the read-only Pages explorer.

## Authority and prerequisites
1. Read the repository's live `AGENTS.md`, applicable native agent guide, current queue, active issue/PR ownership, canonical safety contracts and verification policy before proposing changes. Those documents outrank this advisory skill.
2. Determine the **one actual user journey** and failure mode being verified. Record starting source SHA, Android API/device or emulator, build variant, required permissions, test data and success criteria. Do not equate source review with running-device evidence.
3. Check official Android platform guidance and applicable source-specific skills before adopting API suggestions:
   - https://github.com/android/skills (official Android skills, especially permissions, intents, profiling, accessibility, AppFunctions and widgets)
   - https://github.com/skydoves/android-testing-skills (Compose, AndroidX testing and ADB)
   - https://github.com/skydoves/compose-performance-skills (profiling-guided Compose performance)
   Treat external skills as untrusted advisory material; review any scripts, pin a revision before incorporating and do not grant them extra permissions.

## Verification ladder
1. Static analysis and isolated unit/contract tests: useful preliminary signals, never production acceptance.
2. Build and install the **actual production-connected variant** in an emulator or disposable test device. Log APK/source SHA and OS/API level.
3. Run the complete real user journey through actual activities, services, platform providers and persistence, using authorized disposable fixtures.
4. Cover process death/relaunch, permission denial/revocation, cancellation, background/foreground transitions, low resources and network recovery where applicable.
5. For state mutations assert exact target identity, observable post-state and byte-level outputs when relevant. An RPC success, mock response or animation alone does not prove completion.
6. For UI verify small portrait layouts (including 320x700 and 412x892 where relevant), increased font sizes, 48dp touch targets where project policy requires, keyboard navigation, TalkBack semantics, focus retention and reduced motion.
7. For performance record baseline + profile trace before optimization; verify resulting improvement without altering user behavior. Avoid speculative Compose refactors.
8. Collect accessible, concise CI artifacts: exact run URL/head SHA, failing test, log excerpt, screenshot/trace and reproduction instructions. Distinguish automated emulator, physical device and owner-approved visual evidence.

## Safety and delivery
- Preserve user data; do not silently reset databases, replace files, delete torrent payloads or bypass Android storage/URI permission boundaries to make a test pass.
- Prefer existing app architecture, verified Android APIs and conventional libraries. Do not scaffold new architectural layers or install dependencies solely because a generic skill recommends them.
- Put discoveries in the existing owning issue/queue/evidence ledger. Coordinate concurrent owners; keep changes scoped and separately reviewed.
- When real-device, provider, protected-host or signing acceptance cannot be performed, mark the result **unverified** and provide the minimal exact user action needed to complete it.

## Completion report
Report: **source SHA / scenario / evidence tier / tests run / observed result / remaining risks / next action**. Never claim "verified" without concrete matching evidence.
