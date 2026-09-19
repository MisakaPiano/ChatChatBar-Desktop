# Feature Parity Matrix

Status values: PENDING / IN_PROGRESS / EXACT / EQUIVALENT / BLOCKED / N/A.

Current phase: Phase 0, so implementation rows are PENDING.

| Domain | Target | Status |
|---|---|---|
| Desktop app bootstrap/navigation | EQUIVALENT | PENDING |
| Character Entity / FREEFORM / STRUCTURED | EXACT | PENDING |
| CharacterCardPackage schemas 3..9 | EXACT | PENDING |
| schema 9 default FormatCard | EXACT | PENDING |
| Character JSON import/export | EXACT | PENDING |
| CCB PNG payload | EXACT | PENDING |
| CCB PNG visual renderer | EQUIVALENT | PENDING |
| SillyTavern import | EXACT | PENDING |
| FormatCard v1..2 | EXACT | PENDING |
| WorldBook v1 / ST conversion | EXACT | PENDING |
| WorldBook Engine / timed effects | EXACT | PENDING |
| PromptTemplates / PromptAssembler / ContextWindow | EXACT | PENDING |
| final API message/request order | EXACT | PENDING |
| Model config/discovery/auth/fallback | EXACT | PENDING |
| SSE/thinking/local HTTP/cancel | EXACT | PENDING |
| Session/Message lifecycle | EXACT | PENDING |
| JsonFileStorage + atomic writes | EXACT | PENDING |
| Desktop data dir / Portable / backup | EQUIVALENT+ | PENDING |
| RAG | EXACT | PENDING |
| Long-term Memory | EXACT | PENDING |
| SaveSlot legacy + .cbsave v8 | EXACT | PENDING |
| NovelAI/image generation | EXACT | PENDING |
| image editing/APNG/mosaic | EQUIVALENT/EXACT | PENDING |
| Fish Audio | EXACT | PENDING |
| Desktop audio playback/credential storage | EQUIVALENT | PENDING |
| QQ voice transfer | EQUIVALENT/BLOCKED | PENDING |
| Character/FormatCard/WorldBook AI | EXACT | PENDING |
| Moments | EXACT domain + EQUIVALENT runtime | PENDING |
| Community | EXACT backend + EQUIVALENT OAuth | PENDING |
| Shared Import classifier | EXACT | PENDING |
| Desktop Open With/drag-drop | EQUIVALENT | PENDING |
| Crash diagnostics | EQUIVALENT | PENDING |
| Background AI work | EQUIVALENT | PENDING |
| App updater / Desktop installer | EQUIVALENT | PENDING |
| Upstream watcher/reporting | Desktop-only | PENDING |

Desktop 1.0 gate: every selected-baseline feature is accounted for and resolved as EXACT/EQUIVALENT, or an explicitly accepted BLOCKED exception.
