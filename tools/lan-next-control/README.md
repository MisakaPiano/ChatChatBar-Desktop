# Isolated delivered82 to candidate83 control

This control is additive. The frozen81-to82 requests, workflows, helpers and
delivered files remain unchanged. There is deliberately no build/sign request
until the application owner freezes a candidate source.

The base application must be the exact delivered next-preview82 APK. A new
test-only companion compiled against its frozen10b source seeds synthetic
state; a separate companion compiled against candidate83 verifies that state
after a data-preserving reinstall. Neither fixture may seed during verification.

Only public APK signing blocks enter cloud requests. The persistent signer is
not initialized or read by these controls. Official Android SDK verification
and an actual cloud device execution are separate from local control tests.

## Files and execution

- `build_control.py request` creates the explicit build request only after a real
  application source is frozen. Its immutable contract includes both fixture
  hashes, package, versions82/83 and the exact delivered82 lineage.
  Fixture SHA-256 uses strict UTF-8 with canonical LF line endings; the cloud
  overlay writes exactly those bytes, independent of Windows checkout settings.
- The additive build workflow copies test-only companions into two clean,
  detached cloud worktrees:10b for seed82, the frozen candidate for verify83.
  It builds the82 companion only; it never substitutes a newly built82 app.
  Production worktree status is checked again after compilation.
- Four raw inputs are individually named and source-bound in `raw-manifest.json`:
  original82, seed82-tests, candidate83, verify83-tests. The original82 source
  artifact/run/control and raw SHA are hard-pinned in `contract.py`.
- `sign_control.py request` accepts three new public block receipts produced by
  the existing signing process. It reuses only the upgrade82 public block in
  the frozen old signing request for the base. This helper has no identity-file
  argument and cannot initialize or open an identity.
- The separate sign workflow verifies original artifact metadata, assembles with
  the existing version-independent public assembler, verifies all four APKs with
  SDK apksigner/aapt, then rejects an executable-byte mutation of candidate83.
- `prove_upgrade.py` requires an explicit GitHub Actions flag, exactly one fresh
  API26 emulator and no preinstalled app/test package. It installs exact82,
  runs one successful seed, force-stops it, reinstalls83 with `-r`, replaces only
  the test companion, and runs one successful verifier. JUnit assumptions do not
  qualify. UID/firstInstallTime/dataDir are compared before and after; proof bytes
  are compared before verifier installation and after verification.

Prepare a build request from the control checkout only when the source is ready:

```text
python -B tools/lan-next-control/build_control.py request --source-sha ACTUAL_FROZEN_SOURCE_SHA --output .github/lan-next-sync-control-build-request.json
```

After the raw run succeeds, inspect its exact artifact metadata and four source
rows. An external existing signing process supplies a JSON object keyed exactly
`seed82`, `candidate83`, `verify83`, whose values are its public `prepare` receipts.
No private identity or decrypted material belongs in that file. Then create:

```text
python -B tools/lan-next-control/sign_control.py request --raw-dir RAW_INPUT_DIRECTORY --artifact-metadata EXACT_ARTIFACT_METADATA_JSON --public-blocks THREE_PUBLIC_BLOCK_RECEIPTS_JSON --old-public-request .github/lan-next-preview-signing-request.json --output .github/lan-next-sync-control-sign-request.json
```

Both workflows are push-triggered only by their respective new request path on
`codex/lan-next-sync-control-*` branches. Changes to helper/fixture files alone do
not trigger builds. No request is included in the initial control preparation.
The request creator refuses to overwrite an existing request; use a new reviewed
control checkpoint rather than silently changing a frozen request.

## Preservation scope

Fixtures operate only in `files/lan-delivered82-upgrade-proof-v1` and a dedicated
Android Keystore alias. They reopen Character/Player/Studio history, PNG/Vibe and
post-process metadata, protected sentinel/identity, trusted peer, baseline and
pending plan. Every seeded file hash is compared before and after owner reads.
This proves synthetic private-root retention through the actual package upgrade,
not complete ordinary-user-profile migration, physical-phone acceptance, Wi-Fi,
QR, PC interoperability, large-data transport or the first preview series.

State migration exceptions are currently empty and cannot be wildcarded. A
necessary future migration needs a separately reviewed typed before/after rule
and fixture change, with its own provenance; it cannot waive unrelated hashes.

Local preparation checks (no JVM, SDK, APK signing, emulator or network):

```text
python -B tools/lan-next-control/test_control.py
```

The local checks validate substitution refusals, provenance, actual test status
versus skips, and UID/install-time/data-directory comparisons. Kotlin compilation
and the two actual device methods remain unproven until the explicit cloud runs.
