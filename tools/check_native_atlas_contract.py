#!/usr/bin/env python3
"""Offline contract for native atlas screenshots (tools/native_atlas_manifest.json).

Every mandatory PNG has an executable Kotlin producer, every producer is explicitly
classified (required, evidence_only or retired), required entries name the capability
and requirement they evidence, and a retired entry records why it was retired and what
verifies the requirement now. Removing a screen is therefore an explicit reviewable
manifest change, never a silent deletion.

This checks names and test-source provenance before reserving an emulator.
It does not validate image pixels, test execution, Android accessibility or
real-device behavior; tools/native_add_review.sh must still inspect real PNGs.
Screenshots are review evidence, not approved visual-regression baselines.
"""
from __future__ import annotations

import ast
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = Path("tools/native_add_review.sh")
MANIFEST_FILE = Path("tools/native_atlas_manifest.json")
SOURCE_ROOT = Path("app/src/androidTest")

# The embedded Python verifier is the canonical required screenshot list.
MANIFEST = re.compile(r"\bfor\s+name\s+in\s*(\([\s\S]*?\))\s*:")
CALL = re.compile(r'\b(save|capture)\s*\(\s*"([A-Za-z0-9_-]+)"\s*\)')
DECLARATION = re.compile(r'\bfun\s+(save|capture)\s*\(\s*name\s*:\s*String\b')
# Preserve quoted strings but remove line/block comments. A comment spelling
# capture("...") cannot pretend to be an executable screenshot producer.
TOKEN = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'
    r"'(?:\\.|[^'\\])*'|//[^\n]*|/\*[\s\S]*?\*/"
)


def source_without_comments(source: str) -> str:
    def clean(match: re.Match[str]) -> str:
        value = match.group()
        if value.startswith("//") or value.startswith("/*") or value.startswith('"""'):
            return re.sub(r"[^\n]", " ", value)
        return value
    return TOKEN.sub(clean, source)


IMAGE_NAME = re.compile(r"[A-Za-z0-9_-]+\.png")
CAPABILITY = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")


def load_manifest(text: str) -> dict:
    try:
        manifest = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError("Atlas manifest is not valid JSON") from error
    if not isinstance(manifest, dict) or manifest.get("schema") != "chunks.native-atlas-manifest.v1":
        raise ValueError("Atlas manifest has an unknown schema")
    for key in ("required", "evidence_only", "retired"):
        if not isinstance(manifest.get(key), list):
            raise ValueError("Atlas manifest section is missing: " + key)
    return manifest


def manifest_required(manifest: dict) -> tuple[str, ...]:
    names = []
    for entry in manifest["required"]:
        image = entry.get("image") if isinstance(entry, dict) else None
        if not isinstance(image, str) or not re.fullmatch(r"[A-Za-z0-9_-]+-real-compose\.png", image):
            raise ValueError("Invalid or non-literal mandatory screenshot name")
        capability = entry.get("capability")
        requirement = entry.get("requirement")
        if not isinstance(capability, str) or not CAPABILITY.fullmatch(capability):
            raise ValueError("Required screenshot lacks a stable capability name: " + image)
        if not isinstance(requirement, str) or not requirement.strip():
            raise ValueError("Required screenshot lacks the requirement it evidences: " + image)
        names.append(image)
    if not names:
        raise ValueError("Invalid or non-literal mandatory screenshot name")
    if len(set(names)) != len(names):
        raise ValueError("Duplicate required screenshots obscure evidence provenance")
    return tuple(names)


def required_images(text: str) -> tuple[str, ...]:
    """Required PNG names from the manifest JSON (or a legacy literal-tuple script)."""
    if text.lstrip().startswith("{"):
        return manifest_required(load_manifest(text))
    return _required_from_script(text)


