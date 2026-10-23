# P11-R0-M1R1 Prepare-only correction

**PROJECT CODE REVIEW REQUIRED / SYNTHETIC ONLY.** Base61efd0b72c1faf797c7dd8f78268470a23bade45, safely fast-forwarded from clean99909d1a after remote check. Only this active same-directory task was observed. Six unknown attachments/handoff/cache files remain unread/unhashed/unchanged/unstaged. No reset/force, desktop movement, upstream sync, real root, archive, API or DPAPI use.

## Proven cause and correction

USER_FAILURE.md records the user report separately. Codex independently executed the actual unchanged Windows PowerShell Prepare wrapper with JDK17: raw/red/prepare.log reproduces the dependency-mutation failure and READONLY_R0_BUILD_FAILED. The wrapper process exit is1. This is now independently reproduced, not merely inferred from USER-DIRECT evidence.

The Test.configureEach callback evaluated test.runtimeClasspath.asPath during task configuration. On the prepare-only task graph, resolving that configuration froze testImplementation before Kotlin finished adding test compilation dependencies. Earlier test-first executions did not cover this ordering. The sole existing executable file changed is readonly.init.gradle: defer that Test system-property evaluation to doFirst. The prepare task's existing doLast classpath writer and Kotlin source injection are unchanged. No Prepare wrapper, formal Gradle, Inspector, src/main, restore, SecretStore, Prompt or Entity changes.

Same actual Prepare wrapper after correction, **before any test execution**, succeeds exit0/1m20s; raw/prepare-before contains the real output. R0 seven suites then execute **117 PASS,0 failures/errors/skips,exit0**, with raw XML and command arguments in raw/green. This is23 tool tests +94 existing, not historical counts. A second prepare-only invocation succeeds exit0/21s, compiling the added test-only plain fixture producer; neither prepare log runs the Test task. Classpath generation, source injection and later Java launching therefore work independently of running tests first.

Check-Launcher.ps1 is an executable synthetic regression against exact newly generated paths, not a search of historical build directories: actual wrapper plain0 / encrypted-no-console2 / dangerous inherited JVM-options2 all match expectations. raw/launcher retains outputs/exits. Run-Synthetic.ps1 preserves the exact7-suite invocation. This registration does not overwrite the accepted R0 evidence or its171-item index.

## Identified fixture and terminal investigation

New test-only M1SyntheticFixture calls the existing independently modeled official145 fixture logic, with code84/v1 and pinned test codec version1. It writes exactly the owned build target, refuses overwrite, verifies encrypted format with the Inspector, checks source unchanged, and rejects a wrong password at AUTHENTICATION. Public dummy password is **FAKE_G2B_PASSWORD_NOT_REAL**. Exact archive bytes, SHA/length, generator output and final source are in raw/fixture and raw/final-source. No actual key/password is used. The later plain producer is compiled by the second Prepare and its actual generated file is checked by the real launcher. This is format tooling evidence, not Android acceptance.

ConPTY-SyntheticProbe.py attempts native Windows CreatePseudoConsole + STARTUPINFOEX child creation, waits for the real Java password prompt before sending any dummy input, captures terminal output, closes handles and terminates only an invocation-owned timed-out child. Direct Java and shell/argument-file routes were investigated; the shell's initial command-length failure is separately retained. Final automated ConPTY execution exits2 with **INCONCLUSIVE_NO_JAVA_CONSOLE**: Java did not provide a Console and the reader refused before input. No password was injected. Thus passwordEchoObserved is null, not a proven false. First/second/third probe snapshots/outcomes and final run are retained; no automated terminal PASS is claimed. Actual human Windows Terminal masking remains NOT TESTED. This limitation is specific to the tested launch environment; it does not prove ConPTY/Java universally incompatible.

## One remaining human check

All other bounded automated checks above have passed. On this machine the following is the single remaining manual terminal invocation; do not select another file or prepare anything again:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'; & 'H:\ChatChatBar-Desktop\tools\desktop\interop\readonly-r0\Invoke-ReadOnlyTool.ps1' -SelectedFile 'H:\ChatChatBar-Desktop\app\desktopApp\build\readonly-r0\m1r1\SYNTHETIC-code84-encrypted.cbbackup'
```

At the local hidden prompt type the public dummy password above. Expected: no visible password characters; S0=FORMAT_READABLE, APP_CODE=84, ENCRYPTED=true, SOURCE_BYTES=UNCHANGED, RESTORE_SUPPORTED=false and exit0. If no prompt, any echo, refusal or error occurs, stop and report the category; do not substitute a password argument/environment/file or a real archive. File path can appear in local process arguments. Do not run a terminal transcript/recording. This is synthetic verification only; no real-file permission follows.

## Review, identity and limits

raw/index.json gives SHA-256/length of all52 indexed raw files; raw/junit-summary.json enumerates7 XML; raw/redaction.json records diagnostic sentinel/private-home-path checks with0 hits. Public synthetic archive hashes/dummy password are intentional; no real key, private archive/path or chat body was collected. This is bounded sentinel/manual output review, not universal OS secrecy. Main app trees and accepted historical evidence are unchanged. Skill relevance reviewed: existing full-backup facts remain accurate; local tool README supplies this correction, no Skill edit needed.

The terminal probe is test-only and not part of application startup. No trusted metadata, restore gate or SecretStore policy changes. Formal baseline remains1.4.1; G2C NOT AUTHORIZED; P11/D-038/Post-P8/Public Preview HOLD unchanged.

**STOP: P11-R0-M1R1 PROJECT CODE REVIEW GATE.** Exact source/evidence commit and final receipt SHA are provided in DELIVERY and the final response.
