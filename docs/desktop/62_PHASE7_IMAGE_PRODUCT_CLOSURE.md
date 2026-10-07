# 62 — Phase 7 Image Product Closure

## 0. Goal

Close the remaining user-visible image/NovelAI product gaps after the latest manual HOLD while selectively forward-porting current official image-domain changes from 1.4.4.

Do not reopen already accepted Phase 0–6.

---

# 1. AI Design must be a conversation surface

Current Desktop presentation is too form-like.

Use current CCB ownership:

- dedicated AI Design conversation window
- header:
  - Back to Studio
  - actual design model + provider + credential status
  - History
  - New conversation
  - Settings
- conversation body:
  - user turns
  - assistant design results
  - progress/reasoning collapsible surface
- bottom composer:
  - attach current Studio Prompt
  - text input
  - Send/Stop

Do not show old/current requests as two form fields.

## 1.1 Credential/model preflight

Before Send:
- resolve the exact design model;
- show its display name and provider;
- verify `hasConfiguredAuthentication(...)` using hydrated Desktop secure credential authority;
- if unusable, disable Send and show actionable safe error;
- provide path/button to model settings.

Do not wait for HTTP 401/403 as the first user-visible validation.

Normal chat being configured does not prove the separate AI Design model is configured.

## 1.2 Structured assistant result

Assistant result card:
- target NovelAI model
- Base Prompt module
- ordered Character Prompt modules
- Copy on every module
- Apply to Studio
- Regenerate
- user-turn Edit & branch

Use current shared conversation/reply ownership.

## 1.3 Tag research visibility

AI Design already uses local Danbooru/Codex research.

Expose compact progress:
- planning
- querying local tag catalog
- local Codex recall
- final design
- repair if any

Default collapsed after completion.
Do not dump raw internal debug into the main reply.

---

# 2. Character Prompt cards

## 2.1 Exact header contract

Enabled:

`角色 2　已启用                         [↑][↓] [✓] [删除] [⌃/⌄]`

Disabled:

`角色 2　已停用                         [↑][↓] [×] [删除] [⌃/⌄]`

Rules:

- role label/status on the left;
- all actions aligned right;
- role card has clear border/background;
- disabled card is visibly muted/gray;
- the card never disappears;
- move up/down remains usable while disabled;
- delete remains usable while disabled;
- enable toggle remains usable;
- visual expand/collapse remains usable.

## 2.2 Enabled state

Port official current-upstream `enabled`.

Enabled controls whether the role participates in final NovelAI generation.

Disabled retains:
- ID
- order
- positive
- negative
- center
- history/undo

## 2.3 Visual collapse is separate

Desktop visual collapse is **not** the same as enabled.

Collapse:
- presentation-only;
- does not change activeCharacters;
- does not change final request;
- show up to approximately 3 lines of positive Prompt summary;
- show compact negative/status indicator if useful.

Expand:
- full positive editor
- negative editor
- position summary/actions

When user disables an expanded role:
- initially collapse the body to the disabled compact state;
- the expand/collapse control remains usable;
- if expanded while disabled, body may be inspected/edited but stays muted and remains excluded from generation.

Do not add a new persisted upstream Entity field solely for Desktop visual collapse unless there is a compelling existing Desktop UI-state authority.

---

# 3. Prompt section hierarchy

Peer sections must look like peer cards, not unbounded text rows.

Recommended order:
1. Style
2. Base Prompt
3. Extra Prompt
4. Base Negative Prompt
5. Character Prompt cards
6. Generation Settings

User-approved Desktop presentation change:
Base Negative appears directly after Extra Prompt.

Each collapsible section:
- has one full-width header row;
- collapsed header occupies the card;
- expanding grows that same card;
- icon indicates expand/collapse.

No Prompt semantic reordering occurs in final request.

---

# 4. Current-upstream character positioning

Port `354f151`.

Studio exposes role-position editor when active roles exist.

Desktop:
- compact toolbar/icon entry near role section;
- Auto vs Custom;
- active role selector;
- canvas click/drag;
- numeric % fallback;
- V4.5 grid snap;
- V5 continuous coordinates;
- reset evenly;
- preserve role IDs/order;
- disabled roles excluded.

No AI prompt text changes.

---

# 5. V5 Opus usage battery bar

Existing account response already provides:
- `v5AllowancePercent`
- exhausted state
- approximate image count derived by current CCB policy

Display current V5 status as a real battery/progress surface, e.g.:

`V5 Opus 83% · 约 1436 张`
`████████████████░░░░`

Rules:
- percent bar from account authority;
- approximate images from existing shared calculation;
- exhausted state visible;
- account unknown shows unknown, not zero;
- do not fabricate refill speed.

Current CCB account service does **not** provide an authoritative refill-rate/time-to-full field.
`14.3%/day` / time-to-full remains provider capability audit unless an authoritative response field is demonstrated.

---

# 6. Tag completion + caret inspection

See `63`.

---

# 7. Studio image import IA

Main Prompt toolbar:
- Copy structured positive Prompt
- Paste structured positive Prompt
- Clear

Do not present PNG metadata import as a peer Prompt operation.

Top-level `导入图片` opens the imported-image workflow.

Inside imported-image workflow:
- metadata / load settings
- Image Tools
- reverse Prompt
- Guidance routes
- Enhance/Upscale
- privacy/mosaic/APNG actions where applicable

Recognized metadata import uses current official OFF/REPLACE/APPEND role mode.

---

# 8. Clipboard and drag ingress

## Studio

When Studio itself owns focus and user:
- pastes an image/file from clipboard; or
- drops image files onto the Studio window;

route to `导入图片` workflow.

