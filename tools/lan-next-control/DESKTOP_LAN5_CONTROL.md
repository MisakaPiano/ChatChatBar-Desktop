# V4 lan.4 to lan.5 candidate control

This additive profile retains the exact delivered V4 original artifact. The old
lan.3 to lan.4 request contracts, defaults and workflows remain available.

`desktop_lan5_control.py` selects fixed version `0.1.0-lan.5`, installer
`0.1.40005`, build `40005`, the V4 baseline and distinct manifest kinds. Request
JSON cannot select another profile or replace the delivered baseline.

Create the lightweight build request only after choosing the sole source owner's
fixed commit, including the correct packaged user guide:

```text
python -B tools/lan-next-control/desktop_lan5_control.py build-request --source-sha <40-hex> --output .github/lan-next-sync-desktop-lan5-build-request.json
```

That request path triggers `lan-next-sync-desktop-lan5-build.yml`. It reuses the
ordinary image identity audit, independent ZIP builder, actual launcher and normal
close proof. An application image is not a completed profile-upgrade proof.

After the build has finished, use its original bundle plus exact run/artifact API
metadata with the existing `upgrade-request` command to write
`.github/lan-next-sync-desktop-lan5-upgrade-request.json`. The separate upgrade
workflow authenticates both exact original artifacts and runs the established
five-launch, one-seed engine through `prove_desktop_lan4_lan5.py`.

The upgrade swaps only Program, preserves the old Program and UserData/launcher,
checks ordinary normal process close, and keeps the existing narrowly typed,
observed old-control timestamp rule with exact source bindings. It grants no new
migration waiver, main release or human acceptance. Native operations run only
on the guarded cloud Windows runner, never the user's installed program.
