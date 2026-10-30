// Include AFTER the original upstream swig/libtorrent/session_params.i.
// This is an experiment for an explicitly rebuilt libtorrent4j JNI fork.
// Stock libtorrent4j 2.1.0-39 does not expose disk_io_constructor.
//
// In the fork, add to swig/libtorrent.i AFTER upstream session_params.i:
//     %include "chunks/owned_disk_extension.i"
//
// In swig/CMakeLists.txt, link the owned payload, torrent storage, checked
// piece ranges and full disk_interface adapter sources INTO torrent4j.
// Merely loading a second library is unsafe: the session constructor in the
// original torrent4j binary would continue using default disk I/O.
%{
#include "chunks/owned_libtorrent_disk.h"
#include <stdexcept>
%}

namespace libtorrent {
%extend session_params {
    void set_owned_disk_io_constructor(std::string const& private_receipt_root) {
        // Refuse an empty receipt root; caller is responsible for creating
        // and verifying an app-private directory before SessionManager.start.
        if (private_receipt_root.empty()) {
            throw std::invalid_argument("Missing private native owner receipt root");
        }
        $self->disk_io_constructor =
            chunks::MakeOwnedDiskConstructor(private_receipt_root);
    }
}
}
