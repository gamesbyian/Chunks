"""Bounded independently verified publisher pieces for two real peer phases.

The initial seed advertises at most 4 MiB, keeping an independently fetched
additional source prefix unavailable until the restart phase. Both stages
require actual libtorrent piece verification; the budget never invents bytes.
"""
FULL_PREFIX_LIMIT = 32 * 1024 * 1024
FIRST_PHASE_LIMIT = 4 * 1024 * 1024


def stage_prefix_lengths(total_size: int, piece_length: int) -> tuple[int, int]:
    """Return (full_prefix, initial_prefix) with a strictly positive reserve."""
    if (type(total_size) is not int or type(piece_length) is not int or
            total_size <= 0 or piece_length <= 0 or
            piece_length > FIRST_PHASE_LIMIT):
        raise ValueError("Invalid real peer piece layout")
    full = min(total_size, (FULL_PREFIX_LIMIT // piece_length) * piece_length)
    early = min(full, (FIRST_PHASE_LIMIT // piece_length) * piece_length)
    if not 0 < early < full <= total_size:
        raise ValueError("No independent original pieces reserved for restart")
    return full, early
