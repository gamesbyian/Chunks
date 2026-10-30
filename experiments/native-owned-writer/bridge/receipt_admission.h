#pragma once

#include "../owned_torrent_storage.h"
#include <string>
#include <vector>

namespace chunks {

enum class NativeAdmissionMode { FRESH, RESTORED, REFUSED };

// Read-only discovery of private ownership claims. This classifies receipt
// *presence* only. Reopen/Reserve MUST still parse and verify each receipt,
// physical file, mapping, inode, filesystem token and pathname before I/O.
NativeAdmissionMode ClassifyNativeAdmission(
    const std::string& private_receipts,
    const std::vector<OwnedTorrentStorage::FileSpec>& layout,
    std::string* error);

} // namespace chunks
