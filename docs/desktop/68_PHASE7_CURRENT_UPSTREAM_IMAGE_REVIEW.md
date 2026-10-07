# Phase 7 current-upstream image closure — review evidence

**READY FOR PROJECT PHASE-7 CHAT IMAGE ACTION REVIEW**

## Assistant message image action — CURRENT, 2026-10-08

Narrow slice from `3297fa6bb4e2926d54b824fac73e3cc221fe4401`, same feature branch.
Phase 7 remains **NOT ACCEPTED / NOT MERGED**; controlled user acceptance is not signed off here.

- **Production SHA: `c0b9b6af3c6872233dc3554e491aae4754cde349`**, pushed: `fix(desktop): anchor assistant image actions and preflight`.
- Docs HEAD is the docs-only commit containing this CURRENT section and `16` / `21`:
  `git log -1 --format=%H -- docs/desktop/68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md`.
- Production changes: `DesktopAssistantImageActions`, `DesktopChatImageRegeneration`,
  `DesktopPrimaryChatPanel`, and extraction of footer code from `DesktopMessageImages` in
  `DesktopImageViewer.kt`. Actual image rendering and Viewer implementation are unchanged.

### Authority and behavior

Reference only: official upstream **`8c4ba52ba445a148c2991c68a6356431dc5a3efe`**,
`ChatBubble.kt` → bottom `MessageMetaRow` / `GenerateImageActionButton`, `ChatScreen.kt` →
requirements dialog, `ChatViewModel.generateNovelAiImage` → hint/preference semantics.
No upstream sync/promotion; unrelated advanced-image-settings changes from that commit were **NOT absorbed**.

- Actual media remains before body as before. New footer sits **after all Assistant body segments**;
  compact right-aligned icons have exact accessibility/tooltip labels `生成图片` and `生图要求`.
  Footer ownership requires the exact persisted message in the selected live session and normal action mode;
  generation icons additionally require nonblank ASSISTANT content. USER/SYSTEM/blank/streaming/read-only
  and stale/unselected message states cannot expose these actions. Task status stays source-anchored below body.
- Direct action reads the current session preference. Requirements opens with `图片内容提示` (per-run)
  and `生图偏好` (session preference). Confirm uses the existing shared Designer authority and persists
  preference only when requested; cancel has no persistence/task/network side effects.
- Structured preflight runs before preference persistence or TaskRuntime admission: same durable nonblank
  Assistant source, durable session, linked Character, SecretStore credential presence, existing
  EffectiveModelResolver image-model selection/fallback and usable endpoint, configured model authentication,
  normal shared NovelAI target resolution, and global shared aspect-ratio validation.
  Only fixed safe reasons leave the boundary; no raw exception/provider text or credential content.
  No account/Anlas fetch prerequisite. Task work repeats preflight and retains later source/publication checks.
- Existing runtime/Prompt Designer/HTTP/retry, automatic-image eligibility, Package/Entity schema,
  SecretStore ownership, AI Design, Studio/History/Viewer/Tags/Guidance and image processing remain unchanged.

### Final-source validation

**FINAL-SOURCE DESKTOP FULL REGRESSION PASS** on production `c0b9b6af3c6872233dc3554e491aae4754cde349`.

| Gate | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| Focused actions / fake integration / Compose / existing affected regressions | 6 | 44 | 0 | 0 | 0 |
| Desktop full `--rerun` | 119 | 1065 | 0 | 0 | 0 |

Focused **1m03s**, full **3m05s**, exit 0. Desktop compile PASS (executed during development;
UP-TO-DATE on final focused/full). Working/staged/full-feature `git diff --check` PASS.
No production change after the final full gate. No shared/Android production diff from the slice start;
conditional affected suites/compiles N/A and not rerun.

Nine new tests cover:

- Real repositories/Character/session/resolver, in-memory SecretStore, fake Designer transport and fake
  NovelAI HTTP, real generation adapter/TaskRuntime and durable image persistence. Direct, requirements,
  transient preference and resolver fallback all finish COMPLETED. Reopened repository proves the linked
  ASSISTANT image immediately follows its exact source, with `generatedFromMessageId`, metadata and owned file.
- Actual Compose bubble click also completes the same fake chain; bounds prove preceding USER → Assistant
  body → compact icons → task status. No placeholder-only or mocked persistence success.
- Eleven preflight negative cases (credential/Character/model/invalid URL/auth/ratio/edited source/deleted
  source/missing session/USER/blank) assert **zero task, zero Designer request, zero NovelAI request**, and no
  preference mutation. Visibility covers SYSTEM, transient/unselected and normal-actions-off states too.
