# 280 — 1236 工程范围与 Beta 交叉场景表

本表当前对应冻结应用a647的1236（最终测试后继53仅改两测试/限定workflow），不是正式1.0通过声明或新Preview门槛。完整154、R27原包/生命周期、R28旧client签名18及匿名公开下载已核；旧153失败与138/R24/R26历史证据保留。用户此前1235实际通过组保留；1236用户已明确自更/原资料/重开正常，仅该范围通过。N01a多步/快捷键与N01bIME/选区FAIL，后继5d4e2f01的history7/ui12正在修验，具体见288/289；安装路径未知且agent不代装。

## 已核原件与仍未知的结论

| 场景 | 已有实际证据 | 尚未由该证据证明 | 下一步范围 |
| --- | --- | --- | --- |
| 工程回归 | 154原Windows XML主2617=2598PASS+19SKIP，proxy5/NTFS7/native37/TUF14/exit4；shared1221/Android586复用同体153原件并有154 Linux成功 | 全部Windows环境、真实Provider、真实资料人验；原153两FAIL由精确104及154闭合但原件保留 | 复用原件，不因文档整理重跑 |
| 普通安装/升级/重开/冷暖文件投递 | R27普通1225→1236、忙拒绝、正常退出/Job排空/重开/冷批次；独立中文入口548文件与自己数据/锁/外bootstrap保留；549/548字节核对 | 真实资料/凭据/特殊根/所有外部可执行后代；不把fresh默认假资料当生产验收 | 用户自行更新，root逐项记实际版本/原会话/草稿 |
| 更新器 | R28冻结公开1233实际client15→18、178445507B下载二验、notes/root1一致；公开1236/18的匿名release/tag/10role/完整流SHA另核 | R28受控fetcher不是匿名client执行，匿名HTTP不是用户确认安装；真人1236待 | 复用原包/签名，由用户自行应用内更新 |
| 原生窗口/音频 | 154仍明确16图形/3物理音频SKIP；1235用户已通过的查看器/已有音频组按root实际记录保留；R27普通程序窗生命周期独立PASS | native1新闭合2、native2实际10PASS/4FAIL、native6实际4PASS/1SKIP仅再完整闭合scrollbar/edge/chrome3；合计15原方法，混合Stop仅下载/生成通过，播放wave0未测 | 不再重跑已闭合限定图形；原混合播放和3专门音频、系统IME/其它环境人验保留 |
| 长聊天导出 | 原1225失败保留；最终154包含67条PNG/PDF/可编辑Word、完整vs分块逐像素、背景/头像/Markdown及状态行动选择回归 | 用户原真实长聊天、资源、Word/WPS实际阅读外观；PDF是图片式分页 | root按N组引导原聊天复测，不先归因360/容量；真实原文不上传 |
| 玩家/人物/会话布局 | 既有状态归属、启停、拖宽/收起、草稿和Scene回归；1217人工条目及旧版暂定通过按原版本保留 | 本版全部DPI/IME/宽窄交叉操作 | 不覆盖旧验收结果；本版只记实际版本及具体通过项 |
| AI创作/媒体/用量 | 138实际生产controller、合成HTTP、取消/过期草稿/原撤销；辅助模型参数及反推8+4通过 | 真实NAI/Fish/LLM计费、所有模型精确分词、全部Provider兼容 | 可先检查配置/候选界面；真实付费调用由用户决定，不要求为验配置发付费请求 |
| Token容量 | 用户显式容量与实际wire数字来源；固定分词器14行样本比较已执行 | 字符粗估等于tokenizer、误差上界、可用余额或账单精度 | 保留估算标签；273实测偏差不是精度PASS |
| 备份/合并/恢复 | 1224真实大包隔离往返及用户GUI冲突预览；后续typed错误/取消/恢复回归 | 用户最终生产合并、跨账户/物理断电/未覆盖旧快照 | 只由用户选凭据与冲突并提交；不自动合并，不为测试破坏资料 |
| 完整预设/Prompt互换 | 原生产预览/显式应用/撤销、资源custody与Desktop/Fork配对工程证据 | 系统剪贴板、实际外部安装版；官方Android完整Prompt读取或往返 | Studio消费CCB接口；不新增手机Fork安装前提 |
| 社区 | 原134接线/R20 Main/注册表限定结果复用；协调端2026-11-03 21:47JST官方只读GET enabled:false | 真实Discord/OAuth/浏览器/前台焦点/远端写入/全部后代退出 | 只挂具体服务/环境；其他工程照常，不新建替代社区 |
| 平台/长期使用 | 云端Windows NTFS/普通生命周期、Linux共享逻辑；各人工旧反馈保留 | 用户Win10/11全组合、DPI、输入法、多屏、真实长程与断电 | 每次结果记录OS/应用版本/场景；没有结果写未测，不要求重新启用退役VM |

## 138原件明确跳过的19项

