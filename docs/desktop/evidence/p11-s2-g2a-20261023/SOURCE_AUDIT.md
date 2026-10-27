# G2A selected source authority

Authorization: full166 executable contract +165 receipt;158 R07 product direction;162 limited G1A acceptance;163/164 candidate model, superseded only within166. User explicitly resumed this slice. No additional approval was needed/requested.

Formal baseline remains **ChatBar1.4.1 @5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**. Official **1.4.5 @1198a13cf3008de1358b8a69aa5e40b64797c8d9** is selected archive-format authority only. Existing local Git objects were used; no upstream fetch/sync.

| Pinned official source | Audited fact / bounded use |
|---|---|
| `.agents/skills/chatbar-app-backup/SKILL.md` | v1 streaming envelope; source credentials/community included; Android Keystore and startup transaction are separate OS implementation. Read as fixed historical source authority, not permission for Android execution. |
| `domain/backup/AppBackupModels.kt` | Strict transport backupJson (`ignoreUnknownKeys=false`), v1 Manifest and Preferences keys. Independent existing test wire DTOs reused; no production Inspector DTO fixture generation. |
| `domain/backup/BackupPreferenceTransfer.kt` | Exact NovelAI/Fish/community preference locations; community is serialized session String. Android restore semantics are not adopted as an inert Desktop action. |
| `domain/backup/BackupDataRegistry.kt` and `BackupEntityValidator.kt` | Registered model_configs, embedding_configs, singleton embedding_model_config, app_settings. Official Entity decoder uses its own permissive unknown-field setting; **transport is strict**. G2A does not weaken either Desktop decoder or existing restore admission. |
| `data/local/entity/ModelConfig.kt` | Model ID/displayName/baseUrl/apiKey/modelName/templateType; Embedding ID/displayName/baseUrl/apiKey/modelName/dimensions. Baseline1.4.1 counterpart also checked. Current ModelTemplate enum reused. |
| `data/local/entity/AppSettings.kt` | siliconFlowApiKey global source. |
| `domain/community/CommunityModels.kt` | CommunitySession accessToken, refreshToken defaultnull, tokenType defaultbearer, expiresAtMillis default0, userId, displayName; no Desktop protected lifecycle equivalence. |
| `data/security/NovelAiCredentialStore.kt`, `FishAudioCredentialStore.kt` | Android protected sources are not Windows sinks; existing G1A pinned identity reference preserved. |
| `app/build.gradle.kts` | Official version code84/name1.4.5. No schemaVersion guessed or invented. |

Exact commit/blob/canonical byte SHA/length: `raw/official-source-identities.json` and `raw/baseline-source-identities.json`. Sources were inspected selectively for these fields; no bundled preset/seed content is used by tests. The test-only Model/Embedding/Settings DTOs intentionally support a **minimal strict subset** used by the raw positives, not all valid official Entity configurations. S0 authenticates/validates full archive bytes before projection in positives. Unknown/corrupt fields at the subset boundary reject; no compatibility claim is made for unimplemented projections.

Current Desktop `security/DesktopSecretStore.kt` supplies six key namespaces and no Community key. `WindowsSecretStore` has per-key IO; repository policies only implement their existing single-key compensation. Current full-backup journal/startup do not coordinate the secret root. New code has no reference to those executing services; their exact identities are frozen in `raw/existing-production-identities.json` and their existing targeted tests actually rerun.

The Desktop full-backup skill was read before implementation and reviewed before staging. One concise new entry documents the inert contracts/test map because the new source area would otherwise be absent; no global rules are copied. No other skill becomes stale. No UI/long-press/Prompt/device change requires a tutorial, APK or device handoff; those actions are expressly forbidden in166.
