# 41 — Phase 7 R2 Screenshot Index

All screenshots are user-provided **CCB Android UI reference evidence**.

They do not override upstream source semantics.

| Ref | File | What it demonstrates | Desktop use |
|---|---|---|---|
| 01 | `refs/01_ccb_studio_prompt_workspace.png` | Main Studio is a single Prompt workspace: imported character source, style/base/extra Prompt, character Prompt sections, negative Prompt structure | Preserve this functional grouping in one Desktop workspace |
| 02 | `refs/02_ccb_studio_generation_settings.png` | Generation settings continue below the Prompt workspace; model, size tier, ratio, count, Steps, CFG/Rescale, sampler, seed; generation footer is persistent | Do not make parameters a peer Studio "page"; use an inspector/section and fixed generation footer |
| 03 | `refs/03_ccb_ai_design_conversation.jpg` | AI Design is conversational and can apply a designed Prompt to Studio; history/new/settings controls live in its top bar | Dedicated AI Design panel/window |
| 04 | `refs/04_ccb_ai_design_new_conversation.jpg` | New AI Design conversation empty state with clear model identity and composer | Preserve clear empty state and model display |
| 05 | `refs/05_ccb_ai_design_attach_studio_prompt.jpg` | Current Studio Prompt attachment is explicit and summarized as base + character count; user can remove it | Required visible attachment summary |
| 06 | `refs/06_ccb_ai_design_settings.jpg` | AI Design settings: design model, V5 natural-language mode, extra requirement | Keep as AI Design settings, not scattered global controls |
| 07 | `refs/07_ccb_imported_image_tools.jpg` | Imported image becomes a current-image workspace with metadata/mosaic/reverse-Prompt/enhance style actions | Desktop Current Image / Image Tools surface |
| 08 | `refs/08_ccb_mosaic_editor.jpg` | Mosaic is a direct visual paint/edit operation with brush control, undo/reset and apply/cancel | Use visual editor semantics; add solid-color cover-up in Desktop enhancement |
| 09 | `refs/09_ccb_reverse_prompt_progress.jpg` | Reverse Prompt has visible multi-stage progress and Stop behavior inside the image tool context | Keep progress/cancellation discoverable and task-owned |

## Reading rule

Do not copy:
- mobile navigation bar spacing;
- exact phone card widths;
- Android-specific back gestures;
- Android-only icons or system chrome.

Do preserve:
- which function is primary vs auxiliary;
- where actions are entered from;
- which state is visible at the time of an action;
- Apply/Cancel/Stop boundaries;
- relationship between current Prompt, current image, AI design, history and generation settings.
