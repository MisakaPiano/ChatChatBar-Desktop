# Basic support summary

Added an explicit Data/update-page copy action. It receives build identity, a fixed update-check state and exactly three system properties. It does not read repositories, user paths, model settings, environment maps, request/response diagnostics or arbitrary exception details. Invalid build metadata is reported as unavailable; platform/JVM metadata uses fixed or bounded numeric categories. Browser-handoff failure does not turn a successful update check into a failed check. Clipboard errors are visible with fixed text.

Actual JDK17 offline/headless execution:

- First round: compileTestKotlin exit 1, zero tests executed; the new offscreen fixture incorrectly used Closeable.use on ImageComposeScene. Replaced with try/finally close; the original log/source remain.
- Intermediate: 2 suites / 15 tests / 0 failures/errors/skips / exit 0.
- Final: 2 suites / 15 tests / 0 failures/errors/skips / exit 0, with the final independent update-check failure state.

Five new JUnit methods include a single offscreen component method exercising both copy success and clipboard rejection. Those two branches are not counted as two methods. The existing ten update-check tests actually reran with loopback sources. No system clipboard, network update check, real data root, credentials or Provider was used by the new component test. No artificial runtime RED was manufactured for this new entrypoint. This focused result is not a rerun of the earlier 247-suite integration.

Raw command arrays, exit files, fresh original JUnit XML and pre-execution source snapshots are retained in the three named directories. SOURCE_IDENTITY.json identifies exact tested Kotlin snapshots; RAW_INDEX.json identifies the public raw files. This is Codex evidence, not Project review or human Windows visual acceptance.

Candidate user notes remain an unpublished draft. MSI rollback registration failure, pending administrator repair and physical Android/human UI limitations remain separate.
