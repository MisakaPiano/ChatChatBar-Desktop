# CCB Desktop Project Instructions

Goal: maintain a Windows-native downstream port of ChatChatBar that can continuously follow upstream.

## Invariants
- Upstream current source is the highest technical authority.
- Do not treat Tavern/SillyTavern/CCV2/CCV3 as the default CCB standard.
- Always distinguish transfer Package, persisted Entity, and final model Prompt/request semantics.
- FREEFORM and STRUCTURED are both valid.
- Do not invent image/voice/resource references.
- Desktop UI may differ; core CCB behavior may not.
- Every Android user-facing feature must be tracked as EXACT, EQUIVALENT, PENDING or BLOCKED.
- Do not silently delete Android-only features; design a Desktop equivalent first.

## Git
- `master`: upstream mirror only.
- `desktop`: integration branch.
- `feature/*`: feature work.
- `sync/*`: upstream absorption.
- Never write to the upstream repository.

## Technology
Windows-first: Kotlin/JVM + Compose Desktop + JDK 17.
Do not use Electron/WebView/localStorage/IndexedDB as the core runtime.
Do not force the whole Android project into KMP on day one. Prefer conservative JVM shared extraction.

## Upstream sync
A merge without conflicts is not proof of compatibility. High-risk semantic changes require parity tests and review before the baseline is advanced.

## Prompt ownership
Do not rewrite upstream model prompts as part of a platform port unless the task explicitly requires prompt changes and the user approves them.

## Data safety
Keep atomic JSON storage semantics, add Desktop data-directory visibility, migration snapshots, backups and Portable Mode. Never fix migration bugs by silently deleting user data.

## Secrets
Never commit API keys, NovelAI/Fish tokens, GitHub PATs or OAuth secrets. Use OS secret storage or ignored local config.

## ChatGPT Project vs Codex
Project: architecture, upstream research, task specs, semantic review, PR/commit review.
Codex: local code edits, Gradle, tests, refactors, Windows implementation, commits/PRs.
