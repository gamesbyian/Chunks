#!/usr/bin/env python3
"""Read-only, credential-safe rTorrent forensic receipt for known public magnet hashes.

Only on trusted main with protected CI secrets. Sends exclusively query methods;
never Add/Start/Stop/recheck/remove, changes no payload, prints no remote strings.
"""
import hashlib
import os
import sys
import urllib.request
import xmlrpc.client

RPC = "https://ianseeds.wood.seedhost.eu/rutorrent/plugins/httprpc/action.php"
SOURCES = (
    ("prior-2604-desktop", "https://releases.ubuntu.com/26.04/ubuntu-26.04.1-desktop-amd64.iso.torrent"),
    ("fresh-2510-server", "https://releases.ubuntu.com/25.10/ubuntu-25.10-live-server-amd64.iso.torrent"),
    ("fresh-2510-desktop", "https://releases.ubuntu.com/25.10/ubuntu-25.10-desktop-amd64.iso.torrent"),
)
METHODS = (
    "d.hash", "d.name", "d.state", "d.is_active", "d.is_open",
    "d.size_bytes", "d.completed_bytes", "d.down.total", "d.peers_connected",
    "d.down.rate", "d.directory", "d.mode", "d.ignore_commands", "d.tracker_size",
)
ALLOWED = set(METHODS) | {"directory.default", "download_list"}


def item(data, start, depth=0):
    if depth >= 80 or start >= len(data):
        raise ValueError("invalid bencoding")
    marker = data[start]
    if marker == 105:  # i123e
        stop = data.index(b"e", start + 1)
        int(data[start + 1:stop])
        return stop + 1
    if marker in (108, 100):  # list or dict
        i = start + 1
        while i < len(data) and data[i] != 101:
            i = item(data, i, depth + 1)
        if i >= len(data):
            raise ValueError("unterminated bencoding")
        return i + 1
    if 48 <= marker <= 57:
        colon = data.index(b":", start)
        length = int(data[start:colon])
        end = colon + 1 + length
        if length < 0 or end > len(data):
            raise ValueError("invalid byte string")
        return end
    raise ValueError("invalid bencode type")


def decode_bencode(data, start=0, depth=0):
    if depth > 64 or start >= len(data):
        raise ValueError("invalid metainfo nesting")
    token = data[start]
    if token == 105:
        end = data.index(b"e", start + 1)
        return int(data[start + 1:end]), end + 1
    if token == 108:
        result, pos = [], start + 1
        while data[pos] != 101:
            element, pos = decode_bencode(data, pos, depth + 1)
            result.append(element)
        return result, pos + 1
    if token == 100:
        result, pos = {}, start + 1
        while data[pos] != 101:
            key, pos = decode_bencode(data, pos, depth + 1)
            if not isinstance(key, bytes) or key in result:
                raise ValueError("invalid dictionary")
            val, pos = decode_bencode(data, pos, depth + 1)
            result[key] = val
        return result, pos + 1
    if 48 <= token <= 57:
        colon = data.index(b":", start)
        size = int(data[start:colon])
        end = colon + 1 + size
        if size < 0 or end > len(data):
            raise ValueError("invalid byte string")
        return data[colon + 1:end], end
    raise ValueError("invalid type")


def source_hash(url):
    with urllib.request.urlopen(url, timeout=25) as response:
        if response.status != 200 or response.geturl() != url:
            raise ValueError("source unavailable or redirected")
        data = response.read(1_048_577)
    if not data or len(data) > 1_048_576 or data[0] != 100:
        raise ValueError("invalid metainfo")
    i, found, info_name, info_size = 1, None, None, None
    while i < len(data) and data[i] != 101:
        if not 48 <= data[i] <= 57:
            raise ValueError("non-string key")
        colon = data.index(b":", i)
        end = colon + 1 + int(data[i:colon])
        key = data[colon + 1:end]
        start = end
        i = item(data, start)
        if key == b"info":
            if found:
                raise ValueError("duplicate info")
            found = hashlib.sha1(data[start:i]).hexdigest()
            info, consumed = decode_bencode(data, start)
            if consumed != i or not isinstance(info, dict):
                raise ValueError("invalid info dictionary")
            raw_name = info.get(b"name.utf-8") or info.get(b"name")
            if not isinstance(raw_name, bytes):
                raise ValueError("missing name")
            info_name = raw_name.decode("utf-8")
            info_size = info[b"length"] if b"length" in info else sum(x[b"length"] for x in info[b"files"])
            if not isinstance(info_size, int) or info_size <= 0:
                raise ValueError("invalid total size")
    if found is None or i != len(data) - 1:
        raise ValueError("missing info")
    return found, info_name, info_size


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def rpc_opener():
    password_manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    password_manager.add_password(
        None, RPC, os.environ["CHUNKS_LIVE_RTORRENT_USER"],
        os.environ["CHUNKS_LIVE_RTORRENT_PASS"]
    )
    return urllib.request.build_opener(
        NoRedirect(),
        urllib.request.HTTPDigestAuthHandler(password_manager),
        urllib.request.HTTPBasicAuthHandler(password_manager),
    )


