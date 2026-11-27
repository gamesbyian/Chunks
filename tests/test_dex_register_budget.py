"""Offline checks of the dex register-budget ratchet on synthetic dexdump text."""
import unittest
from unittest import mock
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
import io
import subprocess
import sys
import tempfile
import warnings
import zipfile

from tools import dex_register_budget, dex_null_check_witness

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


class DexEvidenceAvailabilityTests(unittest.TestCase):
    """Exercise both real CLI main functions; mock only the external dexdump."""
    APP_DUMP = DUMP.replace("registers     : 300", "registers     : 30") + (
        "0eaa90:      |[0eaa90] io.github.gamesbyian.chunks.BigKt.small:()V\n"
        "0eaaa0: 0000 |0000: return-void \n"
    )
    LIBRARY_DUMP = APP_DUMP.replace("io.github.gamesbyian.chunks", "example.library").replace(
        "io/github/gamesbyian/chunks", "example/library")

    def run_scan(self, module, entries, replies):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in entries:
                    archive.writestr(name, b"fixture")
            with mock.patch.object(sys, "argv", [module.__name__, str(apk)]), \
                    mock.patch.object(module, "dexdump_tool", return_value="dexdump"), \
                    mock.patch.object(module.subprocess, "run", side_effect=replies) as run, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                status = module.main()
            return status, run.call_count

    @staticmethod
    def reply(text, status=0):
        return subprocess.CompletedProcess(["dexdump"], status, text.encode(), b"")

    def test_missing_primary_dex_fails_without_invoking_tool(self):
        for module in (dex_register_budget, dex_null_check_witness):
            with self.subTest(module=module.__name__):
                self.assertEqual(self.run_scan(module, ["classes2.dex"], []), (1, 0))

    def test_failed_secondary_dex_is_not_a_partial_green(self):
        for module in (dex_register_budget, dex_null_check_witness):
            with self.subTest(module=module.__name__):
                self.assertEqual(self.run_scan(module, ["classes.dex", "classes2.dex"],
                    [self.reply(self.APP_DUMP), self.reply("", 1)]), (1, 2))

    def test_failed_dump_even_with_plausible_stdout_is_red(self):
        for module in (dex_register_budget, dex_null_check_witness):
            with self.subTest(module=module.__name__):
                self.assertEqual(self.run_scan(module, ["classes.dex"],
                    [self.reply(self.APP_DUMP, 1)]), (1, 1))

    def test_empty_or_library_only_dump_is_not_app_evidence(self):
        for module in (dex_register_budget, dex_null_check_witness):
            for dump in ("", self.LIBRARY_DUMP):
                with self.subTest(module=module.__name__, empty=not dump):
                    self.assertEqual(self.run_scan(module, ["classes.dex"],
                        [self.reply(dump)]), (1, 1))

    def test_complete_multidex_scan_with_real_app_markers_passes(self):
        for module in (dex_register_budget, dex_null_check_witness):
            with self.subTest(module=module.__name__):
                self.assertEqual(self.run_scan(module, ["classes.dex", "classes2.dex"],
                    [self.reply(self.LIBRARY_DUMP), self.reply(self.APP_DUMP)]), (0, 2))

    def test_duplicate_primary_is_rejected_before_scan(self):
        for module in (dex_register_budget, dex_null_check_witness):
            with self.subTest(module=module.__name__), warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                self.assertEqual(self.run_scan(module, ["classes.dex", "classes.dex"], []), (1, 0))


if __name__ == "__main__":
    unittest.main()
