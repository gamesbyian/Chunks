#!/usr/bin/env python3
"""Cheap, intentional naming guard for the Local Device execution environment."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
paths = [
    p for source in ("app/src/main/java", "app/src/test/java", "app/src/androidTest/java")
    for p in (root / source).rglob("*.kt")
]
# Descriptive hardware/icon names may be used when they refer to physical
# form factors. Only forbid the old execution-path naming pattern by default.
legacy = re.compile(r"\b(?:Phone[A-Z]\w*|phone[A-Z]\w*|PHONE_[A-Z0-9_]+)\b|(?:EnvironmentId|const val \w+)\s*\(\s*\"phone\"\s*\)")
bad = []
for path in paths:
    text = path.read_text(encoding="utf8").replace("chunks_ic_phone", "")
    if "Phone" in path.name or legacy.search(text):
        bad.append(path.relative_to(root).as_posix())
if bad:
    print("Legacy Phone-named runtime paths remain:")
    print("\n".join(" - " + p for p in sorted(bad)))
    sys.exit(1)
print(f"Canonical Local Device naming passed across {len(paths)} Kotlin sources.")
