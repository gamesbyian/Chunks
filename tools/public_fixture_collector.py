#!/usr/bin/env python3
"""Publicly licensed BitTorrent metadata fixtures. No payload downloads or RPC writes.

Archive: obtain genuine .torrent via Archive item metadata and derive a magnet from
the raw bencoded info dictionary. Debian: the official current amd64 netinst
.torrent from cdimage.debian.org, checked against the published SHA256SUMS, for
a swarm with ordinary seeders (owner-approved 2026-10-28). Wikimedia Commons: catalogue official source
media as content-test references, NOT imaginary torrents. Standard library only.
"""
import argparse
import hashlib
import html
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
ARCHIVE_TRACKERS = ("bt1.archive.org", "bt2.archive.org")
DEBIAN_TRACKERS = ("bttracker.debian.org",)
DEBIAN_BT_CD = "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/"
DEBIAN_NETINST = re.compile(r"debian-([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})-amd64-netinst\.iso\.torrent")

def approved_url(value):
    p = urllib.parse.urlsplit(value)
    host = (p.hostname or "").lower()
    if p.scheme != "https" or p.username or p.password or p.fragment or p.port not in (None, 443):
        raise ValueError("Disallowed URL")
    if host == "archive.org" or host.endswith(".archive.org"):
        return value
    if host in ("commons.wikimedia.org", "upload.wikimedia.org", "cdimage.debian.org"):
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

def attribution_text(value):
    """Keep bounded plain-text credits, never embedded HTML/JS."""
    raw = "; ".join(str(v) for v in value) if isinstance(value, list) else str(value or "")
    return " ".join(html.unescape(re.sub(r"<[^>]*>", " ", raw)).split())[:512]

def license_ok(meta):
    """Recognize an explicit license label or an actual CC license origin.

    Archive/Commons metadata is source-supplied, not independently legally
    adjudicated. A mere substring (including a lookalike or third-party
    redirect URL) is not evidence of a reusable Creative Commons license.
    """
    if isinstance(meta, list):
        meta = meta[0] if meta else ""
    value = str(meta or "").strip()
    if value.lower() in ("public domain", "cc0", "cc-by", "cc-by-sa"):
        return True
    if (len(value) > 512 or not value
            or any(char.isspace() or ord(char) < 32 or ord(char) == 127
                   for char in value)):
        return False
    try:
        url = urllib.parse.urlsplit(value)
        if (url.scheme not in ("http", "https")
                or url.hostname not in ("creativecommons.org", "www.creativecommons.org")
                or url.username is not None or url.password is not None
                or url.query or url.fragment
                or url.port not in (None, 443 if url.scheme == "https" else 80)):
            return False
    except ValueError:
        return False
    segments = url.path.strip("/").split("/")
    if len(segments) < 3:
        return False
    if segments[:2] in (["publicdomain", "zero"], ["publicdomain", "mark"]):
        return (segments[2] == "1.0" and len(segments) <= 4
                and (len(segments) == 3 or bool(re.fullmatch(
                    r"deed\\.[a-z]{2,8}", segments[3]))))
    if (segments[0] != "licenses" or segments[1] not in
            ("by", "by-sa", "by-nc", "by-nd", "by-nc-sa", "by-nc-nd")
            or not re.fullmatch(r"[1-4]\\.[0-9]+", segments[2])
            or len(segments) > 5):
        return False
    # Older CC deeds may append a jurisdiction (e.g. /us/) or a translated
    # deed; do not treat an arbitrary suffix as a recognized CC license.
    return all(bool(re.fullmatch(r"(?:[a-z]{2,8}|deed\\.[a-z]{2,8})", s))
               for s in segments[3:])

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
            previous_key = None
            while cursor < len(raw) and raw[cursor:cursor+1] != b"e":
                key, cursor = parse(cursor, depth + 1)
                # BEP 3 requires lexicographic ordering of the original
                # *bytes*. Unsorted keys yield a plausible raw infohash but
                # are not canonical metainfo across reference clients.
                if (not isinstance(key, bytes)
                        or (previous_key is not None and key <= previous_key)):
                    raise ValueError("Noncanonical or repeated bencode dictionary key")
                previous_key = key
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
    if (not 1 <= len(name) <= 1024 or text in ("", ".", "..")
            or "/" in text or "\\" in text
            or any(ord(x) < 32 or ord(x) == 127 for x in text)):
        raise ValueError("Unsafe torrent name/path")
    return text

