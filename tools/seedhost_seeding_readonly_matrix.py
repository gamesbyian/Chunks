#!/usr/bin/env python3
"""Owner-authorized, exact-primary Seedhost *read-only* seeding getter matrix.

Manual main-only GitHub Actions opt-in. No rule enforcement, Stop/Start, tracker
URLs, names, paths, exported hashes, throttle values, credentials or artifacts.
A challenge accepted for the exact HTTPS origin and ruTorrent mount is required;
a publicly readable endpoint is insufficient principal identity evidence.
"""
from __future__ import annotations

import os
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from xml.sax.saxutils import escape

HOST = "ianseeds.wood.seedhost.eu"
# The primary ruTorrent mount and the owner's numbered development instances.
INSTANCES = {"primary": "", "2": "2", "3": "3"}


def endpoint_for(instance: str) -> str:
    """The exact HTTPS RPC endpoint of one named instance; anything else is refused."""
    if instance not in INSTANCES:
        raise ProbeRefused("Unknown Seedhost instance")
    return "https://" + HOST + "/rutorrent" + INSTANCES[instance] + "/plugins/httprpc/action.php"


ENDPOINT = endpoint_for("primary")
GETTERS = ("d.up.total", "d.down.total", "d.complete", "d.is_active",
           "d.timestamp.finished", "d.throttle_name")
ALLOWED = set(GETTERS) | {"d.hash", "d.multicall2"}
HASH = re.compile(r"[0-9a-fA-F]{40}\Z")
MAX_XML_BYTES = 1_048_576
MAX_ROWS = 4_096


class ProbeRefused(Exception):
    """No positive read-only evidence. The exception message is never logged."""


