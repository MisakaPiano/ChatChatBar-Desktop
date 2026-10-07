# Phase 7 UX Presentation Closure evidence

Date: 2026-10-07. Same branch: `feature/phase7-image-novelai`.
Start: `dd3e68fa3c78204dd075391e4a779123429581c1`; previous R2/R1 production: `b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`.

Status: **IN PROGRESS — presentation implemented; consolidated validation/package pending**. R2/R1 semantic implementation and Project review passed, but second user manual acceptance was UX FAIL/HOLD. This task follows `48`/`50`; it does not mark Phase 7 accepted or merged. `44` is prior R2/R1 evidence, not this presentation artifact.

## Implementation boundary

- `DesktopStudioControls` supplies compact ghost actions/chips, switches, anchored keyboard menus, numeric sliders with precise readouts, disclosure, and a 420×300 custom-size editor. Only large (more than 12) card/model choices use the 520×560 searchable window.
- Generation controls use existing `NovelAiGenerationSettings`, `withActiveSettings`, `imageSize`, and shared validation. Wallpaper excludes Square as formal UI does. Sampler/model enum/API ids/capability, HTTP/Prompt runtime, Entity/Package and SecretStore are untouched. `49` is explicitly excluded implementation work.
- One Studio workspace retains Prompt, generation settings, result pane and reachable footer. Narrow layout stacks Prompt before results. AI Design, History, settings, Guidance and Current Image stay auxiliary. Settings windows are sized to task; result actions and top toolbar are compact.
- `StudioPromptTextEditor` is shared by inline and fullscreen sessions. Shared translation offsets are mapped to actual glyph line spans; wrapped Chinese labels paint beneath the corresponding English text. Exact source matching and query-keyed async state reject stale results. The raw `TextFieldValue` is unchanged by annotations; copy/export/request use the same original Prompt. Fullscreen keeps its existing confirm/cancel draft boundary and preserves opening selection on cancel.
- Tag popup retains shared lookup/ranking, shows English/Chinese, flips/clamps within the window, and supports Up/Down, safe Enter/Tab, and Esc. IME composition and selected text prevent completion shortcuts. Lookup warnings stay visible without discarding text.
- Composer image controls are one compact row below the text editor; pending thumbnails have no empty row. Attachment, automatic-image and background callbacks remain under their existing controllers.

## Formal source mapping

Authority: CCB 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

| Formal owner | Desktop presentation |
|---|---|
| `ImagePromptToolScreen.kt` / `GenerationSettingsSection` / `AdvancedSettings` | `StudioGenerationSettings`, chips, size editor, collapsed summary, sliders, sampler popup, Random switch |
| `ImagePromptToolScreen.kt` Prompt sections / fullscreen / translation overlay; `NovelAiPromptRendering.kt` | One common raw text editor, glyph-aware Chinese annotation painting, compact expand action, optional sections |
| `NovelAiTagEditor.kt` and shared completion/translation services | Field-anchored translated suggestions and keyboard handling; no ranking/Prompt change |
| `NovelAiDesignScreen.kt`, `NovelAiHistoryScreen.kt`, image guidance/tools | Same reviewed auxiliary owners; compact actions and task-sized windows |
| `ChatScreen.kt` composer image entry | `DesktopChatImageToolbar` and conditional pending strip; existing automatic/background ownership |

## Focused evidence

**PASS: 5 suites / 44 tests / 0 failures / 0 errors / 0 skips**. Includes 14 new presentation tests, R2 wide/narrow scene, R1 ownership safeguards, full composer and keyboard/focus scenes. Desktop compile passed. Tests use local fixtures/fake transport only; no real generation.

Checks cover chip keyboard selection and serialized draft equality; enum menu arrows/Enter/Esc including nullable Follow; advanced collapse; exact slider values; custom-size apply/reset/cancel/invalid Apply; fixed-Seed conditional editor; wrapped annotation positions and stale rejection; inline/fullscreen shared editor/request equality; live local suggestion acceptance; popup placement; compact chat callbacks. Wide/narrow Studio images and focused widget renders are in `app/desktopApp/build/phase7-ux-evidence/` (Studio renders copied there at the gate).

Final-source full suite, isolated distributable, launch smoke and CURRENT reconciliation follow this durable checkpoint. Shared/Android source diff is empty; conditional affected tests are N/A. NovelAI real count stays **1/8**, added **0**. Parked sync untouched. Manual checklist: `51_PHASE7_UX_PRESENTATION_MANUAL_ACCEPTANCE_CN.md`; user checkboxes remain pending.
