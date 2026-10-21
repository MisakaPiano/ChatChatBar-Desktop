# 合成材料来源

本目录不含真实用户数据或有效凭据。`official-credential.cbbackup` 中的值为明确无效的 `synthetic-never-real`，只用于整包拒绝测试；不得把该文件当作可恢复支持子集。

- `seed.cbsave`：来自此前已验证的 `app/desktopApp/build/p11-unified-acceptance-prep-20261021/materials/manual-ready.cbsave`，含合成 Alice/Bob 对话、棋盘图片、离线合成 MP3、RAG 和当前 Memory。
- `desktop/desktop.cbsave`：本轮正式 Desktop service 从 seed 恢复，添加明确的备选消息版本后创建/导出。用户源草稿未包含，遵守官方协议。
- `android/android-return.cbsave`：精确官方 Android 主源码实际导入、读档和一次 loopback 续聊后，由正式 ViewModel 导出；原 4 消息 + 新 2 消息。
- `desktop/desktop-supported.cbbackup`：Desktop codec 输出官方兼容的最小两实体子集（app_settings、chat_drafts）。不是完整 Desktop 全应用备份。
- `android/official-supported.cbbackup`、`official-encrypted.cbbackup`：官方 Android codec 输出对应子集。加密合成测试密码 `synthetic-interop-only`；这是公开测试口令，不保护任何真实数据。
- `android/official-credential.cbbackup`、`official-moment-post.cbbackup`、`official-moment-task.cbbackup`：官方 codec 输出拒绝边界材料。
- `android/official-unknown-field.cbbackup`：官方 codec 封装的未来字段合成负例。不是声称当前官方真实 schema 已存在此字段。
- `.txt`：Android 测试自己写出的限定结果、草稿范围和媒体 SHA，不等于真人确认。

所有材料及捕获日志校验见本目录上级 `EVIDENCE_SHA256.json`。APK 较大，未加入 Git；最终 APK 大小及 SHA 在 `raw/apk-identity.json`，可按独立步骤从精确源码重建。保留本机新 AVD、原档案和事务证据，不因测试完成自动清理。
