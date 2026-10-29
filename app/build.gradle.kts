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
        // Release builds supply a monotonically increasing code; dev stays 1.
        versionCode = providers.environmentVariable("CHUNKS_VERSION_CODE").orNull
            ?.toIntOrNull()?.takeIf { it in 1..2_100_000_000 } ?: 1
        // CI provides the actual checked-out commit, including the synthetic PR merge
        // commit when applicable. Local builds without this environment variable
        // retain the plain development version and must not invent provenance.
        val builtCommit = providers.environmentVariable("CHUNKS_SOURCE_SHA").orNull
            ?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.take(12)
        // A release workflow may supply a plain semantic version instead.
        val releaseName = providers.environmentVariable("CHUNKS_VERSION_NAME").orNull
            ?.trim()?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?")) }
        versionName = releaseName ?: ("0.1.0-dev" + (builtCommit?.let { "+$it" } ?: ""))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing is opt-in and secret-driven: all four values must come
    // from the environment (CI secrets), never from the repository. Without
    // them assembleRelease yields only an *unsigned* APK, which the release
    // workflow refuses to publish.
    val releaseKeystore = providers.environmentVariable("CHUNKS_RELEASE_KEYSTORE").orNull
    val releaseStorePassword = providers.environmentVariable("CHUNKS_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("CHUNKS_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("CHUNKS_RELEASE_KEY_PASSWORD").orNull
    val releaseSigningAvailable = listOf(releaseKeystore, releaseStorePassword,
        releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrEmpty() }
    signingConfigs {
        if (releaseSigningAvailable) create("release") {
            storeFile = file(releaseKeystore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            // No shrinking yet: libtorrent4j's JNI bindings need audited keep
            // rules before R8 can be trusted with them.
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; resValues = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
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
    // JVM screenshots of real Compose UI (Robolectric native graphics) for
    // visual review against docs/design/reference-images; never a device claim.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Android 10's system storage-permission dialog is outside the app's UI.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    // libtorrent4j's native library installs its own signal handlers. The JVM relies on
    // SIGSEGV for stack guards and implicit null checks, so without signal chaining a
    // later ordinary test (e.g. deep XML parsing) kills the test JVM with exit 139.
    val signalChaining = File(System.getProperty("java.home"), "lib/libjsig.so")
    if (signalChaining.isFile) environment("LD_PRELOAD", signalChaining.absolutePath)
    // JVM screenshots (visual review only) are written under app/build/chunks-visual.
    systemProperty("roborazzi.test.record", "true")
}

/**
 * Prevent silent source drift: SVG is canonical, the runtime Android
 * VectorDrawable is a reproducible rendering, and the old PNG is reference-only.
 * This check requires no Node installation for ordinary Gradle builds;
 * CI additionally runs the deterministic generator's --check mode.
 */
abstract class VerifyChunksLauncherArtwork : DefaultTask() {
    @get:InputFile abstract val artwork: RegularFileProperty
    @get:InputFile abstract val sourceSvg: RegularFileProperty
    @get:InputFile abstract val referencePng: RegularFileProperty
    @get:InputFile abstract val adaptiveXml: RegularFileProperty
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:Input abstract val referencePngBlob: Property<String>

    @TaskAction
    fun verify() {
        fun gitBlob(bytes: ByteArray): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(("blob ${bytes.size}" + 0.toChar()).toByteArray(Charsets.UTF_8))
            sha1.update(bytes)
            return sha1.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val svg = sourceSvg.get().asFile.readBytes()
        check(svg.size in 1_000..100_000 && String(svg, Charsets.UTF_8).contains("<svg ")) {
            "Canonical Chunks SVG source is missing or invalid"
        }
        val reference = referencePng.get().asFile.readBytes()
        check(gitBlob(reference) == referencePngBlob.get()) {
            "Historical owner-supplied PNG reference was modified"
        }
        val vector = artwork.get().asFile.readText()
        check(vector.contains("<vector ") &&
            vector.contains("SOURCE_SVG_GIT_BLOB=${gitBlob(svg)}") &&
            vector.contains("<gradient ") && vector.contains("<clip-path ")) {
            "Android vector is stale or malformed; run node tools/generate_launcher_vector.cjs"
        }
        val icon = adaptiveXml.get().asFile.readText()
        check(icon.contains("@drawable/chunks_launcher_artwork") &&
            !icon.contains("@drawable/ic_chunks_foreground") &&
            !icon.contains("@drawable/ic_chunks_monochrome")) {
            "Adaptive launcher foreground must consume the SVG-derived native vector"
        }
        val app = manifest.get().asFile.readText()
        val quote = 34.toChar().toString()
        check(app.contains("android:icon=" + quote + "@mipmap/ic_launcher" + quote) &&
            app.contains("android:roundIcon=" + quote + "@mipmap/ic_launcher" + quote)) {
            "Application and round icons must both use ic_launcher"
        }
    }
}

val verifyChunksLauncherArtwork by tasks.registering(VerifyChunksLauncherArtwork::class) {
    group = "verification"
    description = "Verify SVG-derived native vector and preserved reference PNG"
    artwork.set(layout.projectDirectory.file("src/main/res/drawable/chunks_launcher_artwork.xml"))
    sourceSvg.set(layout.projectDirectory.file("../docs/design/approved/chunks-app-icon-master.svg"))
    referencePng.set(layout.projectDirectory.file("../docs/design/approved/chunks-app-icon-master.png"))
    adaptiveXml.set(layout.projectDirectory.file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    referencePngBlob.set("03a8677e9c90f4b455e67683fcc4a2b79517b1ea")
}
tasks.named("preBuild").configure { dependsOn(verifyChunksLauncherArtwork) }


/** Real CFF source-of-truth font may be copied into Android resources, never silently forked. */
val verifyChunksWordmarkFont by tasks.registering {
    group = "verification"
    description = "Verify packaged Chunks display OTF and OFL license are byte-identical to repo masters"
    val sourceFont = rootProject.file("assets/fonts/chunks/Chunks-ExtraBold.otf")
    val packagedFont = project.file("src/main/res/font/chunks_extrabold.otf")
    val sourceLicense = rootProject.file("assets/fonts/source/OFL.txt")
    val packagedLicense = project.file("src/main/assets/licenses/Baloo2-OFL.txt")
    inputs.files(sourceFont, packagedFont, sourceLicense, packagedLicense)
    doLast {
        check(sourceFont.readBytes().contentEquals(packagedFont.readBytes())) {
            "Android font is stale: regenerate with the Chunks font family CI pipeline"
        }
        check(sourceLicense.readBytes().contentEquals(packagedLicense.readBytes())) {
            "Bundled modified-font OFL license must remain byte-identical to its source"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyChunksWordmarkFont) }
