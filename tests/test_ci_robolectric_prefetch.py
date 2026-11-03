"""Offline negative controls for CI-only SHA-512 Robolectric SDK prefetch."""
import hashlib
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools.prefetch_robolectric_sdk import (
    ARTIFACT, SDK_TO_ARTIFACT, PrefetchFailure, prefetch, read_limited,
)

VERSION = SDK_TO_ARTIFACT[35]
BASENAME = f"{ARTIFACT}-{VERSION}"
POM = (
    '<project xmlns="http://maven.apache.org/POM/4.0.0">'
    '<modelVersion>4.0.0</modelVersion><groupId>org.robolectric</groupId>'
    f'<artifactId>{ARTIFACT}</artifactId><version>{VERSION}</version></project>'
).encode()


def fake_jar():
    content = BytesIO()
    with zipfile.ZipFile(content, "w") as z:
        z.writestr("android/Build.class", b"bytecode-fixture")
    return content.getvalue()


def fixtures():
    jar = fake_jar()
    return {
        BASENAME + ".pom": POM,
        BASENAME + ".pom.sha512": hashlib.sha512(POM).hexdigest().encode(),
        BASENAME + ".jar": jar,
        BASENAME + ".jar.sha512": hashlib.sha512(jar).hexdigest().encode(),
    }


class BytesOpener:
    def __init__(self, blobs, mutate=None):
        self.blobs = blobs
        self.requests = []
        self.mutate = mutate or {}

    def __call__(self, url, timeout):
        self.requests.append(url)
        filename = url.rsplit("/", 1)[-1]
        value = self.blobs[filename]
        modifications = self.mutate.get(filename, [])
        if modifications:
            value = modifications.pop(0)
        if isinstance(value, BaseException):
            raise value
        return BytesIO(value)


class PrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name) / "home"
        self.home.mkdir()
        self.gradle = Path(self.temp.name) / "app.gradle.kts"
        self.gradle.write_text('testImplementation("org.robolectric:robolectric:4.17")',
                               encoding="utf-8")
        self.opener = BytesOpener(fixtures())
        self.folder = self.home / ".m2/repository/org/robolectric/android-all-instrumented" / VERSION

    def run_prefetch(self, opener=None):
        return prefetch(sdk=35, gradle_file=self.gradle, home=self.home,
                        opener=opener or self.opener, pause=lambda _: None,
                        maven_root="https://repo.maven.apache.org/maven2")

    def test_exact_sdk_both_sha512s_and_cached_no_network(self):
        result = self.run_prefetch()
        self.assertEqual("verified-prefetch", result["source"])
        self.assertEqual(4, len(self.opener.requests))
        for name in (BASENAME + ".pom", BASENAME + ".jar"):
            self.assertEqual(fixtures()[name], (self.folder / name).read_bytes())
            self.assertEqual(fixtures()[name + ".sha512"],
                             (self.folder / (name + ".sha512")).read_bytes())
        again = self.run_prefetch(opener=BytesOpener({}))
        self.assertEqual("verified-cache", again["source"])
        self.assertEqual(0, again["attempts"])
        self.assertFalse(list(self.folder.glob(".robolectric-staging-*")))

    def test_interrupted_large_jar_retries_exact_one_object(self):
        values = fixtures()
        opener = BytesOpener(values, mutate={BASENAME + ".jar": [OSError("connection-reset")]})
        result = self.run_prefetch(opener=opener)
        self.assertEqual(2, result["attempts"])
        self.assertEqual(2, sum(url.endswith(BASENAME + ".jar") for url in opener.requests))
        self.assertEqual(values[BASENAME + ".jar"],
                         (self.folder / (BASENAME + ".jar")).read_bytes())

    def test_corrupted_maven_pom_retries_and_is_never_committed(self):
        bad = b'<project><unexpected>wrong</unexpected></project>'
        opener = BytesOpener(fixtures(), mutate={BASENAME + ".pom": [bad, bad, bad]})
        with self.assertRaisesRegex(PrefetchFailure, "verified-download-exhausted"):
            self.run_prefetch(opener=opener)
        self.assertFalse((self.folder / (BASENAME + ".jar")).exists())
        self.assertEqual(3, sum(url.endswith(BASENAME + ".pom") for url in opener.requests))

    def test_malformed_checksum_and_html_payload_are_never_accepted(self):
        for override in [b"not-a-sha512", b"1" * 128]:
            with self.subTest(override=override[:10]):
                opener = BytesOpener(fixtures(), mutate={
                    BASENAME + ".pom.sha512": [override] * 3,
                })
                with self.assertRaises(PrefetchFailure):
                    self.run_prefetch(opener=opener)
                self.assertFalse((self.folder / (BASENAME + ".jar")).exists())

    def test_wrong_pom_coordinates_and_invalid_zip_fail_closed(self):
        wrong = POM.replace(b"org.robolectric", b"org.attacker")
        data = fixtures()
        data[BASENAME + ".pom"] = wrong
        data[BASENAME + ".pom.sha512"] = hashlib.sha512(wrong).hexdigest().encode()
        with self.assertRaisesRegex(PrefetchFailure, "wrong-maven-coordinate"):
            self.run_prefetch(opener=BytesOpener(data))
        self.assertFalse((self.folder / (BASENAME + ".jar")).exists())
        broken = fixtures()
        broken[BASENAME + ".jar"] = b"fake jar"
        broken[BASENAME + ".jar.sha512"] = hashlib.sha512(b"fake jar").hexdigest().encode()
        with self.assertRaisesRegex(PrefetchFailure, "invalid-jar"):
            self.run_prefetch(opener=BytesOpener(broken))
        self.assertFalse((self.folder / (BASENAME + ".jar")).exists())

    def test_wrong_sdk_or_robolectric_upgrade_requires_review(self):
        with self.assertRaisesRegex(PrefetchFailure, "unsupported-sdk"):
            prefetch(sdk=34, gradle_file=self.gradle, home=self.home)
        self.gradle.write_text('testImplementation("org.robolectric:robolectric:4.18")')
        with self.assertRaisesRegex(PrefetchFailure, "robolectric-version-changed"):
            self.run_prefetch()
        self.assertFalse(self.folder.exists())

    def test_size_limit_and_https_avoid_unbounded_or_unsafe_content(self):
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / "file"
            with self.assertRaisesRegex(PrefetchFailure, "oversized-artifact"):
                read_limited("https://repo.maven.apache.org/test", p, limit=2,
                             opener=lambda u, timeout: BytesIO(b"three"))
            with self.assertRaisesRegex(PrefetchFailure, "insecure-url"):
                read_limited("http://attacker.local/test", p, limit=10,
                             opener=lambda u, timeout: BytesIO(b"one"))

    def test_corrupted_cached_jar_is_redownloaded_and_reverified(self):
        self.run_prefetch()
        (self.folder / (BASENAME + ".jar")).write_bytes(b"corrupt")
        opener = BytesOpener(fixtures())
        again = self.run_prefetch(opener=opener)
        self.assertEqual("verified-prefetch", again["source"])
        self.assertEqual(fixtures()[BASENAME + ".jar"],
                         (self.folder / (BASENAME + ".jar")).read_bytes())

    def test_foundation_preflight_runs_before_gradle_tests(self):
        source = (Path(__file__).resolve().parents[1] /
                  ".github/workflows/android-check.yml").read_text()
        preflight = source.find("python3 tools/prefetch_robolectric_sdk.py --sdk 35")
        gradle = source.find(":app:testDebugUnitTest :app:assembleDebug")
        self.assertGreaterEqual(preflight, 0)
        self.assertGreater(gradle, preflight)
        self.assertIn("--require-executed", source)
        self.assertIn('"tools/prefetch_robolectric_sdk.py"', source)
        self.assertNotIn("robolectric.offline=true", source)
        self.assertNotIn("--tests ", source)


if __name__ == "__main__":
    unittest.main()
