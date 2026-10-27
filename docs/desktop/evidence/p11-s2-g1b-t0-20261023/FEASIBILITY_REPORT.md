# G1B-T0 cross-root recovery feasibility — synthetic experiment

**PROJECT FEASIBILITY REVIEW REQUIRED. Neither topology nor persistent schema is approved.**

Base `5bb326808a7138f0701cedca7793ef080b92a9c6`, feature/phase8-fish-audio. Formal baseline1.4.1; selected official145 remains backup-format authority only. This experiment does not parse/import a .cbbackup: source.synthetic is an explicit non-archive stand-in to test custody/unchanged bytes. S0/S1/G2B actual authentication/completeness gates remain separate and mandatory before any future real plan. Nothing bypasses CREDENTIAL_APPROVAL_REQUIRED.

## What actually runs

Two new test-source Kotlin files implement an isolated file protocol and20 JUnit tests. Existing WindowsSecretStore is instantiated only with a NON-CRYPTOGRAPHIC FakeProtector and explicit build-owned paths; real envelope handling, blob writer, native data-root ownership and Windows native file-ID observation are reused. No WindowsSecretStore.create, real LOCALAPPDATA/profile/DPAPI, Repository, Provider, live StartupGate wiring, formal recovery or Moments domain is created.

Each fixture has roots A/B, root UUIDs, bindings (same logical Model ID/provider/endpoint), data generations1/2, at least two dummy keys, independent old/new blob/data copies, a reservation/intent, final decision and receipt. Logical ModelApiKey(same-model) and FishAudioApiKey use actual existing key names. Option A resolves one shared profile credential root through the actual resolver. Option B explicitly injects two test-only namespace paths; this does **not** change production resolution or establish a production namespace.

The experiment takes an OS FileLock on a synthetic coordinator file and actual DesktopDataRootOwnership locks in sorted order. It validates directory identity and the frozen binding, retains OLD/NEW bytes, publishes test metadata, compares actual file hashes before writes, and installs one file at a time. A test business callback only reads data/keys and writes a counter after whole-participant verification under the same cooperating locks. A blocked case asserts that counter never exists. There is no physical multi-file atomic commit: partial bytes really exist after halt/failure, and the cooperating admission gate excludes them.

Normal completion chooses ALL_NEW; incomplete published work with intact authority/evidence rolls back to ALL_OLD. Rollback completion means **import not applied**, not import success. A completed or conflicting decision/receipt cannot be guessed away. Old/new evidence and the source stand-in are retained; no failure cleanup/deletion is performed. Repeated recovery verifies receipts/live bytes rather than trusting a phase label. Unpublished preparation may admit verified baseline OLD because no live writes were authorized.

## A/B result and recommendation

**Within this closed cooperating synthetic boundary, B is easier to isolate; it is the preferred direction for further design, not a final choice.** Its A transaction does not mutate B's keys/data when B is unavailable or binds the same Model ID to a different endpoint. A must reject potentially shared-key mutation when B is offline/unverifiable or bindings conflict. A can recover only when all declared affected roots are online, consistently bound, consented and excluded from business use.

The A experiment deliberately includes B data as a participant when replacing shared credentials. This models the cost of closing affected consumers; it is **not permission to rewrite another user's root**. Actual root-A-only import must either prove B unaffected, obtain explicit participant/account approval or refuse. A single coordinator lock does not supply semantic consent.

An executed negative test creates an unregistered root C: the fixed two-root registry cannot discover it, and its OLD data could read a replaced shared key. Therefore this experiment proves **no global consumer closure**. A is not safe for open-ended dormant roots merely because the declared A/B transaction finishes. Another executed negative test uses the actual store primitive while the test coordinator/root locks are held: it can change a key without honoring those locks, after which the candidate gate blocks. A marker/new mutex cannot constrain an old or noncooperating writer. These limitations are findings, not successful production safety claims.

B removes a class of shared-key collisions only by assuming approved namespace isolation. Durable identity, global/singleton credentials, legacy migration, clone handling, binding/AAD and old-binary enforcement are unresolved. Test namespace letters A/B, UUID files and fixture JSON are not a schema proposal. Copying ciphertext is not account/profile portability; fake XOR protection supplies no confidentiality.

