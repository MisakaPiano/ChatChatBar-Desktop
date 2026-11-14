# Exact Desktop upgrade r02

The first execution, run43549286338/control ea310bf6, stopped before native
launches because its downloader expected the pinned inner ZIP at the artifact
root. The old delivered artifact can retain an `output/` prefix. The frozen
native package inspector already supports a uniquely located archive/audit.

`verify_outer_and_extract` now locates exactly one regular file matching the
pinned inner filename after the entire original outer size/hash/CRC/path checks.
It retains exact inner size/hash checks, leaves the tree intact and records
`innerRelativePath`. Multiple matching names refuse; no directory is flattened.

The separate `lan-next-sync-desktop-upgrade-r02.yml` uses only the explicit
`lan-next-sync-desktop-upgrade-r02-request.json` push path. Its request bytes are
identical to the preserved original request (SHA256
`a707909b98f41fbec23cd8c8f6586a60faf63055c98987e7350660b05c6cf015`).
Its output/artifact names add r02; the original workflow/request remain intact.
All package pins, five native phases, one old-version seed, root byte-lock,
normal close and strict retention/timestamp rules are unchanged.

The lightweight `test_desktop_control.py` adds nested `output/<filename>` success,
no-flattening/inner-hash assertions and duplicate-name refusal. These tests run
no application/JVM/SDK. Actual five-launch upgrade proof still requires the new
explicit cloud execution; the old failed run remains failed.
