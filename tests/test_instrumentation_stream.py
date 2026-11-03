"""Offline negative controls for AndroidJUnitRunner raw status receipts."""
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

from tools.parse_instrumentation_stream import failure_locations, parse_stream, write_junit


def event(klass, method, outcome=0, total=1, started=True):
    result = []
    if started:
        result.extend([
            f"INSTRUMENTATION_STATUS: class={klass}",
            f"INSTRUMENTATION_STATUS: test={method}",
            f"INSTRUMENTATION_STATUS: numtests={total}",
            "INSTRUMENTATION_STATUS_CODE: 1",
        ])
    result.extend([
        f"INSTRUMENTATION_STATUS: class={klass}",
        f"INSTRUMENTATION_STATUS: test={method}",
        f"INSTRUMENTATION_STATUS: numtests={total}",
        f"INSTRUMENTATION_STATUS_CODE: {outcome}",
    ])
    return "\n".join(result) + "\n"


def stream(*rows, final=-1):
    return "".join(rows) + f"INSTRUMENTATION_CODE: {final}\n"


class InstrumentationStreamTests(unittest.TestCase):
    def test_locations_ignore_passed_stdout_and_malformed_frames(self):
        location = "  at io.github.gamesbyian.chunks.SampleTest.check(SampleTest.kt:42)"
        self.assertEqual([], failure_locations(stream(event("example.Foo", "ok").replace(
            "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS: stack=ignored\n"
            + location + "\nINSTRUMENTATION_STATUS_CODE: 0"))))
        raw = stream(event("example.Foo", "broken", -2).replace(
            "INSTRUMENTATION_STATUS_CODE: -2",
            location + "\nINSTRUMENTATION_STATUS: stack=ignored\n"
            "  at io.github.gamesbyian.chunks.Sample.check(https://private.invalid:42)\n"
            "  at io.github.gamesbyian.chunks.Sample.check(Sample.kt:42) secret\n"
            "INSTRUMENTATION_STATUS_CODE: -2"))
        self.assertEqual([], failure_locations(raw))

    def test_cli_reports_only_bounded_project_locations_for_failed_stacks(self):
        frames = "\n".join(
            f"  at io.github.gamesbyian.chunks.SampleTest.check(SampleTest.kt:{i})"
            for i in range(1, 25))
        raw = stream(event("example.Foo", "broken", -2).replace(
            "INSTRUMENTATION_STATUS_CODE: -2",
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: secret=https://private.invalid/token\n"
            + frames + "\n  at third.party.Library.run(Library.java:4)\n"
            + "INSTRUMENTATION_STATUS_CODE: -2"))
        with tempfile.TemporaryDirectory() as tmp:
            source, report = Path(tmp) / "raw.txt", Path(tmp) / "TEST.xml"
            source.write_text(raw)
            result = subprocess.run([sys.executable, "tools/parse_instrumentation_stream.py",
                                     "--raw", str(source), "--junit", str(report)],
                                    text=True, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        lines = [line for line in result.stdout.splitlines()
                 if line.startswith("CHUNKS_PREBUILT_FAILURE_LOCATION:")]
        self.assertEqual(16, len(lines))
        self.assertEqual("CHUNKS_PREBUILT_FAILURE_LOCATION: "
                         "io.github.gamesbyian.chunks.SampleTest.check(SampleTest.kt:1)", lines[0])
        self.assertNotIn("secret", result.stdout)
        self.assertNotIn("private.invalid", result.stdout)
        self.assertNotIn("third.party", result.stdout)

    def test_exact_pass_and_ignore_create_structural_junit(self):
        cases = parse_stream(stream(
            event("example.Foo", "first", total=3),
            event("example.Foo", "second", total=3),
            event("example.Bar", "ignored", -3, 3, started=False),
        ))
        self.assertEqual(3, len(cases))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-shard.xml"
            write_junit(cases, path)
            root = ET.parse(path).getroot()
            self.assertEqual("3", root.get("tests"))
            self.assertEqual("1", root.get("skipped"))
            self.assertEqual(3, len(root.findall("testcase")))
            self.assertEqual(1, len(root.findall(".//skipped")))

    def test_actual_failure_is_not_reported_as_a_pass(self):
        cases = parse_stream(stream(event("example.Foo", "broken", -2)))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-error.xml"
            write_junit(cases, path)
            self.assertEqual("1", ET.parse(path).getroot().get("failures"))
            self.assertEqual(1, len(ET.parse(path).getroot().findall(".//failure")))

    def test_assumption_failure_is_skipped_not_passed(self):
        self.assertEqual("skipped",
                         parse_stream(stream(event("example.Foo", "assume", -4)))[0].outcome)

    def test_missing_or_bad_instrumentation_terminator_fails_closed(self):
        for text in (event("A", "b"), stream(event("A", "b"), final=0),
                     stream(event("A", "b")) + "INSTRUMENTATION_CODE: -1\n"):
            with self.subTest(text=text):
                with self.assertRaisesRegex(ValueError, "instrumentation terminator"):
                    parse_stream(text)

    def test_duplicate_and_unfinished_cases_or_wrong_total_fail(self):
        for text in (stream(event("A", "one", total=2)),
                     stream(event("A", "one") + event("A", "one")),
                     stream(event("A", "one", started=False)),
                     stream("INSTRUMENTATION_STATUS: class=A\n"
                            "INSTRUMENTATION_STATUS: test=one\n"
                            "INSTRUMENTATION_STATUS_CODE: 1\n")):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    parse_stream(text)

    def test_unidentified_or_unknown_status_cannot_become_green(self):
        for text in (stream("INSTRUMENTATION_STATUS_CODE: 0\n"),
                     stream("INSTRUMENTATION_STATUS: test=foo\n"
                            "INSTRUMENTATION_STATUS_CODE: 0\n"),
                     stream("INSTRUMENTATION_STATUS: class=A\n"
                            "INSTRUMENTATION_STATUS: test=b\n"
                            "INSTRUMENTATION_STATUS_CODE: 9\n")):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    parse_stream(text)

    def test_xml_escapes_untrusted_test_name(self):
        cases = parse_stream(stream(event("example.Test", "x&y<z")))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-shard.xml"
            write_junit(cases, path)
            self.assertEqual("x&y<z", ET.parse(path).getroot()[0].get("name"))


if __name__ == "__main__":
    unittest.main()
