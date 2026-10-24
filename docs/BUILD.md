# Building the first Android slice

The repository currently has a source-pinned Gradle version but no committed binary Gradle wrapper JAR because the connected GitHub text API cannot copy binary files reliably. This is an explicitly tracked bootstrap limitation; no shell script masquerades as a wrapper. Install Gradle 9.3.1 and JDK 17 locally, or use the GitHub Actions setup-gradle task which installs the exact version.

Run: gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

The workflow installs Android API 37 and uses the same successful SDK 37.0 compatibility approach adopted by Glimpse. Initial APK is a read-only rTorrent XML-RPC probe/list UI. It does not include move/rename/add torrent or local BitTorrent engine and must not be treated as a working full client. No remote server credentials are embedded or stored.

Evidence: a green CI result is required before describing the project as compilable. Build artifacts are unsigned debug only; a successful Gradle job is not a real-server or real-device acceptance.
