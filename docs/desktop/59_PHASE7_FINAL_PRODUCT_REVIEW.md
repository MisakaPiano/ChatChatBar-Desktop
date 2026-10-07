# Phase 7 Final Product Closure evidence

Start: `a0918deab8348ed0c2c70442b08e9312b28de247`, same `feature/phase7-image-novelai` branch. Status: **READY FOR PROJECT PHASE-7 FINAL PRODUCT REVIEW / NOT ACCEPTED / NOT MERGED**.

Production SHA: **`1e7008c3bbec4e6c32e239427cdd8e95c956c3d3`**. Full-regression checkpoint: **`7870b093dde5f06d8aa8bab92065d08eed4d7353`**. Final docs HEAD is the docs-only commit containing this completed evidence (parent `7870b09`; resolve with `git log -1 --format=%H -- docs/desktop/59_PHASE7_FINAL_PRODUCT_REVIEW.md`). It does not change production or tests.

## Scope and formal mapping

The third manual UX HOLD supersedes presentation-ready status in `53`; that document and its package are historical evidence. This closure follows user-authorized `54` / `56`. `55` is the explicit deferred owner map. No Phase 8 or merge.

| Formal CCB 1.4.1 owner | Desktop adaptation |
|---|---|
| `NovelAiDesignScreen` reply card and conversation | `DesktopDesignField` retains TextFieldValue selection/composition and delayed echoes; structured target/base/ordered-character cards with per-module Copy; existing Apply/Retry/Regenerate/Edit-and-branch callbacks |
| `ImagePromptToolScreen.CharacterCardImport` | Formal label and purpose description, card-name selector, no internal reference-source list exposed |
| `ImagePromptToolScreen` dynamic Generate control | Shared `NovelAiImageCostEstimator` result only; free/Anlas/encoding/Vibe labels, busy progress and unavailable state |
| `ImagePromptToolScreen.OutputPanel` | Adaptive aspect-fit result viewport, lazy horizontal current/recent history filmstrip, selection/viewer, same History reuse callbacks |
| Studio Prompt/token/status controls | Three action levels, recognizable icons, grouped account status, two token bars using existing tokenizer counts |
| Chat composer/session settings | Attachment/fullscreen/send action rail; 112dp previewable attachment strip with hover ×; Basic/Context/Images/Advanced settings tabs and global save/cancel footer |
| Desktop diagnostic tool | Tools → Advanced / Diagnostics → Main-chat Prompt Inspector; retained inspector runtime |

