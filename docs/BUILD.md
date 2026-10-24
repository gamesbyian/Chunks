# Building the first Android slice

The repository currently has a source-pinned Gradle version but no committed binary Gradle wrapper JAR because the connected GitHub text API cannot copy binary files reliably. This is an explicitly tracked bootstrap limitation; no shell script masquerades as a wrapper. Install Gradle 9.3.1 and JDK 17 locally, or use the GitHub Actions setup-gradle task which installs the exact version.

Run: gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

The workflow installs Android API 37 and uses the same successful SDK 37.0 compatibility approach adopted by Glimpse. Initial APK is a read-only rTorrent XML-RPC probe/list UI. It does not include move/rename/add torrent or local BitTorrent engine and must not be treated as a working full client. No remote server credentials are embedded or stored.

Evidence: [run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588) passed 42 Gradle tasks including JVM tests and assembly against source SHA `201b53fc3d16482cf2aa1a08c9c01d9e0351174b`; its artifact is `chunks-debug-prealpha`. Subsequent source changes require new proof. Build artifacts are debug only; a successful Gradle job is not a real-server or real-device acceptance.
