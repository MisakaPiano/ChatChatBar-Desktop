# Fixed delivered83 to candidate84

This additive profile keeps all81/82/83 controls unchanged. Its base is the exact
V4 signed83 (`ee602f89…e4af7b`,108385763 bytes), reconstructed only from authenticated
original raw83 plus its already published public block. No base application is rebuilt.

`candidate84_build.py` creates/validates a fixed83/84 request, overlays one seed83
companion at source90 and one verify84 companion at the selected frozen source,
then records four actual SDK-inspected inputs. The two new workflows trigger only
their new explicit request paths on the existing control branch family.

The root prepares the build request only after choosing the frozen source:

```text
python -B tools/lan-next-control/candidate84_build.py request --source-sha FROZEN_SOURCE --output .github/lan-delivered83-candidate84-build-request.json
```

After the real build, inspect its original API metadata and raw manifest. The
existing protected signing process supplies only public receipts keyed exactly
`seed83`, `candidate84`, `verify84`. It must reuse the same persistent801f… signer.

```text
python -B tools/lan-next-control/candidate84_sign.py request --raw-dir ACTUAL_RAW_DIR --artifact-metadata ACTUAL_API_METADATA --public-blocks THREE_PUBLIC_RECEIPTS --output .github/lan-delivered83-candidate84-sign-request.json
```

Neither request exists merely because these tools exist. The profile never opens
or initializes a private identity. Cloud code reuses the public assembler, SDK
verification, outer-ZIP authentication and strict instrumentation parser. It
requires a fresh single API26 qemu device, exact83→`install -r`84, separate
companions, unchanged UID/install-time/dataDir/proof, and actual repository/
protected-byte reads. The post-upgrade Activity method is the existing legacy
sample/repository smoke; it is not group UI or foreground-service evidence.

Local checks are Python/temporary-file checks only:

```text
python -B tools/lan-next-control/candidate84_test_control.py
```

Actual source compilation, SDK validation and native execution remain cloud
evidence. Old originals and failures remain unchanged.