Formal authority remains `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; approved `ace632c...` safety exception remains unchanged. Current runtime, repository, Prompt Designer and HTTP generation ownership is retained. There are no sharedCore/Android production changes in this closure.

## Failure and editor boundary

`DesktopDesignInput` keeps selection/composition across recomposition and queued local repository echoes, replacing text only on a distinct external authority update. New-conversation/turn identities isolate editors. Enter and Shift+Enter insert newlines; Send explicitly submits. Normal keyboard/IME candidate confirmation never sends a design request.

Failure UI uses fixed categories: model unavailable, authentication, network, provider, malformed/incomplete response, cancelled, unknown. Shared transport's fixed HTTP status prefix is inspected without publishing its body. Shared terminal refusal and cancellation types survive the adapter, preventing a new fallback/retry path. The controller writes only safe category text to failed turns; failed design never applies to the Studio draft. Retry and branch still call the existing shared conversation repository and turn runner.

Background remains immediate-save via the existing controller; tab switching never owns a new draft. Other settings stay in the controller's session draft and keep Save/Cancel/leave confirmation behavior. The Desktop-private character background library and Entity/Package meaning are unchanged.

## Local functional acceptance

`runPhase7FinalProductFixture` is a test-classpath-only manual window. It uses a new temporary profile, empty in-memory SecretStore and a fake text transport through the real shared Designer, turn runner and repositories. No actual design-model/image transport is selected. Open AI Design, enter Chinese, select SUCCESS, send, Copy a module, Apply, edit/branch; choose an error category, send and Retry after returning to SUCCESS. The original Studio draft remains intact on failure. Closing removes only this fixture's temporary profile.

The production EXE has no fixture mode. Actual Windows IME manual acceptance remains user-owned in `57`; automated TextFieldValue composition/selection checks are not an OS-IME signoff.

## Validation

Focused **PASS: 5 suites / 77 tests / 0 failures / 0 errors / 0 skips**, including 15 Final Product tests. Desktop affected compile PASS; BUILD SUCCESSFUL in **55s**. Evidence: `app/desktopApp/build/phase7-final-product-evidence/focused-final/` (XML/counts/log).

Coverage: composition/selection/delayed local echo vs external replacement; fake auth/network/provider/malformed/incomplete/terminal classification with no error body/cause leak; shared auxiliary envelope equality and terminal refusal/cancellation; shared design runner success/Copy/Apply/retry/branch/regenerate; cost/encoding/Vibe labels and token thresholds; current/recent order and wheel/selection; 1280/1600/700 layouts; 112dp hover removal/preview/zero-height empty; tab keyboard navigation, preserved draft and background/opacity persistence after Cancel; existing chat/controller/fullscreen regressions.

Development findings were investigated: status-prefix regex escaping was corrected; invalid empty-message error fixtures now use valid auxiliary envelopes; generated role IDs and revision/time stamps are checked by behavior rather than independent constructor equality; the session fixture creates a real persisted session; Copy navigation traverses selectable text; the old collapsed-layout source guard now checks the always-visible action rail. A test returning an exception value was corrected to Unit for JUnit discovery. No production check was waived. Initial failed XML/log: `focused-first-failed/`; compile/development logs remain `app/desktopApp/build/phase7-final-product-*.log`.

Rendered local fixture evidence (no generated account image):

- [Studio 1280](refs/phase7-final-product/validation/studio-1280.png), [1600](refs/phase7-final-product/validation/studio-1600.png), [700](refs/phase7-final-product/validation/studio-700.png)
- [Structured design](refs/phase7-final-product/validation/structured-design.png), [filmstrip after scroll](refs/phase7-final-product/validation/filmstrip-scrolled.png)
- [Session image tab](refs/phase7-final-product/validation/session-images.png), [keyboard tabs](refs/phase7-final-product/validation/session-tabs.png), [attachment hover](refs/phase7-final-product/validation/attachments-hover.png)

First full rerun completed 1005 tests with 3 obsolete-expectation failures: two source guards still required the replaced split composer layout (and used the renamed settings function as their extraction boundary); one adapter test expected the old unclassified exception for a truncated reply. Test-only corrections preserve the send/stop guards and request-envelope checks, and now assert the safe RESPONSE category without body/cause. Correction gate PASS: 2 suites / 13 tests / 0 failures/errors/skips, compile PASS, 32s. Failed full XML/log retained in `phase7-final-product-evidence/full-first-failed/`; corrected focused evidence in `test-corrections/`. No failure was waived and production source remains `1e7008c3bbec4e6c32e239427cdd8e95c956c3d3`.

**FINAL-SOURCE FULL REGRESSION PASS** at test checkpoint `7870b093dde5f06d8aa8bab92065d08eed4d7353`; production source remains `1e7008c3bbec4e6c32e239427cdd8e95c956c3d3`. Desktop full: **110 suites / 1005 tests / 0 failures / 0 errors / 0 skips**, `:desktopApp:test --rerun` executed, BUILD SUCCESSFUL in **2m 1s**, exit 0. Desktop compile PASS (UP-TO-DATE after executed focused compilation). No production changes during/after the full run. Final XML/counts/log: `app/desktopApp/build/phase7-final-product-evidence/final-full/`.

Shared/Android production diff from `a0918de` is empty; conditional affected shared/Android tests/compiles are N/A, not claimed rerun. Working-tree and `desktop...HEAD` diff-check PASS.

All Gradle gates use JDK `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`, from `app/`, with `-I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process`. The init script changes only Desktop generated output to `C:\Users\1\AppData\Local\Temp\ccb-p7-r2-r1-build`; source/worktree stays on H:. Tasks: focused `:desktopApp:test --tests ... :desktopApp:compileKotlin`; full `:desktopApp:test --rerun :desktopApp:compileKotlin`; package `:desktopApp:createDistributable --rerun`.

## Deferred / safety

`55` assigns provider drift to `49` + `14` + release gate `20`; complete Studio presets and global IA to P17; Moments to P13; Community P14; OS/installer integration P15. This task implements none of them. No model/sampler/API capability, pricing algorithm, tokenizer, Prompt literal, Entity/Package, History/Guidance or automatic eligibility changes.

NovelAI real generation remains **1/8**, this task **0 additional requests**. All new functional validation is local/fake; no real external design-model request. The old acceptance packages are preserved.

## Replacement package / launch smoke

`:desktopApp:createDistributable --rerun` **PASS**, BUILD SUCCESSFUL in **27s**, exit 0. New isolated acceptance executable:

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-final-product-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

| File relative to package directory | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-49eb549fa04bb9c24e2af4254dbffd9e.jar` | `6591F9D32295E6DCE8D811ACCABD410A2BC06420E1EC43E773DE42B8A8CA1768` |
| `app/sharedCore-61e5b677e5316ef2dd5eeb9c3a255.jar` | `D0A80F01B58D827F7FF2BD5BE6B14BD63C56A88E1999688D877364DA3467A8F3` |

All three source/destination copy hashes match. Launcher hash alone is not production identity; use the Desktop JAR hash as well. Build log and hash manifest: `app/desktopApp/build/phase7-final-product-package.log`, `phase7-final-product-package-hashes.json`.

Launch smoke **PASS**: exact new executable, independent empty APPDATA/LOCALAPPDATA profile, matching `ChatChatBar` / `SunAwtFrame` main window owned by the launched child; alive before normal WM_CLOSE; launcher/application exit **0/0**, stdout/stderr **0/0 bytes**. No normal user profile/SecretStore was opened and no generation was sent. Script/result: `app/desktopApp/build/phase7-final-product-launch-smoke.ps1`, `phase7-final-product-launch-result.json`. Compact durable gate record: [gate-summary.json](refs/phase7-final-product/validation/gate-summary.json).

## Durable commits / review handoff

- `1e7008c3bbec4e6c32e239427cdd8e95c956c3d3` — `feat(desktop): close Phase 7 product workflows`; source, focused tests, relevant skills, adopted pack and REF evidence.
- `7870b093dde5f06d8aa8bab92065d08eed4d7353` — `test(desktop): align final product regression guards`; test-only correction plus evidence, no production change.
- This final docs-only commit — `docs(desktop): record final product validation`; completed evidence and CURRENT reconciliation. Skill review: implementation mappings were updated with production; test/docs corrections introduce no new stale skill content.

`desktop` and `origin/desktop` remain `b3ecd41267906526e7b603972f7388e59c90648d`; parked `sync/1.4.4` remains `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`. No merge, baseline promotion or new Phase. User manual checklist [57](57_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md) is still pending, including real Windows Chinese IME interaction; automated evidence does not mark Phase 7 ACCEPTED.
