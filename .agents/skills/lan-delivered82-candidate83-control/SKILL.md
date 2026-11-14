---
name: lan-delivered82-candidate83-control
description: Maintain additive cloud-only delivered82 to candidate83 APK and delivered Desktop lan.3 to lan.4 build/upgrade controls.
---

- New controls are under `tools/lan-next-control/` and the two
  `lan-delivered82-candidate83-*.yml` workflows. Frozen next-preview81/82 controls
  remain separate. `README.md` documents request creation and proof scope.
- Retry controls are documented in `tools/lan-next-control/README-r02.md` and
  `DESKTOP_UPGRADE_R02.md`; preserve original failed requests, fixtures and evidence.
- `contract.py` pins exact delivered82 raw/member/artifact lineage and signed
  bytes; seed82 compiles against10b and verify83 against the new source. Each
  companion overlay hash is an explicit build input. A newly built82 is not the
  delivered base.
- No request exists until a real candidate source is frozen. Only the new explicit
  build/sign request paths on `codex/lan-next-sync-control-*` trigger these jobs.
- `sign_control.py request` accepts public block receipts, never identity files.
  It reuses the historical82 public block and existing generic public assembler.
- Upgrade execution requires the guarded fresh API26 cloud emulator, exact82,
  separate82-seed/83-verify companions, install `-r`, exact `[1,0]` JUnit statuses,
  and equal UID/firstInstallTime/dataDir. Verify never seeds missing evidence.
- Fixtures prove byte-preserved synthetic repositories/resources and protected
  peer/baseline/pending custody only. Empty migration exceptions are deliberate;
  necessary typed migrations require a new reviewed fixture/contract checkpoint.
- Desktop uses `desktop_control.py`, `prove_desktop_lan3_lan4.py` and the two
  `lan-next-sync-desktop-*.yml` workflows. The explicit build/upgrade request paths
  run only on `codex/lan-next-sync-control-*`; ordinary `desktop` identity is
  lan.4/0.1.40004/build40004, not Synthetic. The first workflow actually audits,
  packages, launches and closes the independent app before exposing its manifest.
- Desktop upgrade requests are generated from a real successful lan.4 bundle and
  actual artifact/run API metadata. The lan.3 base row is immutable. Cloud checks
  both actual outer/inner ZIP hashes and original source/control lineage before
  five correctly named lan3/lan4 native phases; no package is rebuilt for upgrade.
- The thin runner imports frozen native window/root-lock/seed/retention functions
  without calling the lan2/lan3 main. One old seed, retained Program, snapshots and
  normal close remain required. Only observed typed capturedAt changes can pass,
  and only when all three timestamp-owner source blobs match across actual source
  revisions. Other bytes/migrations are not waived; no full profile/identity,
  pixel, physical or user acceptance follows from these small synthetic fixtures.
