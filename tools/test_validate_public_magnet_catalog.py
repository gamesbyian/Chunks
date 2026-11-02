"""Offline source-catalog identity integrity tests; no network or real transfers."""
import contextlib
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlencode, quote

from validate_public_magnet_catalog import validate, license_evidence_tier


class PublicMagnetCatalogIdentityTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.catalog = Path(self.folder.name) / "catalog.json"
        self.magnets = Path(self.folder.name) / "magnets.txt"
        self.h1 = "a" * 40
        self.item = {
            "magnet": "magnet:?" + urlencode([
                ("xt", "urn:btih:" + self.h1),
                ("dn", "Public Demo.txt"),
                ("tr", "udp://tracker.opentrackr.org:1337/announce"),
            ]),
            "infohash_v1": self.h1,
            "infohash_v2": None,
            "name": "Public Demo.txt",
            "total_bytes": 5,
            "source_url": "https://archive.org/details/public-demo",
        }

    def check(self, item, accepted=False):
        self.catalog.write_text(json.dumps({
            "schema": "chunks.public-magnet-catalog.v1",
            "requested": 1, "verified_metadata_count": 1,
            "fixtures": [item],
        }), encoding="utf-8")
        self.magnets.write_text(item["magnet"] + "\n", encoding="utf-8")
        with contextlib.redirect_stdout(StringIO()):
            if accepted:
                self.assertEqual(1, validate(self.catalog, self.magnets))
            else:
                with self.assertRaises(ValueError):
                    validate(self.catalog, self.magnets)

    def test_source_license_claims_are_tiered_not_equated_to_peer_or_rights_proof(self):
        variants = (
            ("https://archive.org/details/cc", "https://creativecommons.org/licenses/by-sa/3.0/ca/",
             "cc_license_claim_not_rightsholder_verified"),
            ("https://archive.org/details/cc0", "https://creativecommons.org/publicdomain/zero/1.0/",
             "cc0_declaration_not_rightsholder_verified"),
            ("https://archive.org/details/pdm", "http://creativecommons.org/publicdomain/mark/1.0/",
             "public_domain_mark_claim_not_license"),
            ("https://archive.org/details/old", "http://creativecommons.org/licenses/publicdomain/",
             "retired_us_public_domain_certification"),
            ("https://linuxtracker.org/torrents/abc", "Public Linux distribution torrent; per-release licence needs review",
             "linux_distribution_release_review"),
            ("https://archive.org/details/unverified", "https://example.com/?next=https://creativecommons.org/licenses/by/4.0/",
             "missing_or_unrecognized_license_claim"),
            ("https://archive.org/details/no-claim", None,
             "missing_or_unrecognized_license_claim"),
        )
        for source, license_text, expected in variants:
            with self.subTest(evidence=expected):
                self.assertEqual(expected, license_evidence_tier(
                    {"source_url": source, "license": license_text}))

    def test_license_evidence_report_is_advisory_and_never_affects_identity(self):
        fixture = dict(self.item)
        fixture["license"] = "http://creativecommons.org/licenses/publicdomain/"
        self.catalog.write_text(json.dumps({
            "schema": "chunks.public-magnet-catalog.v1",
            "requested": 1, "verified_metadata_count": 1,
            "fixtures": [fixture],
        }), encoding="utf-8")
        self.magnets.write_text(fixture["magnet"] + "\\n", encoding="utf-8")
        output = StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, validate(self.catalog, self.magnets))
        report = json.loads(output.getvalue())
        self.assertEqual(1, report["count"])
        self.assertEqual({"retired_us_public_domain_certification": 1},
                         report["license_claim_evidence_tiers"])
        self.assertIn("no rights-holder verification", report["license_proof_boundary"])

    def test_legacy_license_classification_does_not_trust_redirect_spoofing(self):
        for spoof in (
            "https://creativecommons.org@evil.example/licenses/publicdomain/",
            "https://evil.example/?next=http://creativecommons.org/licenses/publicdomain/",
            "https://creativecommons.org/licenses/publicdomain/?token=unsafe",
            "https://creativecommons.org/publicdomain/mark/1.0/?token=unsafe",
        ):
            with self.subTest(spoof_class="untrusted legacy URI"):
                self.assertEqual("missing_or_unrecognized_license_claim",
                                 license_evidence_tier({**self.item, "license": spoof}))

    def test_verified_v1_and_hybrid_magnet_identity(self):
        self.check(self.item, accepted=True)
        h2 = "b" * 64
        item = dict(self.item)
        item["infohash_v2"] = h2
        item["magnet"] = self.item["magnet"] + "&" + urlencode(
            {"xt": "urn:btmh:1220" + h2})
        self.check(item, accepted=True)

    def test_conflicting_duplicate_or_unverified_exact_topics(self):
        variants = (
            "&" + urlencode({"xt": "urn:btih:" + "b" * 40}),
            "&" + urlencode({"xt": "urn:btih:" + self.h1}),
            "&" + urlencode({"xt": "urn:btmh:1220" + "b" * 64}),
            "&xt=",
        )
        for extra in variants:
            with self.subTest(extra=extra[:32]):
                self.check({**self.item, "magnet": self.item["magnet"] + extra})
        self.check({**self.item, "magnet": self.item["magnet"].replace(
            "urn%3Abtih", "urn%3Abtmh")})

    def test_conflicting_or_missing_display_name(self):
        self.check({**self.item, "magnet": self.item["magnet"] + "&dn=Fake"})
        self.check({**self.item, "magnet": self.item["magnet"].split("&dn=")[0]})
        self.check({**self.item, "name": "Different source display name"})

    def test_forbid_unreviewed_seed_or_peer_parameters(self):
        for extra in ("&ws=https%3A%2F%2Ffake.example%2Fpayload",
                      "&x.pe=127.0.0.1%3A6881",
                      "&xs=https%3A%2F%2Ffake.example%2Fmetadata"):
            with self.subTest(extra=extra[:16]):
                self.check({**self.item, "magnet": self.item["magnet"] + extra})

    def test_control_bytes_in_raw_magnet_are_rejected(self):
        for suffix in ("\n", "\r", "\t", "\x00", "\x7f"):
            self.check({**self.item, "magnet": self.item["magnet"] + suffix})


    def test_tracker_raw_control_characters_cannot_be_hidden_by_urlsplit(self):
        for control in ("\\n", "\\r", "\\t", "\\x00", "\\x7f"):
            malicious = "udp://tracker.opentrackr.org:1337/announce" + control
            with self.subTest(control=repr(control)):
                self.check({**self.item, "magnet": self.item["magnet"]
                            + "&tr=" + quote(malicious, safe="")})

    def test_tracker_invalid_and_missing_udp_ports_fail_closed(self):
        for malformed in (
            "udp://tracker.opentrackr.org/announce",
            "udp://tracker.opentrackr.org:0/announce",
            "udp://tracker.opentrackr.org:99999/announce",
            "udp://tracker.opentrackr.org:invalid/announce",
            "https://tracker.opentrackr.org:99999/announce",
        ):
            with self.subTest(url_shape=malformed.split("/")[0]):
                self.check({**self.item, "magnet": self.item["magnet"]
                            + "&tr=" + quote(malformed, safe="")})


if __name__ == "__main__":
    unittest.main()
