# LR6-A authority contract candidate

**PROPOSED / NOT USER APPROVED PERSISTED SCHEMA.** Source-grounded design only;
no schema, broker, protector, namespace, runtime or restore activation was installed.
197/198 authorize this audit. D-045 already approves B + inactive fresh root,
OLD-key retention and dormant voice/C-A directions. Those directions are not
being reopened; concrete security, persistence and deployment choices remain here.

## 1. Fixed source and real execution paths

Feature audit base `ceeeb09e3327aa70a4858548b94520d87f4c2690`;
`app/` tree **`b148fecde3f3604cdf2298d089dfc14eb362ae1a`**. The new static recheck
binds **45 current source files and seven selected official references** in
`raw/source-audit-current.json`, with exact current snapshots in `raw/audited-source`.
Previously audited entries match LR5 SHA/length/Git blob, not just filenames.
This is Codex static evidence, not Project re-review or runtime consumer closure.
Formal baseline remains 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`;
official 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` is only the selected
backup format. No LR6 read of private archives; exact producer of previously
observed code86/v2 remains unproved.

| Actual owner / symbol | Current behavior and missing participant |
|---|---|
| `DesktopDataDirectory.selectRootAuthority` | Absolute override first, then Portable, then bootstrap/default; invalid selected custom root does not default to empty. All routes need future authenticated authority discovery. |
| `Main` → `DesktopStartupGate.run` | Select root → acquire root ownership → index preflight/Portable activation → full-backup, index, session journal recovery → business/container. No profile-wide credential recovery authority. |
| `DesktopDataRootOwnership.acquire` | Cooperating lifetime file lock on selected root, not a broker fence or credential-context lock. Lock filename presence alone means nothing. |
| `DesktopAppContainer` | Lazy `WindowsSecretStore.create(appDataRoot)` shared by Fish/NAI, model policy and embedding paths; repositories and automatic backup initialize after startup. |
| `WindowsSecretRootResolver.resolve` | `%LOCALAPPDATA%/ChatChatBarDesktopCredentials`; selected root only checks overlap, not namespace. CLI/Portable do not move this root. |
| `DesktopSecretStore` / `DesktopCredentialKey` | Global NAI/Fish/SiliconFlow, model-ID key, legacy embedding-ID and singleton embedding. Canonical names do not bind endpoint, account, root or principal. Community has no target key. |
| `WindowsDpapiSecretProtector` | User-context Crypt32 calls, fixed optional entropy, UI forbidden, no LOCAL_MACHINE flag. No live invocation in LR6. |
| `WindowsSecretStore.load/save/delete` | One ciphertext envelope at a time; absent path returns null, IO/protection errors throw. Writer uses temp + atomic move with non-atomic fallback; not a durable multi-key protocol. |
| `DesktopRepositoryCredentialPolicies` | Key mutation and JSON persistence coordinated by local compensation, not crash-atomic across domains. Cannot promote compensation to a durable journal. |
| `DesktopFullBackupService.scheduleRestore` | Validate/map/graph → flush → scheduler stop/join → audio maintenance → process exclusive → owned data-only journal → restart. No SecretStore dependency. |
| `DesktopFullBackupCommitJournal` | Root-local retained OLD/NEXT file rows, schedule/start/commit markers, startup rollback on mixed known bytes. It cannot represent protected-store context or authenticated profile authority. |
| `DesktopBootstrapAuthorityTransaction` | Separate bootstrap sibling lock, fresh selection comparison, save/readback and explicit indeterminate outcome. Not the root ownership lock. |
| `DesktopDataRootMigrationService` | Destination prepare → pause/drain → exclusive → MANUAL snapshot → byte materialization → bootstrap commit + restart seal. D-023–26 only bootstrap-controlled sources; no hot swap; CLI/Portable persistent switch unsupported. |
| `DesktopAutomaticBackupRuntime` / coordinated snapshot / shared `AppDataSnapshotService` | Snapshot selected root, reserved infrastructure and retention rules; external profile secrets and future dormant participants have no adapter. |
| `DesktopOwnedImageCleanup.deleteUnreferenced` | Examines live entity JSON references for exact image/document candidates. A separate sealed manifest does not protect a live file. Independent owned copies and explicit lifecycle adapters required. |

