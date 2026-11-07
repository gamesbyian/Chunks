#!/usr/bin/env python3
"""Real libtorrent metadata/payload peer for installed remote-Add acceptance.

Two independently published groups of redistributable v1 torrents: official
Debian arm64/amd64 netinst and Blender's CC BY 3.0 Sintel/Big Buck Bunny via
WebTorrent. Only a bounded prefix is fetched over HTTPS; libtorrent checks
the original pieces before the installed app is allowed to submit a magnet.
The Android app submits magnets, never these metainfo bytes, to rTorrent.
The loopback controller only dials the configured disposable/development host.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath
import signal
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import libtorrent as lt
from public_fixture_collector import bstr, debian_fixture, parse_bencode

PREFIX_BUDGET = 32 * 1024 * 1024


def bounded_phase_length(value):
    """Accept only a small, canonical ASCII HTTP request body length.

    A negative Content-Length would make rfile.read(-1) wait for connection
    close, potentially wedging an installed peer-fixture phase indefinitely.
    Reject malformed lengths before accessing the request stream.
    """
    if (not isinstance(value, str) or not 1 <= len(value) <= 3 or
            not value.isascii() or not value.isdecimal()):
        raise ValueError("Invalid phase request size")
    size = int(value)
    if not 1 <= size <= 128:
        raise ValueError("Invalid phase request size")
    return size


def fetch(url, limit, offset=0):
    request = urllib.request.Request(url, headers={
        "User-Agent": "Chunks-real-magnet-acceptance/1.0",
        "Range": f"bytes={offset}-{offset + limit - 1}",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status not in (200, 206) or response.geturl().split(":", 1)[0] != "https":
            raise ValueError("Source did not return an HTTPS payload")
        if offset and response.status != 206:
            raise ValueError("Source ignored a nonzero range")
        data = response.read(limit)
    if len(data) != limit:
        raise ValueError("Source returned incomplete payload bytes")
    return data


def prepare(root):
    sources = {}
    for label, architecture in (("magnet", "arm64"), ("magnet3", "amd64")):
        manifest, raw = debian_fixture(2 * 1024**3, architecture=architecture)
        sources[label] = (raw, manifest["torrent_url"].replace("/bt-cd/", "/iso-cd/").removesuffix(".torrent"),
                          manifest["source_url"], manifest["license"])
    for label, title in (("magnet2", "sintel"), ("magnet4", "big-buck-bunny")):
        source = "https://webtorrent.io/torrents/" + title + ".torrent"
        with urllib.request.urlopen(source, timeout=30) as response:
            raw = response.read(2_097_153)
        if not raw or len(raw) > 2_097_152:
            raise ValueError("Blender metainfo unavailable")
        license_url = "https://durian.blender.org/sharing/" if title == "sintel" else "https://peach.blender.org/about/"
        sources[label] = (raw, "https://webtorrent.io/torrents/", source,
                          "Blender Foundation, CC BY 3.0, " + license_url)
    fixtures = {}
    for label, (raw, payload_source, source, license_text) in sources.items():
        ti = lt.torrent_info(raw)
        if ti.priv():
            raise ValueError("Only the fixed non-private public fixtures may be used")
        trackers = [t.url for t in ti.trackers() if urllib.parse.urlsplit(t.url).scheme in ("http", "https", "udp")]
        if not trackers:
            raise ValueError("Public source has no standard tracker")
        magnet = "magnet:?xt=urn:btih:" + str(ti.info_hashes().v1) + "".join("&tr=" + urllib.parse.quote(t, safe="") for t in trackers)
        (root / (label + ".magnet")).write_text(magnet)
        fs = ti.files()
        total = ti.total_size()
        if not ti.info_hashes().has_v1() or total <= 0 or total > 2 * 1024**3:
            raise ValueError("Invalid public v1 torrent")
        target = root / label
        target.mkdir(parents=True, exist_ok=False)
        # Whole original pieces only, so the independent native recheck can
        # demonstrate availability before any Android/rTorrent Add is tried.
        budget = min(total, PREFIX_BUDGET // ti.piece_length() * ti.piece_length())
        remaining = budget
        for index in range(fs.num_files()):
            relative = PurePosixPath(fs.file_path(index))
            if relative.is_absolute() or any(p in ("", ".", "..") for p in relative.parts):
                raise ValueError("Unsafe source payload path")
            path = target.joinpath(*relative.parts)
            path.parent.mkdir(parents=True, exist_ok=True)
            length = fs.file_size(index)
            with path.open("xb") as out:
                out.truncate(length)
                wanted = min(remaining, length)
                if wanted:
                    url = payload_source if label in ("magnet", "magnet3") else payload_source + urllib.parse.quote(str(relative))
                    out.write(fetch(url, wanted))
                    remaining -= wanted
        (root / (label + ".torrent")).write_bytes(raw)
        fixtures[label] = {
            "hash": str(ti.info_hashes().v1), "name": ti.name(), "size": total,
            "prefix_bytes": budget, "source": source, "license": license_text,
            "torrent_url": (payload_source.replace("/iso-cd/", "/bt-cd/") + ".torrent"
                            if label in ("magnet", "magnet3") else source),
            "metainfo_sha256": hashlib.sha256(raw).hexdigest(),
            "peer_connections": 0, "peer_disconnects": 0,
            "peer_error_alerts": 0, "max_active_peers": 0, "payload_uploaded_bytes": 0,
            "stages": [], "native_verified_bytes": 0,
        }
    if os.getenv("CHUNKS_DISPOSABLE") == "1" and os.getenv("CHUNKS_PUBLIC_DISCOVERY") != "1":
        # Small synthetic *negative* control: the workflow copies these exact
        # bytes into its disposable rTorrent directory before the UI Add. It
        # cannot count as either of the two genuine publisher successes.
        name = b"chunks-precompleted-control.bin"
        payload = hashlib.sha256(os.getenv("GITHUB_RUN_ID", "local").encode()).digest() * 2048
        piece_length = 16384
        pieces = b"".join(hashlib.sha1(payload[i:i + piece_length]).digest()
                          for i in range(0, len(payload), piece_length))
        info = (b"d6:lengthi" + str(len(payload)).encode() + b"e4:name" + bstr(name) +
                b"12:piece lengthi16384e6:pieces" + bstr(pieces) + b"e")
        raw = b"d8:announce" + bstr(b"http://127.0.0.1:1/announce") + b"4:info" + info + b"e"
        target = root / "magnet0"
        target.mkdir()
        (target / name.decode()).write_bytes(payload)
        (root / "magnet0.torrent").write_bytes(raw)
        infohash = hashlib.sha1(info).hexdigest()
        (root / "magnet0.magnet").write_text("magnet:?xt=urn:btih:" + infohash)
        fixtures["magnet0"] = {
            "hash": infohash, "name": name.decode(), "size": len(payload),
            "prefix_bytes": len(payload), "source": "disposable-negative-control",
            "license": "synthetic local test bytes", "metainfo_sha256": hashlib.sha256(raw).hexdigest(),
            "peer_connections": 0, "peer_disconnects": 0, "peer_error_alerts": 0,
            "max_active_peers": 0, "payload_uploaded_bytes": 0, "stages": [], "native_verified_bytes": 0,
        }
    if len({item["hash"] for item in fixtures.values()}) != len(fixtures):
        raise ValueError("Public candidates must have distinct infohashes")
    return fixtures


class Peer:
    def __init__(self, root, target_host, fixtures):
        self.root, self.target_host, self.fixtures = root, target_host, fixtures
        self.active = {}
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.started = time.monotonic()
        # Fixed public fixtures only. No addresses, torrent names, tracker URLs or
        # native alert messages enter this bounded, timestamp-aligned diagnostic.
        self.timeline = {label: [] for label in fixtures}
        self.events = {label: {} for label in fixtures}
        self.requests = {label: [] for label in fixtures}
        self.last_sample = 0.0

    def save(self):
        data = {"source_sha": os.getenv("GITHUB_SHA"), "run_id": os.getenv("GITHUB_RUN_ID"),
                "libtorrent_version": lt.version, "runtime_seconds": round(time.monotonic() - self.started, 2),
                "topology": "real libtorrent TCP peer to " + ("disposable rTorrent" if self.target_host == "127.0.0.1" else "approved development rTorrent"),
                "fixtures": self.fixtures}
        (self.root / "PEER-RECEIPT.json").write_text(json.dumps(data, indent=2) + "\n")
        (self.root / "PEER-TIMELINE.json").write_text(json.dumps(self.timeline, indent=2) + "\n")

    def new_seed(self, label, stage, port=None):
        # The controller is not an arbitrary network probe or filesystem API.
        if label not in self.fixtures or stage not in ("preflight", "metadata", "payload", "resume"):
            raise ValueError("Invalid fixture or phase")
        if stage != "preflight" and (type(port) is not int or not 1 <= port <= 65535):
            raise ValueError("Invalid observed peer port")
        with self.lock:
            item = self.fixtures[label]
            if stage in item["stages"]:
                raise ValueError("A peer phase must not be replayed")
            old = self.active.pop(label, None)
            if old:
                session, handle = old
                item["payload_uploaded_bytes"] += handle.status().total_payload_upload
                session.pause()
            session = lt.session({
                "listen_interfaces": "127.0.0.1:0", "enable_dht": False,
                "enable_lsd": False, "enable_upnp": False, "enable_natpmp": False,
                "enable_outgoing_utp": False, "enable_incoming_utp": False,
                "allow_multiple_connections_per_ip": True, "upload_rate_limit": 128 * 1024,
                "alert_mask": lt.alert.category_t.all_categories,
            })
            # Match LocalTorrentEngine's established local-peer rate-limit rig.
            classes = lt.ip_filter()
            classes.add_rule("0.0.0.0", "255.255.255.255", 1)
            session.set_peer_class_filter(classes)
            ti = lt.torrent_info(str(self.root / (label + ".torrent")))
            metadata_only = label == "magnet0" and stage == "metadata"
            save_path = self.root / ("magnet0-metadata" if metadata_only else label)
            if metadata_only:
                # Supply BEP9 metainfo from an empty directory. The test
                # must establish precompletion by rTorrent's own disk
                # recheck, before any peer can upload payload to it.
                save_path.mkdir()
            params = {"ti": ti, "save_path": str(save_path)}
            handle = session.add_torrent(params)
            # Never fetch missing bytes from public trackers or webseeds.
            handle.replace_trackers([])
            for webseed in handle.url_seeds():
                handle.remove_url_seed(webseed)
            handle.resume()
            if not metadata_only:
                prefix_pieces = (item["prefix_bytes"] + ti.piece_length() - 1) // ti.piece_length()
                def prefix_verified(status):
                    return len(status.pieces) >= prefix_pieces and all(status.pieces[:prefix_pieces])
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    status = handle.status(lt.status_flags_t.query_pieces)
                    if status.error:
                        raise ValueError("Native source recheck failed")
                    if prefix_verified(status):
                        break
                    if status.state not in (lt.torrent_status.checking_files, lt.torrent_status.checking_resume_data):
                        break
                    time.sleep(.1)
                status = handle.status(lt.status_flags_t.query_pieces)
                # Sparse zeros elsewhere in an ISO can themselves be valid pieces.
                # Their aggregate size must never stand in for the fetched prefix.
                if not prefix_verified(status):
                    raise ValueError("Original source pieces are unavailable")
                item["native_verified_bytes"] = sum(ti.piece_size(i) for i in range(prefix_pieces))
            self.active[label] = session, handle
            item["stages"].append(stage)
            if stage != "preflight":
                handle.connect_peer((self.target_host, port))
            self.save()

    def monitor(self):
        while not self.stopping.wait(.1):
            with self.lock:
                for label, (session, handle) in self.active.items():
                    self.fixtures[label]["max_active_peers"] = max(
                        self.fixtures[label]["max_active_peers"], handle.status().num_peers
                    )
                    for alert in session.pop_alerts():
                        if isinstance(alert, lt.peer_connect_alert):
                            self.fixtures[label]["peer_connections"] += 1
                        elif isinstance(alert, lt.peer_disconnected_alert):
                            self.fixtures[label]["peer_disconnects"] += 1
                        elif isinstance(alert, lt.peer_error_alert):
                            self.fixtures[label]["peer_error_alerts"] += 1
                        elif isinstance(alert, lt.block_uploaded_alert):
                            events = self.events[label]
                            events["blocks_uploaded"] = events.get("blocks_uploaded", 0) + 1
                        elif isinstance(alert, lt.invalid_request_alert):
                            events = self.events[label]
                            events["invalid_requests"] = events.get("invalid_requests", 0) + 1
                        elif isinstance(alert, lt.peer_log_alert):
                            # Python does not bind C++'s event_type/direction fields.
                            # Extract ONLY fixed protocol tokens from its formatted
                            # message; never retain or print the message/address.
                            message = alert.message()
                            event = re.search(r"\s(<==|==>|<<<|>>>) (REQUEST|CANCEL|CHOKE|UNCHOKE|INTERESTED|NOT_INTERESTED|HAVE|BITFIELD) \[", message)
                            if event:
                                name = ("in_" if event[1] in ("<==", "<<<") else "out_") + event[2].lower()
                                events = self.events[label]
                                events[name] = events.get(name, 0) + 1
                                if name == "in_request":
                                    request = re.search(r"piece: (\d+) s: (\d+) l: (\d+)", message)
                                    if request:
                                        self.requests[label].append({"epoch_ms": time.time_ns() // 1_000_000,
                                            "piece": int(request[1]), "offset": int(request[2]), "length": int(request[3])})
                                        del self.requests[label][:-8]
                if time.monotonic() - self.last_sample >= 1:
                    self.last_sample = time.monotonic()
                    for label, (session, handle) in self.active.items():
                        status = handle.status(lt.status_flags_t.query_pieces)
                        peers = handle.get_peer_info()
                        self.timeline[label].append({
                            "epoch_ms": time.time_ns() // 1_000_000,
                            "phase": self.fixtures[label]["stages"][-1],
                            "verified_bytes": status.total_done,
                            "available_pieces": sum(status.pieces),
                            "uploaded_bytes": status.total_payload_upload,
                            "upload_rate": status.upload_payload_rate,
                            "peer_count": status.num_peers,
                            "events": dict(self.events[label]),
                            "recent_requests": list(self.requests[label]),
                            "peers": [{"flags": int(p.flags), "pieces": p.num_pieces,
                                       "upload_queue": p.upload_queue_length,
                                       "download_queue": p.download_queue_length,
                                       "uploaded_bytes": p.total_upload} for p in peers[:8]],
                        })
                        del self.timeline[label][:-300]

    def close(self):
        self.stopping.set()
        with self.lock:
            for label, (session, handle) in self.active.items():
                self.fixtures[label]["payload_uploaded_bytes"] += handle.status().total_payload_upload
                session.pause()
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--peer-host", choices=("127.0.0.1", "5.79.112.219"), required=True)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=False)
    peer = None
    try:
        fixtures = prepare(args.root)
        peer = Peer(args.root, args.peer_host, fixtures)
        for label in fixtures:
            peer.new_seed(label, "preflight")
        threading.Thread(target=peer.monitor, daemon=True).start()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if self.path == "/ready":
                    body = json.dumps(peer.fixtures).encode()
                elif self.path == "/diagnostics":
                    with peer.lock:
                        body = json.dumps({"source_sha": os.getenv("GITHUB_SHA"), "run_id": os.getenv("GITHUB_RUN_ID"),
                                           "libtorrent_version": lt.version, "timeline": peer.timeline}).encode()
                elif self.path == "/magnet3.seed.torrent":
                    # The independent on-device seed must never fetch missing ISO
                    # bytes. Keep the publisher's exact info dictionary and strip
                    # only external discovery/webseed fields from this seed copy.
                    _, info = parse_bencode((args.root / "magnet3.torrent").read_bytes())
                    body = b"d4:info" + info + b"e"
                elif self.path == "/magnet3.piece":
                    ti = lt.torrent_info(str(args.root / "magnet3.torrent"))
                    fs = ti.files()
                    if fs.num_files() != 1 or fs.file_size(0) < ti.piece_size(0):
                        self.send_error(400)
                        return
                    with (args.root / "magnet3" / fs.file_path(0)).open("rb") as inp:
                        body = inp.read(ti.piece_size(0))
                elif self.path in tuple("/" + label + extension for label in peer.fixtures
                                        for extension in (".torrent", ".magnet")):
                    body = (args.root / self.path[1:]).read_bytes()
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                parts = self.path.strip("/").split("/")
                try:
                    length = bounded_phase_length(self.headers.get("Content-Length"))
                except ValueError:
                    self.send_error(400, "Invalid peer fixture request size")
                    return
                if len(parts) != 2:
                    self.send_error(400)
                    return
                try:
                    payload = self.rfile.read(length)
                    if len(payload) != length:
                        raise ValueError("Incomplete phase request")
                    body = json.loads(payload)
                    if not isinstance(body, dict) or type(body.get("port")) is not int:
                        raise ValueError("Invalid peer port")
                    peer.new_seed(parts[0], parts[1], body["port"])
                except Exception:
                    self.send_error(400, "Peer fixture phase failed")
                    return
                self.send_response(204)
                self.end_headers()

        server = ThreadingHTTPServer(("127.0.0.1", 18081), Handler)
        def stop(*_):
            raise KeyboardInterrupt()
        signal.signal(signal.SIGTERM, stop)
        print("CHUNKS_REAL_PEER: two original public payload prefixes independently piece-verified", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    except Exception as error:
        # Never copy source responses, paths, headers or credential-bearing data.
        (args.root / "PREPARATION-FAILURE.json").write_text(json.dumps({
            "source_sha": os.getenv("GITHUB_SHA"), "failure_category": type(error).__name__,
            "stage": "public-source-preparation", "installed_android": False,
        }) + "\n")
        raise SystemExit("Public real-peer preparation failed; no Android Add authorized")
    finally:
        if peer:
            peer.close()


if __name__ == "__main__":
    main()