- Actual Compose preflight reason appears below body; requirements form field edits + Cancel retain the
  complete session object and create no task/request. Nonblank hint/preference reach existing Designer requests;
  hint never becomes a session setting. Existing retry test still retains original per-run requirements.

Early failures were test setup only and were corrected without weakening behavior: logical message content
is JsonElement, so the new request assertions needed explicit text conversion; the no-mutation fixture snapshot
must be taken after adding its source message (which normally updates session preview/time).
First executable focused run was 44 / 1 failure; all positive fake and Compose tests already passed.
Logs/XML/counts retained under `app/desktopApp/build/phase7-chat-image-action-evidence/`:
`focused-first-failed`, `focused-final`, `final-full`; compile attempt log is
`app/desktopApp/build/phase7-chat-image-action-compile-failed.log`.

Commands: JDK17 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`, run from `app/` with
`-I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache
--no-configuration-cache --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8"
-Pkotlin.compiler.execution.strategy=in-process`. Focused uses `:desktopApp:test` filters
`*DesktopAssistantImage*Test`, `*DesktopCurrentImagePresentationTest`, `*DesktopPhase7R2R1Test`,
`*DesktopPhase7ReauditTest`, `*DesktopChatFloatingNavigationTest` plus `:desktopApp:compileKotlin`.
Full: `:desktopApp:test --rerun :desktopApp:compileKotlin`; package: `:desktopApp:createDistributable --rerun`.

### Current package, smoke and manual checklist

Fresh isolated package **PASS 30s**, exit 0:

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-chat-image-action-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

| File relative to package | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-ef8a96f1b89e70520f6acffa96cf6.jar` | `5BFB20C4F29C47D22BB62B1345BD6BB33CB6E6FF08AC9323A3C9462F27AC85ED` |
| `app/sharedCore-febeeab028b94c68767721f7465d4823.jar` | `B507F52C9FF10826064443CCD98356E05245AD0D0FD9806BAF60D1F9B716E023` |

All copied hashes match fresh output. Launcher alone is not a production-source fingerprint;
use the Desktop JAR hash too. Blank-profile launch smoke PASS: independent empty APPDATA/LOCALAPPDATA,
exact EXE/child and `ChatChatBar` / `SunAwtFrame` found, alive before normal WM_CLOSE,
launcher/application exit **0/0**, stdout/stderr **0/0 bytes**. Normal profile/real credentials untouched.
Smoke/hash records: `app/desktopApp/build/phase7-chat-image-action-{launch-smoke.ps1,launch-result.json,package-hashes.json}`.

Supplemental user-owned checklist (use blank/local fixture profile; **no real generation authorization**):

- [ ] Open a chat with a persisted USER followed by nonblank Assistant text. Only that Assistant has the
  two compact icons after its text; hover/accessibility labels are `生成图片` / `生图要求`.
- [ ] In a profile without NovelAI credentials, click Generate: safe `未配置 NovelAI Token` appears under
  that Assistant, with no newly admitted image task. Other missing configuration cases use the fixed reasons above.
- [ ] Open requirements: current session preference is populated. Edit both fields and Cancel; reopen to
  confirm persisted preference is unchanged. Confirm/direct success is covered by the fake-only integration above.
- [ ] Existing attached/generated images still display/preview at their original media location; USER,
  blank Assistant, streaming and read-only messages never expose generation icons. Prior AI Design behavior remains.

NovelAI live count remains **1/8**; new real generation / Enhance / Upscale / AI Design requests **0**.
Baseline blob `ea8d53aac709179c33b78bb6b28c2fd58c1c904b`, desktop/origin
`b3ecd41267906526e7b603972f7388e59c90648d`, parked sync `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`
unchanged. No Prompt literal/algorithm changes, no baseline promotion, no merge or Phase 8.

## AI Design parity follow-up — historical evidence, 2026-10-07

Project confirmed the prior R1 repairs PASS at `3c501655d248ee9c6c74df8b551f92c1215dac50`.
This follow-up closes only the three additional Design parity gaps; GIF/History/image-core repairs
were not revisited. Phase 7 remains **NOT ACCEPTED / NOT MERGED**.

- **Production SHA: `13ba18bb27094fae54a085cde6fc3ac58a31d9d8`**, pushed:
  `fix(desktop): align AI Design context and navigation ownership`.