Consumer audit covers direct references in the 45-file ledger: NAI settings,
generation/guidance/post-processing/regeneration/automatic chat images/card and
Studio runtimes; Fish credentials/library/preview/TTS/controller and caches;
model repository/effective resolver and fallback configs; singleton and legacy
embedding/RAG paths. Ordinary settings/repository key writes also matter, not just
restore. Old/offline/unregistered roots, old binaries and detached/cached request
work remain UNKNOWN. A source search cannot establish their absence.

Current Community wire comes from pinned `CommunityModels.kt`: access token,
nullable refresh token, bearer type, Long expiry with default zero, user ID/name.
The selected official backup Skill includes secrets/session by default and
destination expiry handling; Android Keystore replacement is not Windows migration.
G2B validates strict wire and leaves Community UNMAPPED. Nothing in this audit
supplies the missing protected Desktop session lifecycle.

## 2. Candidate trust boundary and concrete placement

**Candidate recommendation, conditional and unimplemented:** broker-owned authority
under `%ProgramData%/ChatChatBarDesktop/RecoveryAuthority/v1/<installationId>/`.
`installationId` is an opaque locator, NOT identity proof. A separately provisioned
principal owns this directory, namespace storage and protected fresh data roots;
normal Desktop/old EXE principal has no direct write, delete-child, ownership or
DACL-change rights there. Approved clients use a versioned authenticated IPC API.
No ProgramData directory, service/account, ACL or registry entry was created.

Exact proposed layout: `identity.json`, `head.json`, `transactions/<txId>/intent.json`,
`participants.jsonl`, `prepared.receipt`, `complete.receipt`; protected blobs at
`namespaces/<rootBinding>/<generation>/<typedParticipantId>.blob`. OLD ciphertext
escrow is immutable under `retained/<txId>/`; old original roots remain untouched.
Fresh payload roots have broker-owned native identity and a descriptor pointing
back to this authority. Private locators/digests/IDs never enter support reports.

All side-by-side/updated clients must resolve the same broker enrollment for a
Windows profile. An authenticated broker-owned locator record has exact fields
`schema`, `version`, `ownerSid`, `authorityId`, `authorityNative`, `installationId`,
`protectorContextId`, `locatorGeneration`, `enrollmentReceiptMac`, `mac`.
The deployment-pinned broker identity/configuration supplies the initial trust
anchor; an untrusted root cannot nominate its own key or authority. A new installer
ID is not permission to create a parallel empty authority. Missing/conflicting
enrollment or an unavailable broker blocks admission on all override routes.
Actual installation/trusted locator enrollment remains separately unauthorized.

Proposal uses a **256-bit service-only HMAC-SHA256 key**, held in the broker and
persisted only as a service-principal DPAPI-protected key blob in the restricted
authority directory, not plaintext beside JSON or derived from the current public
fixed entropy. Optional TPM-backed CNG asymmetric wrapping/identity keys require
separate provider validation; no assumption of a persistent non-exportable HMAC
key API is made. Key identifier/platform witness are required; provider capability,
effective process isolation and non-exportability are NOT TESTED.
DPAPI/CNG under a broker account is a different protection context from today's
interactive user; secure import/rebind requires separate approved adapters. Do not
copy ciphertext and claim a context transfer. Key/profile loss is UNAVAILABLE and
retains evidence, never generates a replacement authority silently.

Same-user-only alternative: keep new roots under ordinary user ownership and use
cooperating gate/markers. It is simpler but **fails enforceable old-writer R03**.
Separate dedicated user deployment can isolate roots without a service, at cost
of a different login/protection/export model. Neither alternative is selected
production schema. Broker recommendation requires an explicit installation,
threat-model and IPC approval; it is not a small rename of the secret directory.

## 3. Exact proposed records and validation

