#!/usr/bin/env python3
"""One-off fresh-install Local Device terminology refactor for Chunks.

Usage: python3 tools/refactor_local_device.py --apply
       python3 tools/refactor_local_device.py --check

This intentionally changes fresh-install-only runtime IDs. It NEVER changes
the Chunks Downloads folder, RPC profile identities, torrent hashes, or payloads.
Dated history, historical evidence, and screenshots are retained unchanged.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CODE_ROOTS = (
    ROOT / "app/src/main/java",
    ROOT / "app/src/test/java",
    ROOT / "app/src/androidTest/java",
)
ACTIVE_DOCS = (
    "AGENTS.md",
    "README.md",
    "docs/PRODUCT.md",
    "docs/UX.md",
    "docs/ARCHITECTURE.md",
    "docs/UNIFIED-ENVIRONMENTS.md",
    "docs/WORK-QUEUE.md",
    "docs/COMPLETION-ROADMAP.md",
    "docs/SPEC-COVERAGE.md",
    "docs/OPTIONS-INFORMATION-ARCHITECTURE.md",
    "docs/FEATURE-EXPERIENCE-CONTRACT.md",
)

# Translate user-facing expressions first. Avoid turning "this phone" into
# the ungrammatical "this local".
PHRASES = (
    (r"\bthis phone's\b", "this device's"),
    (r"\bthis phone\b", "this device"),
    (r"\byour phone's\b", "your device's"),
    (r"\byour phone\b", "your device"),
    (r"\bthe phone's\b", "the local device's"),
    (r"\bthe phone\b", "the local device"),
    (r"\bon the phone\b", "on this device"),
    (r"\bon phone\b", "on this device"),
    (r"\bto the phone\b", "to the local device"),
    (r"\bto phone\b", "to the local device"),
    (r"\bfrom the phone\b", "from the local device"),
    (r"\bfrom phone\b", "from the local device"),
    (r"\bPhone storage\b", "Local device storage"),
    (r"\bphone storage\b", "local device storage"),
    (r"\bPhone rules\b", "Local device rules"),
    (r"\bphone rules\b", "local device rules"),
    (r"\bPhone-only\b", "Local-only"),
    (r"\bphone-only\b", "local-only"),
    (r"\bphone-to-server\b", "local-to-server"),
    (r"\bserver-to-phone\b", "server-to-local"),
    (r"\bPhone-to-server\b", "Local-to-server"),
    (r"\bserver-to-Phone\b", "server-to-Local"),
)
# A phone icon is an actual device pictogram, not a runtime identity.
ICON_SENTINEL = "__CHUNKS_PHYSICAL_PHONE_ICON__"


def replace_terms(text: str, docs: bool = False) -> str:
    text = text.replace("chunks_ic_phone", ICON_SENTINEL)
    # These are form-factor references, not a description of the engine.
    hardware = []
    if docs:
        def protect(m: re.Match[str]) -> str:
            hardware.append(m.group(0))
            return f"__HARDWARE_PHRASE_{len(hardware)-1}__"
        text = re.sub(
            r"\b(?:physical phone|Android phone|Pixel phone|phone display|phone screen size|phone handset)\b",
            protect, text, flags=re.IGNORECASE,
        )
    for pattern, replacement in PHRASES:
        text = re.sub(pattern, replacement, text)
    # All source identifiers: PhoneAdd -> LocalAdd, fromPhone -> fromLocal;
    # PHONE_ID -> LOCAL_ID, phoneAdd -> localAdd.
    text = text.replace("PHONE", "LOCAL").replace("Phone", "Local")
    text = text.replace("phone", "local" if not docs else "local device")
    text = text.replace(ICON_SENTINEL, "chunks_ic_phone")
    for i, phrase in enumerate(hardware):
        text = text.replace(f"__HARDWARE_PHRASE_{i}__", phrase)
    return text


def migrate_code() -> list[tuple[Path, Path]]:
    moves: list[tuple[Path, Path]] = []
    for folder in CODE_ROOTS:
        for path in sorted(folder.rglob("*.kt")):
            new_name = path.name.replace("Phone", "Local").replace("PHONE", "LOCAL")
            new_path = path.with_name(new_name)
            if new_path != path and new_path.exists():
                raise RuntimeError(f"Destination already exists: {new_path}")
            text = path.read_text(encoding="utf-8")
            updated = replace_terms(text)
            if new_path != path:
                new_path.write_text(updated, encoding="utf-8")
                path.unlink()
                moves.append((path, new_path))
            elif updated != text:
                path.write_text(updated, encoding="utf-8")
    return moves


def migrate_docs() -> None:
    banner = (
        "\n**Binding terminology:** The built-in torrent environment is **Local Device** "
        "(UI), **Local** (Kotlin) and the exact environment ID \`local\`. "
        "Remote environments are independently named profiles. "
        "Phone/handset/tablet refers only to hardware, not a transfer or command "
        "scope. Fresh development APKs are installed after uninstalling the "
        "previous build; legacy app preferences are not migrated.\n"
    )
    primary = {"docs/UNIFIED-ENVIRONMENTS.md", "docs/PRODUCT.md",
               "docs/ARCHITECTURE.md", "docs/UX.md"}
    for rel in ACTIVE_DOCS:
        p = ROOT / rel
        if not p.is_file():
            continue
        old = p.read_text(encoding="utf-8")
        new = replace_terms(old, docs=True)
        if rel in primary and "**Binding terminology:**" not in new:
            eol = new.find("\n")
            new = new[:eol+1] + banner + new[eol+1:]
        if new != old:
            p.write_text(new, encoding="utf-8")


def check() -> list[str]:
    violations = []
    for folder in CODE_ROOTS:
        for p in folder.rglob("*.kt"):
            # The remaining physical phone icon resource is deliberately exempt.
            s = p.read_text(encoding="utf-8").replace("chunks_ic_phone", "")
            if re.search(r"Phone|PHONE|\bphone\b|phone[A-Z]", s):
                violations.append(p.relative_to(ROOT).as_posix())
            if "Phone" in p.name or "PHONE" in p.name:
                violations.append(p.relative_to(ROOT).as_posix() + " filename")
    return sorted(set(violations))


def main() -> int:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--apply", action="store_true")
    group.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.apply:
        moves = migrate_code()
        migrate_docs()
        print(f"Renamed {len(moves)} Kotlin files.")
    bad = check()
    if bad:
        print(f"{len(bad)} source files still use the old runtime term:")
        for p in bad[:80]:
            print(" -", p)
        return 1
    print("Local Device source terminology check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
