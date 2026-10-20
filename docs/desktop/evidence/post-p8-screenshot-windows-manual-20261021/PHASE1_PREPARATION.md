# Screenshot Workspace R2 / R2-R1 — Windows 人工 Gate 第一阶段

**第一阶段准备完成；GUI 启动门禁保持关闭，等待用户明确授权及启动前即时资源复核。所有真人项目 NOT TESTED，历史 Close 原因 UNKNOWN，Post-P8 HOLD 不变。**

## 身份与冻结包

- 分支 `feature/phase8-fish-audio`。本地和 GitHub HEAD 均为 `7cc135b971e0dd509f2f6f387b0ff7cfe2828f9f`，直接 parent 为生产 `c38bcf274fca2eafb81a27b10cb3fcb16ed94fbe`；tracked/index clean。
- 正式基线 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` 未改；远程 desktop 仍 `5ef95428421f7c693b010ac88f51d470804ab07a`，未同步/合并。
- 包：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-r2-boundary-20261021\windows-frozen-c38bcf27\runtime-image`。
- Manifest SHA-256 `51f91f5bcd1f9b943467f3e6ecddcebbac1e46807a8cba7ab327f8829743fa7d`；**212/212** 路径、长度、SHA-256 完全一致，缺失0、多余0、不符0。当前 app/skill 与指定生产无漂移。
- 没有重构建、写入冻结包、修改 Kotlin/Main/Chrome/Prompt/schema 或旧证据。四份用户文件仍为原有4路径 untracked，未读取、哈希、修改或暂存。

## 新隔离根与合成材料

准备工作目录：
`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-windows-manual-20261021`

新数据根：
`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-screenshot-windows-manual-20261021\environments\post-p8-profile\ChatChatBarDesktop`

三个会话共14条消息、2张合成角色卡、4张合成图片、5条正式本地录音：

- **截图验收 A · 常规与录音**：User→分段 Assistant→User→后续 Assistant→User→只有录音的空文字 Assistant；包含图片、两个回复版本、Alice/Bob 本地录音。
- **截图验收 B · 异常排列**：无前置 User 的 Assistant、系统记录、连续 User、连续 Assistant，保留真实顺序，不伪造 source-turn。
- **截图验收 C · 长图边界**：User 上下文与240段公开合成文字。真实冻结 renderer 离屏输出 **640×10924**，约699万像素，在现有800万预算内；包含清晰 START/END 与连续编号。

音频复用既有已验证静态样本，只复制媒体文件，不读取旧 app-data root 或真实 SaveSlot。新录音通过冻结 `DesktopVoiceAudioStore.persist/verify` 和真实严格 `VoiceMessageRepository` 建立与验证；5条均保留独立 owner receipt、版本及归属。只录音回复通过实际 `placementsForMessage` 返回1项，官方 block ID 与修复后的 ordered 筛选均接受；实际离屏 PNG 为640×100。

模型 JSON 的两个 apiKey 均为空。新 LOCALAPPDATA 下的 WindowsSecretStore 仅保存无效随机合成凭据，Fish与两个模型使用独立 typed key。实际 DesktopModelCredentialPersistencePolicy + ModelRepository / Studio 可用模型路径检查 PASS；后续扩展未改这两个模型 JSON，字节身份已比对一致。凭据内容、密文文件均未复制进证据或哈希清单。自动备份、Moments、自动格式检查关闭。

PNG 导出目录为新工作目录下 `exports/png`，MP3 为 `exports/mp3`。已放置有效合成 `keep-existing.png` 作为无覆盖目标，原始 SHA 在 `raw/preparation/export-targets.json`；它是准备材料，不是用户实际导出。segment/whole/voice-only/long 等人工导出目标尚不存在。

## 进程、锁、资源与启动门禁

