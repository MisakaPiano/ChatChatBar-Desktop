# Ordinary shortcut resource graph — partial implementation, admission still closed

DD1 continued independent P0 work while DL1 handles its existing copy/first-install steps. **No VM, installer execution, GUI, DPAPI, real credentials or user data operation.** No new DL1 command or package replacement.

## Specific mismatch and implementation

The installed JDK17.0.20.1 source `jdk.jpackage/jdk/jpackage/internal/WixAppImageFragmentBuilder.java` creates shortcut components with `JP_INSTALL_STARTMENU_SHORTCUT` / `JP_INSTALL_DESKTOP_SHORTCUT` conditions. Its menu link lives in a product-named leaf below ProgramMenuFolder; the desktop link lives directly below DesktopFolder. Current Gradle enables both only for the ordinary profile and sets menuGroup to the native product name.

The previous build-time `operation_resource_graph` tried to resolve the menu leaf into INSTALLDIR, producing `DIRECTORY_GRAPH_INVALID` for this legitimate graph. Its direct external-directory checks also did not describe actual conditional shortcut components. **This was a build-time recognition defect, not evidence that ordinary native installation had passed.**

Implemented separate `shortcutDirectories` / `shortcuts` metadata while preserving the program-only `directories` / `files` maps. Recognition is limited to:

- The two fixed ordinary links, exact product name and root main-launcher file; known current-user Desktop / Programs symbolic roots and one product-named menu leaf.
- Exact known choice-property conditions on their own shortcut components; unconditional program components. No arbitrary conditions, external payload ownership, arguments, advertisements, icon/extra shortcut fields or unknown resource family.
- Only that component's own empty menu leaf can be an external RemoveFile target. Desktop/Programs roots, wildcard/file cleanup and external CreateFolder remain refused.

This does **not** implement conditional component persistence/maintenance, choice-state evaluation, native known-folder resolution or existing-link ownership. Existing `component_records` still rejects the ordinary conditional components with `COMPONENT_ATTRIBUTES_OR_CONDITION_UNSUPPORTED`; the native guard still refuses nonempty Shortcut tables. Those are actual remaining engineering tasks, not a request for new Project permission. No source change enables ordinary installation or changes the issued synthetic guard.

## Actual synthetic fixture and executions

A tiny image contains only invented non-executable text named as the launcher and packaging-state XML. JDK17/WiX produced real metadata tables; this is **not a CCB application or full package**. The final fixture uses the current per-user/menu/group/shortcut/vendor/install-dir options, ordinary product identity, version0.1.1118. The raw MSI stays private and unissued. A separate metadata copy authors a Type19 refusal at1 in all five installation entry sequences and has a fresh PackageCode; neither was executed.

Initial exploratory fixture1116 additionally selected directory-chooser UI, which current Gradle does not request; the existing table reader rejected EventMapping. Fixture1117 corrected that option but retained JDK's default vendor, not the Desktop vendor. Both setup differences are retained and are not presented as additional Desktop regressions. Final1118 explicitly sets vendor ChatChatBar. The test golden is exported from its actual tables, independent of the production graph function.

- Initial RED:14 tests /12 PASS /2 FAIL /exit1 on the earlier fixture. One legitimate-shape rejection and one rejection-reason expectation; not two data-loss defects.
- Confirming RED with the **final1118 golden and frozen pre-fix source**:14 /12 PASS /2 FAIL /exit1. The original implementation is loaded from an unchanged snapshot; no production file was rolled back or overwritten.
- Current GREEN: **5 suites /37 tests /0 failures/errors/skips /exit0**, comprising14 new shortcut graph tests and23 actually rerun operation tests. This is not a sum with historical runs. Native tests use restricted Installer sessions/read-only metadata, not installation.
- Direct current-code checks verify issued1112 and prepared1113 complete graph/stream/component contracts,216 files each,0 shortcuts, unchanged input SHA. The final ordinary component-contract refusal is independently observed and retained.

Source snapshots precede RED/GREEN execution; the source-zip entry hash, generation commands/exits, fixture identities, original JUnit/logs and current pair boundary checks are in the [current evidence manifest](SHORTCUT_RESOURCE_GRAPH_SHA256_R02.json). No MSI/EXE/ZIP binary is committed.

Next engineering: native effective-user known-folder/actual link target and resource-ownership checks; exact optional component plan/maintenance handling; shared-GUID shortcut resource compatibility; then full ordinary synthetic acceptance. P1 actual1112 first install, P2 native upgrade and P3 rollback remain independent and pending. No P11/Preview PASS or Project acceptance is claimed.

Evidence assembly correction: the original71fa95f2 manifest included one generated Python import cache. A forward cleanup removes only that public copy; the unchanged original manifest remains for the original commit, and the local original stays retained. The R02 manifest describes the current tree and the exclusion receipt. Source snapshots, commands, logs and XML are unchanged; no test rerun or history rewrite.
