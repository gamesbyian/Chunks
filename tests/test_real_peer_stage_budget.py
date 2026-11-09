"""The real peer must reserve verified original pieces for after Stop/Start."""
import unittest

from tools.real_peer_stage_budget import (
    FIRST_PHASE_LIMIT, FULL_PREFIX_LIMIT, stage_prefix_lengths,
)


class RealPeerStageBudgetTests(unittest.TestCase):
    def test_public_fixture_piece_lengths_reserve_new_verified_source(self):
        for total, piece in ((129302391, 131072), (792723456, 262144),
                             (735358976, 262144), (276445467, 131072)):
            with self.subTest(total=total, piece=piece):
                full, early = stage_prefix_lengths(total, piece)
                self.assertEqual(FULL_PREFIX_LIMIT, full)
                self.assertEqual(FIRST_PHASE_LIMIT, early)
                self.assertEqual(0, full % piece)
                self.assertEqual(0, early % piece)
                self.assertGreater(full - early, 0)

    def test_non_power_of_two_piece_lengths_still_align_and_hold_a_reserve(self):
        piece = 3 * 1024 * 1024
        full, early = stage_prefix_lengths(200 * 1024 * 1024, piece)
        self.assertEqual(30 * 1024 * 1024, full)
        self.assertEqual(3 * 1024 * 1024, early)

    def test_small_or_malformed_sources_fail_closed(self):
        for total, piece in (
            (1, 1), (1_000_000, 131072), (0, 131072),
            (-1, 131072), (True, 131072), ("secret", 131072),
            (100_000_000, 0), (100_000_000, -1),
            (100_000_000, True), (100_000_000, 8 * 1024 * 1024),
        ):
            with self.subTest(total=total, piece=piece):
                with self.assertRaises(ValueError):
                    stage_prefix_lengths(total, piece)


if __name__ == "__main__":
    unittest.main()
