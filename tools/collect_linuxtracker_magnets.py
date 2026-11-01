#!/usr/bin/env python3
"""Source-verify public Linux distribution torrents, with no payload downloads.

Independent public tracker listings are hints; only actual metainfo authenticates
the infohash and exact total size. Seed counts are source-reported observations.
"""
from html.parser import HTMLParser
import re
import time
import urllib.parse
import urllib.request

from public_fixture_collector import MAX_TORRENT, torrent_summary, parse_bencode, NoRedirect

HOST = "linuxtracker.org"
HASH = re.compile(r"^/torrents/([0-9a-fA-F]{40})/?$")
SEARCHES = ("alpine", "slitaz", "tinycore", "netboot", "nethunter", "puppy", "minimal")
ALLOWED_TRACKERS = frozenset({
    "tracker.linuxtracker.org", "bittorrent.kali.org", "tracker.kali.org",
    "tracker.opentrackr.org", "open.demonii.com", "tracker.torrent.eu.org",
    "open.stealth.si", "tracker.qu.ax", "tracker.tryhackx.org",
    "open.demonoid.ch", "tracker.ololosh.space", "tracker2.dler.org",
    "exodus.desync.com", "explodie.org", "tracker.therarbg.to"
})

def get(url, cap):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in (HOST, "www." + HOST)
            or parsed.username or parsed.password or parsed.fragment
            or parsed.port not in (None, 443)):
        raise ValueError("Untrusted LinuxTracker URL")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Chunks-public-magnet-fixtures/1.0 (metainfo only)"})
    # Never follow a redirect to a new host without checking it.
    class Guard(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, location):
            target = urllib.parse.urljoin(req.full_url, location)
            p = urllib.parse.urlsplit(target)
            if p.scheme != "https" or p.hostname not in (HOST, "www." + HOST):
                raise ValueError("Offsite torrent redirect")
            return super().redirect_request(req, fp, code, msg, headers, target)
    with urllib.request.build_opener(Guard).open(req, timeout=15) as response:
        data = response.read(cap + 1)
    if len(data) > cap:
        raise ValueError("Oversized LinuxTracker response")
    return data

class Rows(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows, self.row, self.cell, self.hrefs = [], None, None, None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "tr":
            self.row, self.hrefs = [], []
        elif tag == "td" and self.row is not None:
            self.cell = []
        elif tag == "a" and self.row is not None:
            self.hrefs.append(a.get("href", ""))

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag == "td" and self.cell is not None and self.row is not None:
            self.row.append(" ".join(" ".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if len(self.row) >= 6:
                self.rows.append((self.row, self.hrefs))
            self.row, self.hrefs, self.cell = None, None, None

def parse_rows(data):
    parser = Rows()
    parser.feed(data.decode("utf-8", errors="replace"))
    for cells, hrefs in parser.rows:
        match = None
        for href in hrefs:
            url = urllib.parse.urlsplit(urllib.parse.urljoin("https://" + HOST + "/", href))
            if url.hostname in (HOST, "www." + HOST):
                match = HASH.fullmatch(url.path)
                if match:
                    break
        if not match:
            continue
        # Displayed MB can mean MiB; use 500 as a cautious prefilter.
        size_text = cells[3].strip()
        size_match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)\s*(MB|MiB|GB|GiB|KB|KiB)", size_text, re.I)
        if not size_match:
            continue
        size, unit = float(size_match.group(1)), size_match.group(2).lower()
        worst_bytes = size * (1024 ** 3 if unit in ("gb", "gib")
                              else 1024 ** 2 if unit in ("mb", "mib") else 1024)
        if worst_bytes > 500 * 1024 * 1024:
            continue
        seed_match = re.match(r"^(\d+)", cells[4].strip())
        seeds = int(seed_match.group(1)) if seed_match else 0
        if seeds < 1:
            continue
        yield {"hash": match.group(1).lower(), "seeds": seeds,
               "category": cells[1], "reported_size": size_text}

def safe_trackers(root):
    values = [root.get(b"announce")]
    for tier in root.get(b"announce-list", []):
        if isinstance(tier, list):
            values.extend(tier)
    unique = []
    for raw in values:
        if not isinstance(raw, bytes):
            continue
        try:
            url = raw.decode("utf-8", errors="strict")
            p = urllib.parse.urlsplit(url)
            if (p.scheme not in ("http", "https", "udp")
                or p.hostname not in ALLOWED_TRACKERS or p.username
                or p.password or p.query or p.fragment
                or p.path != "/announce"):
                continue
        except ValueError:
            continue
        if url not in unique:
            unique.append(url)
    return unique[:6]

def harvest(limit, max_bytes, pages=8, delay=0.2):
    """Yield authentic public metainfo identities. At most `limit` torrents."""
    seen, count = set(), 0
    for term in SEARCHES:
        for page in range(1, pages + 1):
            params = urllib.parse.urlencode({"q": term, "page": page, "category": ""})
            try:
                listed = list(parse_rows(get("https://" + HOST + "/torrents/?" + params, 512 * 1024)))
            except Exception as exc:
                print(f"LinuxTracker listing skipped {term}/{page}: {type(exc).__name__}", flush=True)
                continue
            for entry in listed:
                h = entry["hash"]
                if h in seen:
                    continue
                seen.add(h)
                try:
                    url = "https://" + HOST + "/torrents/" + h + "/download/"
                    raw = get(url, MAX_TORRENT)
                    _safe, meta = torrent_summary(raw, max_bytes, ())
                    if meta["infohash_v1"] != h:
                        raise ValueError("Index hash differs from published .torrent info dictionary")
                    root, _info = parse_bencode(raw)
                    magnet = meta["magnet"]
                    trackers = safe_trackers(root)
                    for tracker in trackers:
                        magnet += "&tr=" + urllib.parse.quote(tracker, safe="")
                    result = {
                        "magnet": magnet, "infohash_v1": h,
                        "infohash_v2": meta.get("infohash_v2"), "name": meta["name"],
                        "total_bytes": meta["total_bytes"], "file_count": meta["file_count"],
                        "media_category": "software", "distribution_category": entry["category"],
                        "source_url": "https://" + HOST + "/torrents/" + h + "/",
                        "torrent_url": url,
                        "license": "Public Linux distribution torrent; per-release licence needs review",
                        "tracker_count": len(trackers),
                        "source_reported_seeders": entry["seeds"],
                        "source_reported_size": entry["reported_size"],
                        "ordinary_seeders_verified": False,
                        "peer_bytes_verified": False
                    }
                    count += 1
                    yield result
                    if count >= limit:
                        return
                except Exception as exc:
                    print(f"LinuxTracker rejected {h}: {type(exc).__name__}", flush=True)
                time.sleep(delay)
            time.sleep(delay)
