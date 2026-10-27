# Full1112 package prepared — native P1 pending

This is an internal unsigned synthetic package, **not a public release or an installation PASS**. DD1 has not run its installer. DL1 is operated locally by the user; no RDP/browser automation is used.

| Identity | Fixed value |
|---|---|
| Installer source / package identity commit |`aaf024507fe1113784d926d8651217cc5d44b6cb`|
| Package |`ChatChatBarDesktopSynthetic-0.1.1112.msi`|
| MSI SHA-256 |`5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79`|
| MSI byte length |166182912|
| ProductCode |`{57CF060B-D0A1-3993-834A-15B88CB00D5D}`|
| PackageCode |`{432781D2-6756-4AB8-A829-88E6AD89D32D}`|
| Embedded production guard SHA-256 |`2308ce699ccb9c022520d3e8223e8f806c6af7bde18da16c24d0dad266870832`|
| Guard bytes |212992|
| Local delivery ZIP |`I:\CCB-OP1-DL1-20261027-r01.zip`|
| ZIP SHA-256 |`97a9c3b61e118aa72d2f21b054eb8e1f7bcace1490180f5960ef8e708ab86d6e`|
| ZIP byte length |166313671|
| Manifest SHA-256 |`d6f1701fd16130c7a371c5b2eae2999de077f84f2cf4d8e04c609b35487c8f19`|

Both raw and corrected package, source snapshots, arguments, stdout/stderr/exits and cabinet-preservation receipt are retained under the ignored `app/desktopApp/build/preview-continuous-private-20261025/operation1-native-pair-1112-1113-20261027-r01/A/`. Corrected cabinet bytes match the raw package. Runtime classes are reused from the verified unchanged946c3791/1010 runtime; only the packaged build-identity entry changes. This is a new full MSI/app image with distinct packaging provenance, **not a fresh Kotlin compilation**.

The actual authored payload is216 files,218 components and220 Registry rows. The original image count217 includes `app/.jpackage.xml`, which JPackage does not put in the File table. This is tooling metadata, not a discarded business record. The transfer builder compares the actual graph with the image and allows exactly that one image-only metadata entry.

Additional validation: one restricted-session test on the actual1112 package PASS/exit0, with unchanged source bytes and no installation. Windows PowerShell5.1 helper tests: initial13 cases had two fixture-oracle failures (null expectation coerced to empty string); preserved raw RED. Corrected final **17 cases PASS**, consisting of13 fake orchestration boundaries plus four real tiny-file oracle checks. C# declarations compiled; no real registry query, installer, VM or window was executed by those tests. These counts do not establish native P1.

The seven-file ZIP includes only this full synthetic MSI, fixed manifest, class provenance, two PS helpers, fixed hash-pinning wrapper and instructions. It contains no user archive/data, credential, signed release material or raw private diagnosis.

## User's local DL1 nodes

1. Copy/extract the fixed ZIP on DL1. In administrator PowerShell, run `Create-Operation1TestCopy.ps1 -CreateCopy`. It creates only a new independent copy from the exact clean checkpoint, retains its export, disconnects only its network, and leaves it inactive. It does not restore/delete/start the original or old failure VM. Disk preflight failure preserves the scene and reports required/available bytes.
2. Open/start **CCB-MSI-Op1-1112-1113** locally and copy the whole extracted directory into it. The guest model/SID check cannot by itself prove host VM identity; the human selects that independent copy.
3. Run `Run-FirstInstall-1112.ps1 -ConfirmIsolatedGuest -OpenSyntheticWindow` in the new guest. It installs once, checks actual resources/native registration/rollback mode, then opens an isolated empty-data application window. Close normally; no provider action or account configuration.
4. Preserve `%LOCALAPPDATA%\CCB-OP1-First-1112-r01\RESULT.json`, `INSTALLED_RESOURCES.json` and `install-1112.log`. Any failure stops without cleanup/retry. Raw guest identity/paths remain local; future public evidence must be reviewed/redacted.

Expected installation evidence is exit0, native product5/feature3, all216 file identities,218 component states/KeyPaths,220 own values and `CCB_OPERATION_ADMITTED operation=F ... rollback=ENABLED`. Actual results are **NOT TESTED** until returned from that run. UI observation, descendant drain/root-lock release, P2 maintenance/upgrade and P3 physical rollback are separate outcomes. P4 old cached guards remain unchanged; the ordinary-profile Shortcut adapter is still unfinished. Continue independent DD1 work while the user operates DL1; do not repeat the old package tests.
