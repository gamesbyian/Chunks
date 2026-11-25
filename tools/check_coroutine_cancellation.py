#!/usr/bin/env python3
"""Review broad exception handling in Kotlin suspend bodies and explicit bridges.

Lexical, balanced-delimiter analysis, not Kotlin type or control-flow analysis.
String templates are treated as strings; aliases/indirect wrappers are not
resolved. Findings are review candidates, never proof of swallowed cancellation.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = "app/src/main"
BROAD = {"Exception", "Throwable", "RuntimeException", "IllegalStateException"}
BRIDGES = {"runBlocking", "withContext", "coroutineScope", "supervisorScope"}
DECL = {"fun", "val", "var", "class", "object", "interface", "override", "private",
        "public", "internal", "protected", "suspend", "abstract", "open", "init"}


@dataclass(frozen=True)
class Token:
    value: str
    line: int


def lex(source: str) -> list[Token]:
    """Ignore nested comments, chars and Kotlin quoted/raw strings (including templates)."""
    tokens = []
    n, i, line = len(source), 0, 1

    def comment_end(start):
        depth, j = 1, start + 2
        while j < n and depth:
            if source.startswith("/*", j):
                depth += 1
                j += 2
            elif source.startswith("*/", j):
                depth -= 1
                j += 2
            else:
                j += 1
        if depth:
            raise ValueError("unclosed Kotlin block comment")
        return j

    def quoted_end(start):
        quote = '"""' if source.startswith('"""', start) else source[start]
        j = start + len(quote)
        while j < n:
            if source.startswith(quote, j):
                return j + len(quote)
            if quote != '"""' and source[j] == "\\":
                j += 2
            elif quote != "'" and source.startswith("${", j):
                j = template_end(j + 2)
            else:
                j += 1
        raise ValueError("unclosed Kotlin string or char")

    def template_end(start):
        depth, j = 1, start
        while j < n and depth:
            if source.startswith("//", j):
                end = source.find("\n", j)
                j = n if end < 0 else end
            elif source.startswith("/*", j):
                j = comment_end(j)
            elif source[j] in "\"'":
                j = quoted_end(j)
            elif source[j] == "{":
                depth += 1
                j += 1
            elif source[j] == "}":
                depth -= 1
                j += 1
            else:
                j += 1
        if depth:
            raise ValueError("unclosed Kotlin string template")
        return j

    while i < n:
        start = i
        ch = source[i]
        if ch.isspace():
            i += 1
        elif source.startswith("//", i):
            end = source.find("\n", i)
            i = n if end < 0 else end
        elif source.startswith("/*", i):
            i = comment_end(i)
        elif ch in "\"'":
            i = quoted_end(i)
            tokens.append(Token("<literal>", line))
        elif ch == chr(96):
            end = source.find(chr(96), i + 1)
            if end < 0:
                raise ValueError("unclosed Kotlin quoted identifier")
            i = end + 1
            tokens.append(Token(source[start:i], line))
        else:
            match = re.match(r"[A-Za-z_$][A-Za-z_0-9$]*|[0-9]+|->|\?\.|::|[^\s]", source[i:])
            if not match:
                raise ValueError("unrecognized Kotlin token")
            value = match.group()
            i += len(value)
            tokens.append(Token(value, line))
        line += source.count("\n", start, i)
    return tokens


def delimiters(tokens):
    pairs, stack, depths = {}, [], []
    closing = {"}": "{", ")": "(", "]": "["}
    for i, token in enumerate(tokens):
        v = token.value
        depths.append(len(stack))
        if v in ("{", "(", "["):
            stack.append(i)
        elif v in closing:
            if not stack or tokens[stack[-1]].value != closing[v]:
                raise ValueError(f"unbalanced delimiter at line {token.line}")
            start = stack.pop()
            pairs[start] = i
            pairs[i] = start
    if stack:
        raise ValueError(f"unclosed delimiter at line {tokens[stack[-1]].line}")
    return pairs, depths


