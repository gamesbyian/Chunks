"""Offline fail-closed tests for an explicitly NON-SHIPPING JNI APK overlay.

These synthetic bytes are not native linkage evidence. Actual libraries
must be rebuilt and certified independently before any emulator test.
"""
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "stage_owned_native_preview.py"
SPEC = importlib.util.spec_from_file_location("stage_owned_native_preview", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

BUILD = '''import java.security.MessageDigest
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    defaultConfig {
        applicationId = if (sideBySide) "io.github.gamesbyian.chunks.preview"
                        else "io.github.gamesbyian.chunks"
    }
    testBuildType = providers.gradleProperty("chunks.testBuildType").getOrElse("debug")
}
dependencies {
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class OwnedPreviewStageTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="chunks-owned-preview-")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        app = self.root / "app"
        app.mkdir()
        self.gradle = app / "build.gradle.kts"
        self.gradle.write_text(BUILD)
        self.jar = self.root / "fork.jar"
        self.so = self.root / "owned.so"
        self.output = self.root / "preview-output"
        self.abi = "x86_64"
        self.make_jar(owned=True)
        self.make_elf(62)

    def make_jar(self, owned=True):
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("org/libtorrent4j/SessionParams.class",
                       b"synthetic constant pool " +
                       (b"setOwnedDiskIO" if owned else b"stock"))
            z.writestr("org/libtorrent4j/swig/libtorrent_jni.class",
                       b"synthetic JNI class")

    def make_elf(self, machine):
        image = bytearray(64)
        image[:6] = b"\x7fELF\x02\x01"
        image[18:20] = machine.to_bytes(2, "little")
        self.so.write_bytes(image)

    def stage(self, **overrides):
        opts = {
            "app_gradle": self.gradle, "jar": self.jar,
            "library": self.so, "abi": self.abi,
            "output": self.output, "expected_jar": digest(self.jar),
            "expected_so": digest(self.so),
        }
        opts.update(overrides)
        return module.prepare(**opts)

    def test_debug_only_staged_copy_has_isolated_identity_and_native_artifacts(self):
        path = self.stage()
        self.assertEqual(path, self.output / "build.gradle.kts")
        text = path.read_text()
        self.assertIn('applicationId = "io.github.gamesbyian.chunks.ownedlab"', text)
        self.assertIn("sourceSets.getByName", text)
        self.assertIn('sourceSets.getByName("androidTest")', text)
        self.assertIn("libtorrent4j-owned-lab.jar", text)
        self.assertIn("ownedLabPermittedTasks", text)
        self.assertIn('"assembleDebugAndroidTest"', text)
        self.assertIn('"connectedDebugAndroidTest"', text)
        self.assertIn("release", text)
        self.assertLess(text.index("plugins {"), text.index("gradle.startParameter"))
        self.assertNotIn(module.MAIN_JAR_DEP, text)
        self.assertNotIn(module.ARM_DEP, text)
        self.assertNotIn(module.X86_DEP, text)
        self.assertEqual(BUILD, self.gradle.read_text(), "Production Gradle MUST remain untouched")
        self.assertEqual(self.jar.read_bytes(),
                         (self.output / "libtorrent4j-owned-lab.jar").read_bytes())
        self.assertEqual(self.so.read_bytes(),
                         (self.output / "jniLibs/x86_64/libtorrent4j.so").read_bytes())
        receipt = json.loads((self.output / "PREVIEW-ONLY.json").read_text())
        self.assertEqual(receipt["allowed_build_type"], "debug")
        self.assertEqual(receipt["owned_native_so_sha256"], digest(self.so))
        self.assertEqual(receipt["purpose"], "NON-SHIPPING JNI TEST OVERLAY ONLY")
        smoke = self.output / "androidTest/io/github/gamesbyian/chunks/engine/OwnedJniSessionSmokeInstrumentedTest.kt"
        self.assertEqual(receipt["instrumented_test_sha256"], digest(smoke))
        self.assertEqual(receipt["instrumented_test"],
                         "io.github.gamesbyian.chunks.engine.OwnedJniSessionSmokeInstrumentedTest")
        src = smoke.read_text()
        self.assertIn("params.setOwnedDiskIO(receipts.absolutePath)", src)
        self.assertIn("native.start(params)", src)
        self.assertIn("context.cacheDir", src)
        self.assertNotIn("Downloads", src)
        self.assertNotIn("addTorrent", src)
        self.assertIn("no torrent bytes", receipt["proof_limit"])

    def test_same_output_is_refused_after_a_success(self):
        self.stage()
        with self.assertRaisesRegex(ValueError, "absent or empty"):
            self.stage()

    def test_unexpected_gradle_dependency_seam_is_refused_without_output(self):
        self.gradle.write_text(BUILD.replace("libtorrent4j-android-arm64:", "libtorrent4j-android-missing:"))
        with self.assertRaisesRegex(ValueError, "Unrecognized production Gradle seam"):
            self.stage()
        self.assertFalse(self.output.exists())

    def test_stock_binding_without_owned_constructor_is_refused(self):
        self.make_jar(owned=False)
        with self.assertRaisesRegex(ValueError, "no owned native disk constructor"):
            self.stage()
        self.assertFalse(self.output.exists())

    def test_wrong_abi_and_unverified_binary_are_refused(self):
        with self.assertRaisesRegex(ValueError, "differs from requested"):
            self.stage(abi="arm64-v8a")
        self.make_elf(0)
        with self.assertRaisesRegex(ValueError, "differs from requested"):
            self.stage()

    def test_non_elf_and_mismatched_sha_are_refused(self):
        self.so.write_bytes(b"not a native ELF at all")
        with self.assertRaisesRegex(ValueError, "not ELF"):
            self.stage()
        self.make_elf(62)
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.stage(expected_so="a" * 64)
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.stage(expected_jar="b" * 64)

    def test_no_unverified_output_in_real_app_source_or_parent(self):
        for dest in [self.gradle.parent, self.gradle.parent / "preview",
                     self.root]:
            with self.subTest(dest=dest), self.assertRaisesRegex(ValueError,
                                                                  "production app source"):
                self.stage(output=dest)

    def test_source_substitution_during_copy_refuses_staged_binary(self):
        from unittest.mock import patch
        original_copy = module.shutil.copyfile

        def tampered_copy(source, destination):
            result = original_copy(source, destination)
            if str(destination).endswith(".so"):
                Path(destination).write_bytes(b"tampered after first hash")
            return result

        with patch.object(module.shutil, "copyfile", side_effect=tampered_copy):
            with self.assertRaisesRegex(ValueError, "between verification and copy"):
                self.stage()
        self.assertFalse((self.output / "build.gradle.kts").exists(),
                         "A mismatched JNI copy cannot yield a buildable overlay")
        self.assertEqual(BUILD, self.gradle.read_text())

    def test_unknown_architecture_or_uppercase_hash_is_refused(self):
        with self.assertRaisesRegex(ValueError, "Only Android 64-bit"):
            self.stage(abi="armeabi-v7a")
        with self.assertRaisesRegex(ValueError, "mandatory lowercase hex"):
            self.stage(expected_so="A" * 64)


if __name__ == "__main__":
    unittest.main()