- Docs HEAD is the docs-only commit containing this CURRENT record and `16` / `21`:
  `git log -1 --format=%H -- docs/desktop/68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md`.
- Production changes are limited to `DesktopNovelAiStudioController`, `DesktopDesignConversation`,
  and `DesktopNovelAiStudioPanel`. Relevant skills and seven focused tests accompany the production commit.

| Gap | Ownership / evidence |
|---|---|
| Extra requirement | Design Settings reads `draft.extraRequirement`, writes through `setDesignRequirement` → Studio repository. New conversation context and imported-image reverse design use `launch.extraRequirement`. The fake tests use distinct draft/AppSettings sentinel values and assert only draft requirements reach Designer requests. Settings persistence survives repository reopen; the complete AppSettings object stays unchanged. Changing the draft affects new conversations only; old conversations/continuations retain their original immutable `designContext`. |
| Actual tool exit | `leaveDesignScreen()` mirrors upstream's composing-new guard: restore durable current, clear transient input/attachment/progress/reasoning/status. `DesktopDesignToolLifecycle` spans conversation/settings/history as one lifetime; changing internal surfaces does not dispose it. Return Studio, auxiliary OS close and leaving the whole tool invoke cleanup. Tests cover internal navigation preservation, restored conversation A, no-current behavior, and the real Studio auxiliary OS-close route. Entity bytes **and modification timestamps** plus Studio draft remain unchanged. Existing durable-conversation composer behavior follows upstream's early return. |
| Runtime scroll | Conversation disposal/switch remembers index + offset through shared `rememberScrollPosition`; mounting uses shared `consumeInitialScrollPosition`. No extra persisted entity/map. New-turn arrival may scroll to the newest turn; internal navigation does not blindly scroll down. Compose tests drive actual scrolling with nonzero offset, verify settings/history round-trip restoration, independent A/B positions, and `switchCurrent`'s one-shot newest-position override. |

Authority checked at formal **1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`** and current
**1.4.4 `550409689df8c51f459fb50b4e04c8ac2fa4bf35`**: `NovelAiDesignViewModel` context/leave/scroll,
`NovelAiDesignScreen` scroll lifecycle, `ImagePromptToolViewModel` reverse requirement. This repairs
Desktop wiring; it is not a new Prompt or baseline adoption. `AppSettings.imagePromptToolPreference`
is neither renamed, migrated, deleted nor repurposed.

**FINAL-SOURCE DESKTOP FULL REGRESSION PASS** on the production SHA above:

| Gate | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| Focused parity + existing R1 Design/auth/Compose | 5 | 40 | 0 | 0 | 0 |
| Desktop full `--rerun` | 117 | 1056 | 0 | 0 | 0 |

Focused **53s**, full **2m 17s**, exit 0. Desktop compile executed during development and PASS
(UP-TO-DATE in final focused/full). Working/staged/full-feature diff-check PASS. No production changes
after final gate. Shared/Android production diff from `3c50165` is empty; affected reruns N/A.
JDK17, SSD init and bounded-memory options are the same as the R1 command below. Focused filters:
`*DesktopDesignParityTest`, `*DesktopImageClosureR1DesignTest`, `*DesktopDesignAuthenticationTest`,
`*DesktopFinalProductTest`, `*DesktopCurrentImagePresentationTest`.

Failed focused evidence is retained: first attempt 40 tests / 2 failures, then 40 / 1. The reverse
fixture needed a multimodal fake model; its non-multimodal model correctly triggered shared capability
validation. The scroll harness rendered every frame at default time zero, so its animation never
produced the requested nonzero offset. Advancing the frame clock fixed the harness while retaining
strict restoration/newest-position assertions. No production workaround or waived test.
XML/log/counts: `app/desktopApp/build/phase7-design-parity-evidence/` (`focused-first-failed`,
`scroll-clock-failed`, `focused-final`, `final-full`).

### Current parity package / smoke / manual checks

`:desktopApp:createDistributable --rerun` **PASS 26s**, exit 0. New isolated package:

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-design-parity-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

| File relative to package | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-607db34e2b58ee2f67929ba2c7c0b6.jar` | `B15D877653C7EA821714ACE00226B0DD9D03DF5B2D46933079DD04112D89E634` |
| `app/sharedCore-febeeab028b94c68767721f7465d4823.jar` | `B507F52C9FF10826064443CCD98356E05245AD0D0FD9806BAF60D1F9B716E023` |

