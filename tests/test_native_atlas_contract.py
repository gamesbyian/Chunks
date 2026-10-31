"""Cheap source-level counterexamples for the required native Compose screenshot set."""
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from check_native_atlas_contract import check_repo, required_images, validate, capture_producers


def script(*names: str) -> str:
    return "python3 - <<'PY'\nfor name in " + repr(tuple(names)) + ":\n    pass\nPY\n"


def kotlin(*names: str) -> str:
    return ('private fun capture(name: String) { /* real Android capture helper */ }\n' +
            "".join('capture("' + name.removesuffix(".png") + '")\n' for name in names))


class NativeAtlasContractTest(unittest.TestCase):
    def test_current_repository_has_real_source_calls_for_every_required_png(self):
        names = check_repo()
        self.assertGreaterEqual(len(names), 20)
        self.assertIn("zip-extract-unavailable-real-compose.png", names)
        self.assertNotIn("zip-extract-done-real-compose.png", names)

    def test_matching_required_capture_passes(self):
        names = ["app-transfers-real-compose.png", "zip-extract-unavailable-real-compose.png"]
        self.assertEqual(tuple(names), validate(script(*names), {"ATest.kt": kotlin(*names)}))

    def test_stale_expected_filename_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "zip-extract-done"):
            validate(script("zip-extract-done-real-compose.png"), {
                "ZipTest.kt": kotlin("zip-extract-unavailable-real-compose.png")
            })

    def test_renamed_kotlin_capture_without_manifest_update_fails(self):
        with self.assertRaisesRegex(ValueError, "app-transfers"):
            validate(script("app-transfers-real-compose.png"), {
                "ATest.kt": kotlin("app-list-real-compose.png")
            })

    def test_comment_cannot_forge_capture(self):
        filename = "app-transfers-real-compose.png"
        fake = 'private fun capture(name: String) {}\n// capture("app-transfers-real-compose")\n'
        with self.assertRaisesRegex(ValueError, "no executable"):
            validate(script(filename), {"ATest.kt": fake})
        fake = ('private fun capture(name: String) {}\n'
                '/* capture("app-transfers-real-compose") */\n')
        with self.assertRaisesRegex(ValueError, "no executable"):
            validate(script(filename), {"ATest.kt": fake})

    def test_string_literal_cannot_forge_capture(self):
        filename = "app-transfers-real-compose.png"
        fake = ('private fun capture(name: String) {}\n'
                'val prose = """capture("app-transfers-real-compose")"""\n')
        # Static source-only detection cannot safely prove reachability, but must
        # at least exclude expressions appearing inside a Kotlin string.
        self.assertNotIn(filename, capture_producers({"ATest.kt": fake}))

    def test_undeclared_helper_is_not_accepted(self):
        with self.assertRaisesRegex(ValueError, "no executable"):
            validate(script("app-transfers-real-compose.png"), {
                "ATest.kt": 'capture("app-transfers-real-compose")\n'
            })

    def test_duplicate_required_evidence_is_rejected(self):
        name = "app-transfers-real-compose.png"
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            required_images(script(name, name))

    def test_missing_and_dynamic_manifests_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            required_images("python3 - <<'PY'\nprint('nothing')\nPY")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            required_images(script("a-real-compose.png") + script("b-real-compose.png"))

    def test_non_png_or_external_path_cannot_pass(self):
        for name in ["../a-real-compose.png", "foo.txt", "a.png", ""]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Invalid"):
                required_images(script(name))


if __name__ == "__main__":
    unittest.main()
