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
          "ui-progress", "stopped", "restarted", "resumed-traffic", "ui-controls", "passed", "uncertain")
FAILURE_TYPES = {
    "AssertionError", "IllegalStateException", "IllegalArgumentException",
    "SocketTimeoutException", "ConnectException", "IOException",
}


def parse(raw, exit_code, env=None):
    env = os.environ if env is None else env
    phases = {source: {name: 0 for name in PHASES} for source in SOURCES}
    observed_hashes = {source: set() for source in SOURCES}
    traffic = {source: {} for source in SOURCES}
    current_case = None
    cases = []
    case_results = []
    peerless_hashes = set()
    peerless = 0
    terminal_count = 0
    controlled = env.get("CHUNKS_CONTROLLED_PEER") == "1"
    expected_cases = {
        "installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes",
        "installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes",
    }
    runner = {"started_tests": 0, "passed_tests": 0, "failed_tests": 0,
              "total_test_end_codes": 0, "runner_finished": False,
              "exception_classes": []}
    allow = re.compile(r"^INSTRUMENTATION_STATUS: chunks_real_add_stage=(magnet2|magnet)-([a-z-]+)$")
    hashes = re.compile(r"^INSTRUMENTATION_STATUS: chunks_real_add_hash_(magnet2|magnet)=([a-f0-9]{40})$")
    for line in raw.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: test="):
            candidate = line.split("test=", 1)[1]
            current_case = candidate if candidate in expected_cases else None
        if line in ("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", "INSTRUMENTATION_STATUS_CODE: -3"):
            cases.append(current_case)
            case_results.append({"name": current_case, "outcome": "passed" if line.endswith(": 0") else "failed"})
        negative_hash = re.fullmatch(r"INSTRUMENTATION_STATUS: chunks_real_add_hash_negative=([a-f0-9]{40})", line)
        if negative_hash:
            peerless_hashes.add(negative_hash.group(1))
        if line == "INSTRUMENTATION_STATUS: chunks_real_add_negative=peerless-ui-and-cleanup-passed":
            peerless += 1
        if line == "INSTRUMENTATION_CODE: -1":
            terminal_count += 1
        sample = re.fullmatch(r"INSTRUMENTATION_STATUS: chunks_real_add_traffic_(magnet2|magnet)_(initial|resumed)=([0-9,]{1,120})", line)
        if sample:
            values = sample.group(3).split(",")
            if len(values) == 5 and all(v.isdecimal() and 0 <= int(v) < 2**63 for v in values):
                traffic[sample.group(1)][sample.group(2)] = dict(zip(
                    ("received", "verified", "expected", "peers", "rate"), map(int, values)))
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
            "source_id": (("debian-cdimage" if source == "magnet" else "blender-sintel-webtorrent") if controlled else
                          ("ubuntu-25.10-live-server" if source == "magnet" else "ubuntu-25.10-desktop")),
            "traffic": traffic[source],
            "phase_counts": counts,
            "first_unverified_checkpoint": next((stage for stage in
                ("preflight", "review", "ui-verified", "registered", "metadata", "peer-progress", "bytes-verified", "restarted", "passed")
                if counts[stage] != 1), None),
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
    if controlled:
        complete = complete and peerless == 1 and terminal_count == 1 and set(cases) == expected_cases and len(cases) == 2
        for source in SOURCES:
            initial, resumed = (traffic[source].get(phase, {}) for phase in ("initial", "resumed"))
            complete = complete and all(phases[source][stage] == 1 for stage in
                ("ui-progress", "stopped", "resumed-traffic", "ui-controls")) and bool(initial and resumed)
            if initial and resumed:
                complete = complete and (0 < initial["verified"] < initial["expected"] == resumed["expected"] and
                    0 < initial["received"] < resumed["received"] and initial["verified"] < resumed["verified"])
    return {
        "schema_version": 1, "source_sha": env.get("GITHUB_SHA"),
        "controlled_real_peer": controlled, "peerless_negative_passed": peerless == 1,
        "actual_case_identities": cases, "actual_case_results": case_results, "terminal_count": terminal_count,
        "peerless_negative_hash": next(iter(peerless_hashes)) if len(peerless_hashes) == 1 else None,
        "environment": "disposable" if env.get("CHUNKS_DISPOSABLE") == "1" else "approved-development-Seedhost",
        "runtime_seconds": env.get("CHUNKS_ACCEPTANCE_RUNTIME_SECONDS"),
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
