# Decisions for Project and subsequent user authorization

Do not ask again whether to use B + inactive fresh root, retain OLD keys or preserve
dormant historical voice/C-A raw records and independent media: D-045 approved
those product directions. The following concrete implementation choices remain.

| Decision | Candidate and consequence / why blocked |
|---|---|
| Enforceable deployment boundary | Broker-owned NEW roots/authority under separate principal is recommended for review; existing direct JsonFileStorage/SecretStore architecture cannot gain this by a namespace string. Approve threat model, IPC, installation/account/signing/trust and fallback behavior before code or host setup. |
| Exact durable authority | Approve/revise the candidate record fields/paths, strict codec/MAC key custody, recovery discovery for every root route, publication/CAS, generation/replay threat and trust-anchor recovery. No persisted schema is approved by LR6. |
| Native lab | Supply independently proven disposable Windows guest/principal/snapshot receipts. Current runtime did not prove one. Host setup/account/DPAPI is prohibited; extra disk space does not satisfy isolation. |
| Protector/escrow lifecycle | Select reviewed principal/key provider and secure credential handoff. Define lost profile/key recovery, OLD ciphertext retention and explicit erasure. Never infer account proof from Model ID or path. |
| Community lifecycle | New protected sink with issuer/subject/expiry/refresh/logout/revocation policy; resolve OLD refresh-token escrow vs logout erasure. Missing sink remains a whole-restore incompatibility. |
| Consumer and concurrency semantics | Define rooted global keys, legacy/offline/unregistered roots, request-cache revocation and immutable old escrow. Review lock-order refactor across startup/root switch before wiring. |
| Dormant ownership | Approve manifest/ownership receipt, numerical quotas/reservation, reexport projection and complete backup/snapshot/GC/pending deletion/Portable/P13 adapters. Unknown source semantics still blocks full restore. |
| Production implementation slices | Begin with separately reviewed isolated native read-only identity and strict authority codec tests; then protected inactive participants/durability. Actual G2C runtime integration needs further explicit authorization after Project review. |

No decision here is automatically authorized by the packet. Do not replace D-038
full fidelity with key stripping, incomplete census, a synthetic five-participant
journal or a “format readable” report. Android/Windows real migration, old EXE,
installer/DPAPI upgrade retention and each Preview gate still need their own
approved evidence. A–G signatures remain 0/7; P11 PENDING, D-038 BLOCKED, Post-P8
HOLD, Public Preview HOLD, G1B OPEN, G2C NOT AUTHORIZED.

Storage-only supplemental consent was executed and closed at its exact two AVD
targets; it does not authorize deleting other packages, private witnesses, Git,
raw evidence or residual readonly files. See the separate recommendations.
