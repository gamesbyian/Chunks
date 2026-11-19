"""Offline contract tests for the protected, redacted remote seeding getter probe."""
import unittest
import seedhost_seeding_readonly_matrix as matrix
from seedhost_seeding_readonly_matrix import (
    ALLOWED, GETTERS, ProbeRefused, UnsupportedGetter, inspect,
    read_response, xml_request,
)


def xml(value):
    return ("<methodResponse><params><param><value>" + value +
            "</value></param></params></methodResponse>").encode()


class RemoteSeedingReadOnlyMatrixTest(unittest.TestCase):
    HASH = "ab" * 20

    def case_rpc(self, values=None, inventory=None, changed_hash=False, calls=None):
        values = values or {
            "d.up.total": 1200,
            "d.down.total": 600,
            "d.complete": 1,
            "d.is_active": 1,
            "d.timestamp.finished": 1720000000,
            "d.throttle_name": "shared-bucket"
        }
        inventory = [[self.HASH]] if inventory is None else inventory
        seen = {"d.hash": 0}

        def rpc(method, *args):
            if calls is not None:
                calls.append((method, args))
            if method == "d.multicall2":
                self.assertEqual(("", "main", "d.hash="), args)
                return inventory
            self.assertEqual((self.HASH,), args)
            if method == "d.hash":
                seen["d.hash"] += 1
                if changed_hash and seen["d.hash"] == 2:
                    return "cd" * 20
                return self.HASH.upper()
            if method not in GETTERS:
                self.fail("Unsafe XML-RPC command: " + method)
            value = values[method]
            if isinstance(value, Exception):
                raise value
            return value
        return rpc

    def test_only_the_named_instances_map_to_exact_endpoints(self):
        self.assertTrue(matrix.endpoint_for("primary").endswith("/rutorrent/plugins/httprpc/action.php"))
        self.assertTrue(matrix.endpoint_for("2").endswith("/rutorrent2/plugins/httprpc/action.php"))
        self.assertTrue(matrix.endpoint_for("3").endswith("/rutorrent3/plugins/httprpc/action.php"))
        for bad in ("", "4", "../x", "2/", "primary2"):
            with self.assertRaises(matrix.ProbeRefused):
                matrix.endpoint_for(bad)

    def test_only_hardcoded_read_only_methods_and_exact_hash_can_be_serialized(self):
        for name in ("d.stop", "d.close", "d.erase", "execute.throw",
                     "d.set_upload_rate", "system.multicall", "throttle.up"):
            with self.subTest(name=name), self.assertRaises(ProbeRefused):
                xml_request(name, (self.HASH,))
        for bad in ("x", self.HASH + "\n", "a" * 64, "../" + self.HASH):
            with self.subTest(bad=bad[:8]), self.assertRaises(ProbeRefused):
                xml_request("d.hash", (bad,))
        self.assertEqual(
            set(GETTERS) | {"d.hash", "d.multicall2"}, ALLOWED)
        self.assertIn(b"d.multicall2", xml_request(
            "d.multicall2", ("", "main", "d.hash=")))

    def test_public_report_has_only_capability_labels_not_values_or_identifiers(self):
        calls = []
        report = inspect(self.case_rpc(calls=calls))
        self.assertTrue(report.sampled)
        self.assertEqual([value for _, value in report.getters], ["supported"] * 6)
        self.assertEqual(["d.multicall2", "d.hash", *GETTERS, "d.hash"],
                         [method for method, _ in calls])
        summary = report.public_line()
        self.assertIn("independent_per_torrent_cap=unproven", summary)
        self.assertIn("policy_mutations=disabled", summary)
        for private in (self.HASH, "shared-bucket", "1200", "600",
                        "1720000000", "ianseeds", "passkey"):
            self.assertNotIn(private, summary)

    def test_unsupported_getters_are_unsupported_not_zero_or_unlimited(self):
        values = {
            "d.up.total": UnsupportedGetter(),
            "d.down.total": 200,
            "d.complete": 1,
            "d.is_active": 0,
            "d.timestamp.finished": UnsupportedGetter(),
            "d.throttle_name": UnsupportedGetter()
        }
        report = inspect(self.case_rpc(values=values))
        self.assertEqual(
            ("unsupported", "supported", "supported", "supported",
             "unsupported", "unsupported"), tuple(v for _, v in report.getters))
        self.assertNotIn("throttleName", report.public_line())

    def test_empty_inventory_returns_no_bogus_positive_capability(self):
        calls = []
        report = inspect(self.case_rpc(inventory=[], calls=calls))
        self.assertFalse(report.sampled)
        self.assertEqual([], list(report.getters))
        self.assertEqual([("d.multicall2", ("", "main", "d.hash="))], calls)
        self.assertIn("no eligible", report.public_line())

    def test_malformed_or_duplicate_observed_identity_is_refused_before_getters(self):
        for rows in ([["a" * 64]], [[self.HASH], [self.HASH.upper()]],
                     [[self.HASH, "another"]], [["../bad"]], ["bad"], [42]):
            calls = []
            with self.subTest(rows=str(rows)[:30]), self.assertRaises(ProbeRefused):
                inspect(self.case_rpc(inventory=rows, calls=calls))
            self.assertEqual("d.multicall2", calls[0][0])
            self.assertEqual(1, len(calls))

    def test_changed_exact_hash_refuses_entire_probe(self):
        with self.assertRaises(ProbeRefused):
            inspect(self.case_rpc(changed_hash=True))

    def test_counter_boolean_timestamp_and_throttle_are_validated(self):
        bad = [
            ("d.up.total", -1),
            ("d.down.total", "1200"),
            ("d.complete", 2),
            ("d.is_active", True),
            ("d.timestamp.finished", -20),
            ("d.throttle_name", "a\ncontrol"),
            ("d.throttle_name", "x" * 129)
        ]
        for key, invalid in bad:
            values = {
                "d.up.total": 1200, "d.down.total": 600,
                "d.complete": 1, "d.is_active": 1,
                "d.timestamp.finished": 0, "d.throttle_name": ""
            }
            values[key] = invalid
            with self.subTest(key=key, invalid=str(invalid)[:6]):
                with self.assertRaises(ProbeRefused):
                    inspect(self.case_rpc(values=values))

    def test_transport_or_operational_exception_never_becomes_unsupported(self):
        values = {
            "d.up.total": ProbeRefused("401"),
            "d.down.total": 0, "d.complete": 1, "d.is_active": 0,
            "d.timestamp.finished": 0, "d.throttle_name": ""
        }
        calls = []
        with self.assertRaises(ProbeRefused):
            inspect(self.case_rpc(values=values, calls=calls))
        self.assertEqual(["d.multicall2", "d.hash", "d.up.total"],
                         [method for method, _ in calls])

    def test_xml_reader_accepts_valid_nested_inventory_and_one_scalar(self):
        data = xml("<array><data><value><array><data><value><string>" +
                   self.HASH + "</string></value></data></array></value></data></array>")
        self.assertEqual([[self.HASH]], read_response(data))
        self.assertEqual(12, read_response(xml("<i8>12</i8>")))

    def test_xml_reader_fails_closed_on_entity_html_unsupported_types_or_fault(self):
        cases = (
            b"<!DOCTYPE methodResponse [<!ENTITY a SYSTEM 'file:///private'>]>" +
                xml("<string>a</string>"),
            xml("<boolean>1</boolean>"),
            b"<html>login</html>",
            xml("<i8>01</i8>"),
            xml("<string>okay</string>") + b"\x00",
            b"x" * 1_048_577,
            b""
        )
        for data in cases:
            with self.subTest(data=data[:20]), self.assertRaises(ProbeRefused):
                read_response(data)
        fault = ("<methodResponse><fault><value><struct>" +
                 "<member><name>faultCode</name><value><int>-32601</int></value></member>" +
                 "<member><name>faultString</name><value><string>private</string></value></member>" +
                 "</struct></value></fault></methodResponse>").encode()
        with self.assertRaises(UnsupportedGetter):
            read_response(fault)


if __name__ == "__main__":
    unittest.main()
