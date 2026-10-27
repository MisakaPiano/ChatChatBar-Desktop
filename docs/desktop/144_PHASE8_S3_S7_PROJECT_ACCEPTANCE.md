# 144 — Phase 8 S3–S7 R3 Project qualified engineering acceptance

日期：2026-10-20 JST。**Project verdict: QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS / NEXT UX GATE PENDING**。本裁定是 Project 对真实 GitHub 源码、原始自动化记录、R3 用户签署及 Windows 进程/文件证据的独立限定审查；**不是**完整 Fish EXACT、P8 fully CLOSED、final bounded GREEN、Public Preview 或发行批准。

## Authority / frozen identity

- 冻结生产源码：`008343bb26d2eb76c23cae9b98132af828a9f447`；S3–S7 工程和测试证据 `23b6e6b1422f693140936700bbd00d0bd6b761de`；工程候选完整报告：[143](143_PHASE8_REMAINING_ENGINEERING_CANDIDATE.md)。
- R1 预建 fake 空目录启动失败保全 `3137f5083d6ae7903178844fef1219beda2f4c10`；R2 明文合成凭据触发正确安全拒绝及 Studio 异常退出证据 `39fdc97d1b34591fe2f61329788f239ddff2b0d3`；R3 真实 WindowsSecretStore/仓库八项离线安全预检 `e51d19cc3aed13ced5ea503f44d1d7e96f6de48c`。
- R3 第一次人工实包与独立授权的第二次持久化重启：`6dd2c486029a910725616a1a5b53e3e79ef937d2`。[R3 人工汇总](evidence/p8-s7-r3-manual-20261020/README.md)、[原始用户时间序列](evidence/p8-s7-r3-manual-20261020/user-chronology.jsonl)、[W5 重启最终核验](evidence/p8-s7-r3-manual-20261020/W5-restart-final-verification.json)。
- Formal validated upstream **ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**；较新 master 仅 observed，不自动 sync/promotion。`desktop` 分支、本次用户四份未跟踪文件和历史原始证据均未更改。

## 接受的工程范围

- **S3**：shared VoiceMessageRepository / official anchor+version 语义、Desktop strict complete inventory、正式音频不可变 MP3、原子发布/owner receipt、无 Key 播放与安全删除；R3 真正重开原数据根后五条语音及哈希保留，音频可播可停，文件句柄可释放。
- **S4**：speaker/voice binding、旁白前置选择、单段/整条补齐、官方 Fish Prompt **逐字抽取**至 shared authority 并经 Golden 逐字/最终 messages 比较、标签/翻译严格协议、并发5与取消排水；fake TTS/auxiliary 有界验证。
- **S5**：聊天音频操作、同锚点新增保留历史、明确替换、MP3 保存、只删除选中录音、版本隔离、会话切换安全停止、听书/English 设置持久化。
- **S6**：P11 **audio-only** v8 JSONL/media、legacy 1–7、stage/rollback/ID 映射；不是完整 `.cbsave`/`.cbbackup` 事务和跨端往返。Windows QQ 原生发送 **BLOCKED**；手动导出 MP3 不是 QQ 发送。
- **S7**：最终精确 212 文件发行包；Project 对冻结清单中的13项高风险 Git blob 复核一致。第一次 R3 与用户明确另行授权的 W5 重开，两次实包 Launcher/JVM 退出 **0/0**、运行中根锁拒绝75/退出后重新获得0、fake排水退出0、TLS材料清理；五条正式音频记录、哈希和恢复后独立文件访问均成立，重开 fake 请求0。用户两次均按主窗口 X 正常关闭。

## 测试事实与严格边界

- Final focused：Desktop **146 PASS**；SharedCore **892 PASS**。唯一 complete bounded **1551 = 1547 PASS / 2 FAIL / 2 SKIPPED，exit1**；原有 `cmd.exe CreateProcess error=5` 环境失败保留，曾引入的 GIF cleanup scope 回归在随后 focused 5/5 PASS，但不允许倒改 bounded RED 或宣称 final source full bounded GREEN。既往 Native Robot/截图、symlink SKIPPED/NOT TESTED 保留。
- R3 第一次 fake TTS POST13、辅助模型 POST4（用户确认有额外显式生成和多次确认）；持久化重开 fake 端点0；真实 Fish GET0/TTS0、收费接口0、QQ发送0。
- W1 先前未选择多角色旁白的失败保留，明确选声线后用户可听/停止 PASS；W2 替换有多目标/多次操作，**未建立单击—单目标独立因果证明**，成功反馈不清晰是 UX 缺口；W3 AI 改写文本确认、真实翻译质量/全部听书行为未测；W4 取消/切会话通过；W5 两次 X 正常关闭与重启恢复是明确接受的限定事实。
- 首次退出的 JNA DLL/标记可能留于临时目录；可确认无 preview MP3/.part 和临时 TLS，不冒称整个临时根完全为空。MCI legacy/物理 I/O 绝对时限、设备矩阵、TOCTOU/断电、真实外部 Fish、完整 SaveSlot roundtrip 未经过本次穷尽验证。
- R2 `STUDIO-CREDENTIAL-ERROR-PATH-001` **OPEN**：错误框确认后异常退出不是 W5 正常关闭；R3 正确夹具不关闭异常处理缺陷。`WIN-AUTO-001` OPEN，`WIN-BRAND-001` OPEN/DEFERRED。

## Next / scope

用户已明确要求改造聊天 UX：[145](145_CHAT_CONTEXT_VOICE_UX_AND_SCREENSHOT_CONTRACT.md)：三行紧凑图标/文字右键和左下 `…` 同菜单；左侧 `[复制][重新生成][…][喇叭]`、右侧生图两按钮；单行条件旁白声线 Deck、浮动暂停续播、跨用户消息连续听书；另补官方聊天截图 parity。**这些都没有在冻结 R3 源码里实现，完整 P8 用户 UX/parity 仍 PENDING。**

本次登记 **docs-only / tests rerun NO**；不改 Prompt、Entity、schema、音频文件、baseline、原始证据，不自动合并 `desktop`、不发布。
