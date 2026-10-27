# Supplemental storage authorization — user direct, during LR6

The user asked to prioritize storage cleanup. After the repository census, Codex
asked for a specific exception to 199, naming only these two directories:

- `app/desktopApp/build/p11-interop-20261022/avds`
- `app/desktopApp/build/p11-moments-poc-20261022/avds`

The question disclosed approximately 8.11 GB logical size and loss of saved
emulator state, conditional on no running processes, synthetic-only provenance,
and retention of original acceptance evidence. The user answered:
**“批准核实后删除这两个测试环境”**.

This is new, narrower, task-local consent; it does not rewrite 199, authorize
other emulator roots, Android/ADB execution, VM disposal, releases, private
witnesses, or user-data cleanup. No phone/export directory is a target.

Source attribution: historical `p11-interop-20261022/ANDROID_ACCEPTANCE.md`
documents a fresh dedicated AVD, blank provider build properties and fake local
endpoint. `p11-moments-poc-20261022/EXPERIMENT_PROTOCOL.md` and
`OFFICIAL_SOURCE_PROOF.md` document a separate newly created synthetic-only AVD,
empty model credentials, isolated ports, original archives and instrumentation
receipts. These are retained historical provenance, not new Android execution.

`AVD_CLEANUP_DRY_RUN.json` records the 52 files, direct native identity/handle
checks, no alternate streams/reparse points, and no matching build/emulator/ADB
processes. The local complete identity/path manifest stays ignored. All 647
Git-tracked historical evidence files were checked against HEAD: 646 exact raw
blob matches, one CRLF-normalized text match; total 8,341,335 bytes retained.
No original exported archive, source, XML, test log or historical evidence is a
deletion target. Saved AVD disk/RAM state itself will be discarded under this
explicit consent, not falsely claimed archived or losslessly preserved.
