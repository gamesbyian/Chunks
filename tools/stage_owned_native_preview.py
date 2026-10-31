#!/usr/bin/env python3
"""Create a non-shipping libtorrent4j JNI overlay for an isolated Android CI checkout.

This tool *never edits* app/build.gradle.kts or other repository source. It
writes a proposed patched copy and test-only JNI/jar inputs to an external
staging directory. A separate, explicitly opt-in CI task may copy the patched
Gradle file into its disposable checkout, then build debug and instrumented
tests. Ordinary users and release workflows cannot import this overlay by
accident.

Input jar MUST be rebuilt with the pinned owned SWIG extension. A stock
libtorrent4j.jar lacks SessionParams.setOwnedDiskIO and is refused.
Input native .so MUST have the correct Android 64-bit ELF architecture.
Independent CI must additionally validate all JNI symbols and security
provenance of the actual .so; an untrusted manifest is never a signature.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path

UPSTREAM_JAVA = "da7d2403f06a0939bc2c51a88e67a6c4a85512ef"
UPSTREAM_LIBTORRENT = "24a3adf35d3e689bde700371337aad61b160bafa"
ALLOWED_ABIS = {"arm64-v8a": 183, "x86_64": 62}
MAIN_JAR_DEP = '    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")'
ARM_DEP = '    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")'
X86_DEP = '    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")'
APP_ID = ('applicationId = if (sideBySide) "io.github.gamesbyian.chunks.preview"\n'
          '                        else "io.github.gamesbyian.chunks"')
SOURCE_SET_ANCHOR = '    testBuildType = providers.gradleProperty("chunks.testBuildType").getOrElse("debug")'


# Generated ONLY into an external staging directory. This test instantiates
# the rebuilt owned-disk JNI constructor inside an installed disposable APK.
# It never downloads pieces, opens a user-selected path or accesses Downloads.
ANDROID_SMOKE = """package io.github.gamesbyian.chunks.engine

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.torrent_flags_t

