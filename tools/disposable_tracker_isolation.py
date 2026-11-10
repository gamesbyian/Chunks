"""Keep disposable rTorrent real-publisher peer tests isolated from public swarms.

The original public v1 info dictionary and all its piece hashes survive
byte-for-byte. Only top-level tracker/discovery metadata changes to a
non-listening loopback tracker; no payload, infohash or source-byte evidence is
fabricated. Protected-provider/public-discovery tests must use the original
untouched metainfo instead.
"""
import hashlib

if __package__:
    from .public_fixture_collector import bstr, parse_bencode
else:
    from public_fixture_collector import bstr, parse_bencode

LOOPBACK_TRACKER = b"http://127.0.0.1:1/announce"


def isolated_public_v1_metainfo(original: bytes) -> bytes:
    root, exact_info = parse_bencode(original)
    info = root[b"info"]
    if b"meta version" in info:
        # BEP52/hybrid piece layers can live outside the v1 info dictionary.
        raise ValueError("Only verified public v1 publisher metainfo can be isolated")
    if type(info.get(b"private", 0)) is not int or info.get(b"private", 0) != 0:
        raise ValueError("Private tracker metadata must never be replaced")
    if not isinstance(root.get(b"announce"), bytes):
        raise ValueError("Original public torrent must have a tracker")
    # Bencode's root keys are canonical in lexicographic byte order.
    result = b"d8:announce" + bstr(LOOPBACK_TRACKER) + b"4:info" + exact_info + b"e"
    parsed, persisted_info = parse_bencode(result)
    if (persisted_info != exact_info or parsed[b"announce"] != LOOPBACK_TRACKER or
            hashlib.sha1(persisted_info).digest() != hashlib.sha1(exact_info).digest()):
        raise ValueError("Disposable peer isolation changed the publisher identity")
    return result