## Actual RED and final results

| Run | Actual result | Interpretation |
|---|---|---|
| first |8 suites/118 tests:117 PASS/1 FAIL,exit1,2m43s | Directory clone could retain logical identity when NIO fileKey supplied no useful distinction. Real prototype failure. |
| red | Gradle command-line rejection,0 tests executed,exit1 | Test filter argument slicing error; prior copied XML quarantined as stale-junit-NOT-EXECUTED, never counted as a new run. |
| receipt-red |1 test/1 FAIL,exit1,25s | Completed receipt with lost decision incorrectly rolled back to OLD. Real prototype failure. |
| green |**8 suites/119 PASS,0 failures/errors/skips,exit0,2m12s** |20 new tests +99 existing actually rerun; not accumulated historical totals. |

The first correction uses existing DesktopDocumentIndexReadEvidence's Windows volume/file ID instead of assuming NIO fileKey identifies directories. Binding hashes are frozen with identity. The second correction refuses recovery when completion evidence survives but the final decision is lost. Native file IDs were exercised on this host with synthetic directories; this is not account/DPAPI, physical Portable or reboot validation.

Raw source snapshots, exact commands/exits/JUnit and real child logs remain. Children use Runtime.halt(71) before/after intent or during writes; recovery can halt(72) mid-rollback. Fresh JVMs then acquire the released native locks and read the persisted files. Child process outcomes are real process executions, not printed fictional phase logs. TEST_MATRIX.md maps conditions and limits. Raw fixture inventory includes retained artifacts from **all actual rounds**, including RED, not a claim that every retained final tree belongs to GREEN.

## Limits of the prototype's evidence

- Manifest/receipt hashes detect tested corruption only within a trusted fixture workspace. Unkeyed checksums are not authenticated authority against an actor rewriting the whole journal/registry. No ACL, protected manifest, secure context attestation, global consumer census or persistent fencing is proved.
- Fixed participants, existing nonempty keys, a test-supplied consent boolean and synthetic bindings are assumptions. This is not consent UX, account ownership proof, absence/tombstone policy or arbitrary .cbbackup graph validation.
- Native file-ID and root-lock calls are real on Windows. Renaming a synthetic directory simulates unavailability; an unsafe key directory simulates unreadable/unknown storage. Neither proves physical disk disconnect, OS access-denial behavior, independent Windows account or cross-volume semantics.
- force(true) and successful ATOMIC_MOVE plus process halt are not Windows reboot/power-loss guarantees. Directory flush, controller caches, disk full and interruption during native rename remain unproven.
- Fake context mismatch is rejected as a synthetic assertion. Real DPAPI usability/context loss and same-SID legacy writers require separately authorized Windows experiments. Check/use races with a noncooperating external writer are not solved by the test lock.
- A same-source primitive bypass is not an executed historical binary. Old binary/installer/GUI/Android/paid API were not run. No production activation permission follows from the test callback.

## Decisions and next slice

DECISIONS_REQUIRED.md lists durable identity, migration/retention, conflict and authority questions for user/Project. Recommended smallest future **separately authorized** production slice: an internal, unwired, read-only target-domain identity/consumer-evidence observer, binding native root/store identity, logical account/provider/endpoint and generation observations to G2B/G2A metadata. It must reject unknown identity/consumer closure and make no key/intent/registry writes. Native/file identity failure, clone/rebind, endpoint drift, concurrent observation changes and noncooperating writer limits need explicit tests. This closes an evidence gap without selecting a persistent namespace or activating restore.

Do not implement a durable registry, namespace migration, intent or “safe direct import” based on this lab. That requires architecture/retention/account decisions plus genuine Windows protection and durability tests first.

**STOP: P11-S2-G1B-T0 PROJECT FEASIBILITY REVIEW GATE. G2C NOT AUTHORIZED; P11 PENDING, D-038 BLOCKED, Post-P8 HOLD, Public Preview HOLD unchanged.**
