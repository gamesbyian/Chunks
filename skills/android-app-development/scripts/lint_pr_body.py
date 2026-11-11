#!/usr/bin/env python3
"""Warn on negated GitHub issue-closing keywords in PR description files.

GitHub can treat "Do not close #123" as a closing directive. This is a
limited warning heuristic, not a substitute for checking the actual issue.
"""
import argparse
from pathlib import Path
import re
import sys

NEGATED_CLOSING = re.compile(
    r"(?i)\b(?:do\s+not|does\s+not|don't|doesn't|never|"
    r"won't|will\s+not|should\s+not|must\s+not)\s+"
    r"(?:automatically\s+)?"
    r"(?:close|closes|fix|fixes|resolve|resolves)\s+"
    r"(?:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)?#(?P<issue>\d+)\b"
)

def findings(body):
    if not isinstance(body, str):
        raise TypeError("PR description must be text")
    return [(int(m.group("issue")), body.count("\n", 0, m.start()) + 1)
            for m in NEGATED_CLOSING.finditer(body)]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("body", type=Path, help="Path to local PR description Markdown")
    args = parser.parse_args()
    issues = findings(args.body.read_text(encoding="utf-8"))
    for issue, line in issues:
        print(f"WARNING line {line}: negated closing keyword may close issue #{issue}; "
              f"write 'Issue #{issue} remains open' for unfinished work")
    if not issues:
        print("PASS: no known negated issue-closing pattern")
    return 1 if issues else 0

if __name__ == "__main__":
    sys.exit(main())
