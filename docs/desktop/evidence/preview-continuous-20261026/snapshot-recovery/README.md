# Small synthetic snapshot restore and independent repository reopen

2026-10-26 JST. Existing task203 / Preview Gate A supplemental execution, not a
new phase, production schema or acceptance signature. The preceding docs-only
checkpoint is `c0b2ac1efb18da0816c649b96e964383cd29e8d5`.

## Exact scope and result

The final **1 Python-driven integration suite /2 PASS /0 failures/errors/skips /
exit0** executes **six independent JDK17 child JVMs**. The XML is JUnit-compatible
output from actual Python unittest results, not two additional Gradle/JUnit
methods or six extra tests. Kotlin companion compilation exit0. No Gradle,
application-image rebuild or installation occurred.

Unchanged production code is the1010 candidate source
`946c3791153634b0af65286313a497d141d5c598`. All63 runtime JAR lengths/SHA-256 match
the pre-existing frozen1010 manifest before the final run and remain unchanged
afterward. `raw/green-final/INPUT_JARS.json` is the exact input list;
`SOURCE_IDENTITY.json` binds pre-test companion/driver source hashes and Git blobs,
compiler2.3.20 and compiler-only reflection2.2.10. No compiler support JAR enters
the application or child runtime.

|Actual round|Executed result|Explanation|
|---|---|---|
|environment-red|Compile exit1;0 tests|Missing compiler reflection class in the runtime-only candidate classpath; retained actual failure. Explicit local compiler support added, no product change.|
|probe-red|2 tests /2 FAIL /exit1|Both seed subprocesses failed because the probe hashed the root ownership lock before excluding it. Windows refused reading the held lock. Probe inventory now excludes that reserved file before reading; production locking unchanged.|
|green-before-manifest-pin|2 PASS /exit0|First successful round; retained separately, not accumulated.|
|green-final|**2 PASS /exit0**|Final pre-test sources, explicit frozen-JAR manifest verification and child PID logs; all inputs unchanged.|

The deliberately corrupted snapshot is an **expected fail-closed negative case**,
not a fabricated RED. Prior failures remain intact. There is no production bug
claim from the two probe/environment failures.

## What actually ran

1. Newly created, marker-bearing synthetic roots only. Official repositories
   persist a small invented character/session/message/draft and generated3×2PNG.
   `DesktopCoordinatedSnapshotService` creates a MANUAL snapshot under exclusive
   admission. Then an additional message, different draft and different image
   bytes establish the newer live state.
2. A separate JVM takes normal root ownership, restores the chosen snapshot
   through that same production facade, checks `RESTART_REQUIRED`, and verifies
   that normal business admission is rejected. All old live payload hashes match;
   the chosen snapshot's complete file hashes are unchanged. The automatic
   PRE_RESTORE safety snapshot validates and exactly preserves newer payload.
3. Two further independent JVMs reacquire ownership, construct new repositories
   and verify old character/session, the single old message/content/image
   reference, old draft, decoded PNG dimensions and exact file hashes. They also
   validate the safety snapshot and newer-state hash map. They create no bootstrap.
4. In another synthetic root, the driver damages only the selected snapshot's
   PNG. A new JVM observes invalid snapshot validation and restoration refusal;
   live newer-state hashes and the single existing snapshot count remain
   unchanged. The coordinator remains available because no restore was applied.
5. Every child closes coordinator admission and root ownership; the next process
   successfully reacquires it. Six distinct child PIDs and successful fixed stage
   markers are in final raw logs. This is repository reopening, not Main/GUI or
   full StartupGate validation.

## Evidence and reproduction

`RAW_INDEX.json` binds **71 raw files /132,987 bytes**, including all rounds'
source snapshots, exits/logs/results, actual unittest XML, final63-JAR list and
invented before/after/snapshot file hashes. No binaries or real archives included.
The source snapshots must match their pre-test Git blobs after staging.

Actual Java/compiler commands are preserved locally in the four fresh owned
`app/desktopApp/build/preview-continuous-private-20261025/snapshot-recovery-{first,second,third,final}-20261026/`
rounds. `LOCAL_COMMAND_INDEX.json` records original command byte lengths/SHA-256.
The18 public `commands/` copies are explicitly redacted derivatives, not raw
commands; machine-specific compiler-cache location is replaced by a placeholder.
To reproduce final behavior, use the committed driver with explicit arguments:

```text
python tools/desktop/windows/snapshot-recovery/run_synthetic.py
  --image <frozen1010 image>
  --expected-jars <pre-existing frozen-production.json>
  --compiler <local kotlin-compiler-embeddable-2.3.20.jar>
  --compiler-support <local kotlin-reflect-2.2.10.jar>
  --java <JDK17 java.exe>
  --round <new owned round below the required private build directory>
```

The driver forces fresh rounds, one processor,256MiB compiler/128MiB children,
headless mode, task-owned home/AppData/temp and removes inherited JVM options.
No app-wide build, network request, native installer/registry/ACL operation,
SecretStore/DPAPI call, physical Android, process kill or cleanup is part of it.
Existing source/UI/restore paths and all prior evidence remain unchanged.

## Limits retained

This does **not** prove restoration of the previously retained real large-root
migration snapshot. It does not restore protected credentials, test power loss,
cross-account/physical-device behavior, full StartupGate/GUI, MSI upgrade or
uninstall. Those facts remain separately recorded; neither this result nor the
updated release draft declares P11, D-038, Gate A–G or Preview PASS. No public
Release, merge or download announcement is made.
