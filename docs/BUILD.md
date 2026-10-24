# Building the first Android slice

The repository currently has a source-pinned Gradle version but no committed binary Gradle wrapper JAR because the connected GitHub text API cannot copy binary files reliably. This is an explicitly tracked bootstrap limitation; no shell script masquerades as a wrapper. Install Gradle 9.3.1 and JDK 17 locally, or use the GitHub Actions setup-gradle task which installs the exact version.

Run: gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

The workflow installs Android API 37 and uses the same successful SDK 37.0 compatibility approach adopted by Glimpse. Initial APK is a read-only rTorrent XML-RPC probe/list UI. It does not include move/rename/add torrent or local BitTorrent engine and must not be treated as a working full client. No remote server credentials are embedded or stored.

Evidence: [run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588) passed 42 Gradle tasks including JVM tests and assembly against source SHA `201b53fc3d16482cf2aa1a08c9c01d9e0351174b`; its artifact is `chunks-debug-prealpha`. Subsequent source changes require new proof. Build artifacts are debug only; a successful Gradle job is not a real-server or real-device acceptance.

## Recovered second verified source

Merged PR #12 provided read-only synthetic loopback RPC transport tests. [Run 40057876761](https://github.com/gamesbyian/Chunks/actions/runs/40057876761) passed `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug` on head `5b5b75ed4abaa9f4e97ec4c7e4fa517130e8f681`. A `chunks-debug-prealpha` artifact was produced. These tests do **not** prove real rTorrent auth/proxy interoperability or Android on-device behavior.

See [critical review](CRITICAL-REVIEW-2026-10-24.md) and [quality ledger](QUALITY-LEDGER.md) before extending network or filesystem capabilities.

## New read-only Android dashboard (2026-10-24)

[CI run 40060156362](https://github.com/gamesbyian/Chunks/actions/runs/40060156362) passed `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug` on PR source `7a7aae0a811542d38b3b523640d05f9d24fccbd7` and published a `chunks-debug-prealpha` Actions artifact. Merge commit: `a1573a4cf7047f40e3221d4222c87650ce2937ae`. This is a debug APK, not a signed release. Connect only to a protected HTTPS rTorrent RPC endpoint; credentials are volatile. The Add destination editor previews locally but never sends an Add command. No Android handset acceptance or live rTorrent interoperability has been recorded.

## Android magnet handoff (2026-10-24)

[CI run 40060539699](https://github.com/gamesbyian/Chunks/actions/runs/40060539699) passed on PR SHA `d586f712da63ba5c56a3d275ec68e29eb8834a7b`. It produced another `chunks-debug-prealpha` artifact. The build handles Android ACTION_VIEW magnet links and ACTION_SEND text/plain into a local, unsent Add draft. No server write or actual destination verification occurs. No physical Android Sharesheet/intent observation has yet been recorded.

## Reproducible real rTorrent smoke route

The manual-only [Disposable rTorrent protocol proof](https://github.com/gamesbyian/Chunks/actions/workflows/disposable-rtorrent-probe.yml) installs a throwaway rTorrent 0.9.8 and nginx SCGI proxy on a GitHub runner, and tests version/probe/empty listing through Chunks' JVM adapter, with loopback access only. First completed positive evidence: [run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245), source `cd337cc8560c8c1d90c581be3de6f61176106e1c`. This is neither an app APK build nor a live seedbox acceptance. It uses no user credentials or media. It is retained manual-only to respect the shared runner budget.
