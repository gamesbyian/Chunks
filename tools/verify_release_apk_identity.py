#!/usr/bin/env python3
"""Fail-closed package-identity readback for a signed release APK.

Signature verification and requested Gradle inputs alone do not establish
what application ID and version were actually packaged into the artifact.
"""
import argparse
import re
import subprocess
import sys

EXPECTED_PACKAGE = "io.github.gamesbyian.chunks"
ATTRIBUTE = re.compile(r"(?<!\S)([A-Za-z][A-Za-z0-9]*)='([^']*)'")


def verify_badging(output: str, version_name: str, version_code: str) -> None:
    """Reject missing, ambiguous or contradictory aapt package metadata."""
    package_lines = [line for line in output.splitlines() if line.startswith("package:")]
    if len(package_lines) != 1:
        raise ValueError("expected exactly one aapt package declaration")
    values = {}
    for key, value in ATTRIBUTE.findall(package_lines[0]):
        if key in values:
            raise ValueError("duplicate aapt package attribute")
        values[key] = value
    expected = {"name": EXPECTED_PACKAGE, "versionCode": version_code,
                "versionName": version_name}
    for key, value in expected.items():
        if values.get(key) != value:
            raise ValueError(f"packaged {key} differs from requested release identity")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    args = parser.parse_args(argv)
    try:
        result = subprocess.run(
            [args.aapt, "dump", "badging", args.apk],
            capture_output=True, text=True, check=True, timeout=30,
        )
        verify_badging(result.stdout, args.version_name, args.version_code)
    except (subprocess.SubprocessError, OSError, ValueError) as exc:
        print(f"CHUNKS_RELEASE_IDENTITY: unverified ({type(exc).__name__})", file=sys.stderr)
        return 1
    print("CHUNKS_RELEASE_IDENTITY: packaged app ID, versionName and versionCode verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
