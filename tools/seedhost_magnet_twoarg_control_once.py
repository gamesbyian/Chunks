#!/usr/bin/env python3
"""One-shot protected real rTorrent magnet comparison with Transdroid-style RPC.

The official Ubuntu 26.04.1 server torrent already passed a genuine peer and
piece-verified .torrent Add on this server in CI run 41363266446. This control
reuses its SHA-derived hash ONLY as a magnet. A 2-argument native load.start
(without Chunks' extra directory command) tests whether metadata can resolve.
All mutations are explicitly scoped to that newly absent exact hash and sent
only once. Stop/close/session erase is attempted only after exact registration.
No payload deletion, unrelated jobs, blind retry, or released APK.
"""
import base64
import os
import sys
import time
import urllib.parse
import urllib.request
import xmlrpc.client

from seedhost_magnet_readonly_forensics import (
    RPC, NoRedirect, decode_bencode, source_hash
)

SOURCE = "https://releases.ubuntu.com/26.04/ubuntu-26.04.1-live-server-amd64.iso.torrent"
READS = {
    "system.client_version", "download_list", "d.hash", "d.name", "d.directory",
    "d.size_bytes", "d.state", "d.is_active", "d.is_open",
    "d.peers_connected", "d.down.total", "d.completed_bytes",
}
WRITES = {"load.start", "d.stop", "d.close", "d.erase"}


class ExactRpc:
    def __init__(self, hash_value, magnet):
        self.hash = hash_value
        self.magnet = magnet
        authorization = base64.b64encode((
            os.environ["CHUNKS_LIVE_RTORRENT_USER"] + ":" +
            os.environ["CHUNKS_LIVE_RTORRENT_PASS"]
        ).encode("utf-8")).decode("ascii")
        self.auth = "Basic " + authorization
        # This opener has NO auth handler and rejects redirects: a POST is
        # never automatically replayed after a 401 or a transport error.
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, method, *args):
        if method in READS:
            pass
        elif method == "load.start":
            if args != ("", self.magnet):
                raise ValueError("unexpected Add parameters")
        elif method in {"d.stop", "d.close", "d.erase"}:
            if args != (self.hash,):
                raise ValueError("unexpected exact transfer")
        else:
            raise ValueError("unauthorized RPC operation")
        payload = xmlrpc.client.dumps(args, methodname=method).encode("utf-8")
        request = urllib.request.Request(
            RPC, data=payload, method="POST",
            headers={
                "Content-Type": "text/xml; charset=utf-8",
                "Accept": "text/xml",
                "Authorization": self.auth,
            }
        )
        # HTTP errors, XML-RPC faults and malformed ACKs stop mutations. The
        # caller NEVER resends a load/stop/close/erase after an uncertain ACK.
        with self.opener.open(request, timeout=18) as response:
            if response.status != 200 or response.geturl() != RPC:
                raise ValueError("untrusted RPC response")
            raw = response.read(262_145)
        if len(raw) > 262_144:
            raise ValueError("oversize RPC response")
        result, _ = xmlrpc.client.loads(raw)
        return result[0] if result else None

    def inventory(self):
        data = self.call("download_list")
        if not isinstance(data, (tuple, list)):
            raise ValueError("missing scoped inventory")
        return {str(value).lower() for value in data}

    def sample(self):
        if str(self.call("d.hash", self.hash)).lower() != self.hash:
            raise ValueError("identity changed")
        result = {}
        for method in (
            "d.name", "d.directory", "d.size_bytes", "d.state", "d.is_active",
            "d.peers_connected", "d.down.total", "d.completed_bytes",
        ):
            result[method] = self.call(method, self.hash)
        if str(self.call("d.hash", self.hash)).lower() != self.hash:
            raise ValueError("identity changed during readback")
        return result


def fixture():
    expected_hash, name, size = source_hash(SOURCE)
    with urllib.request.urlopen(SOURCE, timeout=25) as response:
        if response.status != 200 or response.geturl() != SOURCE:
            raise ValueError("official source redirected")
        raw = response.read(1_048_577)
    if len(raw) > 1_048_576:
        raise ValueError("metainfo too large")
    torrent, end = decode_bencode(raw)
    if end != len(raw) or not isinstance(torrent, dict):
        raise ValueError("invalid official metainfo")
    trackers = []
    if isinstance(torrent.get(b"announce"), bytes):
        trackers.append(torrent[b"announce"])
    for tier in torrent.get(b"announce-list", []):
        if isinstance(tier, list):
            trackers.extend(item for item in tier if isinstance(item, bytes))
    valid = []
    for raw_tracker in trackers:
        address = raw_tracker.decode("utf-8")
        uri = urllib.parse.urlsplit(address)
        if uri.scheme not in ("http", "https", "udp") or uri.username or uri.password:
            continue
        if not uri.hostname or not uri.hostname.lower().endswith(".ubuntu.com"):
            continue
        if address not in valid:
            valid.append(address)
    if not valid:
        raise ValueError("official torrent did not specify accepted trackers")
    magnet = "magnet:?xt=urn:btih:" + expected_hash
    magnet += "&dn=" + urllib.parse.quote(name, safe="")
    magnet += "".join("&tr=" + urllib.parse.quote(track, safe="") for track in valid[:8])
    return expected_hash, name, size, magnet