def torrent_summary(data, max_total_bytes, tracker_hosts=ARCHIVE_TRACKERS):
    root, raw_info = parse_bencode(data)
    info = root[b"info"]
    # BEP 52 places Merkle piece layers outside the identity-hashed info
    # dictionary. This v1-only sanitizer deliberately strips top-level
    # extensions; exporting a v2/hybrid torrent without its authenticated
    # piece layers can generate broken metadata despite unchanged infohash.
    # Refuse until a real BEP 52 writer/validator is integrated.
    if b"meta version" in info:
        raise ValueError("BEP 52 v2/hybrid torrent fixture requires verified piece layers")
    # BEP 27 private=1 forbids DHT/PEX peer discovery. This collector
    # produces *public-swarm* test fixtures, so it must not turn a tracker-
    # restricted source into an apparently public magnet by dropping its
    # original announce information. Leave private torrent handling to the
    # user's actual client, not this source-fixture generator.
    private = info.get(b"private", 0)
    if type(private) is not int or private not in (0, 1):
        raise ValueError("Invalid BEP 27 private torrent flag")
    if private == 1:
        raise ValueError("BEP 27 private torrent is unsuitable for public-swarm fixture")
    name = clean_name(info.get(b"name.utf-8", info.get(b"name")))
    # BEP 3 permits exactly one backing layout. Keep the collector's
    # source-verification semantics aligned with the Android importer: a
    # duplicate file, or a file that is another file's parent, cannot
    # resolve to an unambiguous destination on a real filesystem.
    if (b"files" in info) == (b"length" in info):
        raise ValueError("Torrent must declare exactly one file layout")
    paths = []
    if b"files" in info:
        files = info[b"files"]
        if not isinstance(files, list) or not 1 <= len(files) <= 20000:
            raise ValueError("Invalid torrent file list")
        seen_files, seen_directories = set(), set()
        for item in files:
            if not isinstance(item, dict) or type(item.get(b"length")) is not int:
                raise ValueError("Invalid torrent file")
            path = item.get(b"path.utf-8", item.get(b"path"))
            if not isinstance(path, list) or not 1 <= len(path) <= 64:
                raise ValueError("Invalid multi-file path")
            parts = tuple(clean_name(part) for part in path)
            if parts in seen_files or parts in seen_directories:
                raise ValueError("Duplicate or conflicting torrent file path")
            for index in range(1, len(parts)):
                parent = parts[:index]
                if parent in seen_files:
                    raise ValueError("Torrent file path conflicts with a directory")
                seen_directories.add(parent)
            seen_files.add(parts)
            paths.append(("/".join(parts), item[b"length"]))
    else:
        paths.append((name, info[b"length"]))
    if any(type(n) is not int or n < 0 for _, n in paths):
        raise ValueError("Invalid torrent length")
    total = sum(size for _, size in paths)
    if total > max_total_bytes:
        raise ValueError("Torrent payload exceeds test budget")
    # BEP 3: each 20-byte SHA-1 digest describes exactly one piece.
    # Merely checking the hash-string length is a multiple of 20 admits
    # impossible source torrents, despite an authentic infohash.
    piece_length = info.get(b"piece length")
    piece_hashes = info.get(b"pieces")
    if (type(piece_length) is not int or not 1 <= piece_length <= 64 * 1024 * 1024
            or not isinstance(piece_hashes, bytes) or not piece_hashes
            or len(piece_hashes) % 20):
        raise ValueError("Unsupported BitTorrent v1 piece metadata")
    expected_piece_count = (total // piece_length) + (1 if total % piece_length else 0)
    if len(piece_hashes) // 20 != expected_piece_count:
        raise ValueError("BitTorrent v1 piece hashes contradict advertised payload size")
    fields = [("xt", "urn:btih:" + hashlib.sha1(raw_info).hexdigest()),
              ("dn", name)]
    magnet = "magnet:?" + urllib.parse.urlencode(fields)
    # BEP 12: when announce-list exists, announce is ignored. Source
    # selection and sanitation must follow effective tiers, not a misleading
    # top-level fallback. Only retain one approved, public tracker URL;
    # redact every other tracker, tier, comment and web seed from the fixture.
    def approved_tracker(candidate):
        if not isinstance(candidate, bytes) or not 1 <= len(candidate) <= 2048:
            return False
        try:
            url = candidate.decode("utf-8", errors="strict")
            if any(ord(char) < 32 or ord(char) == 127 for char in url):
                return False
            parsed = urllib.parse.urlsplit(url)
            return (parsed.scheme in ("http", "https", "udp")
                    and parsed.hostname in tracker_hosts
                    and not parsed.username and not parsed.password
                    and not parsed.query and not parsed.fragment
                    and parsed.path == "/announce"
                    and parsed.port != 0)
        except (UnicodeError, ValueError):
            return False

    if b"announce-list" in root:
        # Do not trust `announce` if announce-list is empty, malformed, or
        # contains no approved trackers. Bounded traversal is for preview only.
        tiers = root[b"announce-list"]
        candidates = (url for tier in tiers[:16] if isinstance(tier, list)
                      for url in tier[:64]) if isinstance(tiers, list) else ()
    else:
        candidates = (root.get(b"announce"),)
    tracker = next((url for url in candidates if approved_tracker(url)), None)
    # Strip comments, external announce lists, webseed tokens and extensions.
    # Preserve *exact raw info bytes* to keep the original info hash intact.
    safe = b"d"
    if tracker:
        safe += bstr(b"announce") + bstr(tracker)
    safe += bstr(b"info") + raw_info + b"e"
    return safe, {"name": name, "infohash_v1": hashlib.sha1(raw_info).hexdigest(),
                  "infohash_v2": None,
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
           "license": license_name, "creator": attribution_text(metadata.get("creator")), "torrent": details,
           "safe_torrent_sha256": hashlib.sha256(safe).hexdigest(),
           "nonsecret_fixture": True, "download_payload": False}
    return out, safe

def debian_fixture(max_total_bytes, architecture="amd64"):
    """One current official netinst torrent, verified by SHA256SUMS."""
    if architecture not in ("amd64", "arm64"):
        raise ValueError("Unsupported official Debian architecture")
    base = DEBIAN_BT_CD.replace("/amd64/", "/" + architecture + "/")
    pattern = re.compile(DEBIAN_NETINST.pattern.replace("amd64", architecture))
    index = fetch(base, MAX_META).decode("utf-8", errors="replace")
    names = sorted(set(m.group(0) for m in pattern.finditer(index)))
    if len(names) != 1:
        raise ValueError("Expected exactly one current official Debian netinst torrent")
    name = names[0]
    sums = fetch(base + "SHA256SUMS", MAX_META).decode("ascii", errors="strict")
    published = dict((line.split()[1], line.split()[0]) for line in sums.splitlines()
                     if len(line.split()) == 2 and re.fullmatch(r"[0-9a-f]{64}", line.split()[0]))
    url = base + name
    raw = fetch(url, MAX_TORRENT)
    if published.get(name) != hashlib.sha256(raw).hexdigest():
        raise ValueError("Debian torrent does not match the published SHA256SUMS")
    safe, details = torrent_summary(raw, max_total_bytes, DEBIAN_TRACKERS)
    iso = name[:-len(".torrent")]
    if details["file_count"] != 1 or details["name"] != iso or not details["tracker_retained"]:
        raise ValueError("Unexpected Debian torrent layout or tracker")
    out = {"schema": "chunks.public-fixture.v1", "source": "debian-cdimage",
           "source_url": base, "torrent_url": url,
           "license": "Debian, DFSG-free and redistributable (https://www.debian.org/social_contract)",
           "creator": "Debian Project", "torrent": details,
           "published_sha256": {"torrent": published[name], "iso": published.get(iso)},
           "safe_torrent_sha256": hashlib.sha256(safe).hexdigest(),
           "nonsecret_fixture": True, "download_payload": False}
    return out, safe

def clean_commons_asset_url(value):
    """Strip only Wikimedia-supplied UTM trackers from a media CDN URL.

    Never persist credentials, signed requests, passkeys or unknown queries
    as a reusable fixture/webseed. The resulting exact media bytes are
    verified separately before generating any BitTorrent info dictionary.
    """
    raw = approved_url(value)
    p = urllib.parse.urlsplit(raw)
    if p.hostname != "upload.wikimedia.org":
        raise ValueError("Invalid Commons media source")
    params = urllib.parse.parse_qsl(p.query, keep_blank_values=True)
    recognized = {"utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term"}
    if any(key not in recognized for key, _ in params):
        raise ValueError("Unrecognized query on Commons media URL")
    return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path, "", ""))

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
    url = clean_commons_asset_url(info["url"])
    size = int(info["size"])
    if size < 0:
        raise ValueError("Invalid Commons file size")
    return {"schema": "chunks.public-fixture.v1", "source": "wikimedia-commons",
            "source_url": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
            "license": license_name,
            "attribution": {
                "author": attribution_text(extra.get("Artist", {}).get("value")),
                "credit": attribution_text(extra.get("Attribution", {}).get("value")),
                "license_short_name": attribution_text(extra.get("LicenseShortName", {}).get("value")),
                "license_url": str(extra.get("LicenseUrl", {}).get("value", ""))[:1024],
                "source_page": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
            },
            "asset": {"url": url, "bytes": size, "mime": info.get("mime")},
            "torrent": None, "magnet": None, "torrent_status": "no_official_torrent_claimed",
            "nonsecret_fixture": True, "download_payload": False}

