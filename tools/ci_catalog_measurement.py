#!/usr/bin/env python3
"""Bounded advisory measurements from two named synthetic JVM tests, never raw stdout."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

from ci_failure_digest import junit_declared_count_conflicts

SIZES = (100, 1000, 10000, 50000)
CASES = ("name_unchanged", "grouped_speed_unchanged",
         "grouped_speed_updated", "media_search")
POLICY_CLASS = "io.github.gamesbyian.chunks.seeding.RemoteSeedingTickTest"
CATALOG_CLASS = "io.github.gamesbyian.chunks.model.UnifiedTransferCatalogPerformanceTest"
POLICY_TEST = "measuresRemoteSeedingActivationAndQuietPollAtCatalogSizes"
CATALOG_TEST = "measuresMixedClientCatalogQueriesAndOnePercentUpdates"
POLICY = re.compile(
    r"CHUNKS_POLICY_CATALOG: jobs=(100|1000|10000|50000) "
    r"disabled_us=([0-9]{1,9}) activation_us=([0-9]{1,9}) "
    r"steady_median_us=([0-9]{1,9}) steady_range_us=([0-9]{1,9})-([0-9]{1,9}) "
    r"inventory_per_enabled_tick=1 facts_per_tick=0"
)
CATALOG = re.compile(
    r"CHUNKS_UNIFIED_CATALOG: jobs=(100|1000|10000|50000) "
    r"case=(name_unchanged|grouped_speed_unchanged|grouped_speed_updated|media_search) "
    r"median_us=([0-9]{1,9}) range_us=([0-9]{1,9})-([0-9]{1,9})"
)


def collect(directory, klass, test, pattern, expected, prefix, policy=False):
    path = directory / ("TEST-" + klass + ".xml")
    try:
        if path.stat().st_size > 2_000_000:
            return None
        root = ET.parse(path).getroot()
        if root.tag != "testsuite" or root.get("name") != klass:
            return None
        if junit_declared_count_conflicts(root):
            return None
        cases = [c for c in root.findall("testcase")
                 if c.get("classname") == klass and c.get("name") == test]
        if len(cases) != 1 or any(cases[0].find(tag) is not None
                                  for tag in ("failure", "error", "skipped")):
            return None
        rows = {}
        for node in root.findall("system-out"):
            for line in (node.text or "").splitlines():
                if not line.startswith(prefix):
                    continue
                match = pattern.fullmatch(line)
                if match is None:
                    return None
                g = match.groups()
                size = int(g[0])
                if policy:
                    disabled, activation, median, low, high = map(int, g[1:])
                    key = (size,)
                    row = {"jobs": size, "disabled_us": disabled, "activation_us": activation,
                           "median_us": median, "min_us": low, "max_us": high,
                           "inventories_per_enabled_tick": 1, "extra_facts_per_tick": 0}
                else:
                    median, low, high = map(int, g[2:])
                    key = (size, g[1])
                    row = {"jobs": size, "case": g[1],
                           "median_us": median, "min_us": low, "max_us": high}
                if key in rows or not low <= median <= high:
                    return None
                rows[key] = row
        if set(rows) != set(expected):
            return None
        return [rows[key] for key in expected]
    except (OSError, ET.ParseError, ValueError):
        return None


def receipt(directory):
    return {
        "scope": "synthetic hosted JVM; excludes real network, device UI and heap",
        "policy": collect(directory, POLICY_CLASS, POLICY_TEST, POLICY,
                          [(s,) for s in SIZES], "CHUNKS_POLICY_CATALOG:", policy=True),
        "unified_catalog": collect(directory, CATALOG_CLASS, CATALOG_TEST, CATALOG,
                                   [(s, c) for s in SIZES for c in CASES],
                                   "CHUNKS_UNIFIED_CATALOG:"),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    args = parser.parse_args()
    # Missing evidence is null, never a pass or zero-duration sample.
    print("CHUNKS_CATALOG_MEASUREMENTS: " + json.dumps(receipt(args.report_directory),
                                                       sort_keys=True))
