"""Offline checks of the static JNI/R8 witness (A2611-09) with synthetic APKs and R8 outputs."""
from pathlib import Path
import io
import tempfile
import unittest
import zipfile

from tools import release_jni_witness as witness

MAPPING = """org.libtorrent4j.swig.session_handle -> org.libtorrent4j.swig.session_handle:
    int foo() -> a
org.libtorrent4j.swig.torrent_handle -> a.b:
io.github.gamesbyian.chunks.MainActivity -> io.github.gamesbyian.chunks.MainActivity:
"""
USAGE = "org.libtorrent4j.swig.dead_class\n    void unused()\n"


def apk(libs: dict) -> zipfile.ZipFile:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in libs.items():
            z.writestr(name, data)
    return zipfile.ZipFile(io.BytesIO(buffer.getvalue()))


class JniWitnessTests(unittest.TestCase):
    def test_parses_unrenamed_and_renamed_mapping_entries(self):
        mapped = witness.mapping_classes(MAPPING)
        self.assertEqual(mapped["org.libtorrent4j.swig.session_handle"], "org.libtorrent4j.swig.session_handle")
        self.assertEqual(mapped["org.libtorrent4j.swig.torrent_handle"], "a.b")

    def test_usage_lists_only_removed_classes(self):
        self.assertEqual(witness.removed_classes(USAGE), {"org.libtorrent4j.swig.dead_class"})

    def test_native_references_are_found_in_every_expected_abi(self):
        data = b"\x00FindClass org/libtorrent4j/swig/session_handle\x00org/libtorrent4j/swig/x$Inner\x00"
        z = apk({"lib/arm64-v8a/liblibtorrent4j.so": data, "lib/x86_64/liblibtorrent4j.so": data})
        refs, missing = witness.native_class_references(z, ["arm64-v8a", "x86_64"])
        self.assertEqual(missing, [])
        self.assertIn("org.libtorrent4j.swig.x$Inner", refs)
        self.assertEqual(len(refs["org.libtorrent4j.swig.session_handle"]), 2)

    def test_a_missing_abi_is_reported(self):
        z = apk({"lib/arm64-v8a/liblibtorrent4j.so": b"org/libtorrent4j/swig/session_handle"})
        _, missing = witness.native_class_references(z, ["arm64-v8a", "x86_64"])
        self.assertEqual(missing, ["x86_64"])

    def _build(self, native: bytes, abis=("arm64-v8a", "x86_64")) -> str:
        path = Path(self.tmp.name) / "a.apk"
        with zipfile.ZipFile(path, "w") as z:
            for abi in abis:
                z.writestr(f"lib/{abi}/liblibtorrent4j.so", native)
        return str(path)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_retained_classes_in_all_abis_pass(self):
        count, problems = witness.check(self._build(b"org/libtorrent4j/swig/session_handle"),
                                        MAPPING, USAGE, ["arm64-v8a", "x86_64"])
        self.assertEqual((count, problems), (1, []))

    def test_renamed_removed_and_absent_classes_each_fail(self):
        native = (b"org/libtorrent4j/swig/torrent_handle\x00org/libtorrent4j/swig/dead_class\x00"
                  b"org/libtorrent4j/swig/ghost\x00")
        _, problems = witness.check(self._build(native), MAPPING, USAGE, ["arm64-v8a", "x86_64"])
        self.assertEqual(len(problems), 3, problems)
        self.assertTrue(any("RENAMED" in p and "torrent_handle" in p for p in problems))
        self.assertTrue(any("REMOVED" in p and "dead_class" in p for p in problems))
        self.assertTrue(any("absent" in p and "ghost" in p for p in problems))

    def test_missing_abi_or_no_references_fail(self):
        _, problems = witness.check(self._build(b"org/libtorrent4j/swig/session_handle", abis=("arm64-v8a",)),
                                    MAPPING, USAGE, ["arm64-v8a", "x86_64"])
        self.assertEqual(problems, ["missing ABI libraries: ['x86_64']"])
        _, problems = witness.check(self._build(b"nothing here"), MAPPING, USAGE, ["arm64-v8a"])
        self.assertTrue(any("no org/libtorrent4j" in p for p in problems))


if __name__ == "__main__":
    unittest.main()
