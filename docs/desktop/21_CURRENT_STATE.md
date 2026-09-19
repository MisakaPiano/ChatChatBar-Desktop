# Current state

Updated: 2026-09-19

## Phase
Phase 0 — Bootstrap / Audit.

## Upstream
- repo: SaltyFishOTL/ChatChatBar
- branch: master
- version: 1.3.48
- commit: 4c8c1eac51dc632bf9042468819cb86091b7660c

## Fork
- repo: MisakaPiano/ChatChatBar-Desktop
- master verified equal to upstream baseline at project initialization
- desktop branch created from the same commit

## Confirmed schemas
- CharacterCardPackage 9, reads 3..9
- FormatCardPackage 2, reads 1..2
- WorldBookPackage 1
- .cbsave 8

## Architecture
- upstream Android Gradle currently has :app
- Kotlin 2.3.20 / JDK 17 / AGP 9.0.1
- persistence: JsonFileStorage
- Desktop target: Windows-first Kotlin/JVM + Compose Desktop
- no browser/WebView runtime
- no forced full-KMP rewrite

## Not done yet
- Codex local read-only re-audit
- :desktopApp module
- shared storage extraction
- all runtime feature parity work

## Risks
- upstream public redistribution license not detected in current audit
- QQ voice is Android Accessibility-specific
- image/audio/secret/background/updater require platform adapters
- upstream may move before Phase 1; re-check before coding

## Next task
Create the new ChatGPT Project using these Desktop docs, then run Codex's read-only first pass from docs/desktop/03_CODEX_START.md. Only after that should Phase 1 Desktop bootstrap code begin.
