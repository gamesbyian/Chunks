import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

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
 * Immutable, configuration-cache-safe check of the approved launcher art.
 * Uses a declarative task type so Gradle never has to serialize the
 * build.gradle.kts script instance when storing its configuration cache.
 */
abstract class VerifyChunksLauncherArtwork : DefaultTask() {
    @get:InputFile abstract val artwork: RegularFileProperty
    @get:InputFile abstract val adaptiveXml: RegularFileProperty
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:Input abstract val approvedGitBlob: Property<String>

    @TaskAction
    fun verify() {
        val file = artwork.get().asFile
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
        check(blob == approvedGitBlob.get()) {
            "Launcher art differs from owner-approved original; review replacement explicitly."
        }
        val icon = adaptiveXml.get().asFile.readText()
        check(icon.contains("@drawable/chunks_launcher_artwork") &&
            !icon.contains("@drawable/ic_chunks_foreground") &&
            !icon.contains("@drawable/ic_chunks_monochrome")) {
            "Adaptive launcher icon must use the approved artwork"
        }
        val app = manifest.get().asFile.readText()
        check(app.contains("android:icon=\\\"@mipmap/ic_launcher\\\"".replace("\\\"", "\"")) &&
            app.contains("android:roundIcon=\\\"@mipmap/ic_launcher\\\"".replace("\\\"", "\""))) {
            "Application and round icons must both use ic_launcher"
        }
    }
}

val verifyChunksLauncherArtwork by tasks.registering(VerifyChunksLauncherArtwork::class) {
    group = "verification"
    description = "Check approved launcher PNG identity and Android adaptive icon wiring"
    artwork.set(layout.projectDirectory.file("src/main/res/drawable-nodpi/chunks_launcher_artwork.png"))
    adaptiveXml.set(layout.projectDirectory.file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    approvedGitBlob.set("03a8677e9c90f4b455e67683fcc4a2b79517b1ea")
}
tasks.named("preBuild").configure { dependsOn(verifyChunksLauncherArtwork) }
