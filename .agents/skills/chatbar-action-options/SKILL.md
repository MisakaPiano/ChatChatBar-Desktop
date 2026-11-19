---
name: chatbar-action-options
description: Maintain declared action blocks, per-reply rule snapshots and shared chat/image/voice projections.
---

`ActionOptionsFormat` declares literal opening/closing/title and numeric or alphabetic labels. `RoleplayActionOptionsPolicy` is the only recognition and validation owner. The default recognizes complete titled short/legacy long rules and explicitly named legacy action fences. Declared custom fences must match their literal markers. Ordinary, nested, quoted, indented and incomplete code examples remain text; unsupported boundary combinations fail validation. `MarkdownCodeFences.ranges` owns top-level code boundaries.

`FormatCard.actionOptionsFormat` travels through standalone `FormatCardPackage`, character-linked default formats and editor drafts. `ChatMessage` captures the request-time rule and aligned alternative rules; `MessageAlternativeVersionPolicy` carries them through append, selection, edit and removal. Null means the default conventions. Default-absent fields retain old wire omission and unbounded Desktop alternative retention remains independent.

Desktop `DesktopFormatActionOptions` edits the declaration and shows a literal example. `DesktopActionChoices` consumes the shared result; `DesktopComposerInput` retains cursor insertion, exact undo and conditional draft persistence. `DesktopPrimaryChatPresentation` hides custom action text only for the interactive view, preserving copy/export text, original source bounds and voice indexes. Existing STATUS action disclosures keep their placement; custom blocks use the same compact disclosure after the message.

`ChatHistoryActionProjection`, `NovelAiPromptAuthority` scene sources and `VoiceGenerationPolicy`/`VoiceAnchorPolicy` consume the active reply's captured rule. Speech filtering preserves original segment coordinates; old recordings can match either old unfiltered or new filtered source text. Historical numeric or ambiguous references keep the existing conservative chat-history admission.

Desktop format repair completes only reliably recognized trailing blocks and compares existing option text/count/order before either completed or stopped publication. Its requirement builder lives with `FormatRepairPromptAuthority` and the Android `PromptTemplates` facade. Original recovery/restore/redo remains with `DesktopMessageFormatRepairController`.

Tests: shared `ActionOptionsFormatTest`, `ActionOptionsDeclaredFenceCompatibilityTest`; Desktop `DesktopActionOptionsFormatIntegrationTest`, existing `DesktopActionChoicesTest` and `DesktopActionOptionsRequestTest`. Use actual source coordinates and reply alternatives when testing presentation or voice; a string-only parser result does not cover those owners.