Exception:
- when a Prompt text editor owns focus, ordinary text paste remains normal text editing;
- explicit structured Prompt paste remains the dedicated action.

## Chat composer

- Ctrl+V image/file → pending attachment
- drag/drop image files onto composer → pending attachments
- file picker supports multi-select
- attachment strip supports drag reorder

These are Desktop equivalent conveniences for the existing multiple-attachment model.

Windows shell Open With / file associations remain P15.

---

# 9. APNG attachment behavior

Fix current defects:
- do not show the same APNG error twice;
- changing/removing attachments clears stale error state;
- ordinary APNG is accepted as an attachment;
- do not tell chat users to use Image Tools merely because the PNG is animated.

Desktop enhancement approved for this closure:
- detect canonical ChatBar APNG disguise at attachment ingress;
- restore a new owned true-content copy automatically;
- never modify/delete the external source;
- static disguise → restored static PNG;
- dynamic disguise → restored animation;
- show a small one-time badge/status that disguise was restored;
- third-party APNG remains ordinary APNG and is not guessed/restored.

Use the existing APNG codec authority.

---

# 10. Unified image viewer

One Desktop viewer behavior for:
- chat images
- pending attachments
- Studio output
- History
- background library
- imported-image tools

Mouse behavior:
- wheel zoom centered around cursor where practical
- drag pan while zoomed
- double click Fit/1:1 toggle or equivalent
- reset
- previous/next where a collection exists
- Esc closes

Window:
- resizable
- maximize/restore
- no duplicate system-title + content-title clutter

Keep animation support.

---

# 11. Studio result pane

Wide Desktop:
- result pane is a peer right-hand pane, full available height;
- user can resize pane width;
- current large preview fills available area;
- current/recent filmstrip belongs to this pane;
- result actions stay adjacent to selected image.

Selection:
- initial selection = newest image;
- after generation = newest newly generated image;
- new history update does not jump user away if they explicitly selected another older image unless the new result is the current generation they just launched.

Pane modes:
1. Expanded preview
2. Compact filmstrip-only right rail
3. Preview-focus mode: left Prompt/generation pane collapses and preview takes most workspace

Narrow Desktop:
- do not push the preview far below the Prompt;
- render compact top current-image panel with latest thumbnail + essential actions;
- click expands preview.

Do not create a second history repository.

Merge duplicate actions:
`图像操作 / 用作` and `当前图片 · 工具与图像引导` must become one coherent selected-image action surface.

---

# 12. History

Current official CCB history is a grid.

Desktop:
- adaptive image grid, not one 180px-wide full-row image;
- thumbnails preserve aspect-fit/crop consistently;
- click opens detail/viewer;
- selection badge;
- folded album badge/count;
- search/date/fold remain.

Forward-port current official range selection:
- mouse-friendly Shift-click/range selection is preferred;
- preserve official visible-range/album-member semantics;
- explicit range state;
- no hidden cross-filter selection.

History auxiliary surface:
- resizable/maximizable window;
- no duplicate title;
- common Desktop tool-window shell.

---

# 13. Auxiliary tool windows

Apply one common shell to:
- AI Design
- History
- Guidance where a separate window is appropriate
- Image Tools
- large settings/tool surfaces

Requirements:
- resizable
- maximize/restore
- sensible remembered size/placement if existing Desktop window-state authority supports it
- system title not duplicated by an identical content title
- navigation/actions remain in content toolbar

Small confirm dialogs remain dialogs.

---

# 14. Composer / message image actions

Composer:
- action rail aligned bottom-right at ordinary/wide sizes;
- attachment / fullscreen / Send/Stop form one cluster;
- no center-floating rail.

Assistant message:
replace giant text buttons with compact right-side actions:
- Generate image: clear image/spark icon
- Image generation requirements/settings: sliders/settings image icon
- tooltip/accessible text preserves discoverability

---

# 15. Attachments

Current accepted behavior remains:
- multiple attachments
- 96–120dp clear thumbnails
- click viewer
- hover ×
- zero-height when empty

Add:
- multi-file picker
- horizontal scrolling beyond composer width
- wheel/shift-wheel scroll
- drag reorder
- clipboard image ingress
- window/composer drag-drop

Reorder must change pending request order only; do not mutate already persisted message order unexpectedly.

---

# 16. Official current image postprocessing

Forward-port:
- Enhance
- Upscale
- draggable before/after comparison
- cost display
- Stop
- save/share
- apply result as new current imported-image input

No real NovelAI postprocess call in validation.

---

# 17. Privacy export / alpha metadata

Forward-port:
- alpha stealth metadata read
- safe precedence
- privacy PNG export erasing metadata and stealth carrier bits

The existing mosaic/solid-cover tools remain.

User-facing text must clearly distinguish:
- ordinary visual edit
- privacy export
- APNG disguise/restore

---

# 18. Advanced / Diagnostics placement

Tools top navigation:
- `NovelAI Studio`
- other normal user tools
- `高级 / 诊断` as a same-row top-level item at the far right

Do not give Advanced/Diagnostics an entire extra row.

Prompt Inspector remains inside Advanced/Diagnostics.

---

# 19. Session Settings

The implementation already has tabs but user has not completed manual validation.

Do not redesign again unless a defect is found.
Keep final manual gate for:
- tabs
- dirty state
- Save/Cancel
- image settings
- background immediate-save semantics.

---

# 20. Hard boundaries

No unapproved Prompt literal edits.

Do not:
- promote baseline to 1.4.4
- merge parked sync
- generalize Curated models
- change sampler catalog/filtering
- implement full Studio preset import/export
- start Moments
- start global IA overhaul
- start Windows Open With/file associations

No real NovelAI generation/postprocess request.
Current live generation count remains 1/8.
