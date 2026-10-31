#!/usr/bin/env python3
"""Offline contract: every mandatory native atlas PNG has an executable Kotlin producer.

This checks names and test-source provenance before reserving an emulator.
It does not validate image pixels, test execution, Android accessibility or
real-device behavior; tools/native_add_review.sh must still inspect real PNGs.
"""
from __future__ import annotations

import ast
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = Path("tools/native_add_review.sh")
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


def required_images(script: str) -> tuple[str, ...]:
    manifests = []
    for match in MANIFEST.finditer(script):
        if ".png" not in match.group(1):
            continue
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


def validate(script: str, sources: dict[str, str]) -> tuple[str, ...]:
    required = required_images(script)
    producers = capture_producers(sources)
    missing = [name for name in required if name not in producers]
    if missing:
        raise ValueError("Required native atlas PNG has no executable Kotlin capture: " +
                         ", ".join(missing))
    return required


def check_repo(root: Path = ROOT) -> tuple[str, ...]:
    script = (root / SCRIPT).read_text(encoding="utf-8")
    sources = {
        str(path.relative_to(root)): path.read_text(encoding="utf-8")
        for path in sorted((root / SOURCE_ROOT).rglob("*.kt"))
    }
    if not sources:
        raise ValueError("No Android instrumentation Kotlin sources were found")
    return validate(script, sources)


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
