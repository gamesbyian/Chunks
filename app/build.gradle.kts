import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.gamesbyian.chunks"
    compileSdk = 37
    defaultConfig {
        // GitHub CI debug signing keys need not match between builds.
        // CHUNKS_SIDE_BY_SIDE=1 produces a separately installable preview app:
        // never require uninstalling a previous Chunks with app-owned torrents.
        val sideBySide = providers.environmentVariable("CHUNKS_SIDE_BY_SIDE").orNull == "1"
        applicationId = if (sideBySide) "io.github.gamesbyian.chunks.preview"
                        else "io.github.gamesbyian.chunks"
        resValue("string", "chunks_app_name", if (sideBySide) "Chunks Preview" else "Chunks")
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        // CI provides the actual checked-out commit, including the synthetic PR merge
        // commit when applicable. Local builds without this environment variable
        // retain the plain development version and must not invent provenance.
        val builtCommit = providers.environmentVariable("CHUNKS_SOURCE_SHA").orNull
            ?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.take(12)
        versionName = "0.1.0-dev" + (builtCommit?.let { "+$it" } ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Local engine: Arvid Norberg libtorrent via libtorrent4j (not rTorrent's libTorrent).
    val libtorrent4j = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")
    testImplementation("org.libtorrent4j:libtorrent4j-linux:$libtorrent4j")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    // libtorrent4j's native library installs its own signal handlers. The JVM relies on
    // SIGSEGV for stack guards and implicit null checks, so without signal chaining a
    // later ordinary test (e.g. deep XML parsing) kills the test JVM with exit 139.
    val signalChaining = File(System.getProperty("java.home"), "lib/libjsig.so")
    if (signalChaining.isFile) environment("LD_PRELOAD", signalChaining.absolutePath)
}

/**
 * Owner-approved launcher icon is an immutable source asset.
 * Refuse ordinary and preview APK builds when its reference is missing,
 * replaced by a different PNG, or no longer wired through the adaptive icon.
 */
val approvedLauncherArtwork = "03a8677e9c90f4b455e67683fcc4a2b79517b1ea"
val verifyChunksLauncherArtwork by tasks.registering {
    group = "verification"
    description = "Check owner-approved Chunks launcher artwork and manifest wiring"
    inputs.file(layout.projectDirectory.file("src/main/res/drawable-nodpi/chunks_launcher_artwork.png"))
    inputs.file(layout.projectDirectory.file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    doLast {
        val file = layout.projectDirectory.file("src/main/res/drawable-nodpi/chunks_launcher_artwork.png").asFile
        check(file.isFile) { "Approved Chunks launcher PNG missing" }
        val bytes = file.readBytes()
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        check(bytes.size >= 100_000 && bytes.take(8) == signature.toList()) {
            "Approved launcher artwork must be the high-resolution PNG"
        }
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        sha1.update(bytes)
        val blob = sha1.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(blob == approvedLauncherArtwork) {
            "The committed launcher art differs from the owner-approved original. Review replacement explicitly."
        }
        val iconXml = layout.projectDirectory.file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
            .asFile.readText()
        check(iconXml.contains("@drawable/chunks_launcher_artwork") &&
            !iconXml.contains("@drawable/ic_chunks_foreground") &&
            !iconXml.contains("@drawable/ic_chunks_monochrome")) {
            "Android adaptive icon must use the owner-approved artwork"
        }
        val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile.readText()
        check(manifest.contains("android:icon=\\\"@mipmap/ic_launcher\\\"".replace("\\\"", "\"")) &&
            manifest.contains("android:roundIcon=\\\"@mipmap/ic_launcher\\\"".replace("\\\"", "\""))) {
            "The application and round icon must both use ic_launcher"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyChunksLauncherArtwork) }