def create_commons_single_file_torrent(manifest, data):
    """Build a private *test* torrent, not a claim of public tracker/seeder support.

    The verified HTTPS original file is advertised as a BEP19 webseed. Its
    availability to an individual rTorrent build must be tested separately.
    """
    url = manifest["asset"]["url"]
    p = urllib.parse.urlsplit(url)
    if p.hostname != "upload.wikimedia.org" or p.query or p.fragment:
        raise ValueError("Commons media URL not suitable for a public webseed")
    name = urllib.parse.unquote(p.path.rsplit("/", 1)[-1])
    encoded = name.encode("utf-8")
    clean_name(encoded)
    if not data or len(data) != manifest["asset"]["bytes"]:
        raise ValueError("Commons media length disagrees with API metadata")
    piece_length = 65536
    pieces = b"".join(hashlib.sha1(data[i:i + piece_length]).digest()
                      for i in range(0, len(data), piece_length))
    info = (b"d6:lengthi" + str(len(data)).encode() + b"e4:name" + bstr(encoded) +
            b"12:piece lengthi65536e6:pieces" + bstr(pieces) + b"e")
    # URL seed only, no invented public swarm or tracker. Real clients vary
    # in whether they implement and honor webseeds.
    raw_torrent = b"d4:info" + info + b"8:url-list" + bstr(url.encode()) + b"e"
    _safe, summary = torrent_summary(raw_torrent, len(data))
    manifest["asset"]["sha256"] = hashlib.sha256(data).hexdigest()
    manifest["torrent"] = summary
    manifest["torrent_status"] = "locally_generated_unseeded_webseed_unverified"
    manifest["download_payload"] = True
    manifest["asset"]["saved_name"] = name
    return raw_torrent, name

