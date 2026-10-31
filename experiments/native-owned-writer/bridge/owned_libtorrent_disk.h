#pragma once

#include <libtorrent/session_params.hpp>
#include <string>

namespace chunks {

// Called only by a maintained, rebuilt libtorrent4j SWIG/JNI distribution.
// Not callable from the stock libtorrent4j-android native dependency.
libtorrent::disk_io_constructor_type MakeOwnedDiskConstructor(std::string private_receipts);

} // namespace chunks
