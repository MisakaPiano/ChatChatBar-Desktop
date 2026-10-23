# G1B cutover feasibility — source audit, not topology approval

Authority: selected backup145 `1198a13c`, baseline141 `5e76a9cb`, 158/160/163/169/177/178/179 and this user's autonomous-run instruction. T0/T1 experiments were not rerun as new design proof. No new identity/namespace/intent schema or production credential wiring is introduced.

| Existing owner | Source fact | Candidate use / unresolved condition |
|---|---|---|
| WindowsSecretRootResolver | Selected root is used for overlap rejection; secret path still resolves from profile LOCALAPPDATA to one shared directory | REQUIRES_ADAPTER for B. Fresh data root alone leaves identical old global/model logical key addresses |
| DesktopSecretStore / canonicalValue | Global NAI/Fish/SiliconFlow, model-ID, legacy embedding-ID and singleton embedding keys; no Community key or batch/CAS | BLOCKING for Community and cross-key transaction. UUID/path alone cannot establish account/protector identity |
| DesktopAppContainer | Lazy secret factory receives appDataRoot; NAI/Fish/model/embedding consumers resolve through existing shared store; live fuse has a separately resolved secret-root dependency | REQUIRES_ADAPTER across all consumers, not only backup import. Changing a resolver alone would silently orphan or rebind existing keys |
| DesktopBootstrapAuthorityTransaction | Cooperating sibling lock covers fresh authority read, CAS-like source validation, save/readback; bootstrap-controlled provenance required | REUSABLE principle, not cross-domain authority. It does not authenticate prepared credentials, bind protector/profile or fence old processes |
| DesktopDataRootMigrationService / Materializer | Existing root ownership + source snapshot + copied destination + authority commit + restart-required boundary | REQUIRES_ADAPTER. This is copy migration of a selected root, not an authenticated foreign archive/credential restore. Do not feed it arbitrary prepared source and call the transaction complete |
| DesktopDataRootSwitchController | New destination is next-start state; current container stays on original root until restart | REUSABLE restart UX boundary. Cached/old processes and explicit Portable/CLI selection remain independent |
| DesktopStartupGate | Root ownership and G0 preflight precede full-backup/index/session recovery and business construction | REQUIRES_ADAPTER to a future unified authority. Existing recoveries are root-scoped, not shared-secret all-consumer recovery |
| DesktopFullBackupCommitJournal | Retained old/new registered root rows and restart recovery | Cannot recover external shared keys, prove new protected key readability or authorize B cutover |
| G2A/G2B/T1 | Inert typed plan; known-source census; injected/native target metadata observations | UNKNOWN global closure/protector/account evidence remains UNKNOWN. No amount of metadata converts into restore permission |

B + inactive fresh root still offers the conditional blast-radius advantage from T0. It needs an approved root-bound key meaning, clone/rebind rules, retained old keys, authenticated discoverable cutover authority, capacity reserve and legacy writer policy. A fallback to shared keys after missing B data would recreate cross-account ambiguity and is not proposed.

Unresolved counterexamples: existing B process continues using cached shared key after another root imports; old EXE or CLI override opens a fresh root without new admission; copied UUID/receipt accompanies different file identity; Portable roots move to an unavailable profile; authority switch succeeds but one protected participant is unreadable; missing receipt invites guessing OLD/NEW; Community has no sink. None is solved by a marker, a lock only new binaries honor, or N atomic moves.

Recommended next approved slice: explicitly decide topology, account/global-key meaning, old-key retention/rebind consent, Community policy and old-binary threat model before choosing durable layout. Then implement an unwired fresh-target preparer with authenticated full-source proof, protected participant readback, capacity/failure/cancel/hard-exit tests; keep cutover/Startup activation separate until authority durability and discovery are verified. Actual profile/DPAPI, old EXE, VM reboot, power loss and portable disk tests remain NOT TESTED here.

No new prototype duplicates accepted T0/T1: the missing production prerequisites are authority and platform guarantees, not another pure boolean phase model. G1B remains OPEN; G2C remains NOT AUTHORIZED.