def cleanup(client):
    """One Stop, one Close, one session-only Erase; no mutation retries."""
    inventory = client.inventory()
    if client.hash not in inventory:
        print("MAGNET_TWOARG cleanup=already-absent")
        return True
    # The hash was proven absent immediately before our one Add. Refuse if
    # exact readback differs or missing directory/identity prevents targeting.
    before = client.sample()
    if not isinstance(before["d.directory"], str) or not before["d.directory"].startswith("/"):
        raise ValueError("unsafe transfer directory identity")
    original_dir = before["d.directory"]
    client.call("d.stop", client.hash)
    client.call("d.close", client.hash)
    ready = False
    for _ in range(10):
        snapshot = client.sample()
        if snapshot["d.directory"] != original_dir:
            raise ValueError("directory changed during cleanup")
        if snapshot["d.state"] == 0 and snapshot["d.is_active"] == 0:
            ready = True
            break
        time.sleep(2)
    if not ready:
        raise ValueError("transfer not stopped and inactive")
    client.call("d.erase", client.hash)
    if client.hash in client.inventory():
        raise ValueError("session erase unverified")
    print("MAGNET_TWOARG cleanup=exact-session-erased payload=preserved")
    return True


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise ValueError("only first-attempt trusted main")
    if not os.environ.get("CHUNKS_LIVE_RTORRENT_USER") or not os.environ.get("CHUNKS_LIVE_RTORRENT_PASS"):
        raise ValueError("missing protected credentials")
    hash_value, expected_name, expected_size, magnet = fixture()
    client = ExactRpc(hash_value, magnet)

    # CRITICAL: explicit Basic on a READ must work before a single POST
    # mutation. Digest-only servers fail closed without attempting Add.
    version = client.call("system.client_version")
    if not isinstance(version, str) or not version:
        raise ValueError("Basic-auth read preflight failed")
    inventory = client.inventory()
    if hash_value in inventory:
        raise ValueError("known-source hash still in session: no Add")
    print("MAGNET_TWOARG preflight=authenticated exact_hash=absent source=previously-peer-verified")
    added = False
    passed = False
    cleanup_passed = False
    try:
        added = True  # before the non-idempotent operation
        client.call("load.start", "", magnet)
        print("MAGNET_TWOARG native_two_argument_load=acknowledged")
        deadline = time.monotonic() + 180
        registered = False
        metadata = False
        peers = False
        received = False
        verified = False
        while time.monotonic() < deadline:
            try:
                if hash_value not in client.inventory():
                    time.sleep(4)
                    continue
                snapshot = client.sample()
            except Exception:
                # Read-only queries can be retried; writes never are.
                time.sleep(4)
                continue
            if not registered:
                registered = True
                print("MAGNET_TWOARG registration=exact-hash-confirmed")
            metadata = metadata or (
                snapshot["d.name"] == expected_name and
                snapshot["d.size_bytes"] == expected_size
            )
            peers = peers or isinstance(snapshot["d.peers_connected"], int) and snapshot["d.peers_connected"] > 0
            received = received or isinstance(snapshot["d.down.total"], int) and snapshot["d.down.total"] > 0
            verified = verified or isinstance(snapshot["d.completed_bytes"], int) and snapshot["d.completed_bytes"] > 0
            if metadata and peers and received and verified:
                passed = True
                break
            time.sleep(4)
        print("MAGNET_TWOARG evidence registered=" + str(registered).lower() +
              " metadata=" + str(metadata).lower() +
              " connected_peer=" + str(peers).lower() +
              " received=" + str(received).lower() +
              " verified=" + str(verified).lower())
    finally:
        if added:
            try:
                cleanup_passed = cleanup(client)
            except Exception:
                # Once any mutation is ambiguous, do NOT replay it. This
                # public exact hash remains for a separate read-only audit.
                print("MAGNET_TWOARG cleanup=uncertain no-retry")
    if not (passed and cleanup_passed):
        raise ValueError("genuine magnet acceptance or safe cleanup incomplete")
    print("MAGNET_TWOARG PASS real metadata + tracker peers + downloaded and verified pieces")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # A raw stacktrace may contain an authenticated request or tracker URL.
        print("MAGNET_TWOARG result=failed-or-inconclusive; no automatic retry")
        sys.exit(1)