All records use UTF-8 without BOM, duplicate keys rejected before DTO decoding,
unknown fields/enums/version rejected, integers JSON numeric tokens with checked
unsigned ranges, sorted canonical object keys and no floats. MAC input is a
length-delimited domain tag plus canonical bytes excluding `mac`. Raw participant
JSON is a separate immutable blob, not rewritten by canonicalization.
Proposed bounds: 1 MiB identity/head/receipt, 4 MiB intent, 100,000 participants,
4 KiB per metadata row; overflow/overlimit fails before staging. These are
reviewable candidate limits, not changes to S0/Archive limits or approved large-file
capacity. A larger real archive must use an independently reviewed streaming path.

| Record | Required exact field set (all candidate version 1) |
|---|---|
| `identity.json` | `schema`, `version`, `authorityId`, `installationId`, `authorityNative`, `ownerSid`, `brokerPrincipal`, `protectorContextId`, `macKeyId`, `minimumReader`, `minimumWriter`, `mac` |
| `head.json` | `schema`, `version`, `authorityId`, `generation`, `previousHeadMac`, `selectedRootBinding`, `selectedNamespace`, `txId`, `intentMac`, `preparedReceiptMac`, `fenceEpoch`, `mac` |
| `intent.json` | `schema`, `version`, `txId`, `authorityId`, `expectedHeadMac`, `oldGeneration`, `newGeneration`, `oldRootBinding`, `freshRootBinding`, `protectorWitness`, `participantManifestSha256`, `participantCount`, `sourceProof`, `consentReceiptMac`, `capacityReservation`, `phase`, `mac` |
| participant row | `id`, `domain`, `target`, `accountBinding`, `representation`, `oldPresence`, `oldRef`, `oldLength`, `oldSha256`, `newPresence`, `newRef`, `newLength`, `newSha256`, `generation`, `ownerBinding`, `mac` |
| prepared/complete receipt | `schema`, `version`, `txId`, `intentMac`, `generation`, `rootBinding`, `protectorWitnessMac`, `participantManifestSha256`, `verifiedCount`, `choice`, `observedHeadMac`, `publicationWitness`, `mac` |

`schema` has a distinct fixed domain token per record; `phase` only admits
SOURCE_VERIFIED, PREPARED, INTENT_DURABLE, APPLYING, RECOVERING, COMPLETE; each
transition creates a new authenticated record. `choice` is ALL_OLD or ALL_NEW,
never guessed from directory existence. `oldPresence/newPresence` distinguish
MISSING, EXPLICIT_NULL, EMPTY, NONEMPTY and unsupported states; an authenticated
source absence is not a permission to clear an existing target. No plain secret
or plain-secret digest is a metadata field. `representation` is a strict tagged
RAW or PROTECTED_CIPHERTEXT union: credentials must use protected ciphertext and
their digests bind only those blobs; data/media hashes bind non-secret participant
bytes. Mixed representation or credentials under RAW is rejected.

Nested exact types:

- `authorityNative` / root bindings: `{volumeGuid, volumeSerial, directoryFileId128,
  creationWitness, ownerSid, descriptorMac, generation}`; every field observed from
  fresh pinned handles plus authenticated expected binding, never path/UUID alone.
- `protectorWitness`: `{principalSid, profileBinding, keyProvider, keyId,
  algorithm, challengeReceiptMac, observerVersion, status}`. A real challenge under
  the expected principal is required; SID equality alone is insufficient.
- Credential `target` reuses G2A kind/key scope; data/media targets use a separate
  exact domain-relative owned locator, never an invented credential key.
  `accountBinding` preserves source model ID,
  provider template, exact endpoint and remote model identity. Unknown account
  cannot be inferred from display name or Model ID. Same ID/different endpoint
  is CONFLICT; NAI/Fish “global” means **within approved namespace**, not all roots.
- `sourceProof`: `{sourceVersion, envelopeVersion, manifestVersion, decoderId,
  authenticatedStreamReceiptMac, inventoryReceiptMac, sourceGraphReceiptMac}`.
  Existing G2B/S0 completeness limits still apply. A password boolean, an early
  authentication callback, or the large diagnostic tool is not a trusted proof.
