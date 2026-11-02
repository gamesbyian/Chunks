#!/usr/bin/env python3
"""Fail-closed installed Android receipt plus actual runner-derived JUnit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
from parse_installed_magnet_add_receipt import parse


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--runner-temp", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    def read(name):
        path = a.runner_temp / name
        return path.read_text(errors="replace") if path.is_file() else ""
    try:
        code = int(read("chunks-installed-magnets.exit").strip())
    except ValueError:
        code = -1
    env = dict(os.environ)
    runtime = read("chunks-installed-magnets.runtime").strip()
    env["CHUNKS_ACCEPTANCE_RUNTIME_SECONDS"] = runtime if runtime.isdecimal() else None
    raw = read("chunks-installed-magnets.raw")
    receipt = parse(raw, code, env)
    peer_path = a.runner_temp / "chunks-real-peer/PEER-RECEIPT.json"
    peer_valid = False
    if peer_path.is_file():
        try:
            peer = json.loads(peer_path.read_text())
            peer_valid = peer["source_sha"] == env["GITHUB_SHA"] and peer["run_id"] == env["GITHUB_RUN_ID"]
            for label, source in receipt["sources"].items():
                independent = peer["fixtures"][label]
                peer_valid = peer_valid and (source["exact_infohash"] == independent["hash"] and
                    independent["native_verified_bytes"] >= independent["prefix_bytes"] > 0)
                if receipt["public_peer_discovery"]:
                    # Public-mode acceptance must not secretly use our seed.
                    peer_valid = peer_valid and (independent["stages"] == ["preflight"] and
                        independent["payload_uploaded_bytes"] == independent["peer_connections"] == 0)
                else:
                    peer_valid = peer_valid and (independent["payload_uploaded_bytes"] > 0 and independent["peer_connections"] > 0)
            shutil.copyfile(peer_path, a.output / "PEER-RECEIPT.json")
        except (ValueError, KeyError, TypeError):
            peer_valid = False
    receipt["application_id"] = "io.github.gamesbyian.chunks." + env.get("CHUNKS_PREVIEW_SUFFIX", "preview")
    receipt["independent_peer_evidence"] = "verified" if peer_valid else "missing-or-inconsistent"
    receipt["independent_evidence_kind"] = "original source pieces checked; supplied peer unused" if receipt["public_peer_discovery"] else "real supplied peer upload and connections"
    if not peer_valid:
        receipt["result"] = "failed-or-inconclusive"
    (a.output / "RECEIPT.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    # The two test identities and outcomes come from AndroidJUnitRunner, not
    # from successful RPC acknowledgements or invented "passed" XML cases.
    passed = receipt["result"] == "passed"
    expected_cases = ("installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace",)
    suite = ET.Element("testsuite", name="installed-real-rtorrent-magnets", tests=str(len(expected_cases)),
                       failures="0", errors="0", skipped="0", time=runtime if runtime.isdecimal() else "0")
    failures, errors = 0, 0
    for name in expected_cases:
        case = ET.SubElement(suite, "testcase", classname="io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest", name=name)
        actual = [c for c in receipt["actual_case_results"] if c["name"] == name and c["class"] == "io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest"]
        if len(actual) != 1:
            errors += 1
            ET.SubElement(case, "error", type="RunnerReportMissing", message="Actual Android test completion absent or conflicting")
        elif actual[0]["outcome"] != "passed":
            failures += 1
            ET.SubElement(case, "failure", type="AndroidAssertionFailed", message="Actual Android test failed; see phase receipt")
    if not passed and failures == errors == 0:
        errors += 1
        ET.SubElement(ET.SubElement(suite, "testcase", name="independentAcceptanceEvidence"),
            "error", type="AcceptanceIncomplete", message="Required independent peer or negative evidence missing")
        suite.set("tests", str(len(expected_cases) + 1))
    suite.set("failures", str(failures)); suite.set("errors", str(errors))
    ET.ElementTree(suite).write(a.output / "TEST-installed-magnets.xml", encoding="utf-8", xml_declaration=True)
    raw_path = a.runner_temp / "chunks-installed-magnets.raw"
    raw_path.unlink(missing_ok=True)
    if not passed:
        raise SystemExit("Installed real-magnet acceptance incomplete; no qualified APK")
    source = env["GITHUB_SHA"]
    if not re.fullmatch("[a-f0-9]{40}", source):
        raise SystemExit("Invalid source provenance")
    apk = Path("app/build/outputs/apk/debug/app-debug.apk")
    apk_name = "chunks-magnet-verified-preview-" + source[:12] + ".apk"
    shutil.copyfile(apk, a.output / apk_name)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (a.output / "CHUNKS-BUILD-SHA256.txt").write_text(
        f"# source_sha={source}\n# run_id={env['GITHUB_RUN_ID']}\n# debug-signed preview; API35 emulator; physical/OEM not tested\n{digest}  {apk_name}\n")
    print("CHUNKS_INSTALLED_MAGNET: two public magnets, UI controls, resumed verified pieces, peerless truth and cleanup passed")


if __name__ == "__main__":
    main()
