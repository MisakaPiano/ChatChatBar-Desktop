# 48 — Phase 7 UX Presentation Closure

## 0. 状态

Current feature branch:
`feature/phase7-image-novelai`

Reviewed R2/R1 docs HEAD before this closure:
`dd3e68fa3c78204dd075391e4a779123429581c1`

Reviewed R2/R1 production source:
`b6f39a4c14e0c000e889ecd2c9b5d79aef85f1c9`

Phase 7 semantic implementation and Project code review remain PASS.

The second user manual review is **UX FAIL / HOLD** because the Studio and chat image controls are technically present but organized as an engineering form rather than a mature image-generation workspace.

This closure is presentation-only unless a concrete UI bug is found.

---

# 1. Authority order

For this task:

1. Formal CCB 1.4.1 source — feature grouping, state ownership, user workflow, Prompt/runtime semantics.
2. Current Desktop feature source — already reviewed runtime implementation.
3. This presentation contract.
4. NovelAI official Web UI / docs — provider-specific control interaction reference only.
5. Attached screenshots.

Do not use NovelAI Web as authority for CCB Package/Entity/Prompt behavior.

---

# 2. Core presentation rule

The Desktop Studio must stop looking like a generic settings form.

Use the correct control type for the operation:

| Interaction | Required Desktop control |
|---|---|
| Small finite choice, high-frequency | segmented buttons / chips |
| Continuous numeric tuning | slider + compact numeric readout/input |
| Ordinary enum choice | native/Compose dropdown or popup menu |
| Rare complex choice / searchable huge list | dialog |
| Boolean | switch/toggle |
| Optional advanced parameters | collapsible section |
| Custom resolution | small dedicated dialog / popover |
| Prompt editing | text editor with inline tag assistance/annotation |
| Auxiliary workflow | drawer/dialog/window |
| Main workflow | remain in Studio workspace |

A normal 6-item enum such as Sampler must **not** open an 880×780 search dialog.

---

# 3. Studio main layout

Keep the R2 one-workspace structure.

Wide Desktop:

```text
┌──────────────────────────────────────────────────────────────┐
│ model / Anlas / AI设计 / 图像引导 / 导入图 / 历史 / 设置     │
├───────────────────────────────┬──────────────────────────────┤
│ Prompt workspace              │ Current Result / Image       │
│                               │                              │
│ 角色卡来源                    │ preview                      │
│ 画风 / 基础 / 补充            │ seed / reuse / image actions │
│ Character prompts             │                              │
│ negative prompts              │                              │
│                               │                              │
│ Generation Settings card      │                              │
├───────────────────────────────┴──────────────────────────────┤
│ Tokens / Cost / Undo / Redo / Copy Positive       Generate  │
└──────────────────────────────────────────────────────────────┘
```

Narrow windows may stack, but the Generate footer remains reachable.

Do not reintroduce peer pages for Prompt / Parameters / Result.

---

# 4. Generation Settings — exact presentation closure

Formal CCB 1.4.1 already provides the preferred interaction structure. Desktop should follow it.

## 4.1 Model

Use an ordinary dropdown/popup select.

Options:
- Follow Character/global
- V4.5 Full
- V5 Full

Do not open a full-screen searchable dialog for this small list.

## 4.2 Size tier

Use chips/buttons:

`Small | Normal | Large | Wallpaper`

Selected state must be obvious.

## 4.3 Aspect ratio

Use chips/buttons:

`Portrait | Square | Landscape`

Show effective pixel dimensions beside the label.

When Wallpaper makes Square invalid, do not present it as a normal selectable state.

## 4.4 Custom size

Do not permanently occupy the form with two full-width empty text boxes.

Put an edit icon/button next to Resolution.

Click opens a compact dialog:
- Width
- Height
- validation
- Apply / Reset to preset / Cancel

## 4.5 Image count

Use direct buttons:

`1 | 2 | 3 | 4`

No full-width numeric text field.

## 4.6 Advanced generation settings

Default collapsed.

Collapsed summary example:

`28 Steps · CFG 6.0 / 0.00 · Euler Ancestral · Random Seed`

Expanded:

- Steps: slider 1–50 + compact numeric value
- CFG Scale: slider 1–10 + compact numeric value
- CFG Rescale: slider 0–1 + compact numeric value
- Sampler: ordinary dropdown
- Random Seed: switch
- Fixed Seed: compact input only when Random is off

Do not use full-width text boxes for Steps/CFG/Rescale.

## 4.7 Continuous generation

Keep discoverable but secondary.

It belongs below/inside Generation Settings, not as a large independent block.

---

# 5. Dropdown / popover policy

Current generic `StudioChoice` opens a full DialogWindow for nearly every enum.

Replace it with at least two presentation components:

### CompactChoice
For small/medium option sets:
- popup/dropdown anchored to control
- keyboard navigation
- Esc closes
- no huge modal
- no search unless list is actually large

### SearchableChoice
Only for genuinely large sets:
- CharacterCard list
- model/provider list if it becomes large
- catalog-like lists

Do not use one generic searchable modal for everything.

---

# 6. Prompt editor and Chinese annotation — Phase 7 blocker

