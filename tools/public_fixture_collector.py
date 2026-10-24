#!/usr/bin/env python3
"""Publicly licensed BitTorrent metadata fixtures. No payload downloads or RPC writes.

Archive: obtain genuine .torrent via Archive item metadata and derive a magnet from
the raw bencoded info dictionary. Wikimedia Commons: catalogue official source
media as content-test references, NOT imaginary torrents. Standard library only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

MAX_META = 512 * 1024
MAX_TORRENT = 2 * 1024 * 1024
USER_AGENT = "Chunks-fixture-collector/1.0 (public metadata only)"
ARCHIVE_ID = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}$")
COMMONS_NAME = re.compile(r"^[^\x00-\x1f/\\]{1,180}$")

def approved_url(value):
    p = urllib.parse.urlsplit(value)
    host = (p.hostname or "").lower()
    if p.scheme != "https" or p.username or p.password or p.fragment or p.port not in (None, 443):
        raise ValueError("Disallowed URL")
    if host == "archive.org" or host.endswith(".archive.org"):
        return value
    if host in ("commons.wikimedia.org", "upload.wikimedia.org"):
        return value
    raise ValueError("Disallowed source host")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, url):
        # Check every redirect and do not forward credentials (none are sent).
        approved_url(url)
        return super().redirect_request(req, fp, code, msg, headers, url)

def fetch(url, cap):
    approved_url(url)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
        final_url = response.geturl()
        approved_url(final_url)
        data = response.read(cap + 1)
    if len(data) > cap:
        raise ValueError("Source response exceeded byte limit")
    return data

def license_ok(meta):
    if isinstance(meta, list):
        meta = meta[0] if meta else ""
    val = str(meta or "").lower()
    return ("creativecommons.org/licenses/" in val or
            "creativecommons.org/publicdomain/" in val or
            val.strip() in ("public domain", "cc0", "cc-by", "cc-by-sa"))

def bstr(raw):
    return str(len(raw)).encode("ascii") + b":" + raw

def parse_bencode(raw):
    """Return decoded root dictionary and exact raw info value bytes."""
    if len(raw) > MAX_TORRENT:
        raise ValueError("Torrent metadata too large")
    count = [0]
    info_span = [None]
    def parse(pos, depth=0, top=False):
        count[0] += 1
        if count[0] > 100000 or depth > 24 or pos >= len(raw):
            raise ValueError("Invalid/deep bencode")
        tag = raw[pos:pos + 1]
        if tag == b"i":
            end = raw.find(b"e", pos + 1)
            if end < 0:
                raise ValueError("Unterminated integer")
            text = raw[pos + 1:end]
            if not re.fullmatch(rb"(0|-?[1-9][0-9]*)", text) or text == b"-0":
                raise ValueError("Noncanonical integer")
            return int(text), end + 1
        if tag in (b"d", b"l"):
            cursor = pos + 1
            if tag == b"l":
                values = []
                while cursor < len(raw) and raw[cursor:cursor+1] != b"e":
                    value, cursor = parse(cursor, depth + 1)
                    values.append(value)
                if cursor >= len(raw):
                    raise ValueError("Unterminated list")
                return values, cursor + 1
            values = {}
            while cursor < len(raw) and raw[cursor:cursor+1] != b"e":
                key, cursor = parse(cursor, depth + 1)
                if not isinstance(key, bytes) or key in values:
                    raise ValueError("Bad or repeated bencode dictionary key")
                start = cursor
                value, cursor = parse(cursor, depth + 1)
                if top and key == b"info":
                    info_span[0] = raw[start:cursor]
                values[key] = value
            if cursor >= len(raw):
                raise ValueError("Unterminated dictionary")
            return values, cursor + 1
        if tag in b"0123456789":
            colon = raw.find(b":", pos, min(len(raw), pos + 20))
            if colon < 0:
                raise ValueError("Missing byte-string length")
            n = raw[pos:colon]
            if not re.fullmatch(rb"(0|[1-9][0-9]*)", n):
                raise ValueError("Invalid string length")
            end = colon + 1 + int(n)
            if end > len(raw):
                raise ValueError("Short byte string")
            return raw[colon + 1:end], end
        raise ValueError("Unknown bencode tag")
    value, end = parse(0, top=True)
    if end != len(raw) or not isinstance(value, dict) or not isinstance(value.get(b"info"), dict) or not info_span[0]:
        raise ValueError("Missing torrent info dictionary or trailing bytes")
    return value, info_span[0]

def clean_name(name):
    if not isinstance(name, bytes):
        raise ValueError("Invalid torrent name")
    text = name.decode("utf-8", errors="strict")
    if text in ("", ".", "..") or "/" in text or "\\" in text or any(ord(x) < 32 for x in text):
        raise ValueError("Unsafe torrent name/path")
    return text

def torrent_summary(data, max_total_bytes):
    root, raw_info = parse_bencode(data)
    info = root[b"info"]
    name = clean_name(info.get(b"name.utf-8", info.get(b"name")))
    paths = []
    if b"files" in info:
        for item in info[b"files"]:
            if not isinstance(item, dict) or not isinstance(item.get(b"length"), int):
                raise ValueError("Invalid torrent file")
            path = item.get(b"path.utf-8", item.get(b"path"))
            if not isinstance(path, list) or not path:
                raise ValueError("Invalid multi-file path")
            paths.append(("/".join(clean_name(p) for p in path), item[b"length"]))
    else:
        paths.append((name, info.get(b"length")))
    if any(not isinstance(n, int) or n < 0 for _, n in paths):
        raise ValueError("Invalid torrent length")
    total = sum(size for _, size in paths)
    if total > max_total_bytes:
        raise ValueError("Torrent payload exceeds test budget")
    v2 = info.get(b"meta version") == 2
    v1 = isinstance(info.get(b"pieces"), bytes) and len(info[b"pieces"]) % 20 == 0 and len(info[b"pieces"]) > 0
    if not (v1 or v2):
        raise ValueError("Unsupported v1/v2 torrent structure")
    fields = []
    if v1:
        fields.append(("xt", "urn:btih:" + hashlib.sha1(raw_info).hexdigest()))
    if v2:
        fields.append(("xt", "urn:btmh:1220" + hashlib.sha256(raw_info).hexdigest()))
    fields.append(("dn", name))
    magnet = "magnet:?" + urllib.parse.urlencode(fields)
    # The only retained tracker is an official public Archive tracker.
    tracker = root.get(b"announce")
    if isinstance(tracker, bytes):
        candidate = tracker.decode("utf-8", errors="strict")
        p = urllib.parse.urlsplit(candidate)
        if p.hostname not in ("bt1.archive.org", "bt2.archive.org") or p.username or p.password or p.query or p.fragment or p.scheme not in ("http", "https", "udp"):
            tracker = None
    else:
        tracker = None
    # Strip comments, external announce lists, webseed tokens and extensions.
    # Preserve *exact raw info bytes* to keep the original info hash intact.
    safe = b"d"
    if tracker:
        safe += bstr(b"announce") + bstr(tracker)
    safe += bstr(b"info") + raw_info + b"e"
    return safe, {"name": name, "infohash_v1": hashlib.sha1(raw_info).hexdigest() if v1 else None,
                  "infohash_v2": hashlib.sha256(raw_info).hexdigest() if v2 else None,
                  "magnet": magnet, "total_bytes": total,
                  "files": [{"path": path, "bytes": n} for path, n in paths[:1000]],
                  "file_count": len(paths), "truncated_file_list": len(paths) > 1000,
                  "tracker_retained": tracker is not None}

def archive_fixture(identifier, max_total_bytes):
    if not ARCHIVE_ID.fullmatch(identifier) or identifier in (".", ".."):
        raise ValueError("Invalid Internet Archive identifier")
    meta = json.loads(fetch("https://archive.org/metadata/" + identifier, MAX_META))
    metadata = meta.get("metadata", {})
    if not isinstance(metadata, dict) or meta.get("is_dark") or meta.get("is_restricted"):
        raise ValueError("Item unavailable or restricted")
    license_name = metadata.get("licenseurl") or metadata.get("rights")
    if not license_ok(license_name):
        raise ValueError("Item license not clearly public/reusable; manual review required")
    files = meta.get("files", [])
    candidates = [f["name"] for f in files if isinstance(f, dict) and
                  isinstance(f.get("name"), str) and f["name"].endswith(".torrent") and
                  (f.get("source") == "derivative" or f.get("format") == "Archive BitTorrent")]
    if not candidates:
        raise ValueError("Item has no published Archive BitTorrent file")
    name = sorted(candidates)[0]
    if any(x in name for x in ("..", "/", "\\", "?", "#")):
        raise ValueError("Unsafe Archive torrent filename")
    url = "https://archive.org/download/" + identifier + "/" + urllib.parse.quote(name)
    raw = fetch(url, MAX_TORRENT)
    safe, details = torrent_summary(raw, max_total_bytes)
    out = {"schema": "chunks.public-fixture.v1", "source": "internet-archive",
           "source_url": "https://archive.org/details/" + identifier, "torrent_url": url,
           "license": license_name, "torrent": details,
           "safe_torrent_sha256": hashlib.sha256(safe).hexdigest(),
           "nonsecret_fixture": True, "download_payload": False}
    return out, safe

def commons_fixture(filename):
    if not COMMONS_NAME.fullmatch(filename) or filename in (".", ".."):
        raise ValueError("Invalid Wikimedia Commons filename")
    title = filename if filename.startswith("File:") else "File:" + filename
    query = urllib.parse.urlencode({"action": "query", "format": "json", "prop": "imageinfo",
                                     "titles": title, "iiprop": "url|size|mime|extmetadata"})
    result = json.loads(fetch("https://commons.wikimedia.org/w/api.php?" + query, MAX_META))
    pages = result.get("query", {}).get("pages", {})
    info = next((v["imageinfo"][0] for v in pages.values()
                 if isinstance(v, dict) and v.get("imageinfo")), None)
    if not info:
        raise ValueError("Commons source file unavailable")
    extra = info.get("extmetadata", {})
    license_name = str(extra.get("LicenseUrl", {}).get("value") or extra.get("LicenseShortName", {}).get("value") or "")
    if not license_ok(license_name):
        raise ValueError("Commons license not clearly reusable; manual review required")
    url = approved_url(info["url"])
    if urllib.parse.urlsplit(url).hostname != "upload.wikimedia.org":
        raise ValueError("Invalid Commons media source")
    size = int(info["size"])
    if size < 0:
        raise ValueError("Invalid Commons file size")
    return {"schema": "chunks.public-fixture.v1", "source": "wikimedia-commons",
            "source_url": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
            "license": license_name, "asset": {"url": url, "bytes": size, "mime": info.get("mime")},
            "torrent": None, "magnet": None, "torrent_status": "no_official_torrent_claimed",
            "nonsecret_fixture": True, "download_payload": False}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--archive-id", help="Internet Archive item with a published .torrent")
    group.add_argument("--commons-file", help="Wikimedia Commons File: title")
    p.add_argument("--output-dir", default="public-fixture-output")
    p.add_argument("--max-total-mib", type=int, default=512)
    args = p.parse_args()
    if args.max_total_mib < 1 or args.max_total_mib > 8192:
        p.error("max-total-mib must be 1..8192")
    output = Path(args.output_dir)
    if output.exists() and any(output.iterdir()):
        p.error("Use a new empty output directory; never overwrite fixtures")
    if args.archive_id:
        manifest, torrent = archive_fixture(args.archive_id, args.max_total_mib * 1048576)
    else:
        manifest = commons_fixture(args.commons_file)
        torrent = None
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if torrent:
        (output / "source-sanitized.torrent").write_bytes(torrent)
        (output / "source.magnet").write_text(manifest["torrent"]["magnet"] + "\n", encoding="utf-8")
    print(json.dumps({"status": "ok", "source": manifest["source"],
                       "has_torrent": torrent is not None, "artifact_directory": str(output)}))

if __name__ == "__main__":
    main()
