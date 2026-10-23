# G1B + C-A decision-ready proposal — DRAFT / NO PRODUCTION AUTHORIZATION

Project conditionally prefers investigating B+fresh-root. The user has not selected a production topology. This packet requests later architecture review; it creates no schema, namespace, journal or root.

| Candidate | Benefit | Falsifier / cost |
|---|---|---|
| A shared profile coordinator |legacy key addressing retained|unregistered/offline consumer or old cached process prevents complete shared-key replacement proof|
| B per-root protected namespace |limits cross-root key collision|clone/profile/Portable binding and legacy migration required; a UUID/path is not OS identity|
| B+fresh-root and authority switch |old root/data/keys retained until verified cutover|durable profile/root authority, participant receipts, old-writer fencing and restart reconciliation still absent|

Recommend B+fresh-root only conditionally. Proposed owner is one recovery authority shared by startup and every cooperating writer, binding source authentication/scope, target native root generation and protector principal, source account/provider/template/endpoint/remote-model identity and typed key purpose. A root key is a composite (root identity, protector identity, typed consumer/account identity, generation), NOT merely Model ID. Exact durable encoding/location is a decision gate.

Threat model: accidental crash, disk-full/offline root, profile/DPAPI mismatch, stale process and same-user old binary; an actively malicious Windows user with equivalent access is not defeated by a JSON marker. Refuse business activation whenever OLD/NEW participants cannot be proved consistent. Same-user old executable fencing needs enforceable launcher/access/version policy and disposable Windows proof; a new marker alone is inadequate.

Legacy shared secrets remain protected and retained by default. Never overwrite a different account on a name/ID match, delete legacy keys because registered A/B look complete, or treat unavailable context as absent. Provider/endpoint/account conflict needs explicit preview+consent; unknown account blocks. CommunitySession requires a separately specified protected sink, expiry/logout/revocation and refresh lifecycle; no sink means no publishable complete migration. NAI/Fish global consumers cannot be assumed root-local before all call sites migrate.

Proposed transaction: authenticate/census → observe immutable target → prepare complete NEW in inactive root → retain protected OLD/NEW participants → publish one durable authority → restart reconciliation → independently reverify ALL_NEW → activate. Cancellation before publication leaves OLD authoritative. Ambiguous publication, missing/corrupt intent, foreign CAS, unavailable protector or any participant mismatch blocks; ALL_OLD is rollback, never success. Multiple atomic moves are not a cross-domain atomic commit. Locks must order profile authority before root owners, with startup discovery independent of the selected root. Exact discovery/lock/flush contract requires review.

Clone/Portable must rebind only with explicit protector/account consent; inaccessible offline roots remain consumers, not nonexistent. Source disconnect never deletes source/OLD. Receipts must survive snapshot/backup/root switch/upgrade and distinguish generation; power-loss/directory-flush and actual DPAPI are NOT TESTED. A rollback must restore protected participant evidence and authority consistently, retaining foreign-modified evidence for manual recovery.

C-A proposal: original source remains user-owned; independent sealed domain stores exact raw MomentPost/Task bytes plus owned copies for senderAvatar, imagePath and generatedImageMetadata.imagePath. Records bind source format/scope, original record identity/length/SHA, all graph edges and independent-copy ownership. Unknown schema/reference or duplicate/conflicting IDs refuses publication; raw byte retention alone is not proven re-export. No Scheduler/AI/NovelAI/network/Task activation, no automatic deletion. Capacity includes exact raw + unique media + staging + rollback + approved reserve, with overflow/unknown totals/concurrent reservation failure refusing before publication. Never alias live media or silently lose bytes under GC.

Required adapters before production publication: snapshot/full backup inventory, root migration/Portable, GC/pending-deletion protection, startup recovery, downgrade barrier and P13 versioned takeover. Live card/session deletion must not collect seal bytes. P13 reads verified immutable source, stages new entities, commits with rollback, and retains old seal until explicit retention policy permits removal. No implicit Task rescheduling.

First minimal slices AFTER separate authorization:
1. Unwired persistent-identity specification + synthetic protector/clone/CAS tests; no real store. Stop if source/protector identity unresolved.
2. Unwired sealed-domain preparer on synthetic new roots with exact capacity/copy/backup/GC contracts. Stop on missing participant or unknown schema.
3. Durable authority/recovery prototype with fake protected participants and process hard-exit matrix. Stop on any mixed business-visible state.
4. Separately authorized disposable Windows DPAPI/installer/old-writer tests, then independent Project review BEFORE G2C or real key/root writes.

Acceptance falsifiers: second key failure, KEY_FIRST/DATA_FIRST crash, foreign modification, lost receipt, cloned root, offline third consumer, Community unavailable, downgrade writer, Portable profile change, insufficient space, interrupted P13 takeover. Each must produce retained OLD/NEW evidence and blocked business until consistency is reverified. No simulation can sign platform evidence.

## Proposed authority location and consent boundary

Reviewable recommendation (not selected/implemented): one profile-owned recovery coordinator, with a protected authority anchor under symbolic `$LOCALAPPDATA/ChatChatBarDesktop/recovery-v1`, separate from every selected data root. Each inactive fresh root holds only generation/participant references, never the sole discovery authority. Coordinator startup discovery precedes all repository/provider factories. The anchor binds native protector principal, root-native evidence, source version/scope, participant digests, consent receipt and OLD/NEW generations. Exact encoding, atomic publication record, durable flush and version upgrade are separate schema approval items.

OLD/NEW key material would remain protected blobs in the profile recovery domain, never plaintext JSON in either data root, diagnostic log or portable package. Existing shared keys remain untouched until all consumers and consent permit retirement. A root copied to another profile cannot silently inherit this authority; absent/unavailable context blocks and requires separately authorized reauthentication/migration. Current G2A intent is pure only; current G2B census lacks this full source domain and Community sink, T1 cannot prove protector identity/global closure. Existing per-file atomic writes and root journal are reusable mechanics, not this authority.

Same-user old binaries can bypass a new cooperative marker and directly open a JSON root. File names, UUIDs and same-user ACLs do not enforce executable-specific exclusion. Thus the proposed cutover remains a release blocker until an enforceable downgrade policy and real disposable-host test exist; retaining the old root does not alone protect the new root. No claim of cryptographic protection against arbitrary same-user code.

Approval checklist for the later user decision: select topology; approve the profile-anchor/root-participant division and protected OLD retention; specify conflict and legacy retirement consent; approve Community lifecycle; approve C-A schema/ownership/capacity/P13 retention; approve separately bounded production slice and Windows test environment. Approving research or this proposal's review is not approval to execute any of those writes.
