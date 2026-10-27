# T1 source authority and scope

Base: feature/phase8-fish-audio `e90fc229e6ea057bbbc05d12b943608811885cbe`. Local tracked tree was clean; safely fast-forwarded from T0 receipt. Origin matched the requested base; desktop remained `5ef95428421f7c693b010ac88f51d470804ab07a`. App inventory showed no other active task at this repository. Six existing unknown attachment/handoff/cache files were left unread, unhashed, unchanged and unstaged.

Authority selectively reviewed: 00 Source Map, 10 baseline, 21 Current State, 13 parity, 14 compatibility; 163 unified candidate, 168 G2A acceptance, 169 cross-root review, 172 G2B acceptance, 177 T0 acceptance and 95 portability gate. Desktop full-backup Skill and the fixed official145 app-backup Skill were read. No separate Desktop SecretStore Skill exists in the catalog. Formal baseline remains1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; official145 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` is format reference only.

| Existing source | Governing fact |
|---|---|
| DesktopCredentialImportPlan | Reuse ImportCredentialTarget, ImportAccountIdentity, ExistingSecretRelation, valid/key/sameAccount/nameCollision semantics. Exact model ID/provider template/endpoint/remote model comparison; no values accepted. |
| DesktopCredentialSourceCensus / VerifiedCredentialSourceMetadata | Complete refers to a known code84/v1 subset after full Inspector validation; source completeness never proves target closure. Existing observer is synthetic-only, not native account/CAS. |
| WindowsSecretRootResolver | Explicit environment-map overload resolves the same profile directory for different data roots; path isolation is not SID/DPAPI identity. T1 never calls ambient resolve. |
| DesktopSecretStore / WindowsSecretStore | load decrypts; save/delete write. None is called or injected into T1. Existing logical keys do not include provider/endpoint or per-root namespace. |
| DesktopDataRootOwnership | A cooperative root lock, not all-consumer coordination. T1 does not acquire/write this lock or call StartupGate. |
| DesktopDocumentIndexReadEvidence | Reuse native attribute-only observation, final-path/reparse checks and finally-owned handle closure; no directory contents or file bytes read by T1 adapter. Native volume/file ID is not a profile ID. |
| Accepted T0 lab and tests | Copy/replace identity, offline consumers, receipt loss and legacy bypass findings remain. T1 is not the T0 file protocol moved into production. |

Only two new internal main files and one new test file are introduced. One compact Skill entry documents the stable new entry points; no existing production/test/Gradle/Prompt/schema behavior changes. Source/Git blob identities and an automated existing-file projection/nonwiring audit are retained under raw. No CURRENT acceptance/parity promotion is made by Codex.

T1 inputs are explicit synthetic declarations. The adapter requires absolute strict-descendant paths inside an explicitly supplied isolated sandbox and checks sandbox and target through the existing native reader. This is an invocation restriction, NOT an OS permission capability. A malicious caller inside the module could forge injected declarations; the API grants no restore capability even then. No environment lookup, path discovery, registry or persistent identity is introduced.

The component retains only scoped native witnesses and G2A metadata, not Key values/digests/JSON. Configuration snapshots are copied with UNKNOWN secret relations. Raw path/native IDs/account text are redacted in object rendering; exceptions from observations become fixed categories. Cancellation propagates as a fixed sanitized cancellation without producer cause. Runtime source references are checked to be confined to the new internal files; all other use is in tests. No class has a GUI/Service/Startup/Provider constructor call site.
