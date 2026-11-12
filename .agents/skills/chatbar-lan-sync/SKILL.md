---
name: chatbar-lan-sync
description: Maintain paired same-LAN Android/Desktop sync, mutual TLS discovery, common baselines, graph conflicts and recoverable in-place publication.
---

# LAN Sync

- Shared implementation: `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/lanSync/`. `LanSyncScope` declares the negotiated complete coverage; excluded stores are informational and never deletion authority. `LanSyncModel/Plan` use three-way whole-conversation units and deterministic fork identities. Graph/identity/memory-proof adapters originate from the established Desktop backup implementations; keep their opaque-text, version and ancestry behavior aligned.
- `LanSyncWorkspace` normalizes resource references to content hashes, captures immutable blobs and compares the local source again before journal authorization. Its journal is platform-local, outside portable app data. Bootstrap recovery must precede repositories; complete journal/payload/target validation precedes publication.
- `LanSyncTls` uses P-256 device certificates and pinned mutual TLS 1.2/1.3 AEAD. A two-minute one-use QR capability admits initial pairing, with explicit phone confirmation. Private identity bytes use DPAPI or Android Keystore. UDP39237 supplies address hints only; a pinned TLS handshake authenticates every discovered endpoint.
- `LanSyncRuntime` owns foreground-page host/poll jobs, conflict waits, retained transaction plans and bilateral completion receipts. A local apply receipt is not a shared completed baseline. Interrupted commits resume the same identity mapping; do not generate a second fork to resolve a lost acknowledgement.
- First-version UI permits one remembered peer at a time. Temporary captures/received blobs use depth-one invocation-owned scratch; completed transfers release redundant retained blobs and retire older committed payloads while keeping receipts and the latest safety journal. Pending evidence remains separate.
- SaveSlot belongs to its conversation unit. Legacy inline records use separate historical memory proofs. V8 packages remain immutable with validated shared library-envelope rebinding; copied library IDs use their own canonical package filenames, while internal archive source/version/media identities remain intact for the existing restore owner. Interrupted single-sided application rebases subsequent edits against that side's original captured ancestor, retaining the superseded plan and existing fork IDs.
- Desktop adapters/UI: `desktop/lanSync/DesktopLanSync*`, Data workspace. Reuse task, automatic-backup, audio and data-coordinator maintenance. Android adapters/UI: `lanSync/AndroidLanSync`, `LanSyncActivity`; storage gate admission and AI maintenance reservation are both necessary.
- Dedicated cloud verification: `.github/workflows/lan-sync-ci.yml`, branch `codex/lan-sync-v1-20261113`. Baseline: `2daab44734b2e8c44f0ec966ba44e14a6f30adf5`; isolated implementation root is `I:/CCB-LanSync-Dev-20261113-r02`. Checkpoints belong only to `I:/CCB-Desktop-Continuity/updates/lan-sync-20261113`; main checkout/canonical remain independently owned.

Current implementation is under engineering validation; no real-device/LAN acceptance or full-scope completion is implied by compilation or comparison tests. The scope matrix in `docs/desktop/LAN_SYNC_V1_SCOPE_AND_PROTOCOL.md` tracks remaining stores and platform differences.
