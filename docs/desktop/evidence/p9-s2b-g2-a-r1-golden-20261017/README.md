# G2-A-R1 independent frozen golden evidence

**G2-A-R1 TEST CANDIDATE / PROJECT REVIEW PENDING** — 2026-10-17.

Test-only source `23acfda3b0aa19a0167dc747337561f42085ec17`, parent `732e0e0ea9fece5295a864d170e011001b10351e`.
22 files **+129/-0**: one existing test +85 lines, 21 fixed resource files +44 lines.
Three production Kotlin files and all other production/contract/Gradle/shared sources remain unchanged.
The existing runtime fixture builder and the other two G2-A classes were not changed.
Formal validated baseline remains ChatBar 1.4.1. No admission/Writer/recovery/Main integration.

## Independent oracle and exact byte identity

`app/desktopApp/src/test/resources/g2-a-j-v1-golden/` contains a fixed complete first-generation J-v1 transaction: 18 raw transaction/live files, including six control files, old/stage card, two stage/live vectors, source, three JSONL manifests, seal, marker, receipt and HEAD. Empty required directories are explicitly listed and materialized only inside each synthetic root; no placeholder file enters the closed protocol tree.

Frozen manifest: **5037 bytes**, SHA-256 **b4afa563dc58b9830188d0d8d76b1d08a42259cfba795aacebaa2d4d8ad09bde**, literally pinned in Kotlin. File paths, sizes, raw hashes, control kinds and JSONL row order are frozen. Independent SHA-256 audit verifies 18/18 raw files, all six controls and cross-file references. The fixed manifest and resources existed before the first test run and were not revised after execution.

`author-fixed-golden.py` is the one-time standard-library authoring script retained for provenance, NOT invoked by Gradle/tests. Fields and two literal chunk texts were specified independently by reading doc134 and the existing shared ChunkingEngine; no JWire/Inspector/Entity serializer or runtime prepare() output generated the oracle. `audit-fixed-golden.py` reads the frozen resources without rewriting them. Tests copy classpath bytes under real ownership; the existing fixture's in-memory card is not used to build golden expectations.

Resource-local .gitattributes disables byte conversion. All 21 resource Git blobs match worktree bytes exactly; 22/22 candidate blobs match source-manifest, with ordinary Kotlin LF normalization recorded separately. Complete patch SHA-256: **d0ee6e8648ec60b6c12d992641bb3f53585ddfe1b5840946cb561f641923086e**.

## Actual commands, compilation and raw JUnit

Exact commands/UTC start/end/exit/durations: stage `command.txt` and `stage.json`. Full untouched stdout/stderr, resource JSONL, JUnit XML and derived summaries are separate files. JDK Temurin 17.0.20.1, Gradle 9.1.0, existing -Xmx2048m; offline/no-daemon/no-parallel/max-workers=1/priority=low/no-configuration-cache/in-process. No clean/rerun-tasks.

| Run | Exit | Wall seconds | Suites / tests | Reported PASS | Fail / error / skip |
| --- | --- | --- | --- | --- | --- |
| 01 independent wildcard | 0 | 52.4687959 | 1 / 4 | 4 | 0 / 0 / 0 |
| 02 three affected G2-A classes | 0 | 52.0791042 | 3 / 46 | 45 | 0 / 0 / 1 |

Stage01 wildcard also matched the existing `independently hashed entity...` method: four executions comprise three new golden tests plus that existing test. Stage02 is the final coverage total; overlapping stage counts are NOT added.

| Final suite | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| DesktopDocumentIndexReadEvidenceTest | 1 | 0 | 0 | 0 |
| DesktopDocumentIndexTransactionInspectorProcessTest | 3 | 0 | 0 | 0 |
| DesktopDocumentIndexTransactionInspectorTest | 42 | 0 | 0 | 1 |

Stage01 Desktop compileTestKotlin and processTestResources actually execute and PASS. Desktop main, sharedCore and fixtures are UP-TO-DATE; not claimed freshly compiled. Stage02 test compile is UP-TO-DATE, test actually executes. Seven old startup regressions, Android, full regression, Windows GUI/EXE, packaging, real user data and paid API: NOT RUN in R1. Previous evidence remains at `../p9-s2b-g2-a-inspector-20261017/` and is untouched, including its RED and NOT TESTED history. No new RED occurred.

## Behavior and zero writes

- Fixed complete transaction is COMPLETED_STRUCTURE_ONLY, observed live EXPECTED_NEW; businessAdmission is BLOCKED and all four absent production authority proofs remain missing.
- One original stage-vector byte changes with frozen evidence unchanged: both independent ledger comparison and Inspector reject (CORRUPT).
- Appending legal JSON whitespace preserves parsed card value but changes raw length/hash: frozen ledger and Inspector reject (CORRUPT). No reserialization erases byte identity.
- Every check compares full synthetic before/after file/directory identity, size and byte hashes. Fixture setup and deliberate negative-case mutations precede the baseline; they are not inspector writes. Reserved ownership file content is not read. Stage01 has 7 equality records; final stage02 has **69 full equality records**, plus **3 process-span equal inventories**. Every saved before/after map matches.
- Final process tests retain six actual child identities (three inspection/cancellation runs, three independent LOCK reacquisitions), all exit 0/alive=false. They exercise the existing dynamic fixtures, not a fresh-process fixed golden; fixed golden executes in the Gradle test JVM. No forced termination/lock deletion.

## Resources, platform limits and scope

10 periodic samples: min Available **4.738 GiB**, min Commit headroom **4.435 GiB**, max **2949 pages/sec**, sampled Java count at most 4. Aggregate sampled Java working set max 1,096,126,464 bytes; private bytes max 1,735,856,128. Sampling extrema are NOT absolute peaks; Working Set can count shared pages repeatedly. No OOM/build failure observed; post-run Java count 0. Foreground Chrome/NAI responsiveness NOT TESTED; those programs were not operated or terminated. No RAM hard gate was imposed.

One unavailable real symlink fixture is JUnit SKIPPED. The prior four G0 symlink early-return branches remain NOT TESTED and were not rerun; no platform coverage is promoted. Existing limitations remain: real symlink/8.3 aliases, held-root replacement, complete Windows TOCTOU/ABA, all ACL cases, actual CloseHandle failure, physical blocking-I/O absolute deadline, power-loss durability. No production authority, G2-B/G2-C or complete P9 acceptance.

No secrets, real user data, private roots or JPEG content are included. Sensitive review is scoped to new records; three external JPEGs are untouched/unread/untracked. Tracked worktree/index will be clean after evidence publication, but entire worktree remains unclean because of those JPEGs. Project skills reviewed: test-only oracle adds no stale feature ownership/workflow statements, so no skill changes.

Raw artifacts are copied byte-identically (raw-copy-provenance.json); evidence-local attributes preserve CRLF and raw console trailing spaces without rewriting logs/XML. Evidence manifest covers every other evidence file. This is a candidate, not Project acceptance. Stop; no subsequent phase.

Publication whitespace diagnostic: the raw unified patch contains a blank context line encoded as one space. The initial diff-check is retained in publication-initial-diff-check.log. The unchanged patch is marked as a binary byte artifact locally (like raw logs); source and XML/JSON/Markdown whitespace checks remain enabled. No patch/log/XML bytes were trimmed.
