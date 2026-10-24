# 196 — P11-LR6 Native Durability, Credential Authority and Sealed Ownership Decision Gate (DRAFT)

**NEXT-GATE PROPOSAL — 2026-10-24 JST — NOT USER-AUTHORIZED LR6 EXECUTION, NOT APPROVED PERSISTED SCHEMA, NOT G2C.**

Authority: [195 LR5 bounded Project acceptance](195_P11_LR5_BOUNDED_PROJECT_ACCEPTANCE.md), [193/194 LR5 authorization](193_P11_LR5_USER_AUTHORIZATION.md), already user-approved **D-045** product directions, D-023–D-026 root migration and D-037/D-038 Public Preview policy. Unlike LR5, the blocker is no longer proof that an independent JVM can read synthetic OLD/NEW files. Real production restore requires a **genuinely trusted, discoverable and durable authority + safe old-binary/credential/media ownership**, then real Windows and Android platform gates.

## 1. What is accepted and what remains unproved

LR5 real executed synthetic tests: final2 suites/29 PASS,144 independent child JVM runs including50 actual `halt(71)`, strict receipt repair, OLD/NEW participant readback, blocked foreign/corrupt/partial evidence. Source code runs test-only inside isolated synthetic roots. Every outcome has `businessAuthorized=false`.

**887/887 final recorded directory force attempts/observations were UNAVAILABLE**. No Windows power-loss, physical restart, native account/DPAPI/SID/protector, old executable enforcement, network/Community, deployed manifest format, production root/SecretStore authority, cloned profile or real owned media/GC adapters was certified. Published `ALL_NEW` is only a synthetic class, not a user migration. Git `app/` code unchanged. Do not infer production readiness.

## 2. Exact design choices to freeze in LR6-A (requires authorization)

1. **Native trust/identity:** native root file/volume identity and generation, profile user/protector/SID identity with OS protected non-exportable participant context, Portable/rebind/clone/missing profile policy. No reliance on UUID/path alone.
2. **Recovery anchor format and discovery:** propose a single root-independent authority across default/CLI/Portable data-root routes with concrete path/owner, secure integrity/MAC trust root, version/schema/field validations, flush/rename/metadata durability conditions, finite WAL/receipt participant proofs, monotonic generation CAS, lock acquisition ordering and startup/old-process intent. Clearly classify `UNAVAILABLE` vs authenticated absence. This is a PROPOSAL until independently accepted.
3. **Actual complete protected consumer topology:** NovelAI/Fish/model/embedding/legacy scopes, global and offline/unregistered roots, cached request runtimes, account/provider/endpoint identity, CommunitySession protected target+expiry/refresh/logout; specify consent, collision and legacy encrypted blob retention. No automatic shared-key fallback/overwrite/deletion.
4. **Same-user old-executable downgrade fencing:** prove **enforceable** choice; a cooperative new lock, JSON marker or same-user ACL alone is not a barrier. Candidate options should state actual trust assumptions (installer/launcher, private broker/privileged service, root-versioned isolation or other proven OS mechanism) and failure behavior. Old root must remain recoverable without allowing unrecognized writers to mutate the new schema.
5. **Dormant voice/C-A production participant:** source original voice version/audio and raw Post/Task JSON + three separately owned media edges, unique native owners, bounded quota, re-export and schema/unknown-field policy, staging/backup/snapshot/GC/root-switch/Portable/P13 idempotent takeover and rollback. No Task activation/autoplay/automatic cleanup. Unknown unsupported source record must still block whole full-app restore until lossless complete source and adapter evidence exist.
6. **Platform test matrix:** identify Windows volume/filesystem capabilities and distinguish process halt, OS reboot, forced VM power-off, app install/upgrade/uninstall, DPAPI account migration, old binary write attacks and unavailable/offline data root. Record exact source/commit/known claims and fail-closed test setup. Do not invent NTFS/Win32 guarantee without tested API and bounded results.

Deliver `LR6_A_AUTHORITY_CONTRACT_CANDIDATE` with exact proposed on-disk schema, trusted integrity source, ownership/state diagram, implementation diff plan, matrix and specific user decisions to approve. Code contract must never be silently derived from the throwaway `LR5-SYNTHETIC-1` format.

## 3. Recommended LR6-B separately bounded platform feasibility

**Only if the user separately approves both execution AND disposable Windows environment/roots**: create dedicated throwaway Windows test context/VM, never use real ChatChatBar data directory, installed ordinary CCB profile, actual user credentials or prior device/export. Use disposable *synthetic tokens* in real Windows OS-protected test store **only inside the explicitly authorized disposable profile**, verify readback, DPAPI unavailable/moved profile, privilege/ownership and key retention/rollback. Test chosen native publication/flush path with independent processes plus simulated/reboot/poweroff VM where environment and consent permit; capture unsupported FlushFileBuffers/atomic-move behavior as BLOCKED. Test the genuine old version executable only if its exact binary and sandbox boundaries are approved separately; do not auto-run downloaded executables or destructive install.

If a suitable disposable environment is not available, work on LR6-A source/contract only and prepare a reproducible test plan. No guessed Windows capability PASS or production sign-off. **Real `WindowsSecretStore` in current profile, existing users' data roots, installed normal EXEs and Android/ADB remain prohibited** without an explicit new scope.

## 4. Keep Preview critical path visible

Even after LR6, separate blockers: official1.4.5 `.cbbackup` Android→Desktop/Desktop→Desktop/selected Desktop→Android full fidelity; newer code86/v2 supported/unknown schema and complete dormant resource adapters; real `.cbsave` Android directions and nonempty RAG/Memory/voice continuation; D-041 packaged GUI/update/install N→N+1/DPAPI retention; actual media/GC/Task inert lifecycle; D-016 author permission and latest upstream release impact; user Preview release authorization. Do not call test-only ZIP verification real restore.

## 5. Decision boundary

**No automatic LR6 implementation authorization by publication of this draft.** D-045 target directions and LR5 acceptance remain facts. Next ask the user whether to approve an **LR6-A exact-source design freeze plus narrowly sandboxed LR6-B native feasibility work**, with precise test root/profile/VM and allowed OS protected fake material. Production G2C and persisted schema/Startup/SecretStore wiring require a *further* explicit approval after Project reviews test results and code. Do not merge `desktop`, edit official Prompt, sync upstream or release.

Status until user decision: `G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / A–G 0/7 formally signed`. Validated upstream still 1.4.1; selected official backup1.4.5 is not formal baseline promotion.
