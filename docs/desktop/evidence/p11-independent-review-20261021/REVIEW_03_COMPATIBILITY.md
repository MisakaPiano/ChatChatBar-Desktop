# REVIEW-03：官方 1.4.5 数据域兼容核对

官方来源：`1198a13cf3008de1358b8a69aa5e40b64797c8d9` 的 `BackupEntityValidator.kt`、`BackupDataRegistry.kt`。正式基线仍为 1.4.1。本次只读核对，没有同步上游。

**官方注册 39 类，Desktop 注册其中 37 类。** 缺少 `moment_posts`、`moment_tasks`，属确定兼容边界；拒绝整次恢复会保留原件与当前数据，不是数据损坏 FAIL。D-038 完整跨端门槛继续 PENDING。

“已完整支持”必须同时具备具体字段/引用和真实往返证据。目前下列已实现类型不能统一升级成“全部官方历史文件完整兼容”。Desktop 使用现有共享实体并严格拒绝未知字段，官方 validator 则允许未知字段；即使类型名相同，仍存在版本字段边界。

| 审查要求的分类 | 本次结论 |
| --- | --- |
| 已完整支持 | 37 类已有工程实现，但完整真实跨端证据不足，暂不将任何整域签署为全量兼容。逐类实现范围见下表。 |
| 可无损保留但尚不能运行 | 尚无获证的未知域不透明保留机制，不能把原归档仍在误写为已导入后的无损保留。 |
| 平台专有 | Windows 根设置、SecretStore、恢复日志及 Desktop 额外偏好；不自动映射 Android 凭据。 |
| 必须拒绝并保留原归档 | Moments 两类、未知类型/字段/资源，以及不符合当前凭据排除规则的输入。 |

| 官方 Entity | 本轮分类与证据边界 |
| --- | --- |
| `app_settings` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `character_cards` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `character_speaker_migration_state` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_drafts` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_message_indexes` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_message_order_backups` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_messages` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_scroll_positions` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `chat_sessions` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `edit_drafts` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `embedding_configs` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `embedding_model_config` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `format_cards` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `generated_voice_messages` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_commit_journals` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_commits` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_compression_transactions` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_nodes` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_states` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `memory_tier_revisions` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `model_configs` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `model_configuration_migration` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `moment_posts` | 必须拒绝并保留原归档；未实现安全的不透明域保留，也不运行该域 |
| `moment_tasks` | 必须拒绝并保留原归档；未实现安全的不透明域保留，也不运行该域 |
| `novelai_design_conversations` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_design_current` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_generation_history` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_history_fold_preferences` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_studio_draft` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_studio_guidance_checkpoint` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `novelai_studio_history_undo` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `pending_deletions` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `player_setting` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `preset_import_state` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `save_slots` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `vector_chunks` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `voice_anchor_states` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `world_book_migration_state` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |
| `world_books` | 已实现类型校验与路径映射；未知字段/超限仍拒绝；真实跨端待验证 |

## 资源与偏好

| 域 | 当前处理 |
| --- | --- |
| `images` / `documents` | 已实现受限路径映射及平铺身份；消息图片做有限尺寸解码。真实跨设备待验证。 |
| `audio` | 已实现录音引用与媒体映射，新录音校验 MP3 并建立 Desktop 所有权收据；旧文件和 source-turn/version 语义保留。 |
| `draft_assets` | 按官方登记路径映射并纳入清单；图片校验。 |
| `save_slot_packages` | 保留归档原始字节；v8 额外校验包与实体引用。legacy 仍受原预算限制。 |
| `novelai-vibe-payloads` | 内容哈希 token 必须能解析到已登记资源，未知或缺失拒绝。 |
| `danbooru_catalog` | 清单与数据库同时存在；只读 SQLite 检查，不自动重建或替换。 |
| `image_mask_preferences` | brushSize / brushType 校验后存入 Desktop 专有偏好记录，再导出回官方 preferences；不启用凭据迁移。 |
| `novelai_credentials` / `fish_audio_credentials` / `community_session` | 平台保护与授权专有；preferences 中对应字段必须为 null，连空字符串也不放宽接受；其余输入拒绝并保留原归档，不导入 Windows SecretStore。 |
| Entity 中 API Key | 普通导出排除；非空外来输入拒绝。 |
| `desktop_backup_preferences` / `desktop_character_backgrounds` | Desktop 专有，界面标示不能据此宣称 Android 兼容。 |
| `retrieval_model_config` | Desktop 额外登记的共享 ModelConfig 类型，官方本次 validator 未登记；不能宣称该数据可被官方 Android 接受。 |
| 本机 root 设置、SecretStore、恢复日志、诊断/更新缓存 | 平台专有，现有目标保持不变，不作为全量跨端归档实体。 |
| 未来字段、未登记 store 或未知资源类型 | 必须拒绝并保留原归档；没有承诺无损安装到 Desktop。 |

## 未批准事项与可选后续方案

本次不以注册空壳类型或忽略字段绕过 Moments 拒绝。若未来需要“不运行但无损保留”，应先建立独立不可执行容器：锁定官方类型和版本、保存原字节及资源引用、禁止运行后台任务、再导出逐字节核验，并证明本机恢复不会把它当成默认空数据覆盖。没有这套证据前继续拒绝。

凭据迁移需单独批准：显式选择凭据类别与目的账号、加密源验证、只在内存解密、受保护存储事务提交与失败撤销、不记录值、不让普通分享导出包含凭据。当前没有启用此能力；读取加密内容不等于获准安装凭据。

本次没有足够真实 Android 文件/设备证据把任何跨端组合签署为完整 PASS；也不把安全拒绝写为数据安全 FAIL。后续统一 Gate 再处理真实兼容，不拆成用户零散验收。
