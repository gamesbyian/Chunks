# Building the first Android slice

The repository currently has a source-pinned Gradle version but no committed binary Gradle wrapper JAR because the connected GitHub text API cannot copy binary files reliably. This is an explicitly tracked bootstrap limitation; no shell script masquerades as a wrapper. Install Gradle 9.3.1 and JDK 17 locally, or use the GitHub Actions setup-gradle task which installs the exact version.

Run: gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

The workflow installs Android API 37 and uses the same successful SDK 37.0 compatibility approach adopted by Glimpse. Current APK is a pre-alpha native rTorrent remote with a dashboard, challenge-negotiated Basic/Digest auth, Android SAF `.torrent` picker, explicit stopped Add review and confirmed transfer Start/Stop controls. These features compile but do not constitute accepted live Seedhost or physical-device behavior. Actual filesystem move/rename/delete-with-data and the local BitTorrent engine are not implemented. No remote server credentials are embedded or stored.

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

## Hosted ruTorrent URL handling

[Run 40062658654](https://github.com/gamesbyian/Chunks/actions/runs/40062658654) passed `:app:testDebugUnitTest` and `:app:assembleDebug` at PR SHA `b7fc42a8b6d8307ee00309fddb256dbbca9a91c4` (merged #20). The connection form now accepts ruTorrent HTML URLs and derives a candidate `/plugins/httprpc/action.php` endpoint. This compile/test result does not confirm the endpoint works for the owner's server. Use the manual-only [Seedhost RPC smoke workflow](https://github.com/gamesbyian/Chunks/actions/workflows/seedhost-readonly-probe.yml) once the relevant Chunks repository secrets are confirmed.

## Current native build evidence

[CI run 40125371644](https://github.com/gamesbyian/Chunks/actions/runs/40125371644) passed `:app:testDebugUnitTest :app:assembleDebug` for the confirmed transfer lifecycle UI (#49), PR SHA `99322233b093cd9b8dd75837e7fb2fc0f5a5094e`. Artifact: `chunks-debug-prealpha`. The two-stage Add screen (#44) and exact-byte binary load (#43) were independently merged after their focused CI. Native emulator screenshot/hierarchy PRs #45/#48 are separate gates: compilation alone is not a captured screenshot or a real-device usability signoff.

## Current integration baseline (2026-10-24)

Current `main` includes device-local editable transfer aliases (#74), lazy-queue alias sort optimizations (#78), read-only ZIP structural inventory (#79/#85), Android platform XML-RPC parser compatibility (#81), storage-warning policy hysteresis (#87) and session mutation confirmation recycling safety (#89). The #89 merge `3e11d502dca11513e7b272676aab49079280e10d` had Android/JVM [40143850496](https://github.com/gamesbyian/Chunks/actions/runs/40143850496) and API35 [40143850653](https://github.com/gamesbyian/Chunks/actions/runs/40143850653) green at source `cdea90ce1e62cd7d82c6013a24a1dc17846e5389`. Native installed-app populated synthetic acceptance remains [draft #82](https://github.com/gamesbyian/Chunks/pull/82); physical phone, full hosted Seedhost adapter inventory, local download/seeding engine, real quota enforcement/extraction and filesystem mutation acceptance are still outstanding. Consult WORK-QUEUE and QUALITY-LEDGER for live gates; historical build examples above are not the present feature inventory.
