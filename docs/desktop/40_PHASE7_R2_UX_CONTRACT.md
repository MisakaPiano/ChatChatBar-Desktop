# 40 — CCB Desktop Phase 7 R2 UX Contract

## Status / reason

Phase 7:
- automated validation: PASS;
- Project implementation review: PASS;
- final-source regression: PASS;
- first consolidated user manual acceptance: **FAIL**.

R2 exists to repair the Desktop user surface without reopening validated runtime semantics.

Current feature branch before R2:
`feature/phase7-image-novelai @ b30cfc116249abfbd08ec2217f23b06b9293682a`

Final validated pre-R2 production source:
`f8ca2619e67a975e392a7b4563b4ace834dca89a`

Do not merge `desktop` until R2 manual acceptance passes.

---

# A. Governing design rule — CCB structure first

The Desktop application is a downstream port of CCB.

For P7-R2 use this fidelity ladder:

## A1 — Runtime / domain / Prompt / Package: preserve authority

Do not rewrite:
- NovelAI Prompt literals/builders;
- Prompt Designer semantics;
- tag/codex research;
- NovelAI HTTP generation semantics;
- Studio recipe/history semantics;
- automatic-chat-image eligibility;
- Package or Entity schema;
- shared image policy;
- SecretStore ownership.

If a UI rewrite appears to require any of these changes, stop and report the specific blocker.

## A2 — Feature grouping and workflow: follow CCB

Prefer the same mental model and entry relationships as upstream:
- Studio is one workspace;
- AI Design is a Studio auxiliary conversation;
- History is a Studio auxiliary browser;
- Settings are Studio settings;
- imported image opens image actions;
- Guidance is image-oriented;
- chat attachment is part of the composer;
- chat generation actions belong to Assistant messages / chat image controls.

Do not create a new Desktop-only hierarchy merely because a wide screen allows more navigation.

## A3 — Layout: Desktop-equivalent, not Android pixel clone

Allowed:
- two-pane / three-pane wide-screen layout;
- right preview pane;
- drawer/modal instead of a full mobile route;
- keyboard shortcuts;
- mouse hover;
- native file picker;
- compact toolbars;
- resizable panes.

The desktop layout should feel native, while preserving CCB's functional grouping.

## A4 — Desktop enhancements: additive and isolated

Desktop-only enhancements are allowed only when:
- upstream fields keep their meaning;
- no fake Package field is introduced;
- data ownership is explicit;
- Android behavior is unaffected;
- fallback to official CCB data remains deterministic.

The Desktop background library in this R2 is one such approved enhancement.

---

# B. Studio reconstruction — required

The current peer-page design:

`Prompt | 参数 | AI设计 | 图像引导 | 结果 | 历史`

must not remain the primary Studio information architecture.

## B1 — Main workspace

Create one continuous Studio workspace.

Recommended wide layout:

### Top toolbar
Persistent:
- account / Anlas status;
- current image model;
- AI Design;
- Import / Image Tools;
- History;
- Studio settings.

These should map to CCB's top-level Studio actions, not become peer content pages.

### Main prompt column
One scrollable Prompt workspace:
- imported CharacterCard prompt source;
- style Prompt;
- base Prompt;
- extra Prompt;
- character Prompt cards;
- per-character negative Prompt;
- base negative Prompt;
- add/remove/reorder characters where baseline supports it;
- token counts / local annotations.

Collapsible groups are acceptable.

### Generation settings
Remain in the same Studio workspace:
- model;
- size tier;
- aspect ratio;
- custom size;
- count;
- steps;
- CFG;
- CFG rescale;
- sampler;
- random/fixed seed;
- cost/free indication.

They may be a collapsible inspector or lower section, but not a separate peer application page.

### Result/preview pane
On wide screens:
- current selected result;
- intermediate progress;
- result navigation;
- seed/reuse actions;
- metadata/regeneration/image-action shortcuts.

On narrow windows it may stack below the main workspace.

### Fixed generation footer
Persistent where practical:
- positive/negative token usage;
- undo/redo;
- copy-positive;
- Generate / Stop;
- estimated charge/free state;
- overflow actions.

Do not use a real NovelAI request in automated R2 testing.

---

# C. AI Design — required

Open AI Design from Studio toolbar.

Use a dedicated panel/window/drawer that follows CCB `NovelAiDesignScreen`.

Required:
- new conversation empty state;
- current design conversation;
- history button;
- new-conversation button;
- settings button;
- visible design model;
- user request composer;
- send/stop;
- retry;
- regenerate;
- edit-and-branch;
- explicit Apply to Studio;
- current Studio Prompt attachment control.

The attached Studio Prompt must have a visible summary like:
- base only; or
- base + N characters.

Make it obvious what is and is not attached:
- style and negative Prompt exclusion must follow upstream semantics.

Settings dialog must keep:
- Prompt design model;
- V5 natural-language mode;
- extra requirement.

Do not flatten all design conversations into the main Studio page.

---

# D. Imported image / Guidance / image actions — required

The user must be able to start from:

**"I have an image; what can I do with it?"**

Importing/opening an image should open a clear Current Image / Image Tools surface.

