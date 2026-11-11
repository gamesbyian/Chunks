#!/usr/bin/env python3
"""Read-only, bounded GitHub issue/PR *metadata* inventory for Android research.

No issue/PR bodies, comments, review text, credential contents or Actions logs
are written. A result is a non-atomic metadata survey, NOT a reviewed failure audit.
"""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

REPOS = ("gamesbyian/Chunks", "gamesbyian/Glimpse", "gamesbyian/Expository")
API = "https://api.github.com"
MAX_BYTES = 32 * 1024 * 1024
MAX_PAGES = 100


class RejectRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("GitHub API redirect refused (do not forward authentication)")


def check_url(url, collection_path=None, expected_page=None):
    p = urlsplit(url)
    if (p.scheme != "https" or p.netloc != "api.github.com" or p.username
            or p.password or p.fragment or not re.fullmatch(r"/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:pulls|issues|branches/main)", p.path)):
        raise ValueError("Unexpected GitHub API URL")
    if collection_path is not None:
        if p.path != collection_path:
            raise ValueError("Cross-collection pagination URL refused")
        q = parse_qs(p.query, strict_parsing=True)
        if set(q) != {"state", "per_page", "page"} or q["state"] != ["all"] or q["per_page"] != ["100"] or q["page"] != [str(expected_page)]:
            raise ValueError("Unexpected pagination parameters or page")
    elif p.path.endswith("/branches/main"):
        if p.query:
            raise ValueError("Unexpected branch query")
    else:
        q = parse_qs(p.query, strict_parsing=True)
        if set(q) != {"state", "per_page", "page"} or q["state"] != ["all"] or q["per_page"] != ["100"]:
            raise ValueError("Unexpected GitHub collection query")
        if len(q["page"]) != 1 or not q["page"][0].isdigit() or int(q["page"][0]) < 1:
            raise ValueError("Invalid GitHub page")
    return p


def next_url(link_header):
    for part in link_header.split(","):
        m = re.fullmatch(r'\s*<([^>]+)>;\s*rel="([^"]+)"\s*', part)
        if m and m.group(2) == "next":
            return m.group(1)
    return None


def github_get(url, token=None, opener=None):
    check_url(url)
    headers = {"Accept": "application/vnd.github+json",
               "User-Agent": "android-skill-history-snapshot/0.3",
               "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, headers=headers)
    transport = opener or build_opener(RejectRedirect()).open
    with transport(request, timeout=30) as response:
        data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("GitHub response too large")
        return json.loads(data), next_url(response.headers.get("Link", ""))


def collection(url, getter):
    initial = check_url(url, urlsplit(url).path, 1)
    seen, rows, page = set(), [], 1
    while url:
        check_url(url, initial.path, page)
        data, nxt = getter(url)
        if not isinstance(data, list):
            raise ValueError("Expected GitHub collection list")
        if nxt and not data:
            raise ValueError("Unexpected empty intermediate page")
        for item in data:
            number = item["number"]
            if not isinstance(number, int) or number <= 0 or number in seen:
                raise ValueError("Missing, invalid or duplicate item number")
            seen.add(number)
            rows.append(item)
        if nxt:
            check_url(nxt, initial.path, page + 1)
        url = nxt
        page += 1
        if url and page > MAX_PAGES:
            raise ValueError("Pagination limit reached")
    return rows, page - 1


def branch_sha(repo, getter):
    data, _ = getter(f"{API}/repos/{repo}/branches/main")
    sha = data["commit"]["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid branch SHA")
    return sha


def record(row, is_pr):
    fields = {"number": row["number"], "title": row.get("title", ""),
              "url": row.get("html_url"), "state": row.get("state"),
              "created_at": row.get("created_at"), "updated_at": row.get("updated_at"),
              "closed_at": row.get("closed_at"),
              "labels": sorted(x.get("name", "") for x in row.get("labels", []))}
    if is_pr:
        fields.update(merged_at=row.get("merged_at"), draft=row.get("draft"),
                      base_sha=(row.get("base") or {}).get("sha"),
                      head_sha=(row.get("head") or {}).get("sha"))
    return fields


def snapshot(repos, getter):
    result = {"schema": "android-skill-github-metadata-v2",
              "scope": "PR and distinct issue metadata only, no diffs/reviews/CI",
              "repositories": {},
              "limitations": ["GitHub pagination is not atomic",
                              "Main SHA stability does not prove lists remained stable during pagination",
                              "A listed PR is not a reviewed diff or CI outcome"]}
    for repo in repos:
        if repo not in REPOS:
            raise ValueError("Repository is not in the fixed research allowlist")
        before = branch_sha(repo, getter)
        prs, pr_pages = collection(f"{API}/repos/{repo}/pulls?state=all&per_page=100&page=1", getter)
        mixed, issue_pages = collection(f"{API}/repos/{repo}/issues?state=all&per_page=100&page=1", getter)
        issues = [item for item in mixed if "pull_request" not in item]
        after = branch_sha(repo, getter)
        result["repositories"][repo] = {
            "main_before": before, "main_after": after, "main_moved_during_scan": before != after,
            "pr_pages": pr_pages, "issue_pages": issue_pages,
            "pr_count": len(prs), "issue_count": len(issues),
            "pull_requests": [record(item, True) for item in sorted(prs, key=lambda x: x["number"])],
            "issues": [record(item, False) for item in sorted(issues, key=lambda x: x["number"])],
        }
    return result


def atomic_write(path, result, overwrite=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or (path.exists() and not overwrite):
        raise FileExistsError("Refusing to overwrite existing snapshot; use --overwrite explicitly")
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".history-", delete=False) as f:
            temp = Path(f.name)
            json.dump(result, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        if overwrite:
            os.replace(temp, path)
        else:
            os.link(temp, path)  # atomic exclusive final name, same directory
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="Local JSON output filename")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace prior snapshot")
    args = parser.parse_args()
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    result = snapshot(REPOS, lambda url: github_get(url, token))
    atomic_write(args.output, result, overwrite=args.overwrite)
    for name, data in result["repositories"].items():
        print(f"{name}: {data['pr_count']} PRs; {data['issue_count']} issues; "
              f"moving main={data['main_moved_during_scan']}")
    print("Metadata only; full diffs, review threads and Actions evidence remain unaudited")


if __name__ == "__main__":
    main()
