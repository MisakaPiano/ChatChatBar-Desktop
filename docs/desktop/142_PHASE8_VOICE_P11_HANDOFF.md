# P8 S6 audio handoff candidate / P11 review pending

Formal upstream remains ChatBar 1.4.1 (`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`). This is an audio-resource adapter, not a `.cbsave`/`.cbbackup` implementation or P11 acceptance.

## Frozen upstream contract

Android `domain/chat/SaveSlotPackageStorage.kt` writes `voices.jsonl`, `media/audio/<resourceId>.mp3`, and `GeneratedVoiceMessage.audioPath = chatbar-save-slot-audio:<resourceId>`. Restore creates fresh globally owned voice IDs, streams MP3 files, then shared `VoiceMessageRepository.restoreForSession(session, voices, messages)` recreates version-scoped anchors. Schema 1–7 uses `SaveSlot.voiceMessages/audioResources` with inline Base64; schema 8 never embeds MP3 as Base64.

## Desktop adapter

`DesktopVoiceArchiveHandoff` plans audio inclusion without reading omitted resources, writes JSONL and bounded streams, validates resource references before materialization, remaps voice/session/message IDs, and verifies version identities preserved by normalizing messages BEFORE changing their IDs. Only archive resource tokens are accepted; serialized filesystem/URL paths are never opened. Restored audio receives new immutable paths and ownership receipts in `DesktopVoiceAudioStore`.

P11 must validate the entire package, manifest counts, duplicate entries, original message relationships and media inventory before calling this adapter. Supply already-normalized mapped assistant messages and a resource-ID-to-validated-stream callback. Invoke under the app-data operation owner; cancel/join generation and stop playback first. Stage all required media BEFORE atomic entity/session replacement; do not call shared destructive restore in isolation as an application transaction. P11 owns rollback of message/entity sets and D-038 backups. After a confirmed entity commit, retain staged audio. On failure/cancellation/uncertain commit, `rollbackUnreferenced` re-reads strict complete voice references and deletes only known unreferenced files; an unreadable index preserves files. Never recursively delete the generated audio directory.

Current adapter budget: 16 MiB/MP3, structural MP3 validation and 120-second native profile, 64 MiB/staging lease, 10,000 metadata records. P11 must explicitly budget large archives and cannot silently bypass these limits by partial commits. Missing/unsupported media is an error, not successful entity-only import. Historical Android WAV/other decoder formats are not asserted compatible.

## Cross-platform verification required by P11

Desktop→Android and Android→Desktop v8 round trips, audio-off/no-read, legacy 1–7, multi-version same-anchor takes, narration/whole-message orphan placement, ID collisions, source/target version preservation, truncated/overlarge media, cancellation at each stage, uncertain metadata commits and failed rollback must be tested independently. P8 mock handoff PASS does not claim final archive transaction or full application backup PASS.

## Windows QQ: BLOCKED / safe alternative available

Official Android `domain/voice/qq` uses an AccessibilityService press-to-talk state machine targeting the exact QQ Android resource ID and a user-selected chat. This cannot be reused as a Windows client interface. The observed [Tencent SDK documentation](https://github.com/tencent-connect/qqbot-nodejs/blob/main/USAGE.md) describes QQ Open Platform bots with separate AppID/AppSecret and bot reply targets, not controlling the signed-in Windows QQ user's voice recorder. No reliable authorized Windows native-voice equivalent has been verified. This is a bounded evidence gap, not proof that no possible interface exists.

No QQ service/login/recipient messaging was attempted. The available explicit MP3 save action is a file export only and labels that it did not send QQ voice. It must not be counted as native QQ delivery. A future reliable interface requires separate permission, identity and delivery verification.

## Limits retained

No baseline/parity upgrade; no P9 Writer/Recovery, P11 archive engine, merge or release. WIN-AUTO-001 and WIN-BRAND-001 remain open. Physical Windows playback and the final packaged application gate remain separate from these mock tests.
