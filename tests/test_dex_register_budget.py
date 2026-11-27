"""Offline checks of the dex register-budget ratchet on synthetic dexdump text."""
import unittest

from tools.dex_register_budget import methods, over_budget

DUMP = """Class #0            -
  Class descriptor  : 'Lio/github/gamesbyian/chunks/BigKt;'
  Direct methods    -
    #0              : (in Lio/github/gamesbyian/chunks/BigKt;)
      name          : 'Big'
      registers     : 300
    #1              : (in Lio/github/gamesbyian/chunks/BigKt;)
      name          : 'small'
      registers     : 12
Class #1            -
  Class descriptor  : 'Landroidx/compose/Foo;'
    #0              : (in Landroidx/compose/Foo;)
      name          : 'huge'
      registers     : 900
"""


class DexRegisterBudgetTests(unittest.TestCase):
    def test_only_app_methods_are_measured(self):
        found = methods(DUMP, "io.github.gamesbyian.chunks")
        self.assertEqual(found, {"io.github.gamesbyian.chunks.BigKt.Big": 300,
                                 "io.github.gamesbyian.chunks.BigKt.small": 12})

    def test_a_new_method_above_the_limit_fails(self):
        found = {"io.github.gamesbyian.chunks.BigKt.Big": 300}
        self.assertEqual(len(over_budget(found, {})), 1)

    def test_a_listed_method_may_not_grow_but_may_shrink(self):
        budgets = {"io.github.gamesbyian.chunks.BigKt.Big": 300}
        self.assertEqual(over_budget({"io.github.gamesbyian.chunks.BigKt.Big": 300}, budgets), [])
        self.assertEqual(over_budget({"io.github.gamesbyian.chunks.BigKt.Big": 280}, budgets), [])
        self.assertEqual(len(over_budget({"io.github.gamesbyian.chunks.BigKt.Big": 301}, budgets)), 1)

    def test_methods_at_or_below_the_default_limit_pass(self):
        self.assertEqual(over_budget({"io.github.gamesbyian.chunks.A.b": 255}, {}), [])


if __name__ == "__main__":
    unittest.main()