154当前原XML的19项另存连续总账`updates/20261103-selected147-residual-closure/CURRENT154_SKIPPED_CASES.json`。原138表保留历史，后续独立native执行不把原SKIP改写成PASS；需按实际方法/源/环境单列新结果。

来自唯一现存 `windows-original.zip` 的JUnit XML只读提取，未下载副本、未运行新测试。16项理由为原生图形桌面不足，3项为wave output devices=0。完整路径和原始方法名保留在本地 `ORIGINAL138_SKIPPED_CASES.json`。

| 原测试类 | 原方法 | 所需环境 | 138结果 |
| --- | --- | --- | --- |
| `DesktopCurrentImagePresentationTest` | tool window exposes resize maximize and close without duplicating content title | 原生图形桌面/输入 | 未执行 |
| `DesktopDesignParityTest` | real Studio auxiliary OS close runs leave semantics | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerNativeUxTest` | native persistent scrollbar drags current page and explicit page jump preserves request semantics | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerNativeUxTest` | native Stop is top layer visible and operable during download generation and long Windows playback | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerNativeUxTest` | native Escape closes exactly one panel then picker with and without field focus at both widths | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerNativeUxTest` | native resize retains target text and generated cache while sample identity stays distinct | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerWindowTest` | identity scoped overlapping leases restore once in either order and remove callbacks | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerWindowTest` | public main WindowScope supplies exact owner through maximize restore reopen and parent close | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPickerWindowTest` | missing explicit main owner fails closed without composing picker content | 原生图形桌面/输入 | 未执行 |
| `DesktopFishPreviewWindowsTest` | real Windows MP3 device starts stops and releases file for exclusive reopen | 物理音频设备 | 未执行 |
| `DesktopFishPreviewWindowsTest` | independent audio JVM exits then another JVM reacquires root | 物理音频设备 | 未执行 |
| `DesktopVoicePauseWindowsTest` | native Windows position remains frozen on pause and advances on resume | 物理音频设备 | 未执行 |
| `WindowsBackgroundViewerTest` | background library double click viewer accepts native input and returns to unchanged library | 原生图形桌面/输入 | 未执行 |
| `WindowsBackgroundViewerTest` | real chat click opens repeated path in clicked message and navigates all occurrences | 原生图形桌面/输入 | 未执行 |
| `WindowsImageViewerChromeTest` | production viewer and owned screenshot have themed captions and real minimize maximize restore | 原生图形桌面/输入 | 未执行 |
| `WindowsImageWindowMinimumTest` | owned image dialog keeps its original minimum through native chrome and unhooks cleanly | 原生图形桌面/输入 | 未执行 |
| `WindowsNativeDialogIntegrationTest` | owner is chrome top-level Compose HWND and detach never returns stale handle | 原生图形桌面/输入 | 未执行 |
| `WindowsStudioEdgeDragTest` | maximized Studio outer edges remain stationary and restore remains resizable | 原生图形桌面/输入 | 未执行 |
| `WindowsWindowChromeTest` | real Compose HWND preserves native contracts and unhooks on dispose | 原生图形桌面/输入 | 未执行 |

## 后续人工组合流程

这些是待核场景，不是本次全部强制重测或新批准要求。协调端按用户可用时间分批交付；一次只核当前功能组。

1. **查看器与Studio窗口**：普通图片、背景/聊天图片、截图预览分别开窗，试前后翻页、最小化/最大化/还原及关闭；主窗与原选择保持。Studio辅助窗口关闭需经过原草稿离开语义；主窗最大化外沿不应被拖歪，还原后可调整大小。
2. **Fish音库与物理播放**：展开全文、滚动条/跳页、各层Escape与输入焦点；生成/下载/播放中Stop仍能点。已有录音暂停应停止进度，继续后前进；停止/关闭后文件和资料根可再次打开。无需为了此项新生成付费音频。
3. **输入/窗口/文件系统组合**：用当前实际Windows与输入法，缩放/缩窄并切换页面，已编辑文本、草稿、焦点及所有原操作应保留；文件选择器归属于正确主窗。实际遇到的问题按应用版本、动作、结果记录。
4. **用户原问题**：优先重测原长聊天导出、备份读回以及本版应用内更新。生产合并仍由用户选范围/凭据/冲突；不以普通假资料生命周期代验真实合并。

T23同卡多入口等待上游，SF10整条强制重生成与自由正文分段绑定继续暂缓。社区真实外部依赖最后；D-035/模型部署等未冻结范围不擅自变成1.0硬门槛。正式发布仍按216原路线、实际验收与授权处理。

## 2026-11-04 后继修复边界

288所列双层history/输入定位未公开；history4原88=87PASS+1FAIL，history5精确b7e313原件待。数字无效值失焦恢复、排队操作边界、展开取消以及实际选中备份media回读的后补不回填旧结果。原作者独立复审指向共享Medium费用/透明PNG来源归属/计数顺序，待精确作者delta与下游受影响验证；不把历史一般验收或Android APK通过当Desktop/Provider证据。
