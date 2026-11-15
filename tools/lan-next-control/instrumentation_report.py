"""Verify one actually executed Android instrumentation method without a println marker."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys


class ReportError(ValueError):
    pass


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ReportError(reason)


def execution(text: str, expected_class: str, expected_method: str) -> dict:
    require(all(value and value == value.strip() and not any(c in value for c in "\r\n\0")
                for value in (expected_class, expected_method)), "EXPECTED_CASE_INVALID")
    require("\0" not in text, "REPORT_NUL")
    require(not any(marker in text for marker in (
        "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")), "REPORTED_FAILURE")
    blocks: list[tuple[dict[str, str], int]] = []
    pending: dict[str, str] = {}
    result_stream: list[str] = []
    result_keys: set[str] = set()
    reading_result_stream = False
    result_started = False
    terminal: list[int] = []
    lines = text.splitlines()
    for line in lines:
        if terminal:
            require(not line.strip(), "DATA_AFTER_TERMINAL_RESULT")
            continue
        if line.startswith("INSTRUMENTATION_STATUS: "):
            require(not result_started, "STATUS_AFTER_RESULT")
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            require(bool(separator) and bool(key) and key not in pending, "STATUS_FIELD_INVALID_OR_DUPLICATE")
            pending[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            require(not result_started, "STATUS_AFTER_RESULT")
            match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*", line)
            require(match is not None, "STATUS_CODE_INVALID")
            blocks.append((pending, int(match[1])))
            pending = {}
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            require(not pending, "UNTERMINATED_STATUS")
            result_started = True
            key, separator, value = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            require(bool(separator) and bool(key) and key not in result_keys, "RESULT_FIELD_INVALID_OR_DUPLICATE")
            result_keys.add(key)
            # This proof mode emits only stream. Error/stack/unknown result fields
            # cannot coexist with an accepted successful terminal transcript.
            require(key == "stream", "RESULT_FIELD_UNSUPPORTED_OR_FAILURE")
            reading_result_stream = key == "stream"
            if reading_result_stream:
                result_stream.append(value)
        elif line.startswith("INSTRUMENTATION_CODE:"):
            require(not pending and result_started, "UNTERMINATED_OR_MISSING_RESULT")
            match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)\s*", line)
            require(match is not None, "TERMINAL_CODE_INVALID")
            terminal.append(int(match[1]))
        elif result_started and reading_result_stream:
            result_stream.append(line)
    require(not pending, "UNTERMINATED_STATUS")
    require(terminal == [-1], "RUNNER_DID_NOT_COMPLETE_NORMALLY")
    require([code for _, code in blocks] == [1, 0], "CASE_MUST_START_AND_PASS_ONCE_NOT_SKIP")
    for fields, _ in blocks:
        require(fields.get("class") == expected_class, "CASE_CLASS_MISMATCH")
        require(fields.get("test") == expected_method, "CASE_METHOD_MISMATCH")
        require(fields.get("numtests") == "1" and fields.get("current") == "1", "CASE_COUNT_MISMATCH")
        require(fields.get("id") == "AndroidJUnitRunner", "RUNNER_ID_MISMATCH")
        require("stack" not in fields, "CASE_STACK_REPORTED")
        require(set(fields) <= {"class", "test", "numtests", "current", "id", "stream"},
                "STATUS_FIELD_UNSUPPORTED_OR_FAILURE")
    stream = "\n".join(result_stream)
    successes = re.findall(r"^OK \([^\n]*$", stream, re.M)
    require(len(successes) == 1 and re.fullmatch(r"OK \(1 test\)[ \t]*", successes[0]) is not None,
            "RESULT_NOT_EXACTLY_ONE_PASS")
    times = re.findall(r"^Time: ([0-9]+(?:\.[0-9]+)?)[ \t]*$", stream, re.M)
    require(len(times) == 1, "RESULT_TIME_MISSING_OR_DUPLICATE")
    seconds = float(times[0])
    require(math.isfinite(seconds) and seconds >= 0, "RESULT_TIME_INVALID")
    return {"class": expected_class, "method": expected_method, "rawStatuses": [1, 0],
            "executed": 1, "passed": 1, "failed": 0, "errors": 0, "skipped": 0,
            "seconds": seconds, "terminalCode": -1}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--class-name", required=True)
    parser.add_argument("--method", required=True)
    parser.add_argument("--expected-source-sha")
    parser.add_argument("--source-sha-file", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        require((args.expected_source_sha is None) == (args.source_sha_file is None), "SOURCE_BINDING_PAIR_REQUIRED")
        source = None
        if args.expected_source_sha is not None:
            require(re.fullmatch(r"[a-f0-9]{40}", args.expected_source_sha) is not None, "SOURCE_SHA_INVALID")
            actual = args.source_sha_file.read_text(encoding="ascii")
            require(re.fullmatch(args.expected_source_sha + r"\n?", actual) is not None, "SOURCE_SHA_MISMATCH")
            source = args.expected_source_sha
        raw = args.report.read_bytes()
        result = execution(raw.decode("utf-8", errors="strict"), args.class_name, args.method)
        result.update(kind="CCB_SINGLE_INSTRUMENTATION_METHOD_VERIFICATION", sourceSha=source,
                      rawReportSha256=hashlib.sha256(raw).hexdigest())
        encoded = json.dumps(result, indent=2) + "\n"
        if args.output is None:
            print(encoded, end="")
        else:
            # Never replace the input, an original summary, or an earlier verifier receipt.
            with args.output.open("x", encoding="utf-8", newline="\n") as output:
                output.write(encoded)
        return 0
    except (ReportError, OSError, UnicodeError) as failure:
        print("INSTRUMENTATION_PROOF_REFUSED: " + str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