All copied hashes match the rebuilt output; launcher hash alone does not identify production source.
Blank-profile smoke **PASS**: exact EXE/child + `ChatChatBar` / `SunAwtFrame`, alive then normal WM_CLOSE,
launcher/application exit **0/0**, stdout/stderr **0/0 bytes**. Independent empty APPDATA/LOCALAPPDATA,
no normal profile/credential contents read. Scripts/hash/result records live under
`app/desktopApp/build/phase7-design-parity-{launch-smoke.ps1,launch-result.json,package-hashes.json}`.

Manual acceptance remains user-owned; use this new artifact, [66](66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md),
and these local/fake-only follow-up checks:

- [ ] Design Settings extra requirement survives close/reopen without changing other image preference owners; use the fake fixture to inspect new conversation/reverse requests.
- [ ] Conversation A → New → unsent input + attachment → Settings/History → back retains both; Return Studio or OS close → reopen restores A and clears the transient new composer.
- [ ] Read an earlier turn with a nonzero scroll offset, visit Settings/History and return: position survives. Selecting A/B from Design History opens at newest; scrolling each does not overwrite the other's runtime position.

NovelAI remains **1/8**. New real generation / Enhance / Upscale / AI Design requests **0**.
Prompt literals/algorithms, Package/Entity schema, SecretStore, provider capability and other image
runtime work unchanged. Baseline blob `ea8d53aac709179c33b78bb6b28c2fd58c1c904b`, desktop/origin
`b3ecd41267906526e7b603972f7388e59c90648d`, parked sync `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`
unchanged. No merge, baseline promotion, Program Control rewrite or Phase 8.

## R1 evidence — historical artifact, Project review PASS

Project's HOLD at `3300ba7d105f29d7b35679da6602624438b2fc8b` required two blockers and two narrow
presentation corrections. All four are addressed on the same feature branch; Phase 7 remains
**NOT ACCEPTED / NOT MERGED**. The prior record below is historical, including its package and source SHA.

- **Final R1 production source: `3ff3d3e6d3648277b583fabef84ef80816e6c40d`** —
  `fix(desktop): repair image closure R1 ownership and attachments` (pushed).
- Final docs HEAD is the docs-only commit containing this R1 record and updates to `16` / `21`:
  `git log -1 --format=%H -- docs/desktop/68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md`.
- Production scope: six Desktop files only. Relevant image-runtime skill and tests updated in the
  production checkpoint. No production changes during/after final full regression or packaging.

### R1 behavior and focused proof

| Review item | Repair / evidence |
|---|---|
| R1-1 transient input | `DesktopDesignComposerState` is controller-owned session memory. `DesktopDesignConversation` typing/new-conversation only changes that state, never Studio draft. Settings/history remounts preserve unsent input. Tests compare both serialized draft bytes and the entire draft including `contentRevision` / `promptContentRevision`; a new unsent conversation leaves the durable pointer unchanged. |
| R1-1 legacy migration | Current upstream `550409689df8c51f459fb50b4e04c8ac2fa4bf35` `NovelAiDesignViewModel` is the input-ownership reference. `imageDescription` seeds once only when no current durable conversation exists. Send passes explicit transient text to the existing shared create/append path. Only after durable turn + current-pointer publication may the matching legacy field clear; normal repository revision/timestamp bookkeeping remains. Preflight and failed pointer persistence retain original bytes/input; a later fake provider failure retains the durable failed turn and completed migration. Existing conversations never resurrect legacy input. |
| R1-1 retained boundaries | Branch edits remain turn-owned; retry/regenerate keep the shared runner/repository. Active-character-only attachment is asserted. Missing credential/blank input tests assert zero tasks and zero provider calls. Fake Designer only; no real AI Design requests. |
| R1-2 GIF | Both pickers include GIF; import recognizes GIF87a/GIF89a, keeps encoded bytes and `.gif` references, and exact-candidate cleanup recognizes owned GIF names. Picker and actual file-list drop → pending → controller Send → local fake chat HTTP → durable message → reopened repository preserve byte-identical two-frame animation. Request payload continues JPEG adaptation. Edit-picker/add/delete and before-write/after-write failure cleanup are tested; external/unrelated originals survive. PNG/JPEG/WebP/ordinary APNG byte retention and canonical disguise behavior also pass. |
| R1-3 explanation | UI now says `填充画风与基础负面词；角色 Prompt 仅供 AI 设计参考，不参与实际生图`. Import/runtime behavior unchanged. |
| R1-4 History | History leaves the unbounded scrolling wrapper; weighted adaptive grid fills remaining tool height above the existing footer. Compose scenes at 800/1100px verify exactly 300px extra grid height. Deselect removes both selected state and range-start badge. Existing inclusive three-image Shift selection and refresh reset pass against the same shared policy. Filter/level reset code remains unchanged. |

