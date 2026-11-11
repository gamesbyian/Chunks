"""Distinct after-commit and before-commit uncertainty cases."""
import unittest
from candidate import submit

class Ledger:
    def __init__(self, mode):
        self.mode = mode
        self.writes = []
        self.recorded = {}
    def write(self, txid, amount):
        self.writes.append((txid, amount))
        if self.mode == "before":
            raise TimeoutError("before commit")
        self.recorded[txid] = amount
        if self.mode == "after":
            raise TimeoutError("after commit")
    def read(self, txid):
        if self.mode == "read_timeout":
            raise TimeoutError("read unreachable")
        return self.recorded.get(txid)

class Tests(unittest.TestCase):
    def test_positive_confirms_specific_transaction(self):
        led = Ledger("normal")
        self.assertEqual(submit(led, "TX17", 50), "VERIFIED")
        self.assertEqual(led.writes, [("TX17", 50)])
    def test_timeout_after_commit_is_not_replayed(self):
        led = Ledger("after")
        self.assertEqual(submit(led, "TX17", 50), "VERIFIED")
        self.assertEqual(led.writes, [("TX17", 50)])
    def test_timeout_before_commit_remains_uncertain(self):
        led = Ledger("before")
        self.assertEqual(submit(led, "TX17", 50), "UNCERTAIN")
        self.assertEqual(len(led.writes), 1)
    def test_unreachable_reconciliation_preserves_uncertainty(self):
        led = Ledger("read_timeout")
        self.assertEqual(submit(led, "TX17", 50), "UNCERTAIN")
        self.assertEqual(len(led.writes), 1)

if __name__ == "__main__":
    unittest.main()
