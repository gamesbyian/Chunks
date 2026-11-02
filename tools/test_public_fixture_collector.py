"""Offline provenance, integrity and hostile-input tests. No external network."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import public_fixture_collector as f
import collect_linuxtracker_magnets as linux

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

    def test_wikimedia_api_tracking_query_is_removed_before_webseed(self):
        clean = f.clean_commons_asset_url(
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg"
            "?utm_source=commons&utm_campaign=download&utm_content=audio")
        self.assertEqual(
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg", clean)
        for bad in (
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg?token=secret",
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg?utm_source=x&passkey=y",
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Example.ogg?signature=z",
        ):
            with self.assertRaises(ValueError):
                f.clean_commons_asset_url(bad)

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

    def debian_responses(self, torrent, sums_hash=None, index_names=("debian-13.7.0-amd64-netinst.iso.torrent",)):
        index = "".join('<a href="%s">x</a> <a href="debian-edu-13.7.0-amd64-netinst.iso.torrent">' % n
                        for n in index_names).encode()
        sums = ("%s  debian-13.7.0-amd64-netinst.iso.torrent\n" % (sums_hash or hashlib.sha256(torrent).hexdigest())
                + "ab" * 32 + "  debian-13.7.0-amd64-netinst.iso\n").encode()
        def fake(url, cap):
            return {f.DEBIAN_BT_CD: index, f.DEBIAN_BT_CD + "SHA256SUMS": sums,
                    f.DEBIAN_BT_CD + "debian-13.7.0-amd64-netinst.iso.torrent": torrent}[url]
        return fake

    def test_debian_netinst_checked_against_published_sums_and_webseeds_stripped(self):
        info = {b"name": b"debian-13.7.0-amd64-netinst.iso", b"length": 5,
                b"piece length": 262144, b"pieces": b"\x01" * 20}
        torrent = enc({b"announce": b"http://bttracker.debian.org:6969/announce", b"info": info,
                       b"url-list": [b"https://cdimage.debian.org/x.iso"], b"comment": b"c"})
        with patch.object(f, "fetch", self.debian_responses(torrent)):
            manifest, safe = f.debian_fixture(1 << 30)
        self.assertEqual(manifest["torrent"]["infohash_v1"], hashlib.sha1(enc(info)).hexdigest())
        self.assertTrue(manifest["torrent"]["tracker_retained"])
        self.assertEqual(manifest["published_sha256"]["iso"], "ab" * 32)
        self.assertNotIn(b"url-list", safe)
        self.assertIn(b"bttracker.debian.org", safe)
        with patch.object(f, "fetch", self.debian_responses(torrent, sums_hash="00" * 32)), \
                self.assertRaises(ValueError):
            f.debian_fixture(1 << 30)
        with patch.object(f, "fetch", self.debian_responses(torrent, index_names=(
                "debian-13.7.0-amd64-netinst.iso.torrent", "debian-13.8.0-amd64-netinst.iso.torrent"))), \
                self.assertRaises(ValueError):
            f.debian_fixture(1 << 30)
        foreign = enc({b"announce": b"http://evil.example:6969/announce", b"info": info})
        with patch.object(f, "fetch", self.debian_responses(foreign)), self.assertRaises(ValueError):
            f.debian_fixture(1 << 30)

    def test_archive_mode_does_not_retain_debian_tracker(self):
        raw = enc({b"info": self.info, b"announce": b"http://bttracker.debian.org:6969/announce"})
        safe, details = f.torrent_summary(raw, 100)
        self.assertFalse(details["tracker_retained"])
        self.assertNotIn(b"debian", safe)

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


    def test_bep12_announce_list_takes_precedence_without_changing_infohash(self):
        approved = b"https://bt1.archive.org:6969/announce"
        other_approved = b"https://bt2.archive.org:6969/announce"
        untrusted = b"https://untrusted.example/announce?private=redacted"
        cases = [
            # A top-level approved URL must not override an untrusted tier.
            ("untrusted-tier", approved, [[untrusted]], None),
            # A valid tier wins over the unrelated top-level fallback.
            ("approved-tier", other_approved, [[untrusted, approved], [other_approved]], approved),
            # Even an empty announce-list suppresses the top-level fallback.
            ("empty-tier", approved, [], None),
            # Malformed-but-bencoded tier data cannot activate the fallback.
            ("malformed-tier", approved, b"invalid", None),
            # A well-formed tier with a non-list member is not a valid tracker.
            ("invalid-tier-member", approved, [b"not-a-tier"], None),
        ]
        for label, top, tiers, expected in cases:
            with self.subTest(case=label):
                original = enc({b"announce": top, b"announce-list": tiers, b"info": self.info})
                safe, detail = f.torrent_summary(original, 100)
                parsed, _ = f.parse_bencode(safe)
                self.assertEqual(f.parse_bencode(original)[1], f.parse_bencode(safe)[1])
                self.assertEqual(hashlib.sha1(enc(self.info)).hexdigest(), detail["infohash_v1"])
                self.assertEqual(expected, parsed.get(b"announce"))
                self.assertEqual(expected is not None, detail["tracker_retained"])
                self.assertNotIn(b"announce-list", parsed)
                self.assertNotIn(b"untrusted.example", safe)
                self.assertNotIn(b"private=redacted", safe)

    def test_bep12_preserves_single_approved_announce_when_list_absent(self):
        approved = b"https://bt1.archive.org:6969/announce"
        original = enc({b"announce": approved, b"info": self.info})
        safe, detail = f.torrent_summary(original, 100)
        self.assertEqual(approved, f.parse_bencode(safe)[0].get(b"announce"))
        self.assertTrue(detail["tracker_retained"])

    def test_bep12_tier_sanitization_refuses_path_tokens_bad_ports_and_invalid_utf8(self):
        candidates = (
            b"https://bt1.archive.org:6969/announce/session-token",
            b"https://bt1.archive.org:6969/announce?passkey=redacted",
            b"https://bt1.archive.org:invalid/announce",
            b"http://[bad/announce",
            b"https://bt1.archive.org:6969/announce#secret",
            b"\xff\xfe",
        )
        for candidate in candidates:
            with self.subTest(candidate_class="untrusted URL"):
                original = enc({b"announce": b"https://bt1.archive.org:6969/announce",
                                b"announce-list": [[candidate]],
                                b"info": self.info})
                safe, detail = f.torrent_summary(original, 100)
                self.assertFalse(detail["tracker_retained"])
                self.assertEqual({b"info"}, set(f.parse_bencode(safe)[0]))
                self.assertEqual(f.parse_bencode(original)[1], f.parse_bencode(safe)[1])


    def test_bep52_hybrid_piece_layers_are_not_silently_discarded(self):
        # Hybrid metainfo has a v1 pieces array *and* v2 Merkle metadata.
        # "piece layers" is outside info, so copying info alone can produce
        # a correct-looking v1/v2 hash with an unusable v2 payload.
        info = {**self.info, b"meta version": 2,
                b"file tree": {b"Public test.txt": {
                    b"": {b"length": 5, b"pieces root": b"r" * 32}}}}
        original = enc({b"info": info, b"piece layers": {b"r" * 32: b"h" * 64},
                        b"announce": b"https://bt1.archive.org:6969/announce"})
        with self.assertRaisesRegex(ValueError, "BEP 52.*piece layers"):
            f.torrent_summary(original, 100)
        self.assertNotEqual(hashlib.sha1(enc(self.info)).hexdigest(),
                            hashlib.sha1(f.parse_bencode(original)[1]).hexdigest())

    def test_bep52_v2_only_or_unknown_metaversion_fails_explicitly(self):
        for version in (2, 3):
            with self.subTest(version=version):
                info = {b"name": b"Public test.txt", b"piece length": 16384,
                        b"meta version": version,
                        b"file tree": {b"Public test.txt": {
                            b"": {b"length": 5, b"pieces root": b"x" * 32}}}}
                source = enc({b"info": info})
                with self.assertRaisesRegex(ValueError, "BEP 52.*piece layers"):
                    f.torrent_summary(source, 100)


class LinuxTrackerBep12Test(unittest.TestCase):
    primary = b"udp://tracker.linuxtracker.org:6969/announce"
    backup = b"udp://tracker.opentrackr.org:1337/announce"

    def test_tier_order_and_source_announce_precedence(self):
        root = {b"announce": self.primary,
                b"announce-list": [
                    [b"http://untrusted.example/announce?private=redacted", self.backup],
                    [self.primary, self.backup]]}
        self.assertEqual([self.backup.decode(), self.primary.decode()],
                         linux.safe_trackers(root))

    def test_empty_malformed_and_untrusted_tiers_cannot_revive_announce(self):
        cases = ([], b"bad-list", [b"not-a-tier"], [[b"\xff"]],
                 [[b"https://tracker.linuxtracker.org/announce/private"]],
                 [[b"https://tracker.linuxtracker.org/announce?passkey=redacted"]],
                 [[b"https://tracker.linuxtracker.org:bad/announce"]],
                 [[b"https://tracker.linuxtracker.org:0/announce"]],
                 [[b"https://tracker.linuxtracker.org:99999/announce"]],
                 [[self.primary + b"\\x00"]],
                 [[self.primary + b"x" * 2049]])
        for tiers in cases:
            with self.subTest(shape=type(tiers).__name__):
                self.assertEqual([], linux.safe_trackers(
                    {b"announce": self.primary, b"announce-list": tiers}))

    def test_single_announce_fallback_remains_usable_without_list(self):
        self.assertEqual([self.primary.decode()],
                         linux.safe_trackers({b"announce": self.primary}))
        self.assertEqual([], linux.safe_trackers(
            {b"announce": b"https://untrusted.example/announce"}))

    def test_tier_duplicates_and_output_are_bounded_without_network(self):
        many = [[self.primary] * 30, [self.backup] * 30]
        self.assertEqual([self.primary.decode(), self.backup.decode()],
                         linux.safe_trackers({b"announce-list": many}))



class V1PieceCountTest(unittest.TestCase):
    def setUp(self):
        self.info = {b"name": b"Public test.txt", b"length": 5,
                     b"piece length": 16384, b"pieces": b"\\x00" * 20}

    def test_v1_piece_hash_count_must_match_payload_not_just_be_multiple_of_20(self):
        cases = (
            {**self.info, b"piece length": 2},
            {**self.info, b"length": 16385},
            {**self.info, b"length": 0},
            {**self.info, b"piece length": 0},
            {**self.info, b"piece length": -1},
            {**self.info, b"piece length": 65 * 1024 * 1024},
            {**self.info, b"pieces": b"h" * 21},
            {key: value for key, value in self.info.items() if key != b"piece length"},
        )
        for info in cases:
            with self.subTest(piece_length=info.get(b"piece length"),
                              payload_length=info.get(b"length")):
                with self.assertRaisesRegex(ValueError, "piece"):
                    f.torrent_summary(enc({b"info": info}), 100000)

    def test_v1_exact_full_and_partial_piece_identity_roundtrip(self):
        info = {**self.info, b"length": 16385, b"pieces": b"h" * 40}
        source = enc({b"info": info, b"announce": b"https://bt1.archive.org:6969/announce"})
        safe, detail = f.torrent_summary(source, 100000)
        self.assertEqual(16385, detail["total_bytes"])
        self.assertEqual(hashlib.sha1(enc(info)).hexdigest(), detail["infohash_v1"])
        self.assertEqual(f.parse_bencode(source)[1], f.parse_bencode(safe)[1])
        self.assertTrue(detail["tracker_retained"])

if __name__ == "__main__":
    unittest.main()
