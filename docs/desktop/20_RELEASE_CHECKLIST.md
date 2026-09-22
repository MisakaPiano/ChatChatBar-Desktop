# Release checklist

- baseline version/commit/schema verified
- master remains clean upstream mirror
- release based on desktop
- Android compile regression passes
- Desktop/shared tests pass
- clean install + upgrade tested
- data migration snapshot / backup / restore tested
- Character JSON and CCB PNG roundtrip Android <-> Desktop
- Prompt parity
- WorldBook parity
- SaveSlot Android <-> Desktop
- streaming/cancel/session restart
- parity rows for claimed scope are resolved
- no credentials in Git/logs/artifacts
- installer/uninstaller/update path tested
- direct upstream-author authorization record retained/confirmed before public release packaging；repository standard LICENSE metadata status documented separately