**FINAL-SOURCE DESKTOP FULL REGRESSION PASS** on `3ff3d3e6d3648277b583fabef84ef80816e6c40d`:

| Gate | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| Focused R1 + affected Design / image / Compose | 7 | 58 | 0 | 0 | 0 |
| Desktop full `--rerun` | 116 | 1049 | 0 | 0 | 0 |

Focused gate **1m 8s**, full gate **2m 22s**, exit 0. Desktop compile executed in focused and was
UP-TO-DATE in full gate. Working-tree, staged and full `desktop...HEAD` diff-check PASS.
Shared/Android production diff from reviewed `3300ba7` is empty; conditional affected suites are N/A,
not claimed rerun. Prior shared/Android validation remains historical evidence.

First focused attempt: 49 tests / one failure in the new History height test. The assertion incorrectly
expected the grid to reach the window bottom, omitting the existing footer. Corrected the test to
assert footer alignment, grid/footer spacing and the 300px grid-height increase; no runtime behavior
was removed. Failure XML/log retained; final focused/full gates include the correction.

Local evidence: `app/desktopApp/build/phase7-current-upstream-r1-evidence/` contains
`focused-first-failed/`, `focused-final/`, `final-full/` XML/log/counts. Compose images:
`app/desktopApp/build/phase7-current-upstream-evidence/r1-history-800.png` and `r1-history-1100.png`.

Run from `app/` with JDK17 and the same verified bounded-memory options:

```powershell
.\gradlew.bat :desktopApp:test --rerun :desktopApp:compileKotlin -I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

Focused filters: `*DesktopImageClosureR1*`, `*DesktopDesignAuthenticationTest`,
`*DesktopCurrentImagePresentationTest`, `*DesktopCurrentImageIntegrationTest`,
`*DesktopFinalProductTest`, `*DesktopPhase7ImagesTest`.

### R1 package and launch

`:desktopApp:createDistributable --rerun` with the same options **PASS, 28s**, exit 0.
New isolated artifact (old packages preserved):

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-current-upstream-r1-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

| File relative to R1 package | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-13d075542af8e7853a12c2b37a296.jar` | `8F5CE794E2E72369596457F3D849B06B4784E1F84E020B82B0829EF300123B97` |
| `app/sharedCore-febeeab028b94c68767721f7465d4823.jar` | `B507F52C9FF10826064443CCD98356E05245AD0D0FD9806BAF60D1F9B716E023` |

All three copy hashes match the new build. Launcher hash alone is not production-source identity.
Blank-profile smoke **PASS**: exact new EXE/child and `ChatChatBar` / `SunAwtFrame` window matched,
alive before normal WM_CLOSE; launcher/application exit **0/0**, stdout/stderr **0/0 bytes**.
Independent empty APPDATA/LOCALAPPDATA; normal profile/credential contents not read.
Script/result: `app/desktopApp/build/phase7-current-upstream-r1-launch-smoke.ps1` /
`phase7-current-upstream-r1-launch-result.json`; package hashes in `phase7-current-upstream-r1-package-hashes.json`.

Manual review remains pending. Use this new package with [66](66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md)
and these R1 checks; do not send real image or AI Design requests:

- [ ] Type unsent AI Design text, visit Design Settings/History and return: input survives; Studio Prompt stays unchanged. New Conversation opens blank; closing it creates no durable conversation.
- [ ] Use the existing test-source fake Design fixture for legacy-first-send, credential failure and retry/branch checks; no external provider call.
- [ ] Pick/drop an animated GIF into chat; preview and edit-image picker accept it. Automated local fake-chat tests cover Send/reopen and byte preservation; do not use a real provider merely for acceptance.
- [ ] Resize/maximize History; grid grows above its footer. Select, deselect, Shift-range, change filter/level: no stale range badge.
- [ ] Confirm character import explanation includes both style and base negative; runtime import stays unchanged.

**Safety:** NovelAI generation remains **1/8**; added real generation / Enhance / Upscale / AI Design
requests **0**. Prompt/Package/core Entity/SecretStore/provider capability/automatic eligibility/Guidance
unchanged in R1. `10_UPSTREAM_BASELINE.json` blob remains `ea8d53aac709179c33b78bb6b28c2fd58c1c904b`;
formal baseline remains 1.4.1. `desktop` / `origin/desktop` remain `b3ecd41267906526e7b603972f7388e59c90648d`;
parked sync remains `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`. No merge or Phase 8.

