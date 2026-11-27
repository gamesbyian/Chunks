"""Offline checks of the D8 null-check miscompile witness on synthetic dexdump text."""
import unittest

from tools.dex_null_check_witness import scan

HEADER = "0eaa90:      |[0eaa90] io.github.gamesbyian.chunks.CombinedTransfersScreenKt.CombinedTransfersScreen:(Lio/X;)V\n"


def dump(*lines):
    return HEADER + "".join(f"0eaaa0: 0000 |{line}\n" for line in lines)


class DexNullCheckWitnessTests(unittest.TestCase):
    def test_the_observed_miscompile_is_flagged(self):
        text = dump("0c7d: check-cast v0, Landroidx/compose/runtime/MutableState;",
                    "0f11: if-eqz v0, 0f1a // +0009",
                    "0f13: invoke-virtual/range {v256}, Lio/X;.getAliasScope:()Ljava/lang/String;")
        self.assertEqual(len(scan(text, "gamesbyian")), 1)

    def test_a_correct_safe_call_is_not_flagged(self):
        text = dump("0f00: move-object/from16 v11, v256",
                    "0f3a: if-eqz v11, 0f43",
                    "0f3c: invoke-virtual {v11}, Lio/X;.getAliasScope:()Ljava/lang/String;")
        self.assertEqual(scan(text, "gamesbyian"), [])

    def test_a_boolean_condition_before_a_call_is_benign(self):
        text = dump("4887: move/from16 v4, v36",
                    "4889: if-eqz v4, 48b5",
                    "488b: invoke-virtual/range {v343}, Lio/X;.getMetadataKnown:()Z")
        self.assertEqual(scan(text, "gamesbyian"), [])

    def test_library_methods_and_low_receivers_are_ignored(self):
        library = dump("0c7d: check-cast v0, LFoo;", "0f11: if-eqz v0, 0f1a",
                       "0f13: invoke-virtual/range {v256}, LFoo;.bar:()V").replace("gamesbyian", "example")
        self.assertEqual(scan(library, "gamesbyian"), [])
        low = dump("0c7d: check-cast v0, LFoo;", "0f11: if-eqz v0, 0f1a",
                   "0f13: invoke-virtual/range {v20}, LFoo;.bar:()V")
        self.assertEqual(scan(low, "gamesbyian"), [])


if __name__ == "__main__":
    unittest.main()