- 最后资源快照未见 CCB、java/javaw 或 keytool 进程。先前观察到的其他 JVM 未结束/干预；其后自然退出。
- 可用物理内存最后约1.26 GiB、虚拟提交余量约2.36 GiB、H盘约6.64 GiB。物理余量偏低，**这是准备快照，不是未来启动保证**；用户授权后、启动前必须新检查并保存 `launch-recheck.json`，不满足则 BLOCKED，不结束用户进程。
- 独立真实根锁探测序列 **0 → 75 → 0**：空闲可取得；另一个协作 holder 持有时拒绝；发送正常 stdin 释放后再次取得。holder exit0，未强杀、删除或清空锁。实际 GUI 运行期间拒绝、退出后重获仍 NOT TESTED。
- 新 `fixture-prepare-once.lock` 和 `voice-fixture-seed-once.lock` 仅记录本轮夹具准备。**GUI `manual-start-once.lock` 不存在，未消耗一次性启动许可。**
- `launch-admission.json` 保持 `guiAuthorized=false`。supervisor 需要明确的新授权、120秒内即时复核、指定 Git HEAD、新独占 guard，再验证包/夹具/媒体/support 身份，才可能启动。
- `mock-service` 目录不存在、fake 未启动。已准备原验证过的 loopback TLS fake；代码仅本机白名单回应、没有上游转发。动态绑定/readiness/端点及计数只在用户授权后、GUI启动前核实；此处不伪称 fake readiness 已通过。
- runtime-watch 只在授权后运行。外部 C# stream capture 将现有 Main shutdown stderr 实时保存，native-window helper 只枚举本轮 PID 的 HWND/owner/rect/可见性快照。只做记录，不拦截 WM_CLOSE、不修改窗口生命周期。未执行窗口枚举或控制。
- 若未到主窗口X阶段就发生应用退出，即使0/0也分类为 `UNEXPECTED_EXIT_DURING_SCREENSHOT_GATE`，不记正常用户关闭。截图关闭后必须查主窗口存活、Main CLOSE_REQUESTED未新增以及独立锁拒绝；不能只看窗口消失。

## 准备中保留的失败

- 新的独立 Java fixture helper 编译先出现泛型推断及受检异常问题，原始2次 compile RED保留；仅修正辅助代码，compile3及后续诊断构建通过。没有改生产。
- 首两次离屏 helper 缺少启动器已配置的 `skiko.library.path`，得到 LibraryLoadException，日志原样保留。使用冻结包同一 native 路径后 renderer 正常。
- 第一版130段合成长图实际高5974，低于本轮预设大于6000的准备断言；保留原材料和结果，扩展到240段后高10924，未弱化预算或断言。
- 生成外部观察脚本时有一次 PowerShell here-string 拼接解析失败，命令在执行前被拒绝；随后以文件补丁写入，脚本语法及独立 C# 编译通过。监视脚本没有运行、没有窗口事件结论。
- 全部离线验证进程均已退出，根锁已释放；没有未知存活 helper，没有自动 GUI 重试。没有执行 Gradle/JUnit/full bounded/Robot/Android，也没有真实 API 请求。

## 用户授权后的小组人工清单（现在不操作）

每次只给1–3项，等待用户实际反馈，并分别记录技术与 USER 结果。

1. **窗口**：从A的非空回复打开截图窗口；调整大小/焦点。随后分别测试内部关闭、返回聊天、Esc、子窗口X，每次重新打开后再测下一种。每步技术留 HWND/owner、Main trace、进程和独立根锁证据。
2. **选择**：A检查片段/整条/录音以及只有录音回复；B检查异常顺序、User上下文；全部展开/折叠、独立折叠、全选/清空/反选和选择保持；宽/窄/最小窗口与键盘可达。
3. **预览/导出**：C检查首尾编号、双轴滚动、Ctrl滚轮、缩放按钮、100%、适应宽度/窗口和 resize。先取消保存并重试，再验证合成 sentinel 不被覆盖，换新文件名保存；用 Windows 图片查看器打开实际导出文件。技术读取 PNG IHDR、格式、长度、SHA和内容；离屏准备图不能替代真人导出。
4. **退出**：上述完成后才登记用户主窗口X关闭阶段。确认Launcher/JVM实际退出码、根锁独立重获、正式音频/PNG保留及仅本轮临时资源收尾；无强杀、自动重启或清空数据。

完整32项矩阵在 `raw/manual-matrix.json`，所有真人项目仍 **NOT TESTED**。历史截图 Close 根因 **UNKNOWN**，Post-P8 **HOLD**。只有完成实际授权阶段后，才汇总真实结果并向 feature 独立提交推送；本停止点未提交推进HEAD。

**第一阶段 STOP：不启动 GUI/fake，等待本轮明确真人启动授权；授权后仍需即时资源复核。**
