# Windows long-path source validation

The isolated worker-profile follow-up reproduced seven failures in 67 tests. A focused Windows counterexample then failed before the production fix: a valid 332-character synthetic source path was readable through NIO, but the ordinary native attribute lookup returned error 3. The bounded extended Windows path succeeded for the same file. The original failures and XML are retained.

DesktopDocumentRagSource now uses DesktopArchiveFiles.nativePath for the native attribute call. Existing path length/drive restrictions and reparse-point rejection remain; unsupported paths still reject as INVENTORY. No entity, memory state machine, Prompt literal or archive admission was changed.

The same long task-owned worker profile was rerun with the affected request, RAG, Memory, presentation and document evidence suites: **11 suites / 107 tests / 0 failures, errors or skips / exit 0**. The focused test verifies unchanged source bytes and actual source enumeration. Synthetic profile, runtime payloads, private pointers and credentials are excluded from the public selection; commands, source snapshots, logs and XML are retained. This is current-Windows native file API evidence, not DPAPI, restart, power-loss or graphical UI acceptance.

These numbers are per execution, not added to earlier regression totals. Studio evidence is not counted. The separate complete Desktop regression is reported independently.
