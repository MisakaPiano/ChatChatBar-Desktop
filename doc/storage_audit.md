# 单例存储与 Desktop parity 审计

## 已落实的存储契约

- `saveSingleton` 复用临时文件加替换的 `writeJsonFile`，读、写、删按 entityType 互斥。保存前验证现存文件，阻止未加载的默认状态覆盖损坏或不可读的内容。
- `loadSingleton` 仅对 `NoSuchFileException` 返回 null。解码失败与 I/O 失败分别记录为 Corrupt/ReadError，抛出 `SingletonReadException`，不删除或重置原文件；取消不转换为损坏。
- `singletonReadFailures` 提供安全错误说明，不向 UI/启动日志输出原 JSON。MainActivity 显示阻断错误页，可重新读取；ChatBarApp 的持久化初始化捕获该错误，避免启动异常直接终止进程。
- SettingsRepository 在同一锁中完成初始化、读取/缺失创建、迁移和保存；重试可以强制重新加载设置。
- `observeAll` 只观察缓存，完整磁盘初始化仍由 `loadAll` 负责。画图历史仓库已有显式初始化，未改变订阅语义。
- `saveAll` 仍为逐文件提交。失败时缓存反映已经成功的文件，未成功项保持旧缓存，错误继续抛给调用方。

## 批量提交专项结论

生产代码的 `saveAll` 调用位于 MemoryRepository 的节点与修订保存。`commitStateLast` 先保存完整 journal，再写节点/修订，最后发布状态指针；`getState` 重放中断 journal。测试覆盖首个节点已经落盘、后续节点及状态尚未写入的重启恢复，以及状态发布后的清理重放。

绕过 journal 的 `saveNodes` 调用也已检查：单节点编辑/父节点提交、存档快照加载、旧版转换、语义指纹迁移、来源修复。多文件路径不具备整批事务承诺；新节点先写入后发布状态，指纹迁移按节点幂等。此次未将这些业务流程改成通用存储事务，也不宣称其所有中断点已获验证。

## Prompt 与维护文档

- 使用实际消息构建函数与 `StreamingChatService.buildRequestBody` 验证 START、END、BOTH，覆盖 HTTPS 和启用的本地 HTTP：START 在合同确认后、角色前；END 在当前用户和角色 post-history 后；BOTH 使用相同要求两次。HTTP 的角色适配保持内容顺序和最后的 user。
- 修复 `chatbar-model-request-runtime` 中残留的旧位置描述；不改任何提示词正文。
- 图像运行时 Skill 的不存在 WebView Skill 引用改为实际文本请求 owner。
- AGENTS.md 明确区分 JSON 业务实体与 SQLite 辅助词库、目录、索引。

## 验证与边界

- JVM：JsonFileStorageSafetyTest（8 项）、MemoryRepositoryDeletionJournalTest（3 项）、CurrentTurnMessageOrderTest（9 项）通过。
- `:app:compileDebugAndroidTestKotlin` 通过；未启动模拟器、未运行设备诊断。
- 设备列表为空；`redeploy.bat --build-only --no-pause` 成功生成签名 Release APK，未安装设备。
- 测试使用临时目录、内联数据和流式写入故障，不依赖预置资源。
- 未实现自动备份恢复、自动修复损坏 JSON 或破坏性重置。损坏文件需用已有备份或人工修复；重试不会凭空恢复丢失内容。
- 原子替换不等同于断电持久性：沿用不支持 ATOMIC_MOVE 时的普通替换，未增加 fsync/目录同步，也未做真实断电实验。

## 手工验收

1. 保存全局主题/默认模型、玩家名称与人设；退出并重开，确认内容保留。
2. 保存画图草稿，应用一次历史配方并撤销；重开后核对草稿、历史列表和相关图片。
3. 仅在可丢弃的测试安装中备份并损坏单例 JSON：重启应显示读取错误，原文件字节不被默认值覆盖。恢复备份后点击重新读取，应正常进入应用。
4. 正常聊天、长期记忆历史、首次安装初始化不受影响。不要在真实用户数据上做损坏注入。