Formal CCB 1.4.1 already owns Prompt Chinese translation/annotation.

This is **not a future phase**.

Current Desktop backend already calls shared:
- `NovelAiPromptTranslationParser`
- `NovelAiPromptTranslationService`
- tag suggestion translation

But the presentation is reduced to a separate line like:

`tag：中文 · tag：中文 · ...`

That is not an adequate Desktop equivalent of CCB's inline annotation overlay.

## Required behavior

Prompt header toolbar:
- Paste overwrite
- Clear except style
- Translate toggle

When translation is enabled:

- annotation must appear visually aligned with the corresponding tags/segments;
- original Prompt text must not change;
- annotation must not become part of clipboard/export/request;
- tag suggestions show English tag + translated label;
- fullscreen editor shows the same annotation behavior;
- editing invalidates/recomputes annotations without stale overlay;
- warnings are visible but do not destroy editor state.

Desktop may implement:
- overlay beneath token spans;
- two-line tag/token presentation;
- another precise caret/layout-aware annotation method.

A single unaligned StatusText summary is insufficient.

If the user enables translation and sees no annotation, treat it as a functional bug.

---

# 7. Prompt density and collapse

Follow CCB's information hierarchy.

Default:
- Style prompt may be collapsed/compact.
- Base prompt prominent.
- Extra prompt compact/collapsible when blank.
- Character negative prompts collapsed by default.
- Base negative prompt collapsible.
- "Expand editor" remains available but should be an icon/action, not a large button after every field.

Avoid repeating large `展开编辑` buttons under each field.

Prefer a small expand icon in each field header.

---

# 8. Tag suggestion presentation

Current Desktop can query the local catalog.

Improve presentation:
- suggestions anchored beneath/near the active editor;
- do not permanently increase the entire page height excessively;
- show tag + translated name;
- keyboard navigation;
- Enter/Tab to accept where safe;
- Esc to dismiss.

Keep formal tag research/ranking authority unchanged.

---

# 9. Result pane

Keep the current wide-screen result pane.

Refine action hierarchy:

Primary:
- Previous / Next
- Seed
- New-seed redraw
- Seed-only reuse

Secondary:
- Image actions / Use as
- Metadata / load regeneration
- Guidance

Use compact icon/text actions instead of multiple large rectangular buttons when possible.

No runtime changes.

---

# 10. Studio toolbar

Current top bar is functionally correct but visually button-heavy.

Target:
- compact toolbar
- icons + short labels
- current model / Anlas status visually separate from action buttons
- AI Design / Guidance / Import / History / Settings grouped consistently
- disabled Image Tools should not consume equal visual weight to primary active actions

Do not hide official CCB top-level workflow ownership.

---

# 11. Chat composer presentation

Current R2 chat screenshot still uses too much vertical space:

- attachment row
- automatic-image row
- background button row
- composer

Refine without changing behavior.

Target:
- image attachment icon integrated with composer
- pending thumbnails only appear when attachments exist
- compact image-generation/menu button
- background action in same compact toolbar or overflow
- text field gets most horizontal/vertical space
- no two large permanent button rows above an empty composer

Automatic image status remains discoverable but does not dominate the composer.

---

# 12. Auxiliary surfaces

AI Design / History / Image Tools / Guidance remain auxiliary workflows.

Presentation rules:
- dialogs/windows sized to content and task;
- do not default every auxiliary surface to the same 880×780 shell;
- complex History may be large;
- simple settings/selects should be compact;
- keep clear Back/Close semantics.

---

# 13. NovelAI provider capability drift boundary

Do **not** change in this task:

- `NovelAiSampler` enum
- sampler API ids
- sampler-by-model filtering
- model list
- request serialization
- generation validation
- Prompt literals
- Package/Entity schema

Read `49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md`.

Provider capability drift is a separate audit.

The UI may improve how the existing CCB sampler list is selected, but it must not claim that list is current NovelAI provider truth.

---

# 14. No real generation

NovelAI live count remains 1/8.

This task authorizes:

**0 additional real NovelAI image requests.**

Use:
- existing images
- fixtures
- fake account
- fake HTTP
- screenshots
- local history

---

# 15. Validation

Focused UI/presentation tests should cover:

- Size tier chips
- Ratio chips + Wallpaper/Square exclusion
- count 1–4 buttons
- advanced collapse summary
- sliders update exact draft values
- compact Sampler select
- Random Seed conditional field
- custom size dialog
- small vs searchable choice behavior
- translation toggle + actual annotation rendering model
- fullscreen translation presentation
- tag suggestion popup behavior
- narrow/wide Studio layout
- compact chat composer layout
- no semantic draft/request changes from presentation operations

Then:
- Desktop full suite
- Desktop compile
- shared/Android compile only if shared source changes (prefer none)
- diff-check
- isolated distributable
- launch smoke

No need to rerun unrelated shared/Android tests if no shared production source changes.

---

# 16. Completion

Report only:

`READY FOR PROJECT PHASE-7 UX PRESENTATION REVIEW`

Do not mark Phase 7 ACCEPTED.

User manual acceptance follows Project review.