def main():
    p = argparse.ArgumentParser(description=__doc__)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--archive-id", help="Internet Archive item with a published .torrent")
    group.add_argument("--commons-file", help="Wikimedia Commons File: title")
    group.add_argument("--debian-netinst", action="store_true",
                       help="Official current Debian amd64 netinst torrent (ordinary seeders)")
    p.add_argument("--output-dir", default="public-fixture-output")
    p.add_argument("--max-total-mib", type=int, default=512)
    p.add_argument("--download-commons", action="store_true",
                   help="Explicitly download a small licensed Commons file and make a test torrent")
    p.add_argument("--max-commons-mib", type=int, default=8)
    args = p.parse_args()
    if args.max_total_mib < 1 or args.max_total_mib > 8192:
        p.error("max-total-mib must be 1..8192")
    if args.max_commons_mib < 1 or args.max_commons_mib > 16:
        p.error("max-commons-mib must be 1..16")
    if args.download_commons and not args.commons_file:
        p.error("--download-commons requires --commons-file")
    output = Path(args.output_dir)
    if output.exists() and any(output.iterdir()):
        p.error("Use a new empty output directory; never overwrite fixtures")
    payload = None
    saved_name = None
    if args.archive_id:
        manifest, torrent = archive_fixture(args.archive_id, args.max_total_mib * 1048576)
    elif args.debian_netinst:
        manifest, torrent = debian_fixture(args.max_total_mib * 1048576)
    else:
        manifest = commons_fixture(args.commons_file)
        torrent = None
        if args.download_commons:
            max_bytes = args.max_commons_mib * 1048576
            if manifest["asset"]["bytes"] > max_bytes:
                p.error("Commons asset is larger than requested payload cap")
            payload = fetch(manifest["asset"]["url"], max_bytes)
            torrent, saved_name = create_commons_single_file_torrent(manifest, payload)
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if torrent:
        (output / "source-sanitized.torrent").write_bytes(torrent)
        (output / "source.magnet").write_text(manifest["torrent"]["magnet"] + "\n", encoding="utf-8")
    if payload is not None:
        (output / saved_name).write_bytes(payload)
    print(json.dumps({"status": "ok", "source": manifest["source"],
                       "has_torrent": torrent is not None, "artifact_directory": str(output)}))

if __name__ == "__main__":
    main()
