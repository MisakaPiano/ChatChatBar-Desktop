# Android verifier-only r02

This additive control preserves the original format1 request, fixtures and workflows.
The original failed actual upgrade run `43549286323` remains evidence: its verifier
reached repository/Keystore checks and then rejected newly materialized resource-cache
files after capture. This revision changes only the verifier companion and its control.
It does not change frozen source90 production, delivered82 or the seed82 companion.

`contract.verifier_only_request()` creates format2, pinned to production source
`90ead92410ca4c5e240f75d120ed6a145fb6f1c8`. It authenticates original artifact
`14095022954` from successful run `43547894406`, control
`00b934506b09c25adffa3876c9da7636cc9e2532`, outer size `216618510` and SHA256
`d3a4cd8ae1387e013337d3008d8ba9f74d8808ed747e9bf92a065ba48eaa80ba`.
All four original raw roles are checked against their published lengths/hashes and
source/fixture metadata. Base82, seed82 and candidate83 are copied byte for byte;
only verify83 is built anew. The manifest records the exact reuse ledger.

`fixtures/verify83-r02.kt` retains the strict complete pre-capture inventory and
baseline-hash comparison. After capture every original path/hash must still match.
An added file must be exactly `capture-scratch/resources/<lowercase SHA256>` in that
capture's actual resource manifest, with matching filename, byte hash and Long size.
No general scratch exception or state-migration waiver is granted.

The build workflow is `.github/workflows/lan-delivered82-candidate83-build-r02.yml`.
It checks source90 is clean, overlays only the new test source, invokes only
`:app:assembleLanPreviewAndroidTest`, and collects the three original inputs rather
than shipping rebuilt production/seed output. The original ZIP is authenticated by
API provenance and actual outer bytes/digest/CRC before extraction. Its bounded
stream reuses the existing Python download adapter, which strips the bearer token
before following the signed download URL; no extra CLI is needed. All four APKs
still receive independent package/minSdk/version inspection. It publishes:

- `ccb-lan-delivered82-candidate83-inputs`: full four raw inputs, distinguished by run.
- `ccb-lan-delivered82-candidate83-r02-manifests`: small JSON evidence.
- `ccb-lan-delivered82-candidate83-r02-verifier`: only `verify83-tests-input.apk`.

For the local public signer, reconstruct a new raw directory from the three already
authenticated original APKs, the new small verifier artifact and the new manifest /
source-request JSON. `sign_control.validate_raw` dispatches format2 reuse validation;
the original three published byte identities and reuse ledger cannot be relabeled.
Reuse the existing public signing blocks for seed82/candidate83 and create a public
block only for the new verifier. The delivered82 public block stays unchanged.
`sign_control.py request` then accepts the same existing four-role signing contract
with a format2 nested build request and the new full artifact's exact API metadata.
No r02 sign request is created until those new raw inputs and metadata exist.

The independent sign workflow listens only to the future
`.github/lan-next-sync-control-sign-r02-request.json`. It reuses the existing public
assembler, all four SDK verifiers, candidate byte-tamper rejection and API26 actual
install82 / seed / install83-without-clearing / verify flow. A successful build or
Python check is not evidence that this Android execution passed. Evidence artifacts
have r02-specific names; no old evidence is overwritten.

Light synthetic control checks (no JVM/SDK/native execution):

```text
python -B tools/lan-next-control/test_control.py
python -B tools/lan-next-control/test_fixture_only_control.py
```

The first suite retains its 14 original cases. The second checks exact three-role
reuse with real synthetic files, rejects self-consistent substituted files, checks
source/fixture/reuse bindings and authenticated ZIP extraction, and substitutes the
APK metadata adapter only in its collection unit case. Neither suite claims APK
compilation, Android Keystore execution or real-user-profile upgrade coverage.
