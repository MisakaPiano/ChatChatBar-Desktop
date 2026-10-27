# P8 S7 R3 offline fixture preflight

**R3 OFFLINE PREFLIGHT PASS / USER READY REQUIRED** — 2026-10-20 JST.

Base/evidence parent: `39fdc97d1b34591fe2f61329788f239ddff2b0d3`. Frozen product source: `008343bb26d2eb76c23cae9b98132af828a9f447`. Only new independent evidence is committed. All implementation/validation helpers and new synthetic data are in the ignored R3 build directory; no product Kotlin, Gradle, Prompt, Entity/schema, CURRENT, parity or upstream baseline changed.

## New isolated environment

`H:\ChatChatBar-Desktop\app\desktopApp\build\p8-remaining-20261019\windows-final-gate-r3-20261020\`

Synthetic root: `environments/p8s7-profile/ChatChatBarDesktop`. Original R1/R2 directories, locks, screenshots, logs and committed failures remain unchanged. `mock-service`, `manual-start-once.lock` and `runs` do not exist in R3; no fake-owned directory was pre-created. Only `P8S7Support.mock()` may create its directory after future explicit GUI authorization.

Original distribution is copied without modification: **212 files**, source identity unchanged, manifest SHA-256 `cd15d79587869d864ea4777aecef7a557086aa95d6838146c8f7bf83f652a7f7`. Full file identity is in `distribution-manifest.json`; fixture/support identities are in their separate manifests and `identity-result.json`.

## Fixture-only corrections

1. Both persisted model JSON `apiKey` fields are empty. Model filenames use the existing `WindowsSafeModelStorageKeyPolicy` mapping, so both list and exact logical-ID reads work.
2. Three distinct invalid synthetic values are generated at runtime and saved through the real WindowsSecretStore: FishAudioApiKey and ModelApiKey for each of p8s7-chat/p8s7-aux. No value is written to JSON, source, launch arguments, logs or evidence. The fake, when later authorized, will read only these synthetic protected entries for its allowlisted endpoints.
3. The old fixed credential literal was removed from the helper. `fixture-helper-redacted.patch` intentionally redacts that prior deleted literal; it is a redacted explanatory diff, not a byte-exact original-source patch. The new actual helper source and raw hashes are retained.
4. R3's helper reads the synthetic model keys separately from the Fish key. Anonymous sample behavior, loopback-only forwarding refusal, canned response generation and one-shot manual launch ownership otherwise remain unchanged. No real external account is used.

Protected blobs, plaintext runtime values, seeded user-data copies and distribution binaries are not committed. The fixture manifest hashes safe JSON metadata; it is not a secret dump.

## Actual commands/results

| Stage | Result |
|---|---|
| 01 helper compile | **RED exit1**, four Java generic inference errors in the validation helper; no seed/hydrate ran |
| 01b helper compile | PASS exit0 after explicit generic type parameters; original RED source/stdout/stderr retained |
| 02 auxiliary payload selfcheck | PASS exit0; pure offline synthetic JSON/SSE payload check |
| 03 protected synthetic seed | PASS exit0; fresh root, blank model JSON and three independent protected scopes |
| 04 real policy/repository hydrate | **PASS exit0, eight explicit checks** below |
| 05 independent root-lock probe | PASS exit0 acquisition/release after fixture operation drain |

These are bounded Java helper assertions against exact packaged production classes, not JUnit tests. No JUnit XML is fabricated. No Gradle, Robot, bounded, app GUI, fake listener, sample download or TTS was run. Real Fish GET0/TTS0; paid auxiliary0; QQ sends0.

Eight actual checks in `04-real-policy-repository-hydrate.stdout.txt`:

- Real protected-store roundtrip and typed-key isolation: three distinct values, both model keys reload through a new store instance, unknown logical key is absent.
- Real Desktop credential policy and ModelRepository initialization/list/exact-ID hydrate: **both models loaded** with keys equal to their own protected entries.
- The nonblank persisted-form guard still throws the exact unsafe-credential exception; nothing is silently cleared or migrated.
- A newly instantiated real repository and protected store rehydrate successfully.
- Actual Studio dependency `EffectiveModelResolver.availableChatModels()` returns the one chat-selectable model; auxiliary list returns both. Preset fallback is an assertion failure, not a successful empty-list substitute.
- Actual CharacterRepository reads both modes, two cards and four nonblank Fish bindings.
- Actual ChatRepository reads two sessions and four messages with valid card/model references.
- Model JSON raw bytes remain unchanged after positive hydration and the negative guard check.

The fixture owner held the real data-root lock during the check, drained the real operation gate before releasing ownership, and exited. This is not a running CCB lifecycle/Launcher/JVM acceptance test.

## Studio product issue remains OPEN

See `STUDIO-CREDENTIAL-ERROR-PATH-001.md`: **OPEN / PRODUCT ERROR RECOVERY REVIEW REQUIRED**. Source analysis and future focused test plan are recorded. R3's dependency-path PASS does not prove Compose error recovery, error-dialog behavior, or complete Studio loading. The exact controller UI projection and global close callback were not executed. No product workaround, swallowed exception, false empty catalog, automatic key migration or forced process exit was introduced.

## Protection and next boundary

R1 fake-directory RED, R2 unsafe-credential/error-acknowledgment exit, all historical RED/SKIPPED/INCONCLUSIVE, and four user-owned files remain preserved. User files remain KEEP / UNTRACKED / UNREAD / UNMODIFIED / UNSTAGED; no content hashes were taken.

R3 GUI has **NOT EXECUTED**. Wait for a new user Windows-readiness confirmation before one controlled launch and W1–W5. Do not reuse or bypass R1/R2 locks. W5 persistence restart needs another explicit authorization after actual exit/lock verification. No P8 closure, merge or release.

Evidence manifest lists every member's raw length and SHA-256 except itself; independent Git evidence commit follows the base. Skill relevance review: fixture-only correction and issue analysis do not change existing product skill behavior, so no skill edit is required in this evidence-only commit.
