# Post-write condition refinement — DD1 preparation only

The synthetic fault experiment now requires `CCB_SYNTHETIC_FAIL_AFTER_WRITES=1 AND NOT Installed AND NOT REMOVE~="ALL"`. Same-product repair must not use an existing JAR and Registry value as evidence that this experiment wrote NEW content. This changes only the optional fault fixture, not normal admission or issued1112/prepared1113 packages.

The [Installed property](https://learn.microsoft.com/en-us/windows/win32/msi/installed) distinguishes current-user/per-machine maintenance. The real [MsiEvaluateConditionW](https://learn.microsoft.com/en-us/windows/win32/api/msiquery/nf-msiquery-msievaluateconditionw) evaluator was exercised on an IGNOREMACHINESTATE restricted session: enabled forward without Installed is true; Installed=`1` or `0` (both present), REMOVE=`ALL`/`all`, disabled or absent switch are false. These eight combinations are **one JUnit test**, not eight independent tests or an actual upgrade.

Current-source result: **4 suites / 23 tests / 0 failures/errors/skips / exit0**. This reruns the affected operation graph and restricted-session checks, includes one new condition test, and is not added to the prior73. No actual installation, deferred callback, repair, uninstall or rollback ran. Source snapshots were frozen before execution. No new RED occurred; previous real failures remain retained.

## Earlier complete1114 fixture

A full opt-in1114 was built from source `cfa0d7d1d2eaa522295580ce991e521f3491978c` before this condition refinement:

- MSI `ChatChatBarDesktopSynthetic-0.1.1114.msi`,166322176 bytes, SHA-256 `224ba14cfdd628a8a602e93d24f048070d0377cea4ab1db7a404619c387675c3`.
- Product `{26F4D472-00DE-3DF8-A079-FA645F8C74FB}`, Package `{4017F60B-D54C-42B6-B718-7A6F0E69C7D3}`.
- Original operation guardr10 remains unchanged. Both Binary stream identities and action types/sequence were checked. Its own restricted-session test: **1 PASS / exit0**, no installation/deferred execution.
- This **unissued, uninstalled earlier fixture retains the old condition without NOT Installed**. It is preserved; it must not be used to claim the refinement or repair/new-write evidence. A future execution delivery needs a new package identity built from corrected source. Do not rewrite1114,1112 or1113.

Raw checks and source identities: [manifest](POSTWRITE_CONDITION_R02_SHA256.json). The full MSI and image remain only in the ignored local build directory. No user archive, credential, decrypted record or real data root was read or copied.

## Remaining native work

P1 still awaits the independent Win10 guest first installation. P2 lifecycle and P3 verified post-write failure with actual rollback remain NOT TESTED. A1603 alone, metadata success, or a fixed marker without independent final file/registration checks cannot pass P3. Ordinary Shortcut ownership and finite old-cache compatibility remain separate work. No Preview/P11 acceptance is implied.
