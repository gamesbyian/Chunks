#!/usr/bin/env python3
"""Offline source/receipt preflight for the bounded owned-Downloads suite.

Detects contract drift before Gradle or a device is allocated. Does not
replace the actual runtime JUnit cases or named real Android receipts.
"""
from collections import Counter
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "app/src/androidTest/java"
PROBE = "tools/owned_mediastore_document_probe.sh"
MOCK = "tools/test_owned_mediastore_document_probe_mock.sh"
CONTINUE = chr(92)
REQUIRED_CLASSES = {
    "io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublicationInstrumentedTest",
    "io.github.gamesbyian.chunks.LocalSingleZipMediaStoreExportInstrumentedTest",
    "io.github.gamesbyian.chunks.LocalSingleLibraryMediaStoreCopyInstrumentedTest",
    "io.github.gamesbyian.chunks.LocalMultiZipMediaStoreExportInstrumentedTest",
}


def unique_match(pattern, text, label):
    matches = re.findall(pattern, text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"{label}: expected one unambiguous source declaration")
    return matches[0]


def named_cases(text, variable):
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines)
              if line.strip() == f"for {variable} in " + CONTINUE]
    if len(starts) != 1:
        raise ValueError(f"{variable} receipt list is missing or ambiguous")
    names = []
    for line in lines[starts[0] + 1:]:
        item = line.strip()
        final = item.endswith("; do")
        if final:
            name = item[:-4].strip()
        elif item.endswith(CONTINUE):
            name = item[:-1].strip()
        else:
            raise ValueError(f"{variable} receipt list has unsupported shell text")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ValueError(f"{variable} receipt list contains an invalid method")
        names.append(name)
        if final:
            return names
    raise ValueError(f"{variable} receipt list is unterminated")


def compare(expected, observed, label):
    want, got = Counter(expected), Counter(observed)
    if want != got:
        missing = sorted((want - got).elements())
        extra = sorted((got - want).elements())
        raise ValueError(f"{label}: missing={missing}; extra/duplicate={extra}")


def validate(probe, mock, read_source):
    selector = unique_match(
        r"^[ \t]*-e class ([A-Za-z0-9_.,]+)[ \t]*\\[ \t]*$",
        probe, "instrumentation class selector")
    classes = selector.split(",")
    compare(REQUIRED_CLASSES, classes, "required class selector")
    methods = []
    for name in classes:
        source = read_source(SOURCE + "/" + name.replace(".", "/") + ".kt")
        declarations = re.findall(r"(?m)^[ \t]*@Test\b", source)
        found = re.findall(r"(?m)^[ \t]*@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", source)
        if len(declarations) != len(found) or not found:
            raise ValueError(f"{name}: unsupported or missing @Test declaration")
        methods.extend(found)
    if len(set(methods)) != len(methods):
        raise ValueError("method names must be unique across these receipt classes")
    count = int(unique_match(r"OK \(([0-9]+) tests\)", probe, "exact success count"))
    if count != len(methods):
        raise ValueError(f"exact success count {count} != {len(methods)} source cases")
    compare(methods, named_cases(probe, "case_name"), "real probe")
    compare(methods, named_cases(mock, "name"), "offline fixture")
    return len(methods)


def main():
    try:
        probe = (ROOT / PROBE).read_text(encoding="utf-8")
        mock = (ROOT / MOCK).read_text(encoding="utf-8")
        read_source = lambda path: (ROOT / path).read_text(encoding="utf-8")
        count = validate(probe, mock, read_source)
        # Real source-derived negative controls: stale count, removed or
        # duplicated real case, missing mock case, and an omitted test class.
        name = named_cases(probe, "case_name")[0]
        real_line = "  " + name + " " + CONTINUE + "\n"
        mock_line = "          " + name + " " + CONTINUE + "\n"
        for bad_probe, bad_mock in (
            (probe.replace(f"OK ({count} tests)", f"OK ({count - 1} tests)"), mock),
            (probe.replace(real_line, "", 1), mock),
            (probe.replace(real_line, real_line * 2, 1), mock),
            (probe, mock.replace(mock_line, "", 1)),
            (probe.replace("," + sorted(REQUIRED_CLASSES)[0], "", 1), mock),
        ):
            try:
                validate(bad_probe, bad_mock, read_source)
            except ValueError:
                continue
            raise ValueError("source-contract negative control was accepted")
    except (OSError, ValueError) as exc:
        print("CHUNKS_OWNED_CASE_CONTRACT: " + str(exc), file=sys.stderr)
        return 1
    print(f"CHUNKS_OWNED_CASE_CONTRACT: {count} source cases, exact real/mock receipts and negatives verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
