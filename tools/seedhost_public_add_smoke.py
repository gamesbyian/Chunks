#!/usr/bin/env python3
"""Explicit manual live Add acceptance using a licensed public-source test torrent.

Default preview is offline with respect to Seedhost. Never retry a mutation
after missing acknowledgement. Requires a separately confirmed infohash to arm.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request
import xmlrpc.client

sys.path.insert(0, str(Path(__file__).parent))
from public_fixture_collector import archive_fixture, commons_fixture, fetch, create_commons_single_file_torrent

HASH = re.compile(r"^[0-9a-fA-F]{40}$")
MAX_RESPONSE = 4_194_304
# These repository secrets belong to ONE owner-approved development instance.
# A separate numbered tenant needs separate credentials and an explicit tool.
PRIMARY_SEEDHOST = "ianseeds.wood.seedhost.eu"
PRIMARY_WEB_PATH = "/rutorrent"
PRIMARY_RPC_PATH = "/rutorrent/plugins/httprpc/action.php"

def destination(value):
    if not value.startswith("/") or len(value) < 2 or len(value) > 1024:
        raise ValueError("Expected an absolute destination")
    if any(p in ("", ".", "..") for p in value[1:].split("/")):
        raise ValueError("Unsafe destination path")
    if any(ord(x) < 32 or x in (",", ";", "=", '"', "'", "$", "(", ")", "\\", chr(96)) for x in value):
        raise ValueError("Unsupported destination command characters")
    return value

def rpc_endpoint(value):
    """Bind primary repository secrets to their ONE approved HTTPS/RPC mount.

    The manual workflow also pins the URL, but this transport may be invoked
    directly. Never let a supplied URL select a sibling tenant or numbered
    rTorrent instance. Validate the *raw* path before any decoding/rewriting.
    """
    uri = urllib.parse.urlsplit(value)
    if (uri.scheme != "https" or uri.hostname != PRIMARY_SEEDHOST or
        uri.port not in (None, 443) or uri.username is not None or
        uri.password is not None or uri.query or uri.fragment):
        raise ValueError("Primary Seedhost credentials require the exact approved HTTPS origin")
    if uri.path not in (PRIMARY_WEB_PATH, PRIMARY_WEB_PATH + "/", PRIMARY_RPC_PATH):
        raise ValueError("Primary Seedhost credentials require the exact approved ruTorrent mount")
    return urllib.parse.urlunsplit(("https", uri.netloc, PRIMARY_RPC_PATH, "", ""))

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class ProtectedRpc:
    ALLOWED = frozenset(("d.multicall2", "load.raw_verbose", "d.directory", "d.is_multi_file", "d.name", "d.state"))
    def __init__(self, url, username, password):
        if not username or not password:
            raise ValueError("Missing protected repository credentials")
        self.url = rpc_endpoint(url)
        manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        manager.add_password(None, self.url, username, password)
        self.opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPDigestAuthHandler(manager),
            urllib.request.HTTPBasicAuthHandler(manager))
    def call(self, method, *args):
        if method not in self.ALLOWED:
            raise ValueError("Unallowlisted RPC command")
        request = urllib.request.Request(self.url,
            data=xmlrpc.client.dumps(args, methodname=method).encode(),
            headers={"Content-Type": "text/xml"}, method="POST")
        with self.opener.open(request, timeout=20) as response:
            payload = response.read(MAX_RESPONSE + 1)
        if (len(payload) > MAX_RESPONSE or b"<!DOCTYPE" in payload.upper() or
            b"<!ENTITY" in payload.upper()):
            raise ValueError("Unsafe or oversized RPC response")
        response, unused = xmlrpc.client.loads(payload)
        return response[0] if response else None

def main_hashes(client):
    rows = client.call("d.multicall2", "", "main", "d.hash=")
    if not isinstance(rows, (list, tuple)) or len(rows) > 12000:
        raise ValueError("Invalid main-view torrent inventory")
    result = set()
    for row in rows:
        if not isinstance(row, (list, tuple)) or len(row) != 1 or not HASH.fullmatch(str(row[0])):
            raise ValueError("Invalid main-view hash row")
        result.add(row[0].lower())
    return result

def exact_hash_is_present(client, infohash):
    """Ask the unfiltered session for the exact hash before issuing any Add.

    A missing row in rTorrent's "main" view is NOT proof of absence from
    the session. Only the documented legacy/modern unknown-hash faults
    establish absence; unsupported, malformed and other faults stop here.
    """
    try:
        name = client.call("d.name", infohash)
    except xmlrpc.client.Fault as fault:
        detail = fault.faultString.lower()
        if (fault.faultCode == -501 and "could not find info-hash" in detail or
                fault.faultCode == -500 and "info-hash not found" in detail):
            return False
        raise
    if not isinstance(name, str):
        raise ValueError("Invalid exact-session name reply")
    return True


def public_fixture(args):
    if args.archive_id:
        return archive_fixture(args.archive_id, args.max_total_mib * 1048576)
    manifest = commons_fixture(args.commons_file)
    if not 0 < manifest["asset"]["bytes"] <= 8 * 1048576:
        raise ValueError("Commons payload is outside the 1-byte to 8-MiB live test budget")
    content = fetch(manifest["asset"]["url"], 8 * 1048576)
    torrent, _ = create_commons_single_file_torrent(manifest, content)
    return manifest, torrent

def execute(args, factory=ProtectedRpc):
    planned = destination(args.destination)
    manifest, torrent = public_fixture(args)
    expected = manifest["torrent"]["infohash_v1"]
    if not expected or not HASH.fullmatch(expected):
        raise ValueError("Live smoke only supports verified BitTorrent v1 identities")
    receipt = {"schema": "chunks.public-add-smoke.v1", "origin": manifest["source"],
               "source_url": manifest["source_url"], "hash": expected,
               "destination": planned, "announced_bytes": manifest["torrent"]["total_bytes"],
               "operation": "preview" if not args.apply else "add_stopped",
               "result": "preview_only"}
    if not args.apply:
        print("CHUNKS_PUBLIC_ADD: preview only; no server connection")
        return receipt
    if args.confirm_hash.lower() != expected:
        raise ValueError("Confirm the exact public fixture infohash before executing")
    client = factory(args.rutorrent_url, os.environ.get("RT_USER"), os.environ.get("RT_PASS"))
    if expected in main_hashes(client) or exact_hash_is_present(client, expected):
        receipt["result"] = "already_present_no_write"
        print("CHUNKS_PUBLIC_ADD: duplicate hash; no write attempted")
        return receipt
    # No retry after an ambiguous server/network result. Deleting or cleaning up
    # a partially applied load also requires a separate explicit request.
    receipt["result"] = "outcome_uncertain"
    try:
        client.call("load.raw_verbose", "", xmlrpc.client.Binary(torrent),
                    "d.directory.set=" + planned)
    except Exception as exc:
        receipt["error_class"] = type(exc).__name__
        print("CHUNKS_PUBLIC_ADD: acknowledgement missing; inspect hash before any retry")
        return receipt
    receipt["result"] = "acknowledged_pending_readback"
    try:
        if expected not in main_hashes(client):
            print("CHUNKS_PUBLIC_ADD: metadata not yet visible in main view")
            return receipt
        observed = client.call("d.directory", expected)
        multi = int(client.call("d.is_multi_file", expected)) != 0
        torrent_name = client.call("d.name", expected)
        valid_name = (isinstance(torrent_name, str) and torrent_name not in ("", ".", "..") and
                      "/" not in torrent_name and "\\" not in torrent_name and
                      all(ord(c) >= 32 for c in torrent_name))
        expected_root = (planned + "/" + torrent_name) if multi and valid_name else planned
        state = client.call("d.state", expected)
        if isinstance(observed, str) and observed == expected_root and (
                not multi or valid_name) and int(state) == 0:
            receipt["result"] = "verified_stopped_in_destination"
            receipt["observed_directory"] = observed
            print("CHUNKS_PUBLIC_ADD: verified exact hash, chosen parent destination, stopped")
        else:
            receipt["result"] = "readback_mismatch"
            print("CHUNKS_PUBLIC_ADD: readback mismatch, no cleanup attempted")
    except Exception as exc:
        receipt["error_class"] = type(exc).__name__
        print("CHUNKS_PUBLIC_ADD: readback inconclusive, no retry")
    return receipt

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--archive-id")
    src.add_argument("--commons-file")
    parser.add_argument("--destination", required=True)
    parser.add_argument("--max-total-mib", type=int, default=512)
    parser.add_argument("--rutorrent-url", default="")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-hash", default="")
    parser.add_argument("--receipt-path", default="")
    args = parser.parse_args()
    if not 1 <= args.max_total_mib <= 8192:
        parser.error("Invalid maximum torrent budget")
    if args.apply and not args.rutorrent_url:
        parser.error("Applying requires an explicit Seedhost ruTorrent URL")
    receipt = execute(args)
    if args.receipt_path:
        Path(args.receipt_path).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("CHUNKS_PUBLIC_ADD: status=" + receipt["result"])
    if args.apply and receipt["result"] not in ("verified_stopped_in_destination", "already_present_no_write"):
        raise SystemExit(2)

if __name__ == "__main__":
    main()