def regions(tokens, pairs, depths):
    """Identify explicit suspend declaration and coroutine bridge regions."""
    values = [t.value for t in tokens]
    ranges = []
    n = len(tokens)
    for i, value in enumerate(values):
        if value in BRIDGES and (i == 0 or values[i - 1] != "fun"):
            j = i + 1
            if j < n and values[j] == "(":
                j = pairs[j] + 1
            if j < n and values[j] == "{":
                ranges.append((j, pairs[j]))
        if value != "suspend" or i + 1 >= n or values[i + 1] != "fun":
            continue
        j, has_params = i + 2, False
        while j < n:
            v = values[j]
            if v == "(":
                has_params = True
                j = pairs[j] + 1
                continue
            if v == "[":
                j = pairs[j] + 1
                continue
            if v in ("}", ";") or (has_params and v in DECL and tokens[j].line > tokens[i].line):
                break
            if has_params and v == "{":
                ranges.append((j, pairs[j]))
                break
            if has_params and v == "=":
                # Expression body may contain try/catch or trailing lambdas.
                # Stop at the next declaration in the same or outer scope.
                k = j + 1
                while k < n:
                    if depths[k] < depths[i] or (depths[k] == depths[i] and
                       (values[k] == "}" or (values[k] in DECL and
                        tokens[k].line > tokens[j].line))):
                        break
                    k += 1
                ranges.append((j, k - 1))
                break
            j += 1
    return ranges


def rethrows(body, variable):
    # Recognize only the common direct rethrow or an explicit type-guarded throw.
    # Conditional ownership-specific catches need human review.
    return (body == ["throw", variable] or
            body[:8] == ["if", "(", variable, "is", "CancellationException", ")", "throw", variable] or
            body[:10] == ["if", "(", variable, "is", "CancellationException", ")", "{", "throw", variable, "}"])


def candidates(source, path):
    tokens = lex(source)
    v = [t.value for t in tokens]
    pairs, depths = delimiters(tokens)
    ranges = regions(tokens, pairs, depths)
    in_region = [False] * len(v)
    for start, end in ranges:
        in_region[start:end + 1] = [True] * (end - start + 1)
    found = []

    def emit(kind, at, first, last):
        identity = " ".join(v[first:last + 1])
        fingerprint = hashlib.sha256((path + "\0" + kind + "\0" + identity).encode()).hexdigest()[:24]
        found.append({"path": path, "line": tokens[at].line, "kind": kind,
                      "fingerprint": fingerprint, "excerpt": identity[:200]})

    for i, value in enumerate(v):
        if not in_region[i]:
            continue
        if value == "try" and i + 1 < len(v) and v[i + 1] == "{":
            j = pairs[i + 1] + 1
            handled = False
            while j + 2 < len(v) and v[j:j + 2] == ["catch", "("]:
                end_params = pairs[j + 1]
                body = end_params + 1
                if body >= len(v) or v[body] != "{":
                    break
                end_body = pairs[body]
                params = v[j + 2:end_params]
                if ":" in params:
                    colon = params.index(":")
                    var = params[colon - 1]
                    kind = params[-1]
                    direct = rethrows(v[body + 1:end_body], var)
                    if kind == "CancellationException" and direct:
                        handled = True
                    if kind in BROAD and not handled and not direct:
                        emit("broad-catch", j, i, end_body)
                j = end_body + 1
        if value == "runCatching" and i + 1 < len(v):
            j = i + 1
            if v[j] == "(":
                j = pairs[j] + 1
            if j < len(v) and v[j] == "{":
                j = pairs[j] + 1
            if j == i + 1:
                continue
            safe = False
            # Recognize a directly chained rethrow handler before loss of Result.
            k = j
            if k + 2 < len(v) and v[k:k + 2] == [".", "onFailure"] and v[k + 2] == "{":
                end = pairs[k + 2]
                body = v[k + 3:end]
                var = "it"
                if len(body) > 2 and body[1] == "->":
                    var, body = body[0], body[2:]
                safe = rethrows(body, var)
                j = end + 1
            if not safe:
                emit("runCatching", i, i, j - 1)
    return found


def scan(root: Path):
    out, errors = [], []
    for path in sorted((root / MAIN).rglob("*.kt")):
        try:
            out.extend(candidates(path.read_text(encoding="utf-8"), path.relative_to(root).as_posix()))
        except ValueError as exc:
            errors.append({"path": path.relative_to(root).as_posix(), "error": str(exc)})
    return out, errors


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, default=ROOT)
    args = p.parse_args()
    findings, errors = scan(args.root)
    for row in findings:
        print("CHUNKS_CANCELLATION_REVIEW " + json.dumps(row, ensure_ascii=True))
    for row in errors:
        print("CHUNKS_CANCELLATION_SCAN_ERROR " + json.dumps(row, ensure_ascii=True))
    print(f"Cancellation review: {len(findings)} candidates, {len(errors)} lexer errors; review only, no semantic proof.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
