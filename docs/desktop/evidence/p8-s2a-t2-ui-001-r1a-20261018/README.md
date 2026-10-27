# P8-S2A-T2-UI-001-R1A — engineering candidate

**P8-S2A-T2-UI-001-R1A ENGINEERING CANDIDATE / PROJECT REVIEW PENDING; S2A remains PENDING.** Automated results and actual user text signatures are separate. This evidence does not declare Project acceptance.

Source checkpoint `fa5d3f18eebbd6c34f607d7b52d47e24825cb411`, direct parent `c4dece60cddc1aedf673cb532b5976554690b4ea`, branch `feature/phase8-fish-audio`. Exactly three files +129/-2. The two UI files are byte-identical to the Project-reviewed R1 drafts; only the new presentation test changed in R1A. Original R1 was+84/-2; its patch SHA-256 remains `8175acf5f78b56014a5f0d0dc688619cab5bcadf63aab75fff6947bcd0a3e441`. Complete fixed patch, relative R1 test delta, normalized Git/worktree identities and baseline identities are separate records.

## Diagnosis and test driver

One authorized bounded reproduction logged the same active rememberCoroutineScope after resize, scroll maximum1902px, requested1393px, action entered but no movement/return in5000ms. Cancellation was explicitly joined. Local Compose1.10.3 bytecode shows render() defaults to timestamp0 and ScrollByOffset uses animation. Constant frame timestamps explain why the original animated action did not advance. No separate advancing-clock-only experiment was run; we do not claim a single-variable causal comparison.

The corrected test advances a local monotonic frame timestamp and sends actual pointer wheel events using an existing project pattern. It checks measured scroll offset, heading movement and each control's unclipped viewport bounds, then clicks audiobook, discard and save and checks persistence. Controls need not be simultaneously visible. Equal19sp/SemiBold styles, shared semantic parent, unique heading, subordinate Fish Audio, existing fields and stale-model protection remain asserted. Existing five presentation tests are unchanged. Original5000ms timeout is unchanged; no ignored asynchronous exception, extra skip or exclusion.

## Actual separate executions

| Stage | Exit | Suites/tests | Result |
| --- | --- | --- | --- |
|01 selector error|1|No tests executed|Wrapper failed to quote a spaced method selector; Gradle treated voice as a task. Raw command/error retained. Five stale XML copies remain local and are explicitly excluded; their byte identities are listed.|
|02 original-driver diagnostic|1|1/1|Known5000ms timeout reproduced; raw RED preserved.|
|03 corrected wheel test|0|1/1|PASS, first repaired execution.|
|04 five Fish/Settings classes|0|5/54|54 PASS,0 failures/errors/skips.|
|05 bounded Desktop|0|153/1493|1491 reported PASS,2 SKIPPED,0 failures/errors; original four exclusions unchanged.|
|06 createDistributable|0|No JUnit|One actual new distribution build.|

These executions are not summed into a fictitious single test run. Full commands, measured durations, raw stdout/stderr, original current-execution XML and resource samples are included. Task-local test --rerun was used; no --rerun-tasks or clean. JDK17, Gradle9.1, offline/no-daemon/no-parallel, single worker, low priority, Kotlin in-process, existing2GB heap.

Desktop main compiled in original R1 and test source recompiled in stages02/03; unchanged compilation tasks later report UP-TO-DATE. SharedCore is UP-TO-DATE; Android NOT RUN. No unchanged SharedCore/Android/Fish historical tests are claimed as new executions. The two existing symlink skips and historical platform/TOCTOU/NOT TESTED limits remain. No OOM observed; resource extrema are sampled only.

## Exact runtime and actual user gate

New distribution:212 files; complete manifest independently verifies original and isolated copy. Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`; business JAR SHA-256 `a3c1a27586366c97d6d718fcf06726c6a9784cf947a3b0c0b9c2d9c36b206847`. Both packaged UI class entries match current compiled classes. The old T1 JAR was not reused. Runtime files and synthetic credential store are not committed.

A new isolated LOCALAPPDATA, synthetic cards/sessions/credentials and loopback-only TLS fake service supported manual01 and the same-profile manual02 reopen. Process-only proxy/public truststore/UDS configuration; no system certificate/proxy/Registry/pagefile changes. Three local fake readiness calls returned200/200/intentional401; these are not live or user UI requests. Real Fish increment GET0/TTS0.

W02-R1 received explicit user text PASS: “标题同级、模块清楚，窄窗滚动正常”. The new gate has48 user text signature/clarification records, no new screenshots. See `MANUAL_RESULTS_CN.md` for the25-item table, and `manual/user-signatures.jsonl` for original text. Composite manual rows are deliberately15 PASS /10 NOT TESTED: ordinary subcases within partial rows remain PASS, while unperformed injected faults/races/model combinations are not promoted. Old T2 remains4 PASS/1 FAIL/20 NOT TESTED, including its historical W02 FAIL. No FrameArrived retry was made.

Both STRUCTURED/FREEFORM selection/save/reopen paths, explicit/discarded removal, native JSON/CCB PNG export/copy/as-new import, global/session ordinary settings and full app reopen were exercised as scoped in the table. Original card/person identities remain stable on edit; existing transfer format omits internal IDs and new imports/copies receive fresh IDs while retaining complete binding snapshots. First export accidentally selected the structured row; user later confirmed the mistake. Both raw exports and the initial mismatch are retained. Copy uses the existing `(2)` suffix, not our inaccurate “副本” wording.

Actual process evidence:

| Run | Launcher | JVM child | Exit codes | Active lock attempt | Post-exit independent acquire/release |
| --- | --- | --- | --- | --- | --- |
|manual01|19296|26216 (jvm.dll)|0 /0|75, held as expected|0|
|manual02|38880|13940 (jvm.dll)|0 /0|75, held as expected|0|

Processes are identified by image/creation time/parent relation and retained process handles, not PID numbers alone. Watcher close timestamps are null; no exact UI-close time or drain delay is invented. Runtime durations include user waiting, not startup latency. Final read-only check saw old PID26216 reused by a new node_repl.exe; it was not touched. An earlier sandbox CIM query failed permission checks but its first derived summary incorrectly mapped errors to absence; raw derived record and explicit correction/authoritative follow-up are retained. This is a verification-script issue, not product exit evidence.

Fake service gracefully drained and exited0. Its temporary private P12/public truststore are gone; synthetic roots, exports and runtime files remain. Both original and copy runtime manifests reverified212/212 after final close. No credential-store bytes, TLS keys or full runtime image are published. No system settings or user programs were changed. W22 pending-query native cancellation, deterministic manual race/fault injection, new automatic screenshots and broader platform/TOCTOU/full GUI acceptance remain NOT TESTED; automated technical coverage is not mislabeled manual PASS.

All original R1/T1/T2 evidence and failures are unchanged. Protected personal root Markdown and three historical JPEGs are unread/unmodified/untracked/unstaged. No real user data or credentials, live Fish call, Prompt/schema/baseline/parity change, later Fish/G2 slice, upstream sync, merge or release.
