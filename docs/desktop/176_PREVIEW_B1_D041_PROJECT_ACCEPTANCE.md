# 176 — PREVIEW-B1 / D-041 Scoped Project Code Review and Acceptance

**2026-10-23 JST — PREVIEW-B1 CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS.** This is a Project independent review of existing submitted GitHub source/evidence. **D-041 end-to-end, Public Preview Gate B and Public Preview are NOT SIGNED.** The accepted scope is strict metadata parsing, bounded update-check protocol tests, app-image build/version evidence, normal-close scoped process observations, and eight synthetic data/media byte-preservation checks.

## 1. Frozen Git identity and source scope

- Pre-B1 head: `243168e97609f45d8265a2e6ba77db09f6bfa125`.
- Source/tests/Skill with initial actual RED and final GREEN: `a0b0a0707142a8b9a2c7bfd4d33acb106138ae0f`.
- Windows/package/evidence commit: `9c5f3999acc10e99f893ed907b5dcdc92bbf2024`.
- Delivery-only commit and acceptance starting head: `0b7a00b5722175ab2c0dbd207c0bf56411be8ede`.
- Original [B1 handoff](evidence/preview-b1-d041-20261023/PROJECT_HANDOFF.md), [delivery](evidence/preview-b1-d041-20261023/DELIVERY.md) and [raw evidence index](evidence/preview-b1-d041-20261023/raw/index.json) remain unmodified.
- One production Kotlin file `DesktopUpdateChecker.kt`, one test file `DesktopUpdateCheckerTest.kt`, and a relevant `.agents/skills/chatbar-app-update/SKILL.md` note were modified in the tested source commit. Next commit added Windows evidence only; last added receipt only. No `DesktopUpdatePanel`, business Entity, Prompt, formal Gradle, installer updater, backup recovery, SecretStore or upstream code change.

## 2. Independently verified source behavior and RED/GREEN

Source review confirmed release JSON `draft/prerelease` require actual boolean primitives; `tag_name/html_url/published_at` and present `body` require string primitives; missing/null `body` stays empty and visibly handled by the unchanged panel. Strict UTF-8 decoder uses REPORT for malformed/unmappable data, avoiding replacement-character acceptance. Existing fixed Desktop repo, exact `desktop-v` tag/release HTTPS URL, version/channel checks, single unauthenticated GET, response bound and fail-closed rejection remain; no automatic install/download or new third-party source was introduced.

- RED raw JUnit: **3 suites/45 tests, 43 PASS/2 FAIL, 0 errors/skips, Gradle exit1**, with authentic failed `B1 string flags and non string notes` and `B1 malformed UTF8` expectations. The other two suites passed.
- GREEN raw JUnit: **3 suites/45 PASS, 0 failures/errors/skips, Gradle exit0**. Breakdown: `DesktopUpdateCheckerTest` 10, `DesktopStartupGateTest` 20, `DesktopProcessLifecycleTest` 15.
- Project individually opened all **six RED/GREEN JUnit XML** and checked suite counters and testcase counts; checked baseline production source equals RED snapshot and corrected production source/test equals GREEN snapshots, matching exact Git blobs.
- Project independently recomputed SHA-256 + byte lengths for **13/13 representative indexed raw text evidence items**, including failure/success logs, exits, XML, version identity and UI limitation. Codex `64/64` index-wide verification is **Codex self-reported**, not an independent Project full-64 recheck. Project did not rerun Gradle or Windows executables.
- This is bounded parser/transport engineering evidence, not actual on-screen Data/update UI acceptance or a real GitHub Desktop Release discovery. At review time the Desktop fork's public Releases API returned an empty array; no Release or Tag was published here.

## 3. Windows package / process / preservation facts

- Two actual **createDistributable app-images** (NOT EXE/MSI installer-upgrade) use Desktop version `0.1.0-preview.901` and `0.1.0-preview.902`, each reporting **217** packaged files. Embedded `desktop-build.properties` for each binds to source commit `a0b0a070...`, not a `development` fallback. Project reviewed archived identity and manifest metadata; did not independently access local binary packages.
- User-independent Codex native Windows observations show both windows started and were closed via **Alt+F4**, launcher exit 0/0; both traces include Compose return, task/draft/backup/coordinator drain, root ownership release and safe exit handoff. Scoped process inventory after close is empty; root lock is available after close. **JVM child actual exit code UNKNOWN**: for build902 a distinct same-path child process was observed while running, but no child handle/exit code retained. Launcher exit 0 must not be called child exit 0.
- Exact archived `before.json` and `exit.json` were independently compared by Project: **8/8** named initial synthetic character/session/message/draft/SaveSlot/player/image/audio files have identical byte-length and SHA values after both image runs. New derived/bundled preset files appeared in the isolated root, separately inventoried; do not confuse them with the eight seeded files. Root selection and an **opaque noncredential sentinel** remained unchanged per raw evidence, not proof of actual protected-key retention.
- Screenshot capture failed with real `FrameArrived timed out` / window-capture timeout; accessibility provided only window/pane. Thus **Data/update page interaction, rendered release/notes/error states and actual browser handoff = NOT TESTED**, despite static wiring/code and synthetic data checks.
- **Real clean installer EXE/MSI, Preview N→N+1 in-place installer upgrade, uninstall data retention, active protected credential/DPAPI retention, release network positive case and user real-data persistence = NOT TESTED**. Side-by-side app-images with different build labels over the same synthetic root are not installer proof; isolated environment variables do not create an independent Windows SID, HKCU or VM.
- No real user archive, Key/Token, DPAPI, live external provider or non-test root accessed. No release, tag, installer execution or desktop merge.

## 4. Remaining mandatory blockers and disposition

**PREVIEW-B1 / D-041 subset: QUALIFIED ENGINEERING ACCEPTED**; no immediate correction to the two parser fixes required on reviewed scope. **D-041 overall: PENDING; Preview Gate B: NOT SIGNED.** UI acceptance requires a working Windows rendering/accessibility validation path, potentially a narrowly scoped later user check only if Codex-first attempts fail. Installer/upgrade/uninstall and protected credentials require a genuinely disposable Windows account/VM/test host plus separate authorization for OS-level changes; neither is supplied by B1.

Prioritize P11 safe full-app migration and G1B unified data+SecretStore design to advance D-038. Do not force B1 scope to claim Gate B. P8/P9/P10 and P11 overall parity remain governed by their independent milestones. No automatic user-G2C authorization follows from this qualified acceptance. `CREDENTIAL_APPROVAL_REQUIRED` persists.

Formal validated upstream baseline **ChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8** remains. **P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / G2C NOT AUTHORIZED.** No parity promotion, upstream sync, publish, main-branch movement or code edits by this docs-only recording.

**STOP: PREVIEW-B1 PROJECT ACCEPTANCE REGISTERED / D-041 FULL GATE B PENDING.**
