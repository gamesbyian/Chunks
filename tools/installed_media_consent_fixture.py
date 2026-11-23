#!/usr/bin/env python3
"""Local-only HTTPS fixture for the installed second-Add consent journey."""

import argparse
from collections import Counter
from hashlib import sha1
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
from threading import Lock
from urllib.parse import parse_qs, urlsplit

SOURCE_HOST = "example.com"
API_HOST = "api.tvmaze.com"
ART_HOST = "static.tvmaze.com"
SHOWS = {
    "first": ("Babylon 5", "Babylon.5.S02E07.mkv", 475),
    "excluded": ("Fringe", "Fringe.S02E07.mkv", 158),
    "control": ("Firefly", "Firefly.S01E01.mkv", 180),
}


def torrent(fixture):
    name = SHOWS[fixture][1].encode()
    piece = sha1(fixture.encode()).digest()
    info = (b"d6:lengthi" + str(len(fixture)).encode() + b"e4:name" +
            str(len(name)).encode() + b":" + name +
            b"12:piece lengthi16384e6:pieces20:" + piece + b"7:privatei1ee")
    return b"d4:info" + info + b"e", sha1(info).hexdigest()


class Handler(BaseHTTPRequestHandler):
    # No raw requests, titles, headers, system account details, or socket addresses in logs.
    def log_message(self, *_args):
        pass

    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def answer(self, body, mime="application/json", status=200):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_CONNECT(self):
        host = self.path.removesuffix(":443")
        if self.path != f"{host}:443" or host not in {SOURCE_HOST, API_HOST, ART_HOST}:
            self.answer(b"", status=403)
            return
        self.send_response_only(200, "Connection Established")
        self.end_headers()
        with self.server.tls.wrap_socket(self.connection, server_side=True) as tunnel:
            self.__class__(tunnel, host, self.server)

    def do_GET(self):
        host = self.client_address
        if isinstance(host, tuple):
            if self.path == "/counts":
                with self.server.lock:
                    counts = dict(self.server.counts)
                self.answer(json.dumps(counts).encode())
            elif self.path.removeprefix("/hash/") in SHOWS and self.path.startswith("/hash/"):
                self.answer(torrent(self.path.removeprefix("/hash/"))[1].encode(), "text/plain")
            else:
                self.answer(b"", status=404)
            return

        url = urlsplit(self.path)
        if host == SOURCE_HOST:
            if url.path == "/signed.torrent" and url.query == "access=fixture%2Braw%2Ftoken":
                self.count("source_signed_share")
                self.answer(torrent("control")[0], "application/x-bittorrent")
                return
            for fixture in SHOWS:
                if url.path == f"/{fixture}.torrent" and not url.query:
                    self.count("source_" + fixture)
                    self.answer(torrent(fixture)[0], "application/x-bittorrent")
                    return
            self.count("source_unexpected")
        if host == ART_HOST:
            self.count("artwork")
        if host == API_HOST:
            for fixture, (title, _, show_id) in SHOWS.items():
                if url.path == "/search/shows" and parse_qs(url.query) == {"q": [title]}:
                    self.count("search_" + fixture)
                    self.answer(json.dumps([{"show": {
                        "id": show_id, "name": title, "weight": 100,
                    }}]).encode())
                    return
                if url.path == f"/shows/{show_id}/episodebynumber":
                    self.count("episode_" + fixture)
                    self.answer(b'{"name":"Fixture episode"}')
                    return
            self.count("metadata_unexpected")
        self.answer(b"", status=404)

    def count(self, category):
        with self.server.lock:
            self.server.counts[category] += 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cert")
    parser.add_argument("key")
    parser.add_argument("port_file", type=Path)
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        server.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        server.tls.load_cert_chain(args.cert, args.key)
        server.lock = Lock()
        server.counts = Counter()
        args.port_file.write_text(str(server.server_port))
        server.serve_forever()


if __name__ == "__main__":
    main()