class UnsupportedGetter(Exception):
    """The server explicitly reported XML-RPC method-not-found for an optional getter."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def xml_request(method: str, args: tuple[str, ...] = ()) -> bytes:
    if method not in ALLOWED:
        raise ProbeRefused("Method not allowed")
    if method == "d.multicall2":
        if args != ("", "main", "d.hash="):
            raise ProbeRefused("Unexpected inventory arguments")
    elif method == "d.hash" or method in GETTERS:
        if len(args) != 1 or HASH.fullmatch(args[0]) is None:
            raise ProbeRefused("Only an exact observed v1 hash may be queried")
    else:
        raise ProbeRefused("Unknown command shape")
    arguments = "".join("<param><value><string>" + escape(a) +
                        "</string></value></param>" for a in args)
    return ('<?xml version="1.0"?><methodCall><methodName>' + method +
            '</methodName><params>' + arguments + '</params></methodCall>').encode("utf-8")


def unpack_xml(value: ET.Element, depth: int = 0):
    if depth > 5:
        raise ProbeRefused("XML nesting limit")
    children = list(value)
    if not children:
        return value.text or ""
    if len(children) != 1:
        raise ProbeRefused("Invalid XML-RPC value")
    child = children[0]
    if child.tag == "array":
        rows = child.findall("./data/value")
        if len(rows) > MAX_ROWS:
            raise ProbeRefused("Excessive inventory")
        return [unpack_xml(part, depth + 1) for part in rows]
    if child.tag in ("int", "i4", "i8"):
        value = child.text or ""
        if not re.fullmatch(r"-?(0|[1-9][0-9]{0,18})", value):
            raise ProbeRefused("Invalid XML-RPC integer")
        return int(value)
    if child.tag == "string":
        return child.text or ""
    if child.tag == "struct":
        members = child.findall("member")
        if len(members) > 8:
            raise ProbeRefused("Invalid fault structure")
        result = {}
        for member in members:
            name = member.findtext("name")
            part = member.find("value")
            if not name or part is None or name in result:
                raise ProbeRefused("Invalid fault member")
            result[name] = unpack_xml(part, depth + 1)
        return result
    raise ProbeRefused("Unexpected XML-RPC type")


def read_response(body: bytes):
    if not 10 <= len(body) <= MAX_XML_BYTES:
        raise ProbeRefused("Malformed response length")
    if b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
        raise ProbeRefused("DTD/entity refused")
    try:
        root = ET.fromstring(body)
    except ET.ParseError as error:
        raise ProbeRefused("Malformed XML") from error
    if root.tag != "methodResponse":
        raise ProbeRefused("Not an RPC response")
    fault = root.find("./fault/value")
    if fault is not None:
        value = unpack_xml(fault)
        if isinstance(value, dict) and value.get("faultCode") == -32601:
            raise UnsupportedGetter()
        raise ProbeRefused("Unexpected RPC fault")
    value = root.find("./params/param/value")
    if value is None or len(root.findall("./params/param")) != 1:
        raise ProbeRefused("Incomplete RPC response")
    return unpack_xml(value)


class Client:
    def __init__(self, username: str, password: str, endpoint: str = ENDPOINT):
        if not username or not password:
            raise ProbeRefused("Missing protected credential")
        self.endpoint = endpoint
        auth = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        auth.add_password(None, endpoint, username, password)
        self.opener = urllib.request.build_opener(
            NoRedirect(),
            urllib.request.HTTPDigestAuthHandler(auth),
            urllib.request.HTTPBasicAuthHandler(auth))

        # Must first observe a challenge on THIS exact protected endpoint.
        # No anonymous 200 can prove a principal; no automatic redirect.
        req = urllib.request.Request(
            endpoint, data=xml_request("d.multicall2", ("", "main", "d.hash=")),
            headers={"Content-Type": "text/xml"}, method="POST")
        anonymous = urllib.request.build_opener(NoRedirect())
        try:
            with anonymous.open(req, timeout=12):
                raise ProbeRefused("No challenge: account identity not established")
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise ProbeRefused("Unexpected preflight response") from None
            challenges = error.headers.get_all("WWW-Authenticate", [])
            if not any(re.match(r"\s*(Basic|Digest)\s", text, re.I)
                       for text in challenges):
                raise ProbeRefused("Unsupported auth challenge")

    def __call__(self, method: str, *args: str):
        req = urllib.request.Request(
            self.endpoint, data=xml_request(method, args),
            headers={"Content-Type": "text/xml"}, method="POST")
        try:
            with self.opener.open(req, timeout=12) as response:
                if response.status != 200 or response.url != self.endpoint:
                    raise ProbeRefused("Wrong endpoint or response")
                data = response.read(MAX_XML_BYTES + 1)
            return read_response(data)
        except urllib.error.HTTPError as error:
            raise ProbeRefused("HTTP request failed") from None
        except urllib.error.URLError:
            raise ProbeRefused("Transport error") from None


@dataclass(frozen=True)
class Report:
    sampled: bool
    getters: tuple[tuple[str, str], ...]

    def public_line(self) -> str:
        # No hash, name, tracker, server path, user, counter or throttle value.
        if not self.sampled:
            return "CHUNKS_REMOTE_SEEDING_READONLY: no eligible v1 inventory sample; no authority to enforce"
        fields = " ".join(name + "=" + result for name, result in self.getters)
        return ("CHUNKS_REMOTE_SEEDING_READONLY: sample=1 " + fields +
                " policy_mutations=disabled independent_per_torrent_cap=unproven")


def inspect(rpc) -> Report:
    rows = rpc("d.multicall2", "", "main", "d.hash=")
    if not isinstance(rows, list) or len(rows) > MAX_ROWS:
        raise ProbeRefused("Invalid complete view")
    hashes = []
    seen = set()
    for row in rows:
        if not isinstance(row, list) or len(row) != 1 or not isinstance(row[0], str):
            raise ProbeRefused("Invalid inventory row")
        identity = row[0]
        if HASH.fullmatch(identity) is None:
            raise ProbeRefused("Unsupported observed identity")
        identity = identity.lower()
        if identity in seen:
            raise ProbeRefused("Duplicate inventory identity")
        seen.add(identity)
        hashes.append(identity)
    if not hashes:
        return Report(False, ())
    exact = hashes[0]

    def same_hash():
        value = rpc("d.hash", exact)
        if not isinstance(value, str) or value.lower() != exact:
            raise ProbeRefused("Identity changed during read-only probe")

    same_hash()
    results = []
    for getter in GETTERS:
        try:
            value = rpc(getter, exact)
            if getter in ("d.up.total", "d.down.total"):
                if type(value) is not int or not 0 <= value <= 2**63 - 1:
                    raise ProbeRefused("Invalid nonnegative counter")
            elif getter in ("d.complete", "d.is_active"):
                if type(value) is not int or value not in (0, 1):
                    raise ProbeRefused("Invalid boolean getter")
            elif getter == "d.timestamp.finished":
                if type(value) is not int or not 0 <= value <= 253402300799:
                    raise ProbeRefused("Invalid completion timestamp")
            elif getter == "d.throttle_name":
                if not isinstance(value, str) or len(value) > 128 or any(
                    ord(char) < 32 or ord(char) == 127 for char in value
                ):
                    raise ProbeRefused("Invalid throttle observation")
            results.append((getter, "supported"))
        except UnsupportedGetter:
            results.append((getter, "unsupported"))
    same_hash()
    return Report(True, tuple(results))


def main():
    # Run only as an explicitly opt-in step of trusted-main manual workflow.
    if os.environ.get("CHUNKS_APPROVED_SEEDING_READONLY") != "true":
        raise ProbeRefused("No manual consent")
    # The instance label is public (no URL is printed); an unknown one is refused.
    instance = os.environ.get("RT_INSTANCE", "primary") or "primary"
    try:
        report = inspect(Client(
            os.environ.get("RT_USER", ""),
            os.environ.get("RT_PASS", ""), endpoint_for(instance)))
        print(report.public_line() + " instance=" + instance)
    except Exception as error:
        # Never print exception text: endpoints, hash and auth headers can be
        # carried inside urllib/HTTP errors or unexpected parser failures.
        print("CHUNKS_REMOTE_SEEDING_READONLY: unavailable (" +
              type(error).__name__ + "); no authority to enforce")
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
