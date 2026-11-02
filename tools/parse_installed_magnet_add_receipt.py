#!/usr/bin/env python3
"""Sanitized, exhaustive installed Android two-magnet acceptance receipt.

Read test runner protocol markers only. Never copy raw instrumentation
output, exception messages, protected URL, credentials or tracker values.
"""
import argparse
import json
import os
from pathlib import Path
import re

SOURCES = ("magnet", "magnet2")
PHASES = ("preflight", "review", "ui-verified", "registered",
          "readback-error", "metadata", "peer-progress", "bytes-verified",
          "restarted", "passed", "uncertain")
FAILURE_TYPES = {
    "AssertionError", "IllegalStateException", "IllegalArgumentException",
    "SocketTimeoutException", "ConnectException", "IOException",
}


def parse(raw, exit_code, env=None):
    env = os.environ if env is None else env
    phases = {source: {name: 0 for name in PHASES} for source in SOURCES}
    observed_hashes = {source: set() for source in SOURCES}
    runner = {"started_tests": 0, "passed_tests": 0, "failed_tests": 0,
              "total_test_end_codes": 0, "runner_finished": False,
              "exception_classes": []}
    allow = re.compile(r"^INSTRUMENTATION_STATUS: chunks_real_add_stage=(magnet2|magnet)-([a-z-]+)$")
    hashes = re.compile(r"^INSTRUMENTATION_STATUS: chunks_real_add_hash_(magnet2|magnet)=([a-f0-9]{40})$")
    for line in raw.splitlines():
        identity = hashes.fullmatch(line)
        if identity:
            observed_hashes[identity.group(1)].add(identity.group(2))
        match = allow.fullmatch(line)
        if match and match.group(2) in PHASES:
            phases[match.group(1)][match.group(2)] += 1
        if line == "INSTRUMENTATION_STATUS_CODE: 1":
            runner["started_tests"] += 1
        if line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            result_code = line.split(": ", 1)[1]
            if result_code in ("0", "-2", "-3"):
                runner["total_test_end_codes"] += 1
                runner["passed_tests" if result_code == "0" else "failed_tests"] += 1
        if line == "INSTRUMENTATION_CODE: -1":
            runner["runner_finished"] = True
        # The *first* exception class is allowable; never print the stack.
        if line.startswith("INSTRUMENTATION_STATUS: stack="):
            first = line.split("stack=", 1)[1].split(":", 1)[0].rsplit(".", 1)[-1]
            runner["exception_classes"].append(
                first if first in FAILURE_TYPES else "OtherFailure"
            )
    per_source = {}
    for source in SOURCES:
        counts = phases[source]
        per_source[source] = {
            "source_id": "ubuntu-25.10-live-server" if source == "magnet"
                         else "ubuntu-25.10-desktop",
            "phase_counts": counts,
            "exact_infohash": next(iter(observed_hashes[source])) if len(observed_hashes[source]) == 1 else None,
            "hash_receipt": "confirmed" if len(observed_hashes[source]) == 1 else
                            "missing" if not observed_hashes[source] else "conflicting",
            "metadata_verified": counts["metadata"] == 1,
            "real_peer_received": counts["peer-progress"] == 1,
            "piece_verified": counts["bytes-verified"] == 1,
            "restarted": counts["restarted"] == 1,
            "passed": counts["passed"] == 1,
            "cleanup": "verified-by-test" if counts["passed"] == 1 else
                       "uncertain" if counts["uncertain"] else "not-proven",
        }
    complete = (exit_code == 0 and runner["runner_finished"] and
                runner["started_tests"] == runner["passed_tests"] == 2 and
                runner["failed_tests"] == 0 and
                runner["total_test_end_codes"] == 2 and
                len(set.union(*observed_hashes.values())) == len(SOURCES) and
                all(x["hash_receipt"] == "confirmed" and
                    x["passed"] and x["piece_verified"] and x["restarted"]
                    and x["metadata_verified"] and x["real_peer_received"]
                    and x["cleanup"] == "verified-by-test"
                    for x in per_source.values()))
    return {
        "schema_version": 1, "source_sha": env.get("GITHUB_SHA"),
        "run_id": env.get("GITHUB_RUN_ID"), "tier": "API35-installed-emulator-live-rTorrent",
        "result": "passed" if complete else "failed-or-inconclusive",
        "runner": runner, "sources": per_source,
        "previous_torrent_positive_run": 41363266446,
        "physical_device_proven": False,
        "known_evidence_gap": None if complete else
            "At least one required magnet, metadata, peer, restart or cleanup receipt missing",
        "security_note": "No raw instrumentation or exception text included",
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--exit", type=int, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    try:
        data = parse(a.input.read_text(errors="replace"), a.exit)
    except OSError:
        data = parse("", a.exit)
        data["input_log"] = "unavailable"
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n")
    print("CHUNKS_INSTALLED_MAGNET_RECEIPT: status=" + data["result"] +
          " started=" + str(data["runner"]["started_tests"]) +
          " finished=" + str(data["runner"]["total_test_end_codes"]) +
          " confirmed_passes=" + str(data["runner"]["passed_tests"]) +
          " source_passes=" +
          ",".join(name + ":" + str(int(x["passed"]))
                   for name, x in data["sources"].items()))
    step = os.getenv("GITHUB_STEP_SUMMARY")
    if step:
        with open(step, "a", encoding="utf-8") as out:
            out.write("### Installed API35 real magnet acceptance\n\n")
            out.write("- Result: " + data["result"] + ", source: " +
                      str(data["source_sha"]) + ", run: " +
                      str(data["run_id"]) + "\n")
            for label, src in data["sources"].items():
                out.write("- " + label + ": " + ", ".join(
                    k + "=" + str(v) for k, v in src["phase_counts"].items()
                ) + "\n")
            out.write("- No physical-device acceptance inferred\n")


if __name__ == "__main__":
    main()
