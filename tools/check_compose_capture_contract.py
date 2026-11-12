#!/usr/bin/env python3
"""Verify current 200% Compose screenshot producers before costly Android tests.

Evidence inventory only: this does not judge the pixels or prove a user journey.
"""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PRODUCER = ROOT / "app/src/androidTest/java/io/github/gamesbyian/chunks/SystemFontScaleComposeInstrumentedTest.kt"
PREFIX = "chunks-compose-200pct-"
PNG = b"\x89PNG\r\n\x1a\n"


class ContractError(ValueError):
    pass


def capture_names(source: str) -> tuple[str, ...]:
    """The only expected screenshot list is the test's literal savePixels calls."""
    names = []
    for call in re.finditer(r"\bsavePixels\s*\(", source):
        if re.search(r"\bfun\s+$", source[max(0, call.start() - 80):call.start()]):
            continue  # Kotlin function declaration, not a capture
        match = re.match(r'\s*"([a-z][a-z0-9-]*)"\s*\)', source[call.end():])
        if match is None:
            raise ContractError("Dynamic savePixels name cannot be inventoried")
        names.append(match.group(1))
    if len(names) < 2:
        raise ContractError("No meaningful screenshot inventory found")
    if len(set(names)) != len(names):
        raise ContractError("Duplicate screenshot names overwrite evidence")
    return tuple(names)


def verify_dir(folder: Path, names: tuple[str, ...]) -> None:
    wanted = {PREFIX + n + ".png" for n in names}
    found = {p.name for p in folder.glob(PREFIX + "*.png") if p.is_file()}
    if wanted != found:
        raise ContractError(f"Artifact names mismatch: missing={sorted(wanted-found)} extra={sorted(found-wanted)}")
    for name in sorted(wanted):
        data = (folder / name).read_bytes()[:24]
        if len(data) < 24 or data[:8] != PNG or data[12:16] != b"IHDR":
            raise ContractError(f"Bad PNG header: {name}")


def verify_wiring(source: str, runner: str) -> None:
    if '"chunks-compose-200pct-$name.png"' not in source:
        raise ContractError("Screenshot producer filename prefix changed")
    if 'Environment.DIRECTORY_PICTURES + "/ChunksCompose200Pct"' not in source:
        raise ContractError("Screenshot producer directory changed")
    if "adb pull /sdcard/Pictures/ChunksCompose200Pct/." not in runner:
        raise ContractError("Screenshot collector directory changed")
    if "check_compose_capture_contract.py --verify-dir" not in runner:
        raise ContractError("Artifact consumer is detached from producer contract")


def preflight() -> tuple[str, ...]:
    source = PRODUCER.read_text(encoding="utf-8")
    names = capture_names(source)
    runner = (ROOT / "tools/run_compose_system_font_200pct.sh").read_text(encoding="utf-8")
    verify_wiring(source, runner)
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--preflight", action="store_true")
    modes.add_argument("--verify-dir", type=Path)
    args = parser.parse_args()
    try:
        names = preflight()
        if args.verify_dir is not None:
            verify_dir(args.verify_dir, names)
        print(f"Compose screenshot contract valid: {len(names)} source-derived captures")
    except (ContractError, OSError) as error:
        print(f"Contract preflight failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
