#!/usr/bin/env python3
"""Offline check for drift among the Chunks Android Gradle CI toolchains.

This verifies *declared pins only*: no checked-in Gradle Wrapper or dependency
verification metadata exists yet, so it is NOT a cryptographic provenance proof.
Only bounded non-font build workflows are included. No network, Gradle or SDK.
"""
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = "9.3.1"
JDK = "17"

# Build and acceptance workflows; remote/Android font lab is deliberately isolated.
WORKFLOWS = (
    "android-check.yml",
    "android10-downloads-instrumentation.yml",
    "native-add-instrumentation.yml",
    "native-post-add-collision-witness.yml",
    "minified-smoke.yml",
    "native-visual-atlas.yml",
    "android-fgs-timeout-repro.yml",
    "android-public-swarm.yml",
    "disposable-rtorrent-probe.yml",
    "release-apk.yml",
    "publish-debug-apk.yml",
)

PIN = re.compile(r"(?m)^\s*(gradle-version|java-version):\s*['\"]?([^'\"\s#]+)")
AGP = re.compile(r'id\("com\.android\.application"\)\s+version\s+"([^"]+)"')
KOTLIN = re.compile(r'id\("org\.jetbrains\.kotlin\.plugin\.compose"\)\s+version\s+"([^"]+)"')
NUMERIC_VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){2}(?:-[0-9A-Za-z.]+)?")
LIBTORRENT = re.compile(r'val\s+libtorrent4j\s*=\s*"([^"]+)"')


def validate(workflows: dict[str, str], root_gradle: str, app_gradle: str) -> list[str]:
    errors: list[str] = []
    for name in WORKFLOWS:
        source = workflows.get(name)
        if source is None:
            errors.append(f"{name}: workflow missing")
            continue
        if "gradle/actions/setup-gradle@v4" not in source:
            errors.append(f"{name}: expected reviewed setup-gradle action")
        if "actions/setup-java@v4" not in source:
            errors.append(f"{name}: expected reviewed setup-java action")
        pins: dict[str, list[str]] = {"gradle-version": [], "java-version": []}
        for k, v in PIN.findall(source):
            pins[k].append(v)
        for key, required in (("gradle-version", GRADLE), ("java-version", JDK)):
            if not pins[key] or any(v != required for v in pins[key]):
                errors.append(f"{name}: {key} must be explicitly pinned to {required}, got {pins[key]}")
        # Gradle invocation must agree with setup-gradle, rather than fetching
        # an unrelated wrapper or compiling under a locally inherited version.
        if re.search(r"(?<![A-Za-z0-9_])\./gradlew\b", source):
            errors.append(f"{name}: wrapper invocation without checked-in wrapper identity")

    for label, pattern in (("Android Gradle plugin", AGP), ("Kotlin Compose plugin", KOTLIN)):
        matches = pattern.findall(root_gradle)
        if len(matches) != 1 or not NUMERIC_VERSION.fullmatch(matches[0]):
            errors.append(f"{label}: require one explicit numeric fixed version, got {matches}")

    versions = LIBTORRENT.findall(app_gradle)
    if len(versions) != 1 or not NUMERIC_VERSION.fullmatch(versions[0]):
        errors.append(f"libtorrent4j: require one explicit fixed JNI version, got {versions}")

    # No dynamic Gradle coordinates in directly declared dependencies. Compose
    # platform-managed modules may legitimately omit a version entirely.
    for line in (root_gradle + "\n" + app_gradle).splitlines():
        code = line.split("//", 1)[0].strip()
        if not code or code.startswith("*"):
            continue
        if re.search(r'["\'][^"\']*(?:latest\.(?:release|integration)|SNAPSHOT|:[+]|:\[[0-9])[^"\']*["\']', code, re.I):
            errors.append("Dynamic dependency/plugin coordinate found")
            break
    return errors


def main() -> int:
    try:
        docs = {name: (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")
                for name in WORKFLOWS}
        root = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        app = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    except OSError as exc:
        print(f"BLOCKED: cannot read build declaration ({type(exc).__name__})")
        return 2
    errors = validate(docs, root, app)
    for item in errors:
        print("BLOCKED: " + item)
    if errors:
        return 1
    print(f"PASS: {len(WORKFLOWS)} non-font workflows declare Gradle {GRADLE} / JDK {JDK}, "
          "fixed AGP/Kotlin/JNI versions and no inspected dynamic coordinates")
    print("LIMIT: no Gradle Wrapper SHA256 or resolved-artifact verification yet")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
