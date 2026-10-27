# LR5-A source-backed design review — PROPOSED, NOT PRODUCTION ACCEPTED

Authority193/194; audited source start06e5d8638d6fe89babc1f855d96e2ac1253258c9. No private export read is needed or performed. LR4 acceptance191 is limited; its11 unsupported observations are Entity RECORDS, not11 proven types. D-045 directions are approved; no need to ask for them again.

## Findings and falsifiers

Current root ownership, root-independent bootstrap lock and in-process coordinator have different authority. None coordinates the shared profile SecretStore with data-root journals. Repository credentialTransaction is one-key compensating exception handling, not hard-exit recovery. A fresh root limits exposure only if no business sees it until an independently discoverable authority verifies EVERY participant. Existing startup selection can bypass bootstrap via CLI/Portable; a future recovery gate must intercept all routes before container creation. OLD binaries do not understand it.

Proposed order (not current implementation): discover profile recovery anchor independent of chosen root -> acquire profile coordinator lock -> acquire OLD and NEW native root locks in stable identity order -> reobserve generations/context/consumer closure -> drain scheduler/audio/tasks through operation coordinator -> freeze validated source and retain OLD -> prepare inactive NEW participant snapshots -> durable intent -> apply -> verify independently reopened complete participants -> durable receipt -> CAS authority publication -> restart-required -> new process discovers SAME authority, validates all context/receipts/bytes -> synthetic ALL_NEW class. ALL_OLD is rollback only; unknown proof is BLOCKED. Native implementation lock ordering against current ownership must be reviewed for deadlock before any wiring.

Consumer closure is not proved by searching current source. Offline/unregistered roots, copied Portable roots, CLI override and same-user old binaries remain external writers/consumers. Per-root B prevents intentional reuse of global keys only after an approved migration adapter; no fallback to OLD global key is permitted. Unknown account/protector, Community absent sink, unsupported graph and incomplete dormant ownership prevent publication regardless of outer authentication.

## Alternatives left open

Recommend investigating a profile-level discoverable coordinator with per-root namespaces (B), keeping legacy shared keys read-only/retained. Alternative: privileged broker or separate protected profile to fence old same-user writers. A plain JSON marker, hash or same-user ACL cannot fence them. No durable path/schema/namespace, retention expiry, key lifecycle or broker is selected for production by this review.

The LR5-B experiment will implement strict bounded experiment-only state files and source/participant readback. SHA checks detect accidental corruption, not an adversary who can rewrite all files. Fake protector/context declarations and native synthetic file IDs do not attest DPAPI/SID/account identity. File.force and ATOMIC_MOVE do not prove directory persistence or Windows power-loss atomicity. Missing post-publication receipt must block, never fabricate success from matching payload alone.

## Required falsification

Persisted mixed key/data; missing/corrupt authority/receipt; unknown version; old/new snapshots altered; old source changed; stale registry/generation; clone native identity; endpoint/account/protector mismatch; unknown third consumer; offline participant; second-key exception; cancellation/capacity; hard halt before/after intent, each apply stage, receipt, publication and reopen. Restart in independent JVM, verify retained OLD/NEW bytes and ALL_NEW or ALL_OLD/BLOCKED outcome, never business activation. Actual DPAPI, old EXE fencing, reboot/power failure and installer remain NOT TESTED and block production readiness.