At minimum expose discoverably:
- metadata;
- mosaic;
- reverse Prompt;
- rotation / privacy tools already implemented;
- save/copy/reveal;
- remove/replace current image;
- use as img2img;
- use as precise reference;
- use as Vibe;
- inpaint/focused inpaint.

If an upstream action exists but the current Desktop runtime does not actually support it, do not fake it.

Guidance settings should be reached from these image-use actions and/or an obvious image inspector, not only through a peer "图像引导" tab.

The existing validated shared guidance semantics must be reused.

---

# E. Image editor / cover tools — required refinements

## E1 — CCB PNG cover

Keep the validated P7 core.

Refine UX:
- direct drag/pan for horizontal/vertical center;
- hide ordinary X/Y center sliders from the default UI;
- wheel zoom;
- zoom slider;
- mosaic brush;
- solid-color cover-up / block tool;
- undo/redo/reset;
- preview before export.

All masking/cover-up is export-working-copy state.

No CharacterCard or Package mutation.

## E2 — general image tools

The tool dialog should use the same mental model and common image-workspace primitives where practical.

Avoid separate unrelated editing controls for each feature.

---

# F. Chat composer and chat-image controls — required

## F1 — attachment entry

Move image attachment into the composer.

Expected interaction:
- attachment/image icon within composer toolbar;
- selected image thumbnail(s) shown in/adjacent to the composer;
- direct remove button on pending thumbnail;
- text + image and image-only send through the same Send action.

Do not leave a standalone "添加图片" block occupying chat layout.

## F2 — chat image generation discoverability

Expose a compact chat-image menu/control near the composer.

At minimum:
- automatic chat image on/off status;
- current NovelAI model summary;
- current Prompt design model summary if useful;
- session image Prompt requirement shortcut;
- Open Studio.

Detailed session controls may still live in session settings.

## F3 — Assistant message actions

Where CCB provides generation/regeneration actions on Assistant messages, keep the same ownership.

Do not replace a generation action with linked-vision "image understanding" controls.

Image understanding is for user-supplied/model-consumed images and must not masquerade as automatic image generation.

## F4 — task status

Automatic/manual chat image progress and failures should be associated with the relevant chat/message/task context and remain unobtrusive.

Do not occupy a large permanent region of the chat UI for an inactive feature.

---

# G. Background UX — required

Existing upstream meanings remain unchanged:
- Character default background;
- Session override;
- global opacity.

## G1 — discoverability

Add a chat-side Background control/menu.

It should expose:
- current effective background source;
- choose/replace session background;
- clear session override / return to fallback;
- global/effective opacity shortcut;
- Desktop preferred character background;
- manage Desktop character background assets.

Keep the full global Appearance setting too.

## G2 — Desktop background library enhancement

Add Desktop-private per-character background assets.

Requirements described in `39_PHASE7_R2_CCB_STRUCTURE_MAP.md`.

This is not an upstream CharacterCard field.

---

# H. Character → Chat navigation — required

## H1 — New Chat chooser

Current name-only buttons are insufficient.

Reuse a shared Desktop character summary presentation:
- avatar;
- full card name;
- character count;
- document count;
- optional concise non-editing metadata suitable for preview;
- Start Chat action.

It should visually align with Management → Characters but omit edit/delete controls.

Search remains.

## H2 — Management → Characters

Add **Start Chat** to each character row/card.

It must:
- call the existing session-creation authority;
- navigate to Chat;
- not duplicate session creation rules inside management code.

The existing Edit and More actions remain.

---

# I. Manual feedback already accepted

These are not optional "nice to have" notes:

- Character avatar/background/appearance image: current interaction passed.
- drag / wheel zoom / slider / recrop: current interaction passed.
- CCB PNG core: passed.
- normal exit/restart persistence: passed.
- Studio current layout: failed.
- Guidance discoverability: failed.
- automatic-chat-image user surface: failed.
- chat image attachment presentation: failed.
- new-chat character picker: failed UX expectation.
- management character Start Chat: missing.
- Desktop background library: approved R2 enhancement.

---

# J. R2 scope boundaries

Prefer changes in:
- Desktop Compose presentation;
- Desktop navigation;
- Desktop-private preference/resource state;
- reusable Desktop UI components;
- focused presentation tests.

Do not touch sharedCore unless:
- sharing an already-existing pure presentation-neutral helper is clearly necessary, or
- a concrete defect is found.

If shared production semantics would change:
stop and report.

No real NovelAI generation during R2 automation/manual preparation.

Existing accepted live count stays 1/8.

---

# K. Required validation

During work:
- focused Desktop tests;
- affected compile;
- no real NovelAI calls.

Before R2 manual review:
- relevant focused image/Studio/chat tests;
- Desktop full tests;
- shared/Android affected tests if shared code changed;
- compile;
- `git diff --check`;
- rebuild isolated distributable because production UI changed;
- launch smoke;
- clean worktree / pushed durable checkpoint.

Do not merge desktop.

---

# L. Completion state

R2 may report:

`READY FOR PROJECT PHASE-7 R2 REVIEW`

only when the user-visible repair scope is implemented and packaged.

Project reviews the actual diff.

Then user performs `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`.

Only after user manual PASS can Phase 7 be ACCEPTED and integrated.
