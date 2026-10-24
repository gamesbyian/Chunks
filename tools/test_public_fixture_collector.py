"""Offline provenance, integrity and hostile-input tests. No external network."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import public_fixture_collector as f

def enc(value):
    if isinstance(value, bool):
        raise TypeError("no booleans")
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(enc(x) for x in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(enc(k) + enc(v) for k, v in sorted(value.items())) + b"e"
    raise TypeError(value)

class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.info = {b"name": b"Public test.txt", b"length": 5,
                     b"piece length": 16384, b"pieces": b"\x00" * 20}
        self.original = enc({b"info": self.info,
                             b"announce": b"https://bt1.archive.org:6969/announce",
                             b"comment": b"session secret to remove"})
        self.license_url = "https://creativecommons.org/licenses/by/4.0/"

    def test_infohash_and_magnet_are_from_original_info_bytes(self):
        safe, desc = f.torrent_summary(self.original, 100)
        original_root, original_info = f.parse_bencode(self.original)
        self.assertEqual(hashlib.sha1(original_info).hexdigest(), desc["infohash_v1"])
        self.assertEqual("Public test.txt", desc["name"])
        self.assertIn("magnet:?xt=urn%3Abtih%3A", desc["magnet"])
        self.assertEqual(5, desc["total_bytes"])
        self.assertNotIn(b"session secret", safe)
        self.assertIn(b"bt1.archive.org", safe)
        self.assertEqual(original_info, f.parse_bencode(safe)[1])
        self.assertEqual(desc["infohash_v1"], f.torrent_summary(safe, 100)[1]["infohash_v1"])

    def test_untrusted_tracker_stripped_without_changing_infohash(self):
        raw = enc({b"info": self.info, b"announce": b"https://tracker.example/secret?passkey=abc"})
        safe, desc = f.torrent_summary(raw, 100)
        self.assertNotIn(b"passkey", safe)
        self.assertNotIn(b"example", safe)
        self.assertFalse(desc["tracker_retained"])
        self.assertEqual(f.parse_bencode(raw)[1], f.parse_bencode(safe)[1])

    def test_unsafe_name_path_and_size_rejected(self):
        for name in (b"..", b"/absolute", b"../escape", b"folder\\escape", b"bad\nname"):
            with self.subTest(name=name):
                payload = enc({b"info": {**self.info, b"name": name}})
                with self.assertRaises(ValueError):
                    f.torrent_summary(payload, 100)
        with self.assertRaises(ValueError):
            f.torrent_summary(self.original, 4)
        bad = dict(self.info)
        bad.pop(b"length")
        bad[b"files"] = [{b"length": 3, b"path": [b"ok", b".."]}]
        with self.assertRaises(ValueError):
            f.torrent_summary(enc({b"info": bad}), 100)

    def test_bencode_guards(self):
        invalid = (b"d4:infod1:a1:beeEXTRA", b"d4:infodi1e1:bee",
                   b"d4:infod1:a1:b1:a1:cee", b"d4:infod1:ai03eee",
                   b"d4:info" + b"l" * 30 + b"e" * 32)
        for raw in invalid:
            with self.subTest(raw=raw[:30]):
                with self.assertRaises(ValueError):
                    f.parse_bencode(raw)

    def test_real_archive_metadata_requires_license_and_real_torrent(self):
        metadata = {
            "metadata": {"title": "Public fixture", "licenseurl": self.license_url},
            "files": [{"name": "example_archive.torrent", "format": "Archive BitTorrent",
                       "source": "derivative"}]
        }
        def fake_fetch(url, cap):
            return (json.dumps(metadata).encode() if "/metadata/" in url else self.original)
        with patch.object(f, "fetch", side_effect=fake_fetch):
            manifest, blob = f.archive_fixture("test-public-example", 100)
        self.assertEqual("internet-archive", manifest["source"])
        self.assertEqual(manifest["torrent"]["infohash_v1"],
                         f.torrent_summary(blob, 100)[1]["infohash_v1"])
        metadata["metadata"]["licenseurl"] = ""
        with patch.object(f, "fetch", side_effect=fake_fetch):
            with self.assertRaises(ValueError):
                f.archive_fixture("test-public-example", 100)

    def test_wikimedia_reference_is_not_fabricated_torrent(self):
        data = {"query": {"pages": {"1": {"imageinfo": [{
            "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Public.png",
            "size": 100, "mime": "image/png",
            "extmetadata": {"LicenseUrl": {"value": self.license_url}}
        }]}}}}
        with patch.object(f, "fetch", return_value=json.dumps(data).encode()):
            item = f.commons_fixture("Public.png")
        self.assertIsNone(item["torrent"])
        self.assertEqual("no_official_torrent_claimed", item["torrent_status"])
        self.assertEqual("image/png", item["asset"]["mime"])
        data["query"]["pages"]["1"]["imageinfo"][0]["extmetadata"]["LicenseUrl"]["value"] = "unknown"
        with patch.object(f, "fetch", return_value=json.dumps(data).encode()):
            with self.assertRaises(ValueError):
                f.commons_fixture("Public.png")

    def test_commons_bytes_generate_real_infohash_and_webseed(self):
        manifest = {"asset": {"url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Public.ogg",
                              "bytes": 5}}
        raw, name = f.create_commons_single_file_torrent(manifest, b"hello")
        self.assertEqual("Public.ogg", name)
        self.assertEqual("locally_generated_unseeded_webseed_unverified", manifest["torrent_status"])
        self.assertEqual(hashlib.sha256(b"hello").hexdigest(), manifest["asset"]["sha256"])
        root, info_raw = f.parse_bencode(raw)
        self.assertEqual(5, root[b"info"][b"length"])
        self.assertEqual("https://upload.wikimedia.org/wikipedia/commons/a/ab/Public.ogg",
                         root[b"url-list"].decode())
        self.assertEqual(hashlib.sha1(info_raw).hexdigest(), manifest["torrent"]["infohash_v1"])
        self.assertTrue(manifest["torrent"]["magnet"].startswith("magnet:?xt="))

    def test_commons_generation_rejects_length_mismatch_and_secret_query(self):
        m = {"asset": {"url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Public.png",
                       "bytes": 10}}
        with self.assertRaises(ValueError):
            f.create_commons_single_file_torrent(m, b"short")
        m["asset"]["bytes"] = 5
        m["asset"]["url"] += "?token=unsafe"
        with self.assertRaises(ValueError):
            f.create_commons_single_file_torrent(m, b"short")

    def test_public_source_attribution_preserved_as_plain_text(self):
        self.assertEqual("JesseW & Contributors",
                         f.attribution_text('<a href="/wiki/User:JesseW">JesseW &amp; Contributors</a>'))
        self.assertEqual("Alpha; Beta", f.attribution_text(["Alpha", "Beta"]))
        response = {"query": {"pages": {"1": {"imageinfo": [{
            "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg",
            "size": 104793, "mime": "audio/ogg",
            "extmetadata": {
                "LicenseUrl": {"value": "https://creativecommons.org/licenses/by-sa/3.0/"},
                "LicenseShortName": {"value": "CC BY-SA 3.0"},
                "Artist": {"value": "<span>JesseW</span>"},
                "Attribution": {"value": "JesseW &amp; Commons"}
            }
        }]}}}}
        with patch.object(f, "fetch", return_value=json.dumps(response).encode()):
            manifest = f.commons_fixture("File:Example.ogg")
        self.assertEqual("JesseW", manifest["attribution"]["author"])
        self.assertEqual("JesseW & Commons", manifest["attribution"]["credit"])
        self.assertEqual("CC BY-SA 3.0", manifest["attribution"]["license_short_name"])
        self.assertEqual(104793, manifest["asset"]["bytes"])

    def test_host_and_source_validation(self):
        for url in ("http://archive.org/metadata/a",
                    "https://notarchive.org/download/a",
                    "https://archive.org:444/download/a",
                    "https://user:pass@archive.org/download/a",
                    "https://127.0.0.1/metadata/a"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                f.approved_url(url)
        for ident in ("../../etc/passwd", "..", "", "x?filter=1"):
            with self.assertRaises(ValueError):
                f.archive_fixture(ident, 100)

if __name__ == "__main__":
    unittest.main()