@RunWith(AndroidJUnit4::class)
class OwnedJniSessionSmokeInstrumentedTest {
    @Test fun pinnedOwnedDiskConstructorStartsOnlyInsideDisposablePrivateRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Unique, exclusively created test-owned root. No name-based cleanup:
        // the emulator is disposable and its app-data volume is discarded.
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "owned-jni-").toFile()
        val receipts = File(root, "receipts")
        assertTrue(receipts.mkdir())
        Os.chmod(receipts.absolutePath, 448) // 0700 decimal
            val config = SettingsPack().listenInterfaces("127.0.0.1:0")
            config.setEnableDht(false)
            config.setEnableLsd(false)
            val params = SessionParams(config)
            params.setOwnedDiskIO(receipts.absolutePath)
            val native = SessionManager(false)
            try {
                native.start(params)
                assertNotNull("Installed custom JNI did not start", native.swig())
            } finally {
                native.stop()
            }
            assertTrue(receipts.isDirectory)
    }

    @Test fun ownedJniActuallyRefusesPreexistingForeignNativePayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "owned-native-canary-").toFile()
        val receipts = File(root, "receipts").apply { mkdir() }
        Os.chmod(receipts.absolutePath, 448) // exact test-private mode 0700
        val original = ByteArray(16 * 1024) { (it % 251).toByte() }
        val source = File(root, "seed/Foreign fixture").apply { mkdirs() }
        File(source, "payload.bin").writeBytes(original)
        val metadata = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val destination = File(root, "destination").apply { mkdirs() }
        val foreign = File(destination, "Foreign fixture/payload.bin")
        assertTrue(foreign.parentFile.mkdirs())
        val canary = ByteArray(original.size) { ((it + 63) % 251).toByte() }
        assertTrue(!canary.contentEquals(original))
        foreign.writeBytes(canary)
        val settings = SettingsPack().listenInterfaces("127.0.0.1:0")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        val sessionParams = SessionParams(settings)
        sessionParams.setOwnedDiskIO(receipts.absolutePath)
        val session = SessionManager(false)
        try {
            session.start(sessionParams)
            val params = AddTorrentParams()
            params.setTorrentInfo(TorrentInfo.bdecode(metadata))
            params.setSavePath(destination.absolutePath)
            params.setFlags(torrent_flags_t()
                .or_(TorrentFlags.PAUSED)
                .or_(TorrentFlags.DUPLICATE_IS_ERROR))
            val error = error_code()
            val raw = session.swig().add_torrent(params.swig(), error)
            assertEquals("Native Add must register for actual disk refusal inspection", 0, error.value())
            assertTrue(raw.is_valid)
            val handle = TorrentHandle(raw)
            val deadline = System.currentTimeMillis() + 15000L
            var refused = false
            while (System.currentTimeMillis() < deadline) {
                if (handle.status().errorCode()?.isError == true) {
                    refused = true
                    break
                }
                Thread.sleep(50)
            }
            assertTrue("The actual installed owned JNI must refuse a foreign payload", refused)
            assertArrayEquals("Foreign bytes must survive native Add", canary, foreign.readBytes())
        } finally { session.stop() }
        assertArrayEquals("Foreign bytes must survive actual native session shutdown",
            canary, foreign.readBytes())
    }
}
"""

def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ValueError(reason)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while part := stream.read(1024 * 1024):
            digest.update(part)
    return digest.hexdigest()


def verify_jar(jar: Path) -> None:
    require(jar.is_file(), "Rebuilt libtorrent4j jar is missing")
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = {
            "org/libtorrent4j/SessionParams.class",
            "org/libtorrent4j/swig/libtorrent_jni.class",
        }
        require(required.issubset(names), "Jar lacks required pinned SWIG classes")
        contents = archive.read("org/libtorrent4j/SessionParams.class")
        require(b"setOwnedDiskIO" in contents,
                "Stock Java binding has no owned native disk constructor")


def verify_elf(library: Path, abi: str) -> None:
    require(library.is_file(), "Android owned JNI library is missing")
    with library.open("rb") as stream:
        head = stream.read(64)
    require(len(head) >= 20 and head[:4] == b"\x7fELF",
            "Owned JNI artifact is not ELF")
    require(head[4] == 2 and head[5] == 1,
            "Owned JNI requires little-endian ELF64")
    machine = int.from_bytes(head[18:20], "little")
    require(machine == ALLOWED_ABIS[abi],
            f"Owned JNI ELF machine {machine} differs from requested {abi}")


def render_gradle(original: str, jar: Path, jni_root: Path, test_source: Path) -> str:
    required = (MAIN_JAR_DEP, ARM_DEP, X86_DEP, APP_ID, SOURCE_SET_ANCHOR)
    for part in required:
        require(original.count(part) == 1,
                "Unrecognized production Gradle seam; refusing source rewrite: " + part[:80])
    # Absolute paths are produced only for the disposable worker. A generated
    # Gradle copy has no reusable release artifact.
    jar_literal = jar.as_posix().replace("\\", "\\\\").replace('"', '\\"')
    jni_literal = jni_root.as_posix().replace("\\", "\\\\").replace('"', '\\"')
    tests_literal = test_source.as_posix().replace("\\", "\\\\").replace('"', '\\"')
    result = original.replace(
        MAIN_JAR_DEP,
        '    implementation(files("' + jar_literal + '"))  // OWNED LAB ONLY')
    result = result.replace(ARM_DEP, '    // OWNED LAB ONLY: upstream Android ARM JNI excluded')
    result = result.replace(X86_DEP, '    // OWNED LAB ONLY: upstream Android x86_64 JNI excluded')
    result = result.replace(APP_ID,
        'applicationId = "io.github.gamesbyian.chunks.ownedlab"')
    result = result.replace(SOURCE_SET_ANCHOR,
        SOURCE_SET_ANCHOR + '\n'
        '    // Non-shipping owned-JNI lab: never construct release/R8 artifacts.\n'
        '    sourceSets.getByName("main").jniLibs.srcDir("' + jni_literal + '")\n'
        '    sourceSets.getByName("androidTest").java.srcDir("' + tests_literal + '")')
    guard = (
        '// Generated disposable owned-JNI test overlay. DO NOT COMMIT OR SHIP.\n'
        'val ownedLabPermittedTasks = setOf(\n'
        '    "assembleDebug", "assembleDebugAndroidTest",\n'
        '    "connectedDebugAndroidTest", "testDebugUnitTest",\n'
        '    "installDebug", "installDebugAndroidTest"\n'
        ')\n'
        'if (gradle.startParameter.taskNames.isEmpty() ||\n'
        '    gradle.startParameter.taskNames.any {\n'
        '        it.substringAfterLast(":") !in ownedLabPermittedTasks\n'
        '    }) error("Owned JNI lab permits explicit Debug tasks only; no build/assemble/release")\n'
    )
    plugins = (
        'plugins {\n'
        '    id("com.android.application")\n'
        '    id("org.jetbrains.kotlin.plugin.compose")\n'
        '}\n'
    )
    require(result.count(plugins) == 1, "Unknown Kotlin plugins block")
    # Gradle Kotlin DSL requires plugins{} before ordinary statements, so
    # append the defensive release guard *after* this exact plugin block.
    result = result.replace(plugins, plugins + guard)
    require("OWNED LAB ONLY" in result and result.count("OWNED LAB ONLY") == 3,
            "Owned lab dependency rewrite incomplete")
    return result


def prepare(
    app_gradle: Path, jar: Path, library: Path, abi: str, output: Path,
    expected_jar: str, expected_so: str
) -> Path:
    require(abi in ALLOWED_ABIS, "Only Android 64-bit arm64-v8a or x86_64 is supported")
    app_gradle, jar, library, output = [
        p.expanduser().resolve() for p in (app_gradle, jar, library, output)
    ]
    require(app_gradle.is_file() and app_gradle.name == "build.gradle.kts"
            and app_gradle.parent.name == "app",
            "Provide the real checked-out app/build.gradle.kts")
    require(output != app_gradle.parent and
            output not in app_gradle.parents and
            app_gradle.parent not in output.parents,
            "Output must not be a production app source directory or ancestor")
    require(not output.exists() or not any(output.iterdir()),
            "Output directory must be absent or empty to prevent stale JNI collision")
    require(jar != library and jar != app_gradle and library != app_gradle,
            "Inputs cannot alias app source or each other")
    require(len(expected_jar) == 64 and len(expected_so) == 64 and
            all(ch in "0123456789abcdef" for ch in expected_jar + expected_so),
            "Both SHA-256 identities are mandatory lowercase hex")
    verify_jar(jar)
    verify_elf(library, abi)
    require(sha256(jar) == expected_jar, "Rebuilt Java jar SHA-256 mismatch")
    require(sha256(library) == expected_so, "Owned JNI library SHA-256 mismatch")
    original = app_gradle.read_text(encoding="utf-8")
    target_jar = output / "libtorrent4j-owned-lab.jar"
    target_so = output / "jniLibs" / abi / "libtorrent4j.so"
    staged_gradle = output / "build.gradle.kts"
    patched = render_gradle(original, target_jar, output / "jniLibs", output / "androidTest")
    target_so.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, target_jar)
    shutil.copyfile(library, target_so)
    # An input pathname could change after its first hash. The actual
    # staged bytes, not the pre-copy survey, are the intended build inputs.
    require(sha256(target_jar) == expected_jar and
            sha256(target_so) == expected_so,
            "Staged JNI inputs changed between verification and copy")
    verify_jar(target_jar)
    verify_elf(target_so, abi)
    smoke_path = output / "androidTest" / "io" / "github" / "gamesbyian" / "chunks" / "engine" / "OwnedJniSessionSmokeInstrumentedTest.kt"
    smoke_path.parent.mkdir(parents=True, exist_ok=True)
    smoke_path.write_text(ANDROID_SMOKE, encoding="utf-8")
    staged_gradle.write_text(patched, encoding="utf-8")
    receipt = {
        "purpose": "NON-SHIPPING JNI TEST OVERLAY ONLY",
        "abi": abi,
        "original_gradle_sha256": hashlib.sha256(original.encode()).hexdigest(),
        "owned_java_jar_sha256": sha256(target_jar),
        "owned_native_so_sha256": sha256(target_so),
        "expected_upstream_java_commit": UPSTREAM_JAVA,
        "expected_upstream_libtorrent_commit": UPSTREAM_LIBTORRENT,
        "artifact_validation": "ELF architecture and SWIG class presence; independent provenance required",
        "allowed_build_type": "debug",
        "instrumented_test": "io.github.gamesbyian.chunks.engine.OwnedJniSessionSmokeInstrumentedTest",
        "instrumented_test_sha256": sha256(smoke_path),
        "proof_limit": "owned disk constructor started only, no torrent bytes or external storage acceptance",
    }
    (output / "PREVIEW-ONLY.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return staged_gradle


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-gradle", type=Path, required=True)
    parser.add_argument("--fork-jar", type=Path, required=True)
    parser.add_argument("--owned-so", type=Path, required=True)
    parser.add_argument("--abi", choices=tuple(ALLOWED_ABIS), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--so-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        path = prepare(args.app_gradle, args.fork_jar, args.owned_so,
                       args.abi, args.output_dir,
                       args.jar_sha256, args.so_sha256)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"CHUNKS_OWNED_PREVIEW_REFUSED: {error}", file=sys.stderr)
        return 2
    print(f"CHUNKS_OWNED_PREVIEW_STAGED={path}")
    print("NON-SHIPPING: never copy this overlay to a release checkout")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
