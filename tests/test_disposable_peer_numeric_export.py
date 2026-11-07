"""Fail-closed, privacy-bounded export of existing disposable real peer evidence."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.export_disposable_peer_numeric import sanitize, export, MAX_SAMPLES


class DisposablePeerNumericExportTests(unittest.TestCase):
    sha = "a" * 40

    def sample(self):
        return {
            "epoch_ms": 1794016800000, "phase": "payload",
            "verified_bytes": 33554432, "available_pieces": 3,
            "uploaded_bytes": 16384, "upload_rate": 1000, "peer_count": 1,
            "events": {"in_request": 2, "out_unchoke": 1,
                       "raw_peer_address": "private.example"},
            "recent_requests": [{"epoch_ms": 1794016800000, "piece": 2,
                                 "offset": 0, "length": 16384,
                                 "tracker": "https://private.example/passkey"}],
            "peers": [{"flags": 4, "pieces": 10, "upload_queue": 0,
                       "download_queue": 2, "uploaded_bytes": 16384,
                       "ip": "192.0.2.7"}],
            "payload": "SECRET", "source_url": "https://private.example",
        }

    def test_keeps_real_numeric_upload_request_choke_queue_without_private_fields(self):
        result = sanitize({"magnet3": [self.sample()]}, self.sha)
        encoded = json.dumps(result)
        self.assertNotIn("private.example", encoded)
        self.assertNotIn("192.0.2.7", encoded)
        self.assertNotIn("SECRET", encoded)
        self.assertNotIn("source_url", encoded)
        self.assertNotIn("raw_peer_address", encoded)
        self.assertEqual(2, result["fixtures"]["magnet3"][0]["events"]["in_request"])
        self.assertEqual(16384, result["fixtures"]["magnet3"][0]["peers"][0]["uploaded_bytes"])
        self.assertEqual([], result["fixtures"]["magnet"])

    def test_rejects_wrong_sha_missing_or_invalid_numeric_and_unknown_phase(self):
        sample = self.sample()
        with self.assertRaises(ValueError):
            sanitize({"magnet": [sample]}, "bad")
        for altered in (
            dict(sample, uploaded_bytes=-1),
            dict(sample, peer_count=True),
            dict(sample, phase="https://private.example"),
            {key: value for key, value in sample.items() if key != "peer_count"},
            dict(sample, recent_requests="bad"),
            dict(sample, peers=[{"uploaded_bytes": -3}]),
        ):
            with self.subTest(altered=str(sorted(altered))[:80]):
                with self.assertRaises(ValueError):
                    sanitize({"magnet3": [altered]}, self.sha)

    def test_bounded_sample_window_and_exact_file_export(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "private-timeline.json"
            destination = Path(directory) / "numeric.json"
            sample = self.sample()
            source.write_text(json.dumps({"magnet3": [dict(sample, epoch_ms=i)
                for i in range(MAX_SAMPLES + 9)]}), encoding="utf-8")
            export(source, destination, self.sha)
            result = json.loads(destination.read_text())
            samples = result["fixtures"]["magnet3"]
            self.assertEqual(MAX_SAMPLES, len(samples))
            self.assertEqual(9, samples[0]["epoch_ms"])
            self.assertEqual(MAX_SAMPLES + 8, samples[-1]["epoch_ms"])
            self.assertNotIn("private.example", destination.read_text())
            self.assertLess(destination.stat().st_size, 2_000_000)
            destination.unlink()
            source.write_text('{"magnet3":[{"phase":"wat"}]}', encoding="utf-8")
            with self.assertRaises(ValueError):
                export(source, destination, self.sha)
            self.assertFalse(destination.exists())

    def test_does_not_promote_missing_raw_timeline_to_synthetic_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with self.assertRaises(ValueError):
                export(path / "missing.json", path / "numeric.json", self.sha)
            self.assertFalse((path / "numeric.json").exists())


if __name__ == "__main__":
    unittest.main()
