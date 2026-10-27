# Synthetic Moments preservation POC

Not a production importer. See the complete scope, source pin, safety boundaries,
results and original RED in
`docs/desktop/evidence/p11-moments-poc-20261022/PROJECT_HANDOFF.md`.

Offline host reproduction from repository root (use a fresh output directory):

```powershell
python tools/desktop/experiments/p11-moments-preservation/poc.py run docs/desktop/evidence/p11-moments-poc-20261022/raw/20261022-180236-184-pull/artifacts/official-source.cbbackup app/desktopApp/build/moments-replay-new
```

`compare.py` takes source archive, host archive, official-return archive, output
JSON, and optionally the two corresponding extracted logical directories. It
checks every logical field and resource, including the exact Android package
root canonical alias. It does not silently discard unknown fields.

`Run-Official.ps1` is deliberately tied to the recorded new AVD and isolated scope.
It does not create a device or authorize another run. Reproduction requires a
fresh isolated AVD, exact source extraction, blank build credentials, and outbound
network denial before APK installation as described in EXPERIMENT_PROTOCOL.
Never point it at a physical device or an existing user application. The SDK,
JDK, APK and AVD images are not bundled in Git. All production sources remain
unchanged; OfficialMomentsPocTest.kt is copied only into the isolated official
androidTest tree.

Known limits: closed fixture schema, plain v1 only, imagePrompt=null, empty
characterPrompts, one explicitly permitted synthetic historical orphan. No
generic credential handling, production journal, durable power-loss proof,
post-publication crash proof, or active-provider scheduler test.
