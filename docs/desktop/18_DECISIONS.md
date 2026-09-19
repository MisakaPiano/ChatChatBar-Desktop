# Architecture decisions

D-001 Desktop is a downstream port, not a similar rewrite.
D-002 We use our own Fork; upstream is never modified.
D-003 GitHub is the Project/Codex code source of truth.
D-004 Windows-first Kotlin/JVM + Compose Desktop.
D-005 Do not force full KMP at the start.
D-006 Function parity, not pixel parity.
D-007 Every upstream feature is tracked as EXACT/EQUIVALENT/PENDING/BLOCKED.
D-008 Package / Entity / Prompt are distinct.
D-009 Final serialized model request is Prompt truth.
D-010 Keep JsonFileStorage semantics; do not switch to SQLite for MVP.
D-011 Add Desktop backup/data-root/Portable safety as enhancements.
D-012 Do not maintain a second copy of domain logic.
D-013 NovelAI/Fish/RAG/Memory/Moments/Community are all 1.0 parity scope.
D-014 QQ voice gets a separate Windows feasibility decision.
D-015 Every release records exact upstream version/commit/schema baseline.
D-016 Public redistribution requires a separate license/permission gate.
