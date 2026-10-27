# R0 delivery receipt — Project gate only

Source/test/main evidence commit: **`ea02b0cc9974a837761c74d114c19c73a4ddea6c`**.

Start: `5c7238e55ebbfb65ea5a78cad32d5437a598de43`, original feature branch. This receipt and COMMITTED_VERIFICATION.json are a subsequent evidence-only commit; exact final receipt HEAD is supplied with the final response/remote check. No source or tests changed after the source commit.

- [Project handoff](PROJECT_HANDOFF.md), [execution and matrix](TEST_EXECUTION.md), [source audit](SOURCE_AUDIT.md), [committed verification](COMMITTED_VERIFICATION.json).
- [Complete tool patch](CODE_DIFF.patch) matches the exact Git diff from start to source commit for all6 new tool files. Full source/evidence diff: https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/5c7238e55ebbfb65ea5a78cad32d5437a598de43...ea02b0cc9974a837761c74d114c19c73a4ddea6c . All186 paths in that commit are newly added within the two allowed directories; no existing production/history file changes.
- [Tool/local-use and future authorization steps](../../../../tools/desktop/interop/readonly-r0/README.md).

Final JVM result: **7 suites /117 PASS /0 failure/error/skip /exit0**;23 new tool tests +94 existing. First7/114 GREEN retained separately, not added to final count. Actual launcher RED is preserved; corrected PowerShell argument-array launch passes plain0, no-console2 and inherited-options2, outer exit0. See raw/launcher and raw/launcher-green. No Gradle rerun after wrapper-only correction, because that wrapper is checked by actual direct launches and both scripts parse; final Kotlin and init helper remain the exact final JVM snapshot.

Codex independently opened the committed Git archive and rechecked **171/171 indexed raw SHA-256/length entries**, all6 final canonical tooling blobs,2 final tested Kotlin snapshots, the final actual launcher snapshot, all3 unchanged main-tree identities and the complete tool patch. Initial assumption that text:auto tool bytes in Git archive would equal canonical LF blobs failed: this Windows Git exports those text files as CRLF. Verification was corrected to compare canonical Git blobs to tested LF snapshots, and separately verify raw/** -text bytes exactly; this is recorded transparently in COMMITTED_VERIFICATION. No functional/source mismatch was found and no test evidence was rewritten. These are Codex checks, not Project independent acceptance.

Known-sentinel diagnostic audit:36 files,6 synthetic sentinels,0 hits. Fake-valued synthetic fixtures/source intentionally excluded; no universal arbitrary-input/heap/OS secrecy claim. Passwords generated for new encrypted fixtures stay in memory, are cleared and are not retained as rerunnable secrets. Source file and synthetic existing journal bytes stay unchanged in each owned-directory test. Existing6 unknown local attachments/handoff/cache files remain unread/unhashed/unchanged/unstaged.

**NOT TESTED / remaining limits:** real official user archive, human interactive Windows masked-console entry, actual Android/Windows restore, real protected credentials/DPAPI, privileged or directory-swap adversaries, maximum-size memory stress, power-loss/hard-kill memory erasure, full nested SaveSlot/media semantics and durable cross-root recovery. The CLI's mixed fixture proves bounded format recognition and explicit incomplete classification, not valid decoded PNG/MP3/SaveSlot contents. Existing S0 source64MiB/decrypted ZIP64MiB/expanded128MiB/JSON8MiB/ZIP32 bounds remain.

Before any real read: Project must accept the reader; the user then designates one locally held regular official `.cbbackup` outside Git, confirms version, retains an independent original and explicitly authorizes a single offline read-only invocation. Enter a password only in a functioning local hidden console. If absent, stop without fallback. No original/private raw report/token/password/private SHA upload, directory scan, root write or restore is permitted by that future read-only step.

Formal upstream baseline1.4.1 unchanged; official145 is selected format authority only. No G2C, merge to desktop, P11/D-038/Post-P8/Public Preview promotion.

**STOPPED AT P11 REAL-ARCHIVE READ-ONLY TOOL PROJECT REVIEW GATE.**
