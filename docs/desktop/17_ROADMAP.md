# CCB Desktop roadmap

## Phase 0 — Bootstrap / Audit
Initial docs and source audit. No Desktop runtime code yet.

## Phase 1 — Desktop Bootstrap
- add :desktopApp
- Compose Desktop native window
- version/About/data-root skeleton
- Android :app still compiles
- no business extraction yet

## Phase 2 — Shared Storage
- make JsonFileStorage root Path-driven
- Android + Desktop adapters
- stable app-data dir
- Portable Mode
- backup/snapshot framework

## Phase 3 — Entities / Package / Import
- Character / FormatCard / WorldBook entities
- transfer packages and historical schemas
- CCB PNG payload
- ST compatibility
- file picker/drag-drop ingress base
- Android/Desktop package roundtrip

## Phase 4 — Prompt / WorldBook / Chat Core
- PromptTemplates / PromptAssembler
- ContextWindow
- WorldBookEngine
- Session / Message
- Prompt Inspector
- test transport first

## Phase 5 — Model Runtime / Real Chat
- provider settings/discovery/auth
- SSE/thinking/fallback/local HTTP
- cancellation/task runtime
- persistence across restart
Milestone: Desktop Alpha.

## Phase 6 — Primary Desktop UI / Editors
- card/session browser
- three-pane chat
- settings
- Character / FormatCard / WorldBook / model editors
- responsive layouts
- Open With/file associations

## Phase 7 — Images / NovelAI
Full image resource and NovelAI parity, Studio/history/guidance/vibe/inpaint/APNG/mosaic.

## Phase 8 — Fish Audio
Bindings/library/tags/generation/batch/anchors/playback.
Separate Windows QQ voice feasibility spike.

## Phase 9 — RAG
Documents, embeddings, vectors, retrieval, chat-memory RAG.

## Phase 10 — Long-Term Memory
Episode/Arc/Era/Archive/HEAD/Gap/backfill/compression/repair/regeneration.

## Phase 11 — SaveSlot / Session Lifecycle
Legacy formats + .cbsave v8 cross-platform restore.

## Phase 12 — AI Authoring / Repair
Character AI, rewrite, appearance, covers, FormatCard AI, WorldBook AI, research, message repair.

## Phase 13 — Moments
Full runtime/UI parity while app is running.

## Phase 14 — Community
Browse/download/upload/Discord OAuth/update/community-card behavior.

## Phase 15 — OS Integration
Tray/task center/notifications, clipboard/reveal, crash diagnostics, installer/updater.

## Phase 16 — Upstream Automation
Watcher, changed-file classification and sync reports.

## Phase 17 — Beta Hardening
Large/corrupt data, migrations, DPI/IME, network/proxy/VPN, Windows 10/11.

## Phase 18 — 1.0
Selected-baseline full parity, interoperability tests, packaging/updater and public-release license gate.