## Pre-R1 record — historical, Project HOLD superseded by R1 evidence above

Phase 7 is **NOT ACCEPTED / NOT MERGED**. Same `feature/phase7-image-novelai`, start
`d9696f0a4e54fb5820118cbff8ecba8f33b3b27b`. This handoff supersedes the packages/evidence in `59`,
`53`, `44` and `38`; those remain historical records. User manual checklist: [66](66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md), pending.

## Source and durable commits

- `6329c5b` — selective shared image policies, Android/Desktop raster adapters, core focused tests and adopted 60–67/REF pack.
- `1cc800b3cf50ec40c965fc89745db4ecabb619be` — Desktop product adapters, integration/UI tests, relevant skills; last behavior change.
- `5e63a673791f1854a60e4f5659f26d6bfd53e701` — test-only correction for the two explicit result-reuse actions.
- **Final production source: `56ac3063f4817ce5b0eb4dfea3f5a6d98bfd14a1`** — removes a trailing blank line; no behavior change from `1cc800b`.
- Final docs HEAD is the docs-only commit containing this completed record (`git log -1 --format=%H -- docs/desktop/68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md`). No production edits after the final full gate.

Formal baseline remains **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**;
`10_UPSTREAM_BASELINE.json` is unchanged (Git blob `ea8d53aac709179c33b78bb6b28c2fd58c1c904b`).
`desktop` / `origin/desktop` remain `b3ecd41267906526e7b603972f7388e59c90648d`;
parked `sync/1.4.4` remains `9b6378dbb595dd2f3ff5143a7a8e46653c99e721`.
No merge, full 1.4.4 sync, baseline promotion or Phase 8 work.

## Official adoption / shared authority

The complete 18-commit audit and full upstream SHAs are recorded individually in [60](60_PHASE7_UPSTREAM_IMAGE_DELTA_AUDIT.md)
and the CURRENT exception table in [14](14_UPSTREAM_COMPAT.md). Adopted:

| Official change | Authority now used |
|---|---|
| `354f151` positions | Shared `NovelAiCharacterPositionPolicy`, Studio settings/recipe and `NovelAiImageService`; Desktop canvas/numeric adapter. No identity-reminder Prompt diff. |
| `1a4e5a` → `1874eb3` → `b1bb01f` clear/card negative | Shared Studio import/clipboard final current behavior, Android and Desktop consumers. Intermediate clear behavior is superseded. |
| `148b3a9` Enhance/Upscale | Shared `NovelAiImagePostProcessing`, request-only Enhance options, `NovelAiUpscaleService`, `ProcessedImageModels`; Desktop raster/SecretStore/TaskRuntime adapter. |
| `b6d6832` privacy | Shared `PrivacyPngEncoder` clears carrier bits and emits fresh PNG. Android `ImagePrivacyExporter`, Desktop `DesktopImageMetadata`; container-only stripping has a distinct name. |
| `01b6b72` enabled | Shared persisted enabled/default/activeCharacters authority, Android consumers and Desktop independent visual collapse. |
| `c69afe2` role import | Shared OFF/REPLACE/APPEND merge policy and Desktop metadata selection. |
| `cb9c0f7` alpha metadata | Shared bounded `StealthAlphaMetadata` and ordinary-first metadata reader; Android Bitmap and Desktop ImageIO/Skia original-raster adapters. |
| `559f83f` image History part | Shared visible-album inclusive range selection, Desktop adaptive grid / one-shot Shift anchor. Unrelated chat changes excluded. |
| `ace632c` safety | Already adopted, unchanged. No Prompt literal changes in this closure. |

JVM-neutral policy is not duplicated on Desktop. Android Chat/Moments existing metadata call sites now use
`AndroidNovelAiPngMetadataReader`; this is an extraction adapter change, not adoption of Moments features.
Old Studio payloads default enabled=true, center=null, useCharacterPositions=false. Core Entity/Package schema,
Prompt Designer semantics, general Studio model/sampler capability, automatic-image eligibility and SecretStore ownership are unchanged.

## Upstream workflow → Desktop mapping