- `capacityReservation`: `{volumeBinding, reservedBytes, requiredPeakBytes,
  observationGeneration, allocationReceiptMac}`. Free-space observation alone is
  not an actual reservation or a guarantee against concurrent consumption.

MISSING means authenticated nonexistence under a validated reachable owner;
UNAVAILABLE means unreadable/locked/offline/protection failure; FOREIGN means
observed native/owner identity differs; AMBIGUOUS means multiple plausible
authorities/heads or uncertain publication. Only authenticated supported absence
can be MISSING. Others block, retain originals, and produce fixed-category errors.

MAC prevents unauthenticated edits **only within the stated trust boundary**. It
does not prevent whole-volume rollback/replay of an old valid head. A monotonic
trusted witness outside rollbackable payload is a separate requirement; candidate
broker generation storage and optional TPM witness need platform validation and
recovery policy. If this cannot be proved, snapshot rollback/clone detection stays
BLOCKED. Do not claim durable CAS or anti-rollback merely from a signed integer.

## 4. Recovery, discovery, lock order and crash classification

Future desired path for **every** default/CLI/Portable startup:
locate authenticated installed authority → pin protector/authority identity →
acquire broker-wide transaction lease → discover outstanding intent → pin all
root participants → recover/verify complete state → admit selected root → build
repositories/providers. An override cannot bypass discovery. A copied Portable
root without authority/protector is FOREIGN/UNAVAILABLE, not an empty new install.
First install's absence requires independently authenticated enrollment, not
“file missing therefore create”. No current production route does this.

Acquisition order: profile/broker lease, then root handles sorted by native
volume/file identity, then protected-namespace locks sorted by typed target,
then internal participant operations. No callbacks/network/interactive UI while
holding this chain. Running application maintenance drains scheduler/audio/tasks
**before** transaction lock acquisition; lease generation revokes cached requests.
Current startup owns root first and migration owns source then destination; future
wiring must refactor acquisition under separate review to avoid inversion. A lock
upgrade from the existing process coordinator is not safe by assumption.

Fresh preparation never mutates live OLD keys/data. Fully authenticate source and
graph, validate explicit replacement/account consent, reserve peak space, copy
OLD protected escrow, write all NEW payload/sealed media and new protected keys,
fresh-read all bytes/context, then persist prepared proofs. Durable intent must
be independently discoverable before publication. A complete receipt must bind
**all** participants, not just two keys or a phase flag. Publication is one
authenticated selected-root/namespace head CAS against expected head/epoch under
lease, followed by restart seal. Multiple file moves are not a cross-domain atomic
transaction; uncertain publication means RECOVERING/BLOCKED, no current runtime
resume. New business admission occurs only after fresh process verification.

Avoid a circular receipt/head digest: the head references the immutable
**prepared** receipt, whose `observedHeadMac` is the expected OLD head and whose
`publicationWitness` is explicitly null. Only after actual head CAS/readback is
`complete.receipt` written; it binds the observed NEW head and the actual native
publication witness. The head does not hash this later completion receipt.
Missing/conflicting completion after NEW-head publication blocks business; it is
not safe to reconstruct success from the prepared receipt or rewrite the head
automatically. COMPLETE phase requires this final proof and independent readback.

Necessary ALL_NEW evidence: authenticated current selected NEW head, fresh native
root/protector match, complete intent/receipt chain, all NEW protected/data/media
bytes verified, required consumers routed to that namespace, enforceable old-writer
fence and no ambiguous publication/unknown participant. Sufficient only under an
accepted OS durability/trust model. ALL_OLD requires proven OLD authoritative head,
complete OLD escrow/data/context, no possible NEW publication or unresolved intent,
and the same admission/fence guarantees; it means import not applied. If any proof
is absent neither class may activate providers. Missing/corrupt/conflicting receipt,
second-key failure, source removal, disk full, CAS mismatch, cancelled task or
protector change retains artifacts and blocks rather than fabricating rollback.

