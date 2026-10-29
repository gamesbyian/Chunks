#!/usr/bin/env python3
"""Offline Markdown link validator: python3 tools/check_docs.py [--strict-anchors]."""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
DOCUMENTS = ("README.md", "AGENTS.md", "docs", "assets/fonts/README.md")
LINK_RE = re.compile(r"!?\[[^\]\n]*\]\((<[^>\n]+>|[^)\n]+)\)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$")
ID_RE = re.compile(r'<a\s+(?:name|id)=["\x27]([^"\x27]+)["\x27]', re.I)


def documents(root: Path = ROOT) -> list[Path]:
    entries = []
    for item in DOCUMENTS:
        path = root / item
        if path.is_file():
            entries.append(path)
        elif path.is_dir():
            entries.extend(path.rglob("*.md"))
    return sorted(set(entries))


def without_fenced_code(text: str) -> str:
    result, fence = [], None
    for line in text.splitlines():
        match = re.match(r"^\s{0,3}(" + re.escape(chr(96)) + r"{3,}|~{3,})", line)
        if match:
            char = match.group(1)[0]
            if fence is None:
                fence = (char, len(match.group(1)))
            elif char == fence[0] and len(match.group(1)) >= fence[1]:
                fence = None
            result.append("")
        else:
            result.append("" if fence else line)
    return "\n".join(result)


def slug(text: str) -> str:
    text = re.sub(r"!\[([^\]]*)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"</?[^>]*>", "", text)
    text = text.lower().replace(chr(96), "")
    text = "".join(c for c in text if c.isalnum() or c in " -_")
    return re.sub(r"\s+", "-", text.strip())


def heading_ids(text: str) -> set[str]:
    known = set(ID_RE.findall(text))
    counter: Counter[str] = Counter()
    for line in without_fenced_code(text).splitlines():
        m = HEADING_RE.match(line.strip())
        if not m:
            continue
        base = slug(m.group(1))
        if not base:
            continue
        n = counter[base]
        known.add(base if n == 0 else f"{base}-{n}")
        counter[base] += 1
    return known


def destination(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("<"):
        return raw.split(">", 1)[0][1:]
    return re.split(r'\s+(?=["\x27])', raw, maxsplit=1)[0]


def verify(root: Path = ROOT, *, strict_anchors: bool = False) -> tuple[list[str], list[str], int]:
    failures, warnings, count = [], [], 0
    cache: dict[Path, set[str]] = {}
    for page in documents(root):
        src = page.read_text(encoding="utf-8")
        for line_no, line in enumerate(without_fenced_code(src).splitlines(), 1):
            line = re.sub(chr(96) + "[^" + chr(96) + "]*" + chr(96), "", line)
            for match in LINK_RE.finditer(line):
                raw = destination(match.group(1))
                if not raw or raw == "#":
                    continue
                parsed = urlsplit(raw)
                if parsed.scheme or parsed.netloc or raw.startswith("//"):
                    continue
                target_name = unquote(parsed.path)
                target = (page.parent / target_name).resolve() if target_name else page.resolve()
                try:
                    target.relative_to(root.resolve())
                except ValueError:
                    failures.append(f"{page.relative_to(root)}:{line_no}: link escapes repository: {raw}")
                    continue
                if target.is_dir():
                    candidates = [target / "README.md", target / "index.md"]
                    target = next((c for c in candidates if c.is_file()), target)
                if not target.is_file():
                    failures.append(f"{page.relative_to(root)}:{line_no}: missing target: {raw}")
                    continue
                count += 1
                fragment = unquote(parsed.fragment)
                if fragment and target.suffix.lower() == ".md":
                    if target not in cache:
                        cache[target] = heading_ids(target.read_text(encoding="utf-8"))
                    if fragment not in cache[target]:
                        problem = f"{page.relative_to(root)}:{line_no}: unknown heading #{fragment} in {target.relative_to(root)}"
                        (failures if strict_anchors else warnings).append(problem)
    return failures, warnings, count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict-anchors", action="store_true")
    args = parser.parse_args()
    failures, warnings, checked = verify(strict_anchors=args.strict_anchors)
    for item in failures:
        print("ERROR:", item)
    for item in warnings:
        print("WARN:", item)
    print(f"Documentation links: {checked} local targets; {len(failures)} errors; {len(warnings)} anchor warnings")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
