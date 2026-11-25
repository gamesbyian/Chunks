"""Only the app's rTorrent view-write RPCs count as Add recovery mutations.

Tests run offline: no daemon, Android emulator, credentials or torrent payload.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "interop"))
from add_response_fault_proxy import FaultState, STOP, accepted_stop, rtorrent_view_mutation


def rpc(method, nested=()):
    members = "".join(
        "<value><struct><member><name>methodName</name>"
        f"<value><string>{name}</string></value></member></struct></value>"
        for name in nested
    )
    return (
        "<methodCall><methodName>" + method + "</methodName>"
        + "<params><param><value><array><data>" + members
        + "</data></array></value></param></params></methodCall>"
    ).encode()


class RTorrentViewMutationRelayTests(unittest.TestCase):
    def test_only_view_changes_are_counted(self):
        for name in ("view.set_not_visible", "view.set_visible", "view.filter",
                     "view.filter_all", "view.sort", "d.views.push_back"):
            self.assertTrue(rtorrent_view_mutation("/RPC2", rpc(name)), name)
        for name in ("d.hash", "d.get_name", "d.multicall2", "view.size_not_visible",
                     "view.size", "download_list"):
            self.assertFalse(rtorrent_view_mutation("/RPC2", rpc(name)), name)

    def test_nested_multicall_cannot_hide_a_view_write(self):
        self.assertFalse(rtorrent_view_mutation("/RPC2",
            rpc("system.multicall", ("d.hash", "view.size_not_visible"))))
        self.assertTrue(rtorrent_view_mutation("/RPC2",
            rpc("system.multicall", ("d.hash", "view.set_visible"))))
        self.assertTrue(rtorrent_view_mutation("/RPC2",
            rpc("system.multicall", ("d.views.push_back",))))
        self.assertFalse(rtorrent_view_mutation("/json", rpc("view.set_visible")))
        self.assertFalse(rtorrent_view_mutation("/RPC2", b""))

    def test_arm_isolates_each_lost_ack_and_read_only_observation(self):
        state = FaultState()
        state.arm("timeout")
        state.begin(True, False, False)
        state.begin(False, False, False)
        self.assertEqual(0, state.report()["view_mutations"])
        state.begin(False, False, True)
        self.assertEqual(1, state.report()["view_mutations"])
        state.arm("drop")
        self.assertEqual(0, state.report()["view_mutations"])
        self.assertEqual(0, state.report()["adds"])

    def test_hold_stop_faults_only_the_typed_stop_and_counts_any_replay(self):
        state = FaultState()
        state.arm("hold-stop")
        self.assertEqual((None, None), state.begin(True, False, False, False))
        mode, _ = state.begin(False, False, False, True)
        self.assertEqual("hold-stop", mode)
        self.assertEqual((None, None), state.begin(False, True, False, False))
        counts = state.report()
        self.assertEqual((1, 1, 1), (counts["adds"], counts["stops"], counts["starts"]))
        # Ordinary Add-fault modes never fault a Stop.
        state.arm("hold")
        self.assertEqual((None, None), state.begin(False, False, False, True))
        self.assertEqual({"torrent-stop"}, STOP["transmission"])
        self.assertFalse(STOP["qbittorrent"] | STOP["deluge"] | STOP["rtorrent"])

    def test_only_an_executed_transmission_stop_is_withheld(self):
        self.assertTrue(accepted_stop("transmission", 200, b'{"result":"success","arguments":{}}'))
        self.assertFalse(accepted_stop("transmission", 200, b'{"result":"invalid argument"}'))
        self.assertFalse(accepted_stop("transmission", 409, b""))
        self.assertFalse(accepted_stop("deluge", 200, b'{"result":"success"}'))


if __name__ == "__main__":
    unittest.main()