| Official screen/workflow | Desktop equivalent and evidence |
|---|---|
| `ImagePromptToolScreen` / ViewModel | One Studio with peer Prompt cards, Base Negative after Extra, full-height resizable right preview, rail/focus modes and compact top preview at narrow width. Imported-image metadata/tools/guidance/postprocessing share one selected-image surface. |
| `NovelAiDesignScreen` / shared conversation & turn runner | `DesktopDesignConversation`: read-only user turns, explicit edit/branch, structured assistant modules with Copy/Apply/Regenerate, collapsible content/reasoning progress, bottom IME-safe composer. Header exposes exact model/provider/auth status, history/new/settings and return navigation. |
| Official role position / enabled controls | `DesktopStudioCharacters`: muted retained disabled cards, independent three-line collapse, move/delete/enable actions on right, active-only position canvas and numeric fallback. Focused inpaint uses the request crop aspect. |
| `NovelAiHistoryScreen` / folding/filter/range policy | `DesktopStudioHistory`: adaptive grid, album count, selection badge, explicit range-start badge, Shift range, reset on filter/history/level change, existing repository and recipe detail/actions. |
| `NovelAiPostProcessScreen` / `ImageComparison` | `DesktopImagePostProcessPanel`: Enhance/Upscale tabs, official sizes/cost/options, Stop, retained per-tab result, draggable before/after, Save/Copy/use-as-current. No draft/history publication merely from processing. |
| `ImagePreviewDialog` / `ImageMosaicEditor` | Common `DesktopImageToolWindow` + `DesktopImageZoomSurface` for owned/transient images: resize/maximize/restore, wheel cursor zoom, zoomed drag pan, double-click fit/native equivalent, reset/Esc, animation retained. Tools expose visual edits separately from privacy PNG and APNG. |
| Chat image attachment / message image actions | Ordered multi-picker, local image clipboard/drop, 112dp scrolling/reorder strip, hover removal, canonical-disguise restored copy/badge; action cluster bottom-right. Assistant generate/requirements are compact accessible icons. |

Exact-model preflight uses the real model repository and hydrated SecretStore authority before TaskRuntime launch.
An explicitly missing selected model never falls back to a different model. Header state contains no credential;
missing authentication disables Send and offers the exact model settings route. Runtime rechecks at launch.
New UI models returned to presentation have their API key stripped. No new model-facing Prompt is introduced.

Tag caret inspection reuses the shared parser and existing catalog/dictionary service: caret-only movement inspects
whole tag, exact result first, category/count or honest dictionary-only label; explicit selection replaces the full
syntactic tag while preserving weight/markers. Typing retains completion behavior. IME composition blocks acceptance,
raw Prompt text remains authoritative and no second catalog/database exists.

Initial/new generation selection is newest. Unrelated History updates or removal of other results do not override an
explicit older selection. V5 battery reflects authoritative percent/exhaustion and shared approximate count; unknown is
not zero and no refill speed is invented. Advanced/Diagnostics is on the same tools row at the far right.
Session Settings tabs/draft/Save/Cancel/immediate-background semantics are retained, not redesigned.

Window-local Studio ingress opens Current Image; chat ingress appends to captured-session pending attachments.
Multi-file order/reorder affects pending order only. Canonical APNG restoration uses the shared codec and creates new
bytes; external source remains unchanged. Ordinary/third-party APNG is accepted without guessed restoration. Successful
replacement/removal clears stale attachment error and the active composer shows it once.

Desktop-private background-library/preferred-character background ownership remains unchanged. New durable resource →
durable authority → exact-candidate cleanup remains the rule; indeterminate authority retains files. Window-local imported
copies/postprocess results are transient until explicitly saved/used, not new Entity/Package fields. Animated inputs are
never silently flattened by privacy or postprocessing. Desktop bounded decoding remains a platform limit.

## Validation

**FINAL-SOURCE FULL REGRESSION PASS** on production `56ac3063f4817ce5b0eb4dfea3f5a6d98bfd14a1`.

| Gate | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| sharedCore full `--rerun` | 111 | 722 | 0 | 0 | 0 |
| Desktop full `--rerun` | 114 | 1036 | 0 | 0 | 0 |
| Android debug JVM full `--rerun` | 135 | 805 | 0 | 0 | 0 |

`:sharedCore:compileKotlin`, `:desktopApp:compileKotlin`, `:app:compileDebugKotlin` PASS.
Final gate BUILD SUCCESSFUL **3m 10s**, exit 0. Working-tree and complete `desktop...HEAD` diff-check PASS.
Full log/XML/counts: `app/desktopApp/build/phase7-current-upstream-evidence/final-full/`.

