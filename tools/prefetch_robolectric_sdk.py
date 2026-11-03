#!/usr/bin/env python3
"""CI-only, checksum-verified Robolectric Android 15 SDK preflight.

Robolectric 4.17's MavenArtifactFetcher checks ~/.m2/repository before
fetching SDK jars at JVM-test runtime. This is an early, bounded download;
it changes no test selection, Gradle offline mode or production APK.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile

ROBOLECTRIC_VERSION = "4.17"
SDK_TO_ARTIFACT = {35: "15-robolectric-13954326-i7"}
GROUP_PATH = "org/robolectric/android-all-instrumented"
ARTIFACT = "android-all-instrumented"
MAVEN_ROOT = "https://repo.maven.apache.org/maven2"
JAR_LIMIT = 300 * 1024 * 1024
POM_LIMIT = 512 * 1024
CHECKSUM_LIMIT = 256
MAX_ATTEMPTS = 3


class PrefetchFailure(RuntimeError):
    """Bounded acquisition or integrity failure; never disclose HTTP content."""


def check_robolectric_pin(gradle_file: Path) -> None:
    source = gradle_file.read_text(encoding="utf-8")
    expected = f'testImplementation("org.robolectric:robolectric:{ROBOLECTRIC_VERSION}")'
    if expected not in source:
        raise PrefetchFailure("robolectric-version-changed")


def read_limited(url: str, destination: Path, *, limit: int, opener=urlopen) -> None:
    if not url.startswith("https://"):
        raise PrefetchFailure("insecure-url")
    total = 0
    with opener(url, timeout=40) as response, destination.open("wb") as out:
        while True:
            chunk = response.read(256 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise PrefetchFailure("oversized-artifact")
            out.write(chunk)
    if not total:
        raise PrefetchFailure("empty-artifact")


def verified_download(base: str, filename: str, scratch: Path, *, limit: int,
                      attempts: int = MAX_ATTEMPTS, opener=urlopen,
                      pause=time.sleep) -> tuple[Path, Path, int]:
    if not re.fullmatch(r"android-all-instrumented-[A-Za-z0-9.\-]+\.(?:pom|jar)", filename):
        raise PrefetchFailure("unexpected-artifact-name")
    payload = scratch / filename
    checksum = scratch / (filename + ".sha512")
    for attempt in range(1, attempts + 1):
        try:
            read_limited(f"{base}/{filename}.sha512", checksum,
                         limit=CHECKSUM_LIMIT, opener=opener)
            expected = checksum.read_text(encoding="ascii").strip().lower()
            if not re.fullmatch(r"[0-9a-f]{128}", expected):
                raise PrefetchFailure("invalid-maven-checksum")
            read_limited(f"{base}/{filename}", payload, limit=limit, opener=opener)
            actual = hashlib.sha512()
            with payload.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    actual.update(block)
            if actual.hexdigest() != expected:
                raise PrefetchFailure("maven-sha512-mismatch")
            return payload, checksum, attempt
        except (OSError, UnicodeError, PrefetchFailure, ValueError):
            payload.unlink(missing_ok=True)
            checksum.unlink(missing_ok=True)
            if attempt == attempts:
                raise PrefetchFailure("verified-download-exhausted") from None
            pause(min(2 ** (attempt - 1), 4))
    raise PrefetchFailure("attempt-accounting")


def validate_jar(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            if not any(name.endswith(".class") for name in archive.namelist()):
                raise PrefetchFailure("no-android-classes")
    except (zipfile.BadZipFile, OSError):
        raise PrefetchFailure("invalid-jar") from None


def validate_pom(path: Path, version: str) -> None:
    try:
        root = ET.parse(path).getroot()
        ns = "{http://maven.apache.org/POM/4.0.0}"
        lookup = lambda name: root.findtext(ns + name) or root.findtext(name)
        if (lookup("groupId"), lookup("artifactId"), lookup("version")) != (
            "org.robolectric", ARTIFACT, version
        ):
            raise PrefetchFailure("wrong-maven-coordinate")
    except ET.ParseError:
        raise PrefetchFailure("invalid-pom") from None


def prefetch(*, sdk: int, gradle_file: Path, home: Path,
             maven_root: str = MAVEN_ROOT, opener=urlopen,
             pause=time.sleep) -> dict:
    if sdk not in SDK_TO_ARTIFACT:
        raise PrefetchFailure("unsupported-sdk")
    check_robolectric_pin(gradle_file)
    version = SDK_TO_ARTIFACT[sdk]
    jar_name = f"{ARTIFACT}-{version}.jar"
    pom_name = f"{ARTIFACT}-{version}.pom"
    target = home / ".m2" / "repository" / GROUP_PATH / version
    target.mkdir(parents=True, exist_ok=True)
    jar_target = target / jar_name
    pom_target = target / pom_name
    # A previously cached JAR is trusted only with its verified sidecar.
    existing = True
    for name in (jar_name, pom_name):
        file = target / name
        checksum = target / (name + ".sha512")
        if not file.is_file() or not checksum.is_file():
            existing = False
            break
        digest = checksum.read_text(encoding="ascii").strip().lower()
        if not re.fullmatch(r"[0-9a-f]{128}", digest):
            existing = False
            break
        hasher = hashlib.sha512()
        with file.open("rb") as reader:
            for block in iter(lambda: reader.read(1024 * 1024), b""):
                hasher.update(block)
        if hasher.hexdigest() != digest:
            existing = False
            break
    if existing:
        validate_jar(jar_target)
        validate_pom(pom_target, version)
        return {"sdk": sdk, "source": "verified-cache", "attempts": 0}

    base = f"{maven_root.rstrip('/')}/{GROUP_PATH}/{version}"
    with tempfile.TemporaryDirectory(prefix=".robolectric-staging-", dir=target) as tmp:
        scratch = Path(tmp)
        pom, pom_sha, pom_tries = verified_download(base, pom_name, scratch,
            limit=POM_LIMIT, opener=opener, pause=pause)
        validate_pom(pom, version)
        jar, jar_sha, jar_tries = verified_download(base, jar_name, scratch,
            limit=JAR_LIMIT, opener=opener, pause=pause)
        validate_jar(jar)
        # Robolectric treats jar existence as the fetched marker: install last.
        for name in (pom_sha.name, pom.name, jar_sha.name, jar.name):
            os.replace(scratch / name, target / name)
    return {"sdk": sdk, "source": "verified-prefetch", "attempts": max(pom_tries, jar_tries)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=int, required=True, choices=tuple(SDK_TO_ARTIFACT))
    parser.add_argument("--gradle-file", type=Path, default=Path("app/build.gradle.kts"))
    args = parser.parse_args()
    started = time.monotonic()
    try:
        result = prefetch(sdk=args.sdk, gradle_file=args.gradle_file, home=Path.home())
    except (PrefetchFailure, OSError) as exc:
        kind = exc.args[0] if isinstance(exc, PrefetchFailure) else "filesystem-error"
        print(f"CHUNKS_ROBOLECTRIC_PREFLIGHT: status=failed kind={kind}")
        raise SystemExit(1)
    print(f"CHUNKS_ROBOLECTRIC_PREFLIGHT: status=passed sdk={result['sdk']} "
          f"source={result['source']} attempts={result['attempts']} "
          f"elapsed_s={time.monotonic()-started:.1f}")


if __name__ == "__main__":
    main()
