#!/usr/bin/env python3
"""Secret-free, fail-closed real BEP9 receipt, including early JVM/test failures.

Only allowlisted markers and the synthetic public test hash are published.
Real JUnit is authoritative for suite execution; a protected sidecar preserves
phases when Gradle/JUnit drops per-test stdout or the JVM dies unexpectedly.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

STAGES = (
    "runner-ready", "preflight", "destination", "peer", "seed-ready",
    "add-submitted", "added-started", "started", "seed-started", "peer-connected", "metadata-verified", "bytes-verified",
    "stopped", "removed", "inventory-restored", "manual-reconciliation-required",
)
STAGE_SET = set(STAGES)
ERROR_CLASSES = {
    "AssertionError", "IllegalStateException", "IllegalArgumentException",
    "SocketTimeoutException", "ConnectException", "UnknownHostException",
    "IOException", "NoSuchMethodError", "UnsatisfiedLinkError",
    "OutOfMemoryError",
}


def parse(xml_path, sidecar_path, exit_code, env=None):
    env = os.environ if env is None else env
    phases, hashes = [], set()
    suite = {"tests": None, "failures": None, "errors": None, "skipped": None,
             "report": "missing", "failed_categories": [], "cases": []}

    def consume(line):
        line = line.strip()
        match = re.fullmatch(r"CHUNKS_HOST_BYTES_STAGE: ([a-z0-9-]+)", line)
        if match and match.group(1) in STAGE_SET:
            phases.append(match.group(1))
        match = re.fullmatch(r"CHUNKS_HOST_BYTES_HASH: ([a-f0-9]{40})", line)
        if match:
            hashes.add(match.group(1))

    # A marker under RUNNER_TEMP can survive premature JVM exit. Fixed format
    # prevents exceptions or secret-bearing host responses reaching CI output.
    if sidecar_path.is_file():
        if sidecar_path.stat().st_size > 16_384:
            suite["sidecar"] = "oversize"
        else:
            for line in sidecar_path.read_text(encoding="utf-8").splitlines():
                if line.startswith("stage:"):
                    consume("CHUNKS_HOST_BYTES_STAGE: " + line.removeprefix("stage:"))
                elif line.startswith("hash:"):
                    consume("CHUNKS_HOST_BYTES_HASH: " + line.removeprefix("hash:"))
            suite["sidecar"] = "read"
    else:
        suite["sidecar"] = "missing"

    if xml_path.is_file():
        try:
            root = ET.parse(xml_path).getroot()
            if root.tag != "testsuite":
                raise ValueError("unexpected junit root")
            for key in ("tests", "failures", "errors", "skipped"):
                suite[key] = int(root.attrib[key])
            cases = root.findall(".//testcase")
            if len(cases) != suite["tests"]:
                raise ValueError("inconsistent junit test count")
            for case in cases:
                for node in case.findall("system-out"):
                    for line in (node.text or "").splitlines():
                        consume(line)
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                outcome = ("skipped" if case.find("skipped") is not None else
                           "failed" if failure is not None else "passed")
                if failure is not None:
                    raw_class = failure.attrib.get("type", "").rsplit(".", 1)[-1]
                    suite["failed_categories"].append(
                        raw_class if raw_class in ERROR_CLASSES else "OtherFailure"
                    )
                identity = (case.get("classname") or "") + "\0" + (case.get("name") or "")
                suite["cases"].append({
                    "id_sha256": hashlib.sha256(identity.encode("utf-8")).hexdigest(),
                    "outcome": outcome,
                })
            for node in root.findall("./system-out"):
                for line in (node.text or "").splitlines():
                    consume(line)
            suite["report"] = "valid"
        except (OSError, ValueError, ET.ParseError, KeyError, TypeError):
            suite["report"] = "invalid"
    unique_hash = next(iter(hashes)) if len(hashes) == 1 else None
    stages = [stage for stage in STAGES if stage in phases]
    cleanup = ("verified" if "inventory-restored" in phases else
               "manual-reconciliation-required" if
               "manual-reconciliation-required" in phases else
               "unconfirmed-after-add" if "add-submitted" in phases or "added-started" in phases else
               "no-verified-add")
    passed = (exit_code == 0 and suite["report"] == "valid" and
              suite["tests"] == 3 and
              suite["failures"] == suite["errors"] == suite["skipped"] == 0 and
              len({case["id_sha256"] for case in suite["cases"]}) == 3 and
              unique_hash is not None and
              all(phase in phases for phase in ("metadata-verified", "bytes-verified",
                                                "inventory-restored")) and
              cleanup == "verified")
    return {
        "schema_version": 1, "result": "passed" if passed else "failed-or-inconclusive",
        "source_sha": env.get("GITHUB_SHA"), "run_id": env.get("GITHUB_RUN_ID"),
        "test_tier": "host-jvm-real-BEP9-magnet", "gradle_exit_code": exit_code,
        "failure_boundary": next((label for stage, label in (
            ("peer", "host-listener-reachability"),
            ("seed-ready", "runner-seeder-registration"),
            ("added-started", "remote-magnet-registration"),
            ("started", "remote-active-state"),
            ("seed-started", "runner-seeder-recheck"),
            ("peer-connected", "peer-connection"),
            ("metadata-verified", "BEP9-metadata"),
            ("bytes-verified", "payload-pieces"),
            ("inventory-restored", "session-cleanup"),
        ) if stage not in phases), None),
        "junit": suite, "stage_evidence": stages,
        "last_stage": phases[-1] if phases else "unavailable",
        "synthetic_infohash": unique_hash,
        "hash_identity_proven": len(hashes) == 1,
        "cleanup": cleanup,
        "acceptance": {
            "metadata": "metadata-verified" in phases,
            "peer_bytes_and_pieces": "bytes-verified" in phases,
            "session_removed": "inventory-restored" in phases,
            "installed_android": False, "physical_device": False,
        },
        "security_note": "Only allowlisted labels, exception classes and synthetic hash",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--sidecar", type=Path, required=True)
    parser.add_argument("--exit-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        exit_code = int(args.exit_file.read_text().strip()) if args.exit_file.is_file() else -1
        if exit_code not in range(-1, 256):
            raise ValueError("invalid Gradle exit status")
        receipt = parse(args.junit, args.sidecar, exit_code)
    except (OSError, ValueError, UnicodeDecodeError):
        receipt = {
            "schema_version": 1, "result": "failed-or-inconclusive",
            "source_sha": os.getenv("GITHUB_SHA"), "run_id": os.getenv("GITHUB_RUN_ID"),
            "receipt": "invalid-runner-input", "acceptance": {"installed_android": False},
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    summary = (
        "CHUNKS_REAL_MAGNET_PEER: result=" + receipt["result"] +
        " junit=" + receipt.get("junit", {}).get("report", "unavailable") +
        " last_stage=" + receipt.get("last_stage", "unavailable") +
        " cleanup=" + receipt.get("cleanup", "unknown") +
        " synthetic_hash=" + str(receipt.get("synthetic_infohash") or "not-recorded")
    )
    print(summary)
    step = os.getenv("GITHUB_STEP_SUMMARY")
    if step:
        with open(step, "a", encoding="utf-8") as out:
            out.write("### Real magnet metadata and peer acceptance\n\n")
            out.write("- " + summary + "\n")
            if "junit" in receipt:
                j = receipt["junit"]
                out.write("- Tests: " + str(j.get("tests")) +
                          ", failed: " + str(j.get("failures")) +
                          ", errors: " + str(j.get("errors")) +
                          ", skipped: " + str(j.get("skipped")) +
                          ", failure classes: " + ",".join(j.get("failed_categories", [])) + "\n")
            out.write("- No Android installed-app acceptance or physical-device claim\n")
    if receipt["result"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
