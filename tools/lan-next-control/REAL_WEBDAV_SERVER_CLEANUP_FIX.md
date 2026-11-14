# Fixed real-WebDAV server cleanup contribution

This scratch contribution replaces only the CI server lifecycle. It is based on
control `9cf00289c61aa13e5a0488f3647563f3b5a96fcc`; root owns integration, requests,
Git/index, and the new cloud execution. No request is created here.

## Original result remains a failure

Root's retained original run `43617054679` contained an actual single passing
JUnit method (1.02 seconds), successful Gradle work, and the final source check.
The runner nevertheless exceeded the outer 18-minute bound during script-exit
cleanup. That run remains FAIL. The exact Bash jobs/pipeline liveness mechanism
is not established by this contribution.

## Integrate exactly these code files

1. `tools/lan-next-control/real_webdav_interop_control.py`
2. `tools/lan-next-control/test_real_webdav_interop_control.py`
3. `tools/lan-next-control/run-real-webdav-interop.sh`
4. `tools/lan-next-control/real_webdav_process_owner.py` (new)
5. `tools/lan-next-control/test_real_webdav_process_owner.py` (new)

The copied Kotlin fixture and workflow are unchanged references, not proposed
edits. Keep their existing bytes. The README and validation/freeze files are
contribution evidence, not part of the original eight-report upload allowlist.

## Ownership and bounded cleanup

The shell authenticates the same official rclone archive and prepares the same
private synthetic environment. It then calls the new Python owner with only
`--source-dir` and `--case-root`. The owner derives the fixed binary, loopback
address, Basic username, and exact Gradle method. Its CLI exposes no arbitrary
server command, host, credentials, selector, or tool choice.

Python directly owns the rclone `Popen`. It creates no session or separate PGID,
so the existing outer helper still owns all descendants. On success, a nonzero
Gradle return, readiness failure, or exception, its finally block polls its own
child; an exited/reaped child is not signalled. Otherwise it terminates that
child and waits five seconds, then, only if still unreaped, kills it and waits
five seconds. There is no jobs-table lookup, PID discovery, Bash trap, or
unbounded server wait. Repeated termination signals cannot interrupt this finite
cleanup. Diagnostics omit exception values, server argv, and passwords.

The unchanged outer helper retains its 18-minute whole-run limit and owned-group
termination fallback. This contribution does not claim a hard bound on arbitrary
kernel I/O or Gradle work. The direct server's two waits are explicitly bounded.

The outer helper's existing finally path still copies original JUnit bytes and
writes execution metadata when the script fails. A nonzero runner or failed
cleanup cannot become a PASS merely because JUnit passed. Original XML is never
replaced by a marker.

## Trusted control revision

Request format is now exactly `2`, with the same three fields: `format`,
`sourceSha`, and `fixtureSha256`. Source remains
`6ab083be4ee3de70757b06ea75ba49a77c558385`; fixture remains 7,476 bytes with SHA-256
`56075f65de42a46ea3dffe1ebf38f91691316473705e1da068e7f5dbe4af9a69`.
The control binding now also includes the process-owner module hash. Format 1
is intentionally refused by this revised trusted control; its old original
evidence is retained separately. Root creates the explicit format-2 request.

The fixed rclone archive hash, `127.0.0.1:18765`, synthetic Basic credentials,
single source overlay, exact-method strict JUnit parser, and eight output-file
allowlist are unchanged. The workflow needs no new parameter or report glob.

## Validation boundary

The attached validation records actual Python/mock/filesystem guards only. They
do not start Bash, rclone, Gradle, an SDK, a JVM, or network services. They cover
TERM/KILL timeouts, exit races, already-reaped children, nonzero client results,
readiness/client/interruption exceptions, cleanup failure, safe diagnostics,
format/hash binding, strict report parsing, and unchanged fixture/workflow bytes.
Actual cleanup completion and real-WebDAV success require root's new cloud run;
no native PASS is asserted by this contribution.
