"""Synthetic report negatives: OK text alone must never authorize a proof."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from instrumentation_report import ReportError, execution, main


CLASS = "com.example.synthetic.ImageTest"
METHOD = "copiesOriginalAndMakesPreview"


def valid_report():
    def block(code):
        return ("INSTRUMENTATION_STATUS: numtests=1\n"
                "INSTRUMENTATION_STATUS: stream=.\n"
                "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
                f"INSTRUMENTATION_STATUS: test={METHOD}\n"
                f"INSTRUMENTATION_STATUS: class={CLASS}\n"
                "INSTRUMENTATION_STATUS: current=1\n"
                f"INSTRUMENTATION_STATUS_CODE: {code}\n")
    return (block(1) + block(0) + "INSTRUMENTATION_RESULT: stream=\n\n"
            "Time: 14.928\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n")


class InstrumentationReportTest(unittest.TestCase):
    def parse(self, report):
        return execution(report, CLASS, METHOD)

    def refuse(self, report):
        with self.assertRaises(ReportError):
            self.parse(report)

    def test_actual_execution_needs_no_plain_println_marker(self):
        result = self.parse(valid_report().replace("\n", "\r\n"))
        self.assertEqual([1, 0], result["rawStatuses"])
        self.assertEqual(1, result["passed"])
        self.assertEqual(0, result["skipped"])
        self.assertEqual(14.928, result["seconds"])

    def test_failure_error_skip_and_assumption_codes_refuse_even_with_ok_text(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code):
                self.refuse(valid_report().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}"))

    def test_missing_start_and_duplicate_pass_refuse(self):
        report = valid_report()
        first_end = report.index("INSTRUMENTATION_STATUS_CODE: 1\n") + len("INSTRUMENTATION_STATUS_CODE: 1\n")
        self.refuse(report[first_end:])
        self.refuse(report[:first_end] + report)

    def test_both_status_blocks_bind_the_exact_class_method_and_count(self):
        for old, new in (("class=" + CLASS, "class=other.Class"), ("test=" + METHOD, "test=otherMethod"),
                         ("numtests=1", "numtests=2"), ("current=1", "current=2"),
                         ("id=AndroidJUnitRunner", "id=otherRunner")):
            for occurrence in (1, 2):
                with self.subTest(field=old, occurrence=occurrence):
                    report = valid_report()
                    position = -1
                    for _ in range(occurrence):
                        position = report.index(old, position + 1)
                    self.refuse(report[:position] + new + report[position + len(old):])

    def test_duplicate_status_field_and_stack_refuse(self):
        self.refuse(valid_report().replace("INSTRUMENTATION_STATUS_CODE: 1", f"INSTRUMENTATION_STATUS: class={CLASS}\nINSTRUMENTATION_STATUS_CODE: 1"))
        self.refuse(valid_report().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS: stack=synthetic failure\nINSTRUMENTATION_STATUS_CODE: 0"))

    def test_failure_or_unknown_fields_refuse_before_and_after_result_stream(self):
        for key in ("stack", "error", "failure", "shortMsg", "unknown"):
            for insertion in ("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_CODE: -1"):
                with self.subTest(key=key, insertion=insertion):
                    self.refuse(valid_report().replace(insertion, f"INSTRUMENTATION_RESULT: {key}=synthetic\n" + insertion))
            self.refuse(valid_report().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS: {key}=synthetic\nINSTRUMENTATION_STATUS_CODE: 0"))

    def test_normal_final_result_must_be_unique_complete_and_last(self):
        report = valid_report()
        self.refuse(report.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"))
        self.refuse(report.replace("INSTRUMENTATION_CODE: -1", ""))
        self.refuse(report + "INSTRUMENTATION_CODE: -1\n")
        self.refuse(report + "late nonempty output\n")

    def test_success_text_must_be_in_final_result_stream(self):
        self.refuse(valid_report().replace("OK (1 test)", "OK (2 tests)"))
        self.refuse(valid_report().replace("OK (1 test)\n", ""))
        self.refuse(valid_report().replace("OK (1 test)", "OK (1 test)\nOK (2 tests)"))
        self.refuse(valid_report().replace("INSTRUMENTATION_STATUS: stream=.", "INSTRUMENTATION_STATUS: stream=OK (1 test)").replace("\nOK (1 test)\n", "\n"))
        self.refuse(valid_report().replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: shortMsg=process crashed"))

    def test_missing_duplicate_time_and_failure_markers_refuse(self):
        report = valid_report()
        self.refuse(report.replace("Time: 14.928\n", ""))
        self.refuse(report.replace("Time: 14.928", "Time: 14.928\nTime: 1"))
        for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED"):
            with self.subTest(marker=marker):
                self.refuse(report.replace("INSTRUMENTATION_RESULT:", marker + "\nINSTRUMENTATION_RESULT:"))

    def test_cli_checks_source_and_preserves_existing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.txt"; report.write_text(valid_report(), encoding="utf-8")
            source = root / "source.txt"; source.write_text("a" * 40 + "\n", encoding="ascii")
            output = root / "checked.json"
            args = ["--report", str(report), "--class-name", CLASS, "--method", METHOD,
                    "--source-sha-file", str(source), "--expected-source-sha", "a" * 40, "--output", str(output)]
            self.assertEqual(0, main(args))
            self.assertEqual("a" * 40, json.loads(output.read_text())["sourceSha"])
            original = output.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, main(args))
                source.write_text("b" * 40, encoding="ascii")
                self.assertEqual(2, main(args[:-2]))
            self.assertEqual(original, output.read_bytes())
            self.assertEqual(valid_report(), report.read_text())


if __name__ == "__main__":
    unittest.main()
