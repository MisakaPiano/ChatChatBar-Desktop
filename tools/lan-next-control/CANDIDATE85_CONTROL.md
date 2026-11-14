# Delivered84 to candidate85 control

`candidate85_control.py` selects one code-owned `candidate85_profile.PROFILE`.
`upgrade_series_engine.py` takes an explicit trusted profile and reuses the
existing ZIP/API validator, public assembler, SDK helpers, strict instrumentation
parser and serial-bound Device I/O. Old candidate84 modules, namespaces, requests
and workflows are unchanged.

The baseline is the exact interim-delivered V5/84. The full original raw artifact
14117481315 is authenticated and its original84 manifest validated with the
unchanged candidate84 raw validator. The unchanged public block comes from the
fixed31ef candidate84 signing request; reconstruct base84, never re-sign it.

Both new workflows trigger only their own absent request files. The source owner
must allow preview85 and supply a mature frozen source before a build request
is prepared. Root can use SeriesEngine.build_request(frozen_source) and the
exclusive contract writer. After an actual raw build, prepare exactly three
public receipts using the existing protected801f signer, then prepare-sign.
No new identity, source, request, signature or CI run is created by these files.

Actual cloud SDK verification precedes freshAPI26 install-r84-to85. Upgrade and
legacy Activity smoke are separate phases and retain original reports. Synthetic
fixtures preserve repository/image/legacy-peer/Keystore inventory; they do not
prove group/cloud UI, actual providers, physical phones or the whole user root.
Group/cloud behavior requires its separate affected checks.

Light guards: python -B -m unittest test_candidate85_control candidate84_test_control
from tools/lan-next-control. These Python/FS checks do not compile the Kotlin
companions or perform SDK/native work. Native checks remain cloud-only.
