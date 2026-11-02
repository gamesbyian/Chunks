#!/usr/bin/env python3
"""Require one genuinely executed, passing R8 Android smoke case at exact source.

Consumes the secret-free structured JUnit digest, never raw test output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

SHA40 = re.compile(r"[0-9a-f]{40}\Z")
EXPECTED_CLASS = "io.github.gamesbyian.chunks.MinifiedBuildSmokeInstrumentedTest"
EXPECTED_TEST = "nativeEngineMovesExactBytesAndComposeRenders"
# Match the identity encoding used by the shared secret-free CI digest.
EXPECTED_TEST_ID = hashlib.sha256(
    (EXPECTED_CLASS + "\\0" + EXPECTED_TEST).encode("utf-8")
).hexdigest()


def require_single_smoke(digest, expected_sha, expected_run_id):
    if not isinstance(digest, dict) or digest.get("schema_version") != 1:
        raise ValueError("missing versioned JUnit digest")
    sha = digest.get("source_sha")
    run_id = digest.get("run_id")
    if not isinstance(sha, str) or not SHA40.fullmatch(sha):
        raise ValueError("missing exact source commit")
    if not isinstance(run_id, str) or not run_id.isdecimal():
        raise ValueError("missing run identifier")
    # A correctly shaped stale digest is still not evidence for THIS run.
    if (not isinstance(expected_sha, str)
            or not SHA40.fullmatch(expected_sha)
            or not isinstance(expected_run_id, str)
            or not expected_run_id.isdecimal()
            or sha != expected_sha or run_id != expected_run_id):
        raise ValueError("JUnit digest does not match this workflow execution")
    junit = digest.get("junit")
    cases = digest.get("test_cases")
    if (digest.get("suite") != "r8-qa-minified"
            or digest.get("reported_job_status") != "success"
            or digest.get("phase") != "none"
            or digest.get("evidence_executed") is not True
            or digest.get("test_identity_coverage_complete") is not True
            or digest.get("test_identity_count") != 1
            or digest.get("invalid_reports")
            or not isinstance(junit, dict)
            or not isinstance(cases, list)
            or len(cases) != 1
            or cases[0].get("outcome") != "passed"
            or cases[0].get("id_sha256") != EXPECTED_TEST_ID
            or junit.get("reports", 0) < 1
            or junit.get("tests") != 1
            or junit.get("executed") != 1
            or junit.get("failed") != 0
            or junit.get("skipped") != 0):
        raise ValueError("R8 smoke not proved by one executed, passing JUnit case")
    return sha


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("digest", type=Path)
    args = parser.parse_args(argv)
    try:
        sha = require_single_smoke(
            json.loads(args.digest.read_text(encoding="utf-8")),
            os.environ.get("GITHUB_SHA"), os.environ.get("GITHUB_RUN_ID"),
        )
    except (ValueError, OSError, TypeError):
        print("ERROR: R8 smoke exact-source JUnit evidence incomplete or invalid",
              file=sys.stderr)
        return 1
    print(f"R8 smoke: 1 executed and passed; exact tested source: {sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