def call(opener, method, *args):
    if method not in ALLOWED:
        raise ValueError("not a read-only RPC method")
    request = urllib.request.Request(
        RPC,
        data=xmlrpc.client.dumps(args, methodname=method, allow_none=False).encode("utf-8"),
        headers={"Content-Type": "text/xml; charset=utf-8", "Accept": "text/xml"},
        method="POST",
    )
    with opener.open(request, timeout=15) as response:
        if response.status != 200 or response.geturl() != RPC:
            raise ValueError("unexpected HTTP response")
        raw = response.read(256_001)
    if len(raw) > 256_000:
        raise ValueError("oversized XML-RPC reply")
    values, _ = xmlrpc.client.loads(raw)
    return values[0] if values else None


def evidence(opener, method, hash_value):
    try:
        return call(opener, method, hash_value), "ok"
    except xmlrpc.client.Fault:
        return None, "fault"
    except Exception:
        return None, "error"


def boolean_number(data):
    return "yes" if isinstance(data, int) and data > 0 else "no" if isinstance(data, int) else "unknown"


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise ValueError("not a first-attempt trusted-main run")
    if not os.environ.get("CHUNKS_LIVE_RTORRENT_USER") or not os.environ.get("CHUNKS_LIVE_RTORRENT_PASS"):
        raise ValueError("no protected credentials")
    opener = rpc_opener()
    try:
        inventory = call(opener, "download_list")
        indexed = {str(h).lower() for h in inventory}
        print("MAGNET_FORENSICS inventory=ok available_hashes=" + str(len(indexed)))
    except Exception:
        indexed = None
        print("MAGNET_FORENSICS inventory=unavailable")
    try:
        default = call(opener, "directory.default")
    except Exception:
        default = None
    for label, url in SOURCES:
        try:
            target, expected_name, expected_size = source_hash(url)
        except Exception:
            print("MAGNET_FORENSICS source=" + label + " upstream=unavailable")
            continue
        state = {}
        for method in METHODS:
            state[method] = evidence(opener, method, target)
        value = lambda key: state[key][0]
        status = lambda key: state[key][1]
        registered = status("d.hash") == "ok" and str(value("d.hash")).lower() == target
        if indexed is not None:
            registered = registered and target in indexed
        known_name = value("d.name")
        size = value("d.size_bytes")
        expected_filename = url.rsplit("/", 1)[-1].removesuffix(".torrent")
        print(
            "MAGNET_FORENSICS source=" + label +
            " upstream=ok registered=" + str(registered).lower() +
            " name_matches_source_filename=" + str(known_name == expected_filename).lower() +
            " name_matches_metainfo=" + str(known_name == expected_name).lower() +
            " size_matches_metainfo=" + str(size == expected_size).lower() +
            " size_class=" + ("under-1mb" if isinstance(size, int) and size < 1_048_576 else "over-1mb" if isinstance(size, int) else "unknown") +
            " metadata_size_positive=" + boolean_number(size) +
            " started=" + boolean_number(value("d.state")) +
            " active=" + boolean_number(value("d.is_active")) +
            " open=" + boolean_number(value("d.is_open")) +
            " received=" + boolean_number(value("d.down.total")) +
            " verified=" + boolean_number(value("d.completed_bytes")) +
            " connected_peers=" + boolean_number(value("d.peers_connected")) +
            " trackers=" + boolean_number(value("d.tracker_size")) +
            " directory_equals_default=" + str(
                isinstance(value("d.directory"), str) and
                isinstance(default, str) and
                value("d.directory").rstrip("/") == default.rstrip("/")
            ).lower()
        )
        # No hostname, tracker, path, private data, credentials, or fault text.
        print(
            "MAGNET_FORENSICS_METHODS source=" + label + " " +
            " ".join(method.replace(".", "_") + "=" + status(method) for method in METHODS) +
            " ignore_commands=" + (str(value("d.ignore_commands")) if isinstance(value("d.ignore_commands"), int) else "unknown") +
            " mode=" + (str(value("d.mode")) if isinstance(value("d.mode"), int) else "unknown")
        )
    print("MAGNET_FORENSICS completed; zero mutation RPCs issued.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Never print exception URLs, request bodies, realm, headers, secrets.
        print("MAGNET_FORENSICS fatal=transport-or-preflight-error")
        sys.exit(1)
