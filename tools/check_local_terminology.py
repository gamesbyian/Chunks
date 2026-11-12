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
# These are shared execution identities, not presentation copy. Check the exact
# IDs alongside the lexical guard so a future refactor cannot drift one system.
expected = {
    "app/src/main/java/io/github/gamesbyian/chunks/model/EnvironmentSnapshots.kt":
        'EnvironmentId("local")',
    "app/src/main/java/io/github/gamesbyian/chunks/TransferListSortStore.kt":
        'const val LOCAL = "local"',
    "app/src/main/java/io/github/gamesbyian/chunks/seeding/LocalSeeding.kt":
        'const val ENVIRONMENT = "local"',
    "app/src/main/java/io/github/gamesbyian/chunks/SeedingRulesScreen.kt":
        'const val LOCAL_ID = "local"',
}
for rel, snippet in expected.items():
    file = root / rel
    if not file.exists() or snippet not in file.read_text(encoding="utf8"):
        bad.append(rel + ": missing canonical local execution ID")

if bad:
    print("Legacy Phone-named runtime paths remain:")
    print("\n".join(" - " + p for p in sorted(bad)))
    sys.exit(1)
print(f"Canonical Local Device naming passed across {len(paths)} Kotlin sources.")
