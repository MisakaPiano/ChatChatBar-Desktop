# P8-S2B-UI-LAYOUT-001-R6H — Win32 命中与输入交付取证

**HOLD / INPUT DESTINATION UNVERIFIED / PROJECT REVIEW PENDING。** 本轮取得可信的原生输入目标证据；唯一测试在发送 Press 前安全 RED。没有强行激活、额外点击、第二次执行或产品修复。R6F/R6G 历史 Press0 根因仍 UNKNOWN。

## 工程与执行身份

- 起点 HEAD：`06945846e970fd1ea608a3c2daf5ded71393298b`；直接 parent：`15d35ab8a46decdf710d09d12c9b0d3b87656444`。
- 分支 `feature/phase8-fish-audio`；预检及执行后 local/tracking/live 均为起点。新证据提交的直接 parent 为该起点；不创建源码 checkpoint。
- `origin/desktop`：`5ef95428421f7c693b010ac88f51d470804ab07a`。旧本地 `desktop`：`4c2af2576e2319e8041ffd2a6c5a205450f18246`，未更新或切换。
- 正式兼容 baseline 仍为 ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`，未同步或提升 parity。
- 两份生产草稿字节身份不变；仅 NativeUx 测试增加只读探针和紧接 Press 前后的安全准入检查，增量 **+118/-2**。原 R6G 观察器、Robot 手势机制、全部分页/滚动断言保留。
- JDK17 / offline / no-daemon / no-parallel / single worker / low priority / Kotlin in-process / 原 2048MiB heap；一次命令包含必要编译和唯一方法，不另外运行其他 Gate。

[原始起点与测试源码身份](source-baseline-and-tested.json)、[测试后身份](source-identities.json)、[唯一测试增量](native-hit-test-only.patch)、[增量身份](source-diff-identity.json)、[preflight](A-preflight.json)、[执行后 preflight](POST-preflight.json)。baseline-sources 和 tested-sources 保留三份原始源码副本；这些是证据副本，工程目录三份 Kotlin 草稿继续未暂存、未提交。

## 唯一 Gate 原始结果

命令原文见 [command.txt](01-native-method/command.txt)。`DesktopFishPickerNativeUxTest.native*persistent*` 仅执行一次：

| 项目 | 本轮实际结果 |
| --- | --- |
| compileTestKotlin | PASS，实际编译 |
| JUnit | 1 suite / 1 test / 0 PASS / 1 FAIL / 0 errors / 0 SKIPPED |
| 进程退出码 | 1 |
| Gate 时间 / 方法时间 | 83.1805912s / 6.086s |
| 首个失败 | NativeUxTest 第608行，`INPUT_DESTINATION_UNVERIFIED: HIT_TEST_TARGET_MISMATCH; no Press sent` |
| 原来的 Press 必须为1断言 | NOT REACHED；不能将本轮记作又一次已发送 Press 的0事件失败 |

原始 [stdout](01-native-method/gradle.stdout.log)、[stderr](01-native-method/gradle.stderr.log)、[JUnit XML](01-native-method/junit/TEST-com.example.chatbar.desktop.DesktopFishPickerNativeUxTest.xml)、[完整失败栈](failures.json)、[JUnit 账本](junit-ledger.json) 均保留。原 XML **33572 bytes**，SHA-256：

`92ba25096fd39a87d3f6d580efb95d96bbb9cf43c752bf27376e88edd40084be`

异常栈中的 coroutine 恢复 cause 不代表第二次测试执行。没有重试、clean、rerun-tasks、跳过或删改断言。

## Win32 / AWT 实测

[JUnit system-out 提取](native-system-out.txt)、[原生命中原始记录](win32-hit-test-raw.jsonl)、[结构化实测与限制](input-target-observations.json)。日志标记为 `FISH_R6H_NATIVE_HITTEST`；唯一记录是 `before-press`。

| 观测项 | 原始事实 |
| --- | --- |
| 目标 / GA_ROOT | 均 `OWNED:0x1c31cfc`；本次测试 JVM PID 23768；owned root 有效 |
| Robot 计划位置 | (1124,207) |
| 原3次稳定采样 | attempt0，3次均为 (1124,207)，matches=true，无重新定位 |
| 随后 AWT / GetCursorPos | 均为 (1131,207)，相对计划移动 (+7,0) |
| 实际 thumb | Rectangle(1120,155,8,104)；实际指针已不在 thumb 内 |
| track / 缩放 | Rectangle(1120,155,8,527)；scale1.0，AWT与Win32坐标一致 |
| WindowFromPoint(actual cursor) / root | OTHER_PROCESS / OTHER_PROCESS；hitRootOwned=false，hitInJvm=false |
| 前台 / root | OTHER_PROCESS / OTHER_PROCESS；foregroundOwned=false |
| AWT状态 | showing=true，active=true，focused=true |
| 观察器 | listenerRegistered=true，observeScrollbar=true |
| Press前左键 | GetAsyncKeyState=0，leftDown=false |
| Press后 / Release后左键 | **未采样**：准入失败，根本没有发送 Press，也无对应 Release |
| AWT目标实际事件 / Press | ownedEvents=0 / ownedPresses=0；self/descendant/regionOther均0 |

这些 API 是依次采样，不是原子快照。AWT active/focused 为 true 不能代替 Win32 前台身份。WindowFromPoint 查询的是**实际指针位置**；没有测量计划点 (1124,207) 当时下方的窗口。不能将结果扩写为“计划点必然被遮挡”。

只输出本测试 owned HWND/PID；其他应用仅保留 OTHER_PROCESS 分类，没有读取窗口标题、屏幕内容、账号或完整命令行。未证明命中窗口与前台窗口属于同一个外部进程/窗口，也不寻找其身份。

### 对 Project 八项问题的答复

1. **鼠标下方真实 HWND 是目标吗？否。** 实际 cursor 的命中 root 不属于目标，也不属于本 JVM。
2. **前台 HWND 是目标吗？否。** 原生前台 root 为 OTHER_PROCESS。
3. **Press 前后按钮状态？** Press前 UP（0）；Press后及Release后不适用/未采样，不能填0。
4. **目标 AWT 收到 Press 吗？没有。** 安全门禁在调用 mousePress 前抛出，0事件是预期观测，不能据此判定监听器丢事件。
5. **拖动与分页完成了吗？没有。** 1100dp 初始布局及稳定指针采样完成；真实 Press、16步 Drag、Release、wheel、分页、720/360dp、unknown total 和 Int 边界均 NOT REACHED。
6. **解释 R6F/R6G 了吗？尚不能。** 当前证明了本轮目标不安全这一事实；历史没有对应 Win32 命中记录，旧 Press0 原因仍 UNKNOWN。7px移动和前台变化的来源也 UNKNOWN。
7. **最小可靠替代验证建议？** 仅建议 Project 另行授权：在稳定的交互式 Windows 会话，使用同一合成 owned-window fixture、原只读 Win32/AWT 记录和全部滚动/条目/查询/绑定断言，做一次用户真实鼠标输入对照，与 Robot 通道分开。人工签署与自动化 PASS 分开，不以人工结果自动取代原 Gate；本轮没有实施此方案。详见 [建议边界](alternative-validation-proposal.json)。
8. **提交/保护？** 仅新 R6H evidence 提交；三个工程草稿保持未暂存/未提交，四份用户文件 KEEP/UNREAD/UNMODIFIED/UNSTAGED，R3–R6G 历史不改。最终提交 SHA 由外部 Git 对象确定，提交自身不写入其 manifest；交接答复提供 SHA/parent。

## 观察分类与根因边界

- **OBSERVED：HIT_TEST_TARGET_MISMATCH + FOREGROUND_MISMATCH**，以及指针离开 thumb；安全前置条件失败，未向其他窗口发送 Press。
- **INFERRED：** 本轮若继续按下将违反输入目标合同，因此停止正确；这不证明 Robot 已发送后会怎样。
- **UNKNOWN：** 指针漂移/前台变化来源、R6F/R6G 历史 Press0 根因、R6E error5 根因。不能将本轮新事实追溯标为历史 PROVEN。
- **NOT APPLICABLE / NOT TESTED：** NATIVE_BUTTON_STATE_NOT_OBSERVED、NATIVE_DOWN_WITHOUT_AWT_EVENT、OWNED_INPUT_DELIVERED，均需要实际 Press 才有判断基础；本轮无 Press。

## API 依据及实现边界

使用既有 JNA/JNA Platform5.18.1，不修改依赖。[公开签名 javap 原文](jna-public-api.txt)、[JAR身份](jna-artifact-identities.json)、[API使用依据](api-use-basis.json)。现有 User32 没有 WindowFromPoint；测试私有 StdCallLibrary 使用 `WinDef.POINT.ByValue` 按值绑定，实际编译与调用成功。其余使用 Native.getWindowPointer、GetAncestor(GA_ROOT)、GetForegroundWindow、GetWindowThreadProcessId、GetCursorPos、GetAsyncKeyState。

没有 SendInput、SetForegroundWindow、AttachThreadInput、全局 hook 或系统权限/驱动改动。原正常显示准备逻辑保留，没有为绕过门禁额外激活、关闭外部窗口或补点。探针失败也会先停止，未尝试 Press。范围与 skill 相关性复查见 [scope-and-skill-review.json](scope-and-skill-review.json)。

## 请求、资源和 finally

[阶段/资源汇总](stage-summary.json)、[逐次资源采样](01-native-method/resources.jsonl)、[阶段原始进程结果](01-native-method/stage.json)、[可达性与清理依据](reachability-and-cleanup.json)。

- synthetic catalog调用1、mock HTTP请求0、binding0；真实 Fish **GET0/TTS0**。未读取真实用户数据或凭据；未启动实包或人工 fake 服务。
- Gate 8次资源采样：最低可用物理内存729268224B（约0.679GiB），最低Commit余量12443418624B（约11.589GiB），最高pages/s24403。存在资源压力；未观察到OOM，不能将压力直接归因为命中不符。
- 执行后3次采样可用物理内存2383437824–2461597696B，Commit余量14671970304–14757736448B；Java/javaw和相关构建进程列表为空。没有杀用户进程、清缓存/包或改pagefile。
- 原方法 finally 执行 fixture.dispose/server.close。既有清理路径完成 picker/preview关闭、runtime drain、scope取消join、owner恢复断言、窗口dispose、观察器移除和owned临时根下无.mp3/.part断言。唯一失败栈没有清理异常；执行后无残留JVM。未增加产品清理逻辑。
- 日志 `R5-released-or-admission-failed` 是 finally 的通用阶段名：本轮 pressed=false，**没有 mouseRelease 调用**；不能把阶段名当实际 Release 证据。
- 以上属于 Native fixture 清理，不冒充实包 Launcher/JVM退出、真实数据根锁重获或用户听觉验收。空临时目录可能保留，没有广泛删除。

## 保全、静态验证与停止

[历史树身份保全](history-preservation.json)、[收尾状态](closing-state.json)、[静态验证](static-validation.json)。所有证据成员由 manifest.json 保存原始长度与SHA-256（manifest排除自身）；原始日志/XML/patch中的空白不清洗，完整 diff-check 的原始提示另存，元数据与源码副本静态检查独立记录。

原 R3–R6G RED/PASS/manifest 保持；历史 R6D/R6E 的 Native PASS 不冒充本轮 PASS。当前0 SKIPPED，未执行的 bounded 历史两项SKIPPED也不改变分类。四个 user-owned 文件仅枚举路径，未读内容或hash，未暂存或更改。两份生产草稿未修改，Native草稿保留本轮诊断增量。

**STOP：** 不执行 Native整类、Fish全聚焦、bounded、distribution、Windows人工、R6I、源码候选提交、desktop merge、upstream sync或release；不进入S3/G2/P9/P10/P11。WIN-AUTO-001与WIN-BRAND-001继续OPEN，交Project独立复审。
