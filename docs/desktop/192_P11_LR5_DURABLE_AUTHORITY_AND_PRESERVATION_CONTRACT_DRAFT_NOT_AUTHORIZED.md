# 192 — P11-LR5 Durable Recovery and Sealed Preservation: Production Contract Gate (DRAFT)

**PROJECT NEXT-GATE SPECIFICATION — 2026-10-24 JST — DRAFT / NO NEW USER EXECUTION APPROVAL / NOT A PERSISTENT SCHEMA DECISION.**

Purpose: after [191 LR4 qualified acceptance](191_P11_LR4_BOUNDED_PROJECT_ACCEPTANCE.md), stop repeating pure categorical archive scans and close the **design/ownership gap** that prevents D-038 full-app restoration. Product choice **D-045 is already approved**: B+inactive fresh root with OLD key retention; historic unknown-version voice exact raw/audio inert preservation or whole-import refusal; C-A original MomentPost/Task bytes plus three independent media edges, no Task activation/auto-delete. Do **not** ask the user to reapprove these goals.

## Current authority

- Feature `feature/phase8-fish-audio`, Codex delivered LR4 code `69488dc14ff2bf0c5991ea5c771a5a7e087d4090`; [191](191_P11_LR4_BOUNDED_PROJECT_ACCEPTANCE.md) is Project's docs-only review. Check current exact remote HEAD before any new task. Protected `desktop` remains `5ef95428421f7c693b010ac88f51d470804ab07a`. No upstream writes or baseline promotion.
- Formal validated baseline: official1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. Selected D-038 full-app transport: official1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`; observed newer real code86/v2 is NOT proven official145 interoperability.
- Existing authorities: [D-045](18_DECISIONS.md), [189](189_P11_LR4_USER_DIRECTION_AND_BOUNDED_ENGINEERING_AUTHORIZATION.md), [190](190_P11_LR4_AUTHORIZED_UNWIRED_ENGINEERING_CODEX_TASK.md), prior G2A/G2B/T1, D-023–D-026 root migration, D-037/038, [93](93_PUBLIC_PREVIEW_POLICY.md), [95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md).

## Necessary independent contract decisions, not yet signed

1. **Durable data-root identity + protected credential participant:** typed root generation/root-native witness, Windows protector principal and binding, account/provider/endpoint/model, legacy shared-key ownership and OLD/NEW retained protected participants. Determine real Source of Truth for every global NovelAI/Fish/model/embedding and Community credential; no assumption that a UUID/path or synthetic `SYNTHETIC_VERIFIED` proves OS identity. CommunitySession needs an explicit protected sink with expiry/logout/revocation/refresh.
2. **Recoverable authority and cutover:** a discoverable coordinator independent of selected data root, versioned bounded intent/schema, generation compare-and-set, lock ownership/order, durable flush, verified participants and deterministic ALL_OLD/ALL_NEW, plus explicit AMBIGUOUS / RESTART_BLOCKED states. Snapshot/current root/legacy keys retained; cancel before commit is not success. No mixed visible state permitted. Same-user old executable, Portable, clone/offline third root and missing/malicious changes are explicit falsifiers. **A JSON marker/cooperating lock/same-user ACL alone does not enforce an old-writer barrier.**
3. **Historic voice owned source and media schema:** exact original record version ID and bytes (including future fields), owned audio bytes and reference graph, provenance, separate dormant/no-play ownership, private digest and re-export proof. Any failed reference/capacity/unknown-scope proof refuses whole import before publication; no original voice mutation or silent current-version fallback.
4. **C-A Moment sealed domain:** exact raw Post/Task bytes plus independent avatar/body/generated metadata images, source link/evidence, media ownership and audited GC/backup/snapshot/root switch/Portable participation. Task remains inert; failure/low disk cannot publish a partial seal. Define future P13 takeover/rollback and retention; cannot declare complete archive portability if source semantics unknown or unsupported.
5. **Platform and release proof:** separate disposable Windows profile/VM for real DPAPI, account/clone, old-binary and installer/upgrade/reboot/power-loss; selected official Android1.4.5 source/device and .cbsave Android↔Desktop continuation. D-016 author permission and user final Preview release approval are separate.

## Recommended LR5 order (after explicit user approval)

**LR5-A design counter-audit + exact contract freeze:** use current upstream and Desktop source to name owners/call chains/boot order, credential consumers, every source/destination invariant, conflict and old-writer fail-safe state. Produce a precise versioned *proposed* schema/authority location/retention agreement, compare alternatives, threat model and minimal acceptance matrix. **User/Project must approve exact persisted representation and protection policy before making this a production-readable schema.**

**LR5-B synthetic durable prototype:** only after scope authorization, implement a separate **unwired, test-only** file-backed local actor/journal with fake protected participants on new disposable synthetic roots. Inject process `halt` at each transition, missing receipt/corrupt/checksum/foreign generation/missing protector/old writer attempts, account conflict, capacity/failure/cancel and restart. Assert no business hydration or SecretStore exposure; include exact source snapshots and original red/green data. No real %LOCALAPPDATA%, user root, Windows protected Key, StartupGate/GC/UI wiring or G2C. Failure to prove old-binary fencing remains a Preview release blocker even if synthetic tests pass.

An acceptable handoff includes an exact authority/state chart, trusted/untrusted sources, schema/adapter migration policy, resource ownership graph, no-loss invariant, raw synthetic evidence and a concise list of decisions **not** resolved by a pure simulation. Avoid re-running previous LR4 validation unless code changes affect it.

## Explicit permission boundary

This **192** file does not itself launch Codex or authorize LR5-A/B. D-045 approved direction and [190] authorized *LR4 only*. Future actual persistent schema selection, global key movement, real SecretStore/DPAPI, restoration, startup/GC changes, old EXE, Android physical device, installer/uninstall, release and branch merge all require separately scoped consent and evidence. Do not weaken Prompt/API request or Package/Entity semantics. Original user `H:\手机CCB导出` read-only source remains limited by its existing prior consent; new LR5 task must restate exact intended use.

**Next user-facing decision candidate:** approve LR5-A source audit + proposed persistent contract plus LR5-B **disposable synthetic-only, unwired journal prototype** as one long Codex task? Approval would NOT select a deployable real persistence/SecretStore schema or open G2C. Project independently reviews both before recommending production wiring.

Until that explicit decision: `G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / 0/7 A–G signed`; formal baseline1.4.1 and protected `desktop` unchanged.