def _required_from_script(script: str) -> tuple[str, ...]:
    manifests = []
    for match in MANIFEST.finditer(script):
        try:
            images = ast.literal_eval(match.group(1))
        except (ValueError, SyntaxError) as error:
            raise ValueError("Screenshot names must be a literal Python tuple") from error
        if not isinstance(images, tuple):
            raise ValueError("Screenshot names must be a literal Python tuple")
        manifests.append(images)
    if len(manifests) != 1:
        raise ValueError("Expected exactly one mandatory PNG tuple in native Add review script")
    result = manifests[0]
    if not result or any(not isinstance(name, str) or
                         not re.fullmatch(r"[A-Za-z0-9_-]+-real-compose\.png", name)
                         for name in result):
        raise ValueError("Invalid or non-literal mandatory screenshot name")
    if len(set(result)) != len(result):
        raise ValueError("Duplicate required screenshots obscure evidence provenance")
    return result


def capture_producers(sources: dict[str, str]) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for filename, raw in sources.items():
        code = source_without_comments(raw)
        declared = set(DECLARATION.findall(code))
        for method, name in CALL.findall(code):
            if method in declared:
                result.setdefault(name + ".png", set()).add(filename)
    return result


def validate(text: str, sources: dict[str, str], root: Path | None = None) -> tuple[str, ...]:
    required = required_images(text)
    producers = capture_producers(sources)
    missing = [name for name in required if name not in producers]
    if missing:
        raise ValueError("Required native atlas PNG has no executable Kotlin capture: " +
                         ", ".join(missing))
    if text.lstrip().startswith("{"):
        validate_classification(load_manifest(text), producers, root)
    return required


def validate_classification(manifest: dict, producers: dict[str, set[str]],
                            root: Path | None) -> None:
    required = set(manifest_required(manifest))
    evidence = set()
    for entry in manifest["evidence_only"]:
        image = entry.get("image") if isinstance(entry, dict) else None
        if not isinstance(image, str) or not IMAGE_NAME.fullmatch(image):
            raise ValueError("Invalid evidence_only screenshot name")
        evidence.add(image)
    retired = set()
    for entry in manifest["retired"]:
        if not isinstance(entry, dict):
            raise ValueError("Invalid retired entry")
        image = entry.get("image")
        if not isinstance(image, str) or not IMAGE_NAME.fullmatch(image):
            raise ValueError("Invalid retired screenshot name")
        for field in ("retired", "reason", "requirement", "replacement_verification"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                raise ValueError("Retired screenshot needs a '" + field + "': " + image)
        retired.add(image)
    if required & retired:
        raise ValueError("A screenshot cannot be both required and retired: " +
                         ", ".join(sorted(required & retired)))
    if required & evidence:
        raise ValueError("A screenshot cannot be both required and evidence_only: " +
                         ", ".join(sorted(required & evidence)))
    unclassified = sorted(set(producers) - required - evidence - retired)
    if unclassified:
        raise ValueError("Native screenshot producers must be classified in "
                         "tools/native_atlas_manifest.json (required, evidence_only or retired): " +
                         ", ".join(unclassified))
    if root is not None:
        for entry in manifest["required"] + manifest["retired"]:
            doc = entry["requirement"].split("#", 1)[0].strip()
            if not (root / doc).is_file():
                raise ValueError("Requirement document does not exist: " + doc)


def check_repo(root: Path = ROOT) -> tuple[str, ...]:
    manifest = (root / MANIFEST_FILE).read_text(encoding="utf-8")
    sources = {
        str(path.relative_to(root)): path.read_text(encoding="utf-8")
        for path in sorted((root / SOURCE_ROOT).rglob("*.kt"))
    }
    if not sources:
        raise ValueError("No Android instrumentation Kotlin sources were found")
    return validate(manifest, sources, root)


def main() -> int:
    try:
        names = check_repo()
    except (OSError, ValueError) as error:
        print(f"CHUNKS_NATIVE_ATLAS_CONTRACT FAILED: {error}", file=sys.stderr)
        return 1
    print(f"CHUNKS_NATIVE_ATLAS_CONTRACT OK: {len(names)} required PNGs have Kotlin capture callsites")
    return 0


if __name__ == "__main__":
    sys.exit(main())