Live old root/legacy keys may later be changed by an old application; immutable
broker-owned escrow is distinct from that mutable root. Such outside mutation
cannot silently become the OLD rollback image. Do not overwrite it to roll back;
fresh recovery into another root and explicit approval may be needed.

File flush, parent publication and OS crash/power-loss guarantees remain unproved.
Microsoft documents per-file flushing/access requirements and qualified move options;
neither certifies this multi-volume protocol. No silent non-atomic fallback is
allowed for a future authority writer. Unsupported publication/flush capability
blocks the transaction; LR5's 887 unavailable directory-force observations remain
historical negative evidence. [FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers),
[MoveFileExW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw).

DPAPI generally depends on user/computer context with documented exceptions; a
working ciphertext copy does not prove a portable account binding. Current fixed
optional entropy is a compatibility parameter, not an application-exclusive trust
root. [CryptProtectData](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata).

## 5. Community, legacy keys and old writers

Community proposed protected participant contains issuer/environment, subject,
access/refresh pair, token type, expiry and session generation. Source userId alone
is not authenticated issuer/subject proof. Unknown issuer, missing protected sink,
expired/ambiguous identity or unsupported refresh semantics must block full restore;
no background refresh, login, token validation network request or Task is allowed.
Future refresh rotates access/refresh atomically in the same protected participant;
logout disables consumption immediately. Retaining encrypted OLD refresh tokens
can conflict with revocation/privacy, so retention-vs-erasure after explicit logout
requires a user policy, not default resurrection from rollback. Error evidence
must never print tokens. No Community implementation exists in LR6.

Existing profile-shared legacy blobs are retained, never renamed into the new
namespace, borrowed across accounts, cleared, or silently overwritten. Migrating
them needs explicit source context/consent and ownership receipts. Offline/unlisted
consumers remain UNKNOWN; B narrows blast radius but does not prove global closure.
Full account resolution and Community sink remain blockers even if secrets are
syntactically supported by G2B.

See [OLD_WRITER_FENCING_RESULT](OLD_WRITER_FENCING_RESULT.md). Code signing and
installer provenance should pin permitted protocol clients and verify signatures
using Windows trust APIs; signatures alone cannot stop an old same-user EXE writing
ordinary files or a malicious process injecting an approved client. Broker-owned
I/O boundary is an architectural proposal with explicit limited threat assumptions,
not a tested enforcement result.

## 6. Versioned implementation plan and acceptance matrix

| Future slice (all NOT AUTHORIZED here) | Exact owners / review gates |
|---|---|
| Native read-only identity/protector adapter | New internal adapter; disposable Windows context only. Distinguish missing/access/foreign/reparse, validate fresh handle/OS principal/challenge; no SecretStore mutation. |
| Trusted authority codec/observer | New strict schema/MAC parser and independent golden fixtures; fuzz duplicate/unknown/overflow/truncation and replay. No runtime admission. |
| Isolated protected fresh-root participants | New broker/namespace boundary and Community lifecycle, explicit escrow consent/capacity. Fake then approved disposable native tests; no current-user migration. |
| Discoverable durable protocol | New native journal/publication implementation, proven crash/reboot matrix, unsupported-filesystem refusal; independent restart reader. |
| Runtime integration (G2C separately) | `Main`, Startup/DataDirectory/ownership/bootstrap/coordinator, Service/Journal, repository credential policies and caches; one reviewed lock order and one authority, no coexistence of two conflicting recovery writers. |
| Dormant adapters / release | FullBackup/Policy/Mapping, Snapshot/automatic backup, GC/pending deletion, root migration/Portable, P13 takeover, signed installer/legacy-fence tests and Android full fidelity. |

Acceptable for **future** admission: supported exact source, fully proven roots,
context, complete participants and account consent, exact durable generation,
known filesystem capability and enforceable fence. Reject/block: UNKNOWN consumer
treated as absent; account mismatch; missing escrow; unsupported Community/source;
root/profile clone; old binary direct write capability; invalid receipt/MAC; space
shortfall; cancellation/publication ambiguity; loss of trusted witness. No exact
candidate in this document is approved for production until Project review,
separate user authorization and corresponding actual Windows gates.
