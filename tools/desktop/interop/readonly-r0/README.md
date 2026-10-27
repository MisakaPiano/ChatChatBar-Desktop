# R0 single-file read-only tool — PROJECT REVIEW REQUIRED

Bounded test-classpath tooling, not a CCB application or recovery entry point. R0 authorizes only synthetic tests. **Do not select a real archive until Project accepts this reader and the user gives a new explicit single-file read-only permission.** Neither preparing this tool nor an earlier offer to supply a backup grants that permission.

## Later local use, after review and separate permission

1. Pin the reviewed feature commit listed in the R0 evidence DELIVERY. Use a clean trusted checkout, JDK17 and already cached dependencies. Prepare the reader without selecting any archive:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
   & .\tools\desktop\interop\readonly-r0\Prepare-ReadOnlyTool.ps1
   ```

2. The user keeps an independent original backup, chooses exactly one regular `.cbbackup` on a local Windows drive **outside the repository**, and confirms its official application/export version. Recommended first scope: official1.4.5/code84/v1. Obtain explicit permission covering that one path, one offline invocation and category-only results. Do not paste a password, token, archive or private digest into chat/GitHub. No permission for restore, root scanning, extraction, SecretStore access, network or data cleanup follows.
3. In a local interactive terminal with **no transcript, screen recording, output redirection or shell debug tracing**, run the command below, replacing only the selected-file placeholder. It is a file argument, never a password argument. Do not execute it inside an automated/redirected Codex tool session for an encrypted archive.

   ```powershell
   & .\tools\desktop\interop\readonly-r0\Invoke-ReadOnlyTool.ps1 -SelectedFile '<user-selected absolute local .cbbackup path>'
   ```

   The JVM receives the selected path and no secret argument. An encrypted envelope causes Java `Console.readPassword` to request the password without echo. If Java has no real Console (including a pipe), EOF/cancellation, wrong-length input, or another unsupported environment, it refuses. **No readLine/stdin/password-file/environment fallback exists.** Current preparation has not tested a human's interactive masked entry; the encrypted success tests inject ephemeral synthetic char arrays, and a real child JVM proves the no-console refusal. If interactive masking is unavailable, stop and report that limit; do not supply the password by another channel.

   The launcher refuses inherited JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS rather than accepting unreviewed Java agents, diagnostics or heap-dump settings. Use the reviewed wrapper, not a custom JVM command. This cannot secure a compromised terminal/JDK/OS.
4. Output is category-only text on the terminal. No result file is created by the reader. Keep even that report local unless the user approves its release and someone reviews redaction. Exit0 means the file was inspected through the three independent bounded capabilities; it does **not** mean restore or full portability. Exit2 is refusal/failure; exit3 is cancellation. Some capabilities can report BLOCKED/INCOMPLETE while S0 remains FORMAT_READABLE. Do not label that alone archive corruption.
5. Close the terminal invocation normally. Source and existing application files remain in place. The reader creates no data root, staging folder, journal, intent or extracted ZIP. It closes its read guard, clears its password char array and closes the census. Do not delete user data as remediation for failure. Failed/crashed JVM memory and OS access-time behavior are not covered by an erasure guarantee.

## Capability and confidentiality limits

- S0 retains source/decrypted ZIP64MiB, expanded128MiB, per-JSON8MiB and existing ZIP32/file limits; this tool does not enlarge them. A legitimate larger official archive may be unsupported. Envelope1/2 and official codes84–86 follow the existing S0 reader; v2 is never restorable here.
- S1 reports existing Moments graph classification separately. Known credentials, unknown entity families or unresolved dependencies may block it. It does not persist or schedule tasks.
- G2B covers only its declared default official145/code84/v1 known credential locations. Characters/chats/media/SaveSlots, unknown fields/entities or nested content may make it INCOMPLETE. It never queries target credentials or installs keys. Complete means only that known-source subset, not arbitrary-text secret absence.
- Plain archives have integrity checks, not password-based authenticity. Encrypted archives must pass full authentication before trusted results. The initial envelope hint is untrusted and only determines version/password preflight.
- Report construction uses explicit fixed enums, booleans and numbers. No report object, parser error, original path/name, entity text, token/password or source/record digest is printed. There is no private raw report or automatic upload. Public test evidence contains **only independent synthetic fixtures**.
- Windows read-only sharing denies writes/deletes during the scans; native no-follow/reparse checks are reused at each read, with before/after identity and private digest comparison. This is bounded synthetic Windows evidence, not a security proof against a privileged adversary or directory replacement races outside the existing reader contract. UNC/device/alternate-stream/relative paths and non-Windows execution are refused.
- Password copies owned by the tool/census are cleared. Immutable parser strings, JVM/OS memory, paging, terminal instrumentation and crash artifacts cannot be universally erased/protected by this tool. Java attach and automatic heap-dump-on-OOM are disabled by the wrapper, not proof of protection from administrators.

Formal validated baseline stays ChatBar1.4.1. Selected official145 is a format reference only. P11/D-038/Post-P8/Public Preview HOLD remain. No G2C or real-data authorization is implied.

## M1R1 prepare-only correction

The external init helper resolves Test's runtime classpath in doFirst, not configureEach, so Kotlin can finish test dependency setup on a prepare-only graph. Independent prepare-before/test/prepare-after and actual launcher evidence is in `docs/desktop/evidence/p11-r0-m1r1-20261023/`. The new M1SyntheticFixture producer and ConPTY probe are synthetic-only testing tools, not user-archive readers. A fixed dummy-password encrypted fixture is generated without guessing prior build artifacts; the handoff supplies its exact path and single human command. Automated ConPTY did not obtain Java Console here and remains INCONCLUSIVE; human masking is NOT TESTED. No real-file read authorization follows.
