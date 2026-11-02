#!/usr/bin/env python3
"""Bounded real public-swarm A/B: disposable rTorrent and real libtorrent.

No credentials, protected host, webseed, supplied peer or prepared payload.
The exact same original public magnet is handed to both implementations.
This answers discovery/BEP9 availability; it is not installed-app acceptance.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import xmlrpc.client

import libtorrent as lt
from public_fixture_collector import debian_fixture


def main():
    root = Path(os.environ["RUNNER_TEMP"]) / "chunks-public-metadata-probe"
    root.mkdir(exist_ok=False)
    results = {}
    started = time.monotonic()
    rpc = xmlrpc.client.ServerProxy("http://127.0.0.1:18080/RPC2", use_builtin_types=True)
    socket.setdefaulttimeout(8)
    sessions = []
    before = None
    try:
        ready_until = time.monotonic() + 100
        while True:
            try:
                before = set(rpc.download_list())
                break
            except (OSError, xmlrpc.client.ProtocolError):
                if time.monotonic() >= ready_until:
                    raise
                time.sleep(1)
        if before:
            raise ValueError("Disposable server must start empty")
        manifest, debian = debian_fixture(2 * 1024**3)
        with urllib.request.urlopen("https://webtorrent.io/torrents/sintel.torrent", timeout=20) as response:
            sintel = response.read(2_097_153)
        if len(sintel) > 2_097_152:
            raise ValueError("Invalid public metainfo")
        fixtures = (("debian", debian, manifest["torrent_url"]),
                    ("sintel", sintel, "https://webtorrent.io/torrents/sintel.torrent"))
        for label, raw, source in fixtures:
            ti = lt.torrent_info(raw)
            if not ti.info_hashes().has_v1() or ti.priv():
                raise ValueError("Only genuine non-private v1 fixtures")
            for i in range(ti.files().num_files()):
                parts = Path(ti.files().file_path(i))
                if parts.is_absolute() or ".." in parts.parts:
                    raise ValueError("Unsafe public fixture path")
            target = str(ti.info_hashes().v1)
            trackers = [t.url for t in ti.trackers()
                        if urllib.parse.urlsplit(t.url).scheme in ("http", "https", "udp")]
            if not trackers:
                raise ValueError("Public fixture has no standard tracker")
            magnet = "magnet:?xt=urn:btih:" + target + "".join("&tr=" + urllib.parse.quote(t, safe="") for t in trackers)
            # This parent is the explicit writable volume in the modern container.
            # A runner-only evidence directory is not a server download Place.
            data = Path(os.environ["RUNNER_TEMP"]) / "probe-data" / label
            data.mkdir()
            item = {"infohash": target, "name": ti.name(), "size": ti.total_size(),
                    "source": source, "magnet_sha256": hashlib.sha256(magnet.encode()).hexdigest(),
                    "tracker_hosts": sorted({urllib.parse.urlsplit(t).hostname for t in trackers}),
                    "registered": False, "rtorrent_metadata": False, "rtorrent_received": 0,
                    "rtorrent_verified": 0, "rtorrent_peak_peers": 0,
                    "libtorrent_metadata": False, "libtorrent_received": 0,
                    "libtorrent_verified": 0, "libtorrent_peak_peers": 0,
                    "cleanup": "not-submitted", "stages": [], "rtorrent_state": {}, "tracker_counts": []}
            results[label] = item
            # Standard incoming/outgoing peer traffic and public tracker/DHT
            # discovery only. No local seeder exists in this experiment.
            session = lt.session({"enable_dht": True, "enable_lsd": False,
                "enable_upnp": False, "enable_natpmp": False,
                "download_rate_limit": 256 * 1024, "upload_rate_limit": 128 * 1024})
            params = lt.parse_magnet_uri(magnet)
            params.save_path = str(root / (label + "-libtorrent"))
            params.flags &= ~lt.torrent_flags.auto_managed
            handle = session.add_torrent(params)
            handle.resume()
            sessions.append((session, handle, item))
            item["cleanup"] = "add-may-have-applied"
            item["stages"].append("add-submitted")
            getattr(rpc, "load.start")("", magnet, "d.directory.set=" + str(data))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            all_done = True
            for session, handle, item in sessions:
                status = handle.status(lt.status_flags_t.query_pieces)
                item["libtorrent_peak_peers"] = max(item["libtorrent_peak_peers"], status.num_peers)
                if handle.has_metadata():
                    info = handle.torrent_file()
                    item["libtorrent_metadata"] = info.name() == item["name"] and info.total_size() == item["size"]
                item["libtorrent_received"] = max(item["libtorrent_received"], status.total_payload_download)
                if status.has_metadata:
                    info = handle.torrent_file()
                    verified = sum(info.piece_size(i) for i, complete in enumerate(status.pieces) if complete)
                    item["libtorrent_verified"] = max(item["libtorrent_verified"], verified)
                h = item["infohash"]
                try:
                    exact = getattr(rpc, "d.hash")(h)
                    item["registered"] = exact.lower() == h
                    meta = getattr(rpc, "d.is_meta")(h)
                    item["rtorrent_state"] = {key: getattr(rpc, key)(h) for key in
                        ("d.state", "d.is_active", "d.is_open", "d.is_meta")}
                    try:
                        item["tracker_counts"] = getattr(rpc, "t.multicall")(h, "", "t.is_enabled=", "t.success_counter=", "t.failed_counter=", "t.latest_sum_peers=")
                    except xmlrpc.client.Fault:
                        item["tracker_counts"] = "unsupported"
                    item["rtorrent_peak_peers"] = max(item["rtorrent_peak_peers"], getattr(rpc, "d.peers_connected")(h))
                    if not meta:
                        item["rtorrent_metadata"] = getattr(rpc, "d.name")(h) == item["name"] and getattr(rpc, "d.size_bytes")(h) == item["size"]
                        item["rtorrent_received"] = max(item["rtorrent_received"], getattr(rpc, "d.down.total")(h))
                        item["rtorrent_verified"] = max(item["rtorrent_verified"], getattr(rpc, "d.completed_bytes")(h))
                except xmlrpc.client.Fault as error:
                    # Upstream metadata promotion briefly replaces the hash.
                    if error.faultCode != -501:
                        raise
                done = (item["rtorrent_metadata"] and item["rtorrent_received"] > 0 and item["rtorrent_verified"] > 0 and
                        item["libtorrent_metadata"] and item["libtorrent_received"] > 0 and item["libtorrent_verified"] > 0)
                if not done:
                    all_done = False
                else:
                    item["passed"] = True
                    handle.pause()
                    if "pieces" not in item["stages"]:
                        getattr(rpc, "d.stop")(h)
                        item["stages"].append("pieces")
            if all_done:
                break
            time.sleep(.4)
    except Exception as error:
        results["setup_failure"] = {"category": type(error).__name__}
    finally:
        for session, handle, item in sessions:
            handle.pause()
            h = item["infohash"]
            try:
                present = h.upper() in {v.upper() for v in rpc.download_list()}
                if present:
                    if getattr(rpc, "d.state")(h):
                        getattr(rpc, "d.stop")(h)
                    getattr(rpc, "d.close")(h)
                    getattr(rpc, "d.erase")(h)
                item["cleanup"] = "session-absent" if h.upper() not in {v.upper() for v in rpc.download_list()} else "uncertain"
            except Exception:
                item["cleanup"] = "uncertain"
        for item in results.values():
            if "infohash" not in item:
                continue
            item["first_unproven"] = next((key for key in ("registered", "rtorrent_metadata", "rtorrent_received", "rtorrent_verified",
                "libtorrent_metadata", "libtorrent_received", "libtorrent_verified") if not item[key]), None)
        valid = len(sessions) == 2 and all(item.get("passed") and item["cleanup"] == "session-absent" for _, _, item in sessions)
        try:
            restored = before is not None and set(rpc.download_list()) == before
            version = rpc.system.client_version()
        except Exception:
            restored, version = False, None
        receipt = {"source_sha": os.getenv("GITHUB_SHA"), "run_id": os.getenv("GITHUB_RUN_ID"),
            "runtime_seconds": round(time.monotonic() - started, 2), "rtorrent": version,
            "libtorrent": lt.version, "result": "passed" if valid and restored else "failed-or-inconclusive",
            "entry_path": "direct disposable XML-RPC; no installed Android claim", "fixtures": results,
            "unrelated_inventory_restored": restored, "webseeds": False, "supplied_peers": False}
        (root / "RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n")
        suite = ET.Element("testsuite", name="real-public-magnet-discovery", tests="2",
                           failures=str(sum(not restored or not results.get(label, {}).get("passed", False) or results.get(label, {}).get("cleanup") != "session-absent" for label in ("debian", "sintel"))), errors="0", skipped="0")
        for label in ("debian", "sintel"):
            case = ET.SubElement(suite, "testcase", classname="PublicMagnetDiscovery", name=label)
            if not restored or not results.get(label, {}).get("passed") or results.get(label, {}).get("cleanup") != "session-absent":
                ET.SubElement(case, "failure", type="PublicMetadataOrPeerUnavailable", message=results.get(label, {}).get("first_unproven") or "not-executed")
        ET.ElementTree(suite).write(root / "TEST-public-magnets.xml", encoding="utf-8", xml_declaration=True)
        print("CHUNKS_PUBLIC_MAGNET_PROBE: " + receipt["result"], flush=True)
    if not valid or not restored:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
