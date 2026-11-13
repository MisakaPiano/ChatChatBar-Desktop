---
name: lan-delivered82-candidate83-control
description: Maintain the additive cloud-only exact delivered82 to candidate83 APK build and data-preserving upgrade control.
---

- New controls are under `tools/lan-next-control/` and the two
  `lan-delivered82-candidate83-*.yml` workflows. Frozen next-preview81/82 controls
  remain separate. `README.md` documents request creation and proof scope.
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