Focused evidence: core shared **7 suites / 35 tests**, Desktop **2 / 23**, Android **1 / 30**;
product Desktop **8 / 81**, shared caret **1 / 2**; final product checkpoint Desktop **3 / 18**.
All zero failures/errors/skips. Gates overlap and are not summed. Final full includes every new test.

Coverage includes final serialized positive/negative active-role arrays and coordinate flags, old payload defaults,
draft/history/enable/center round trips, import empty/missing/append behavior, clear/card negatives, postprocess cost/geometry,
fake Upscale route/body/response/error and Enhance request shape, transport cancellation and one-attempt 429 handling,
ordinary metadata precedence/alpha fallback/bounds, PNG and WebP original alpha, privacy unreadability/source retention,
caret syntax/IME, real repository credential hydration, actual Compose role/design/result/grid/window/composer scenes,
pending persistence order and canonical vs ordinary APNG. Existing full suites cover shared Prompt/envelope/order,
Studio/history/guidance/automatic-image and data-ownership regressions.

First full attempt: Desktop **1036 tests / 1 failure**. The failed source guard required an old `listOf(...)` UI loop,
while the two final actions now explicitly call the same shared reuse path. The existing runtime missing-source matrix
passed. Test-only correction keeps checks for both actual action routes. No failed test was ignored. Failed XML/log remains
under `phase7-current-upstream-evidence/full-first-failed/`. A subsequent all-green gate passed in 3m 55s; then full-feature
diff-check found one trailing blank line. After removing it, the complete three-module gate and package were rerun on
`56ac306`; that final run is the table above. No production changes afterwards.

Reproducible environment: JDK `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`, run from `app/`:

```powershell
.\gradlew.bat :sharedCore:test --rerun :desktopApp:test --rerun :app:testDebugUnitTest --rerun :sharedCore:compileKotlin :desktopApp:compileKotlin :app:compileDebugKotlin -I desktopApp/build/phase7-r2-r1-ssd.init.gradle --offline --console=plain --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

Init script only redirects Desktop generated build output to `C:\Users\1\AppData\Local\Temp\ccb-p7-r2-r1-build`.
Package uses the same options with `:desktopApp:createDistributable --rerun`.

## Package / launch smoke

New isolated acceptance executable:

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-current-upstream-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

Build **PASS, 26s**, exit 0. Copy hashes match the fresh build:

| File relative to package | SHA-256 |
|---|---|
| `ChatChatBarDesktop.exe` | `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F` |
| `app/desktopApp-a6a762f834aa5ffa44785a93bb0ff36.jar` | `91CFB95D8996517F1BDDAAB251030B0E7CF81FD1F9733E8FED3CCACF8919BF71` |
| `app/sharedCore-febeeab028b94c68767721f7465d4823.jar` | `B507F52C9FF10826064443CCD98356E05245AD0D0FD9806BAF60D1F9B716E023` |

Launcher hash alone is not source identity; use both production JAR hashes. Old acceptance packages are preserved.
Blank-profile launch smoke **PASS**: exact new EXE/child and `ChatChatBar` / `SunAwtFrame` window matched, alive before
normal WM_CLOSE, launcher/application exit **0/0**, stdout/stderr **0/0 bytes**. Independent empty APPDATA/LOCALAPPDATA;
normal user profile and credential contents were not read. Script/result:
`app/desktopApp/build/phase7-current-upstream-launch-smoke.ps1` / `phase7-current-upstream-launch-result.json`.

Durable compact evidence: [gate-summary.json](refs/phase7-current-upstream/validation/gate-summary.json).
Local fixture screenshots are indexed in [67](67_SCREENSHOT_INDEX.md); they are rendered validation evidence, not user
manual acceptance. Real Windows Chinese IME and manual interaction checklist [66](66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md)
remain user-owned. Existing `runPhase7FinalProductFixture` is test-classpath-only, with isolated storage/in-memory secrets
and fake design transport; it can support local manual design review without an external model call.

## Safety / deferred ownership

**NovelAI real generation remains 1/8. Added real generation, Enhance, Upscale and AI Design requests: 0.**
Fake/local fixture tests only; no live fuse consumed. SecretStore-only credential ownership is preserved.
`64` updates the prior `55` map for P7 window-local image conveniences. General provider models/samplers/refill inference,
full preset schema/import/export, global IA, Moments, Community, shell associations and installers remain with their
recorded owners. No complete 1.4.4 compatibility claim. Relevant skills were updated with implementation; the final
test/whitespace/docs changes introduce no further stale skill facts.
