"""Cheap source-level counterexamples for the required native Compose screenshot set."""
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import json
from check_native_atlas_contract import check_repo, required_images, validate, capture_producers, load_manifest


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

    def test_existing_android_job_runs_contract_before_any_sdk_or_emulator_setup(self):
        workflow = (ROOT / ".github/workflows/native-add-instrumentation.yml"
                    ).read_text(encoding="utf-8")
        call = "python3 tools/check_native_atlas_contract.py"
        self.assertEqual(1, workflow.count(call))
        self.assertLess(workflow.index("uses: actions/checkout@v4"),
                        workflow.index(call))
        self.assertLess(workflow.index(call),
                        workflow.index("uses: actions/setup-java@v4"))
        self.assertLess(workflow.index(call),
                        workflow.index("Install pinned Android compile toolchain"))
        self.assertIn("python3 -m unittest discover -s tests -p test_native_atlas_contract.py",
                      workflow)

    def test_offline_controls_run_from_existing_docs_workflow(self):
        workflow = (ROOT / ".github/workflows/documentation-validation.yml"
                    ).read_text(encoding="utf-8")
        # Push and PR filters both include validator changes, without making
        # this new tool a reason to run the heavyweight emulator by itself.
        self.assertEqual(2, workflow.count("- 'tools/check_native_atlas_contract.py'"))
        self.assertEqual(2, workflow.count("- 'tests/test_native_atlas_contract.py'"))
        self.assertIn("python3 tools/check_native_atlas_contract.py", workflow)
        self.assertIn("python3 -m unittest discover -s tests -p test_native_atlas_contract.py",
                      workflow)

    def test_non_png_or_external_path_cannot_pass(self):
        for name in ["../a-real-compose.png", "foo.txt", "a.png", ""]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Invalid"):
                required_images(script(name))

def manifest(required=(), evidence=(), retired=()):
    return json.dumps({
        "schema": "chunks.native-atlas-manifest.v1",
        "required": [{"image": n, "capability": "some-capability", "requirement": "docs/UX.md"}
                     for n in required],
        "evidence_only": [{"image": n} for n in evidence],
        "retired": list(retired),
    })


RETIRED = {"image": "old-real-compose.png", "retired": "2026-11-11", "reason": "screen removed",
           "requirement": "docs/UX.md", "replacement_verification": "new-real-compose.png"}


class NativeAtlasManifestTest(unittest.TestCase):
    def test_current_manifest_classifies_every_producer_and_names_its_requirements(self):
        text = (ROOT / "tools/native_atlas_manifest.json").read_text()
        data = load_manifest(text)
        self.assertGreaterEqual(len(data["required"]), 20)
        self.assertTrue(all(e["capability"] and e["requirement"] for e in data["required"]))
        self.assertEqual(len(check_repo()), len(data["required"]))

    def test_every_capture_must_be_classified(self):
        text = manifest(required=["a-real-compose.png"])
        with self.assertRaisesRegex(ValueError, "classified.*b-real-compose"):
            validate(text, {"T.kt": kotlin("a-real-compose.png", "b-real-compose.png")})

    def test_evidence_only_capture_is_allowed_but_never_required(self):
        text = manifest(required=["a-real-compose.png"], evidence=["b-real-compose.png"])
        self.assertEqual(("a-real-compose.png",),
                         validate(text, {"T.kt": kotlin("a-real-compose.png", "b-real-compose.png")}))

    def test_removing_a_required_screen_without_retiring_it_fails(self):
        text = manifest(required=["a-real-compose.png", "gone-real-compose.png"])
        with self.assertRaisesRegex(ValueError, "no executable Kotlin capture.*gone"):
            validate(text, {"T.kt": kotlin("a-real-compose.png")})

    def test_a_removed_screen_is_retired_with_reason_and_replacement(self):
        text = manifest(required=["new-real-compose.png"], retired=[RETIRED])
        self.assertEqual(("new-real-compose.png",),
                         validate(text, {"T.kt": kotlin("new-real-compose.png")}))

    def test_retired_entry_must_say_why_and_what_verifies_the_requirement_now(self):
        for field in ("reason", "requirement", "replacement_verification", "retired"):
            broken = dict(RETIRED); broken[field] = " "
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field):
                validate(manifest(required=["new-real-compose.png"], retired=[broken]),
                         {"T.kt": kotlin("new-real-compose.png")})

    def test_a_screenshot_cannot_be_required_and_retired(self):
        with self.assertRaisesRegex(ValueError, "both required and retired"):
            validate(manifest(required=["old-real-compose.png"], retired=[RETIRED]),
                     {"T.kt": kotlin("old-real-compose.png")})

    def test_required_entry_needs_capability_and_requirement(self):
        for field in ("capability", "requirement"):
            data = json.loads(manifest(required=["a-real-compose.png"]))
            data["required"][0][field] = ""
            with self.subTest(field=field), self.assertRaises(ValueError):
                required_images(json.dumps(data))

    def test_missing_requirement_document_is_rejected_when_checked_against_a_repo(self):
        data = json.loads(manifest(required=["a-real-compose.png"]))
        data["required"][0]["requirement"] = "docs/DOES-NOT-EXIST.md"
        with self.assertRaisesRegex(ValueError, "does not exist"):
            validate(json.dumps(data), {"T.kt": kotlin("a-real-compose.png")}, ROOT)


if __name__ == "__main__":
    unittest.main()
