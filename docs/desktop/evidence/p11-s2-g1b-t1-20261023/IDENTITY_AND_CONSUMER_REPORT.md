# Target metadata evidence — bounded result

**TARGET METADATA OBSERVED ≠ GLOBAL CONSUMER CLOSURE ≠ RESTORE AUTHORIZED.**

T1 supplies topology-neutral read-only evidence. It does not select A/B, migrate a namespace, authenticate an OS account, hold a lease or publish a transaction. It is entirely unwired. Inputs are explicit test-owned directory paths and injected synthetic configuration snapshots; no genuine Repository, SecretStore, archive restoration or production configuration observer is used.

| Evidence | Meaning / limit |
|---|---|
| Data/credential directory VERIFIED | Two native observations agree and match the caller's prior native object witness (volume/file ID/creation); scoped to these directory objects and this interval. UUID/path/Model ID alone is insufficient. Directory change stamps additionally detect observed changes between passes. |
| Missing baseline UNKNOWN | Observed object cannot be associated with the caller's expected object. Never default to ABSENT or safe overwrite. |
| Directory UNAVAILABLE | Native read refused/missing/inaccessible/unsafe; cause text is discarded. Offline is simulated by renaming an owned synthetic directory, not physical disconnection/ACL proof. |
| Configuration VERIFIED | Two injected metadata snapshots agree in generation and G2A account identity under observed directory baselines. Provenance is explicitly SYNTHETIC_INJECTED. This is not a production file decoder or proof of authenticated configuration ownership. |
| Configuration CONFLICT | Changed generation/account metadata, duplicate roots/keys, overlapping roots, invalid identity, name or same logical ID/provider/endpoint/remote-model conflicts. Distinct credential directories do not settle an account conflict or select a namespace policy. |
| Declared consumers | Only the ordinal roots supplied by the caller, and their observed configuration metadata, are reported. No scan or proof excluding a third/legacy/offline consumer. An unavailable declaration is retained as unavailable, never dropped as nonexistent. |
| Global NovelAI/Fish/SiliconFlow | Known logical families, unknown actual account and global consumption range. Global scope reason is explicit even if all declared roots agree. |
| Protection/account binding | Always UNKNOWN. Native directory IDs and supplied generations cannot prove SID, DPAPI profile, protector context, account ownership, credential equality or permission to overwrite. |
| CommunitySession | No protected sink; UNKNOWN/NO_PROTECTED_SINK. No API-key comparator or substitute key. |
| Secret relation / activation | Always UNKNOWN; readyToCommit=false and restoreAuthorized=false for every result, including empty or otherwise verified declarations. Existing CREDENTIAL_APPROVAL_REQUIRED is unchanged. |

The metadata reader has no secret/value/comparator interface. Directory observer reads only explicit directory attributes using existing same-handle native logic; it never enumerates directories or opens key payloads. The two-pass protocol detects the tested changes, not every concurrent interleaving/ABA or mutation after a final read. Supplied generations are NOT native CAS, OS locks or durable fencing. G2A sameAccount excludes display-name equality; name-collision classification uses observed metadata, and a future real reader must prove complete configuration provenance/revisions. There is no trusted runtime target reader here.

Invocation bounds (64 declarations,4096 metadata entries per snapshot) bound this internal probe's work; they are not approved production capacity/schema limits. Over-limit/invalid input stays incomplete. A sandbox assertion is not OS authorization and cannot be reused to read actual user roots without a future explicit permission and adapter review.

## Is T1 sufficient for persistent transaction design?

T1 is sufficient to make the next design more explicit about expected native objects, unknown consumer range and configuration conflicts. **It is not sufficient to admit persistent transactions or protected credential import.** T0's conditional isolation advantage for B remains a research finding, not a topology choice.

Before any persistent slice, Project/user must decide durable root/account/protector identity, clone/rebind and Portable policy; A consumer closure or B reversible namespace migration; ownership and retention of old keys; consent/conflict semantics; authenticated authoritative intent/discovery/receipts; capacity and durable rollback; Community lifecycle; and an enforceable old-binary boundary. No duration/schema/migration default was chosen.

**NOT TESTED:** real DPAPI/protected context, actual Windows account/SID/HKCU identity, real credentials, complete global consumers, real configuration repositories, native CAS/lease, physical disk/ACL/profile change, root migration/Portable, old executable, reboot/power loss, Android/real archive interoperability, Provider/Repository admission. Native handle/file-ID calls on synthetic directories are actual Windows observations only.

Suggested next step: Project review T1 plus freeze a durable identity/consumer-evidence design and platform validation plan before authorizing any persistence. No G2C or automatic next engineering slice is started.

Stop: **P11-S2-G1B-T1 PROJECT CODE REVIEW GATE**. G1B OPEN; G2C NOT AUTHORIZED; P11 PENDING/D-038 BLOCKED/Post-P8 HOLD/Public Preview HOLD; baseline1.4.1 unchanged.
