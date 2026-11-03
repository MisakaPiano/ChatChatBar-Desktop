# 280 — 1235 工程范围与 Beta 交叉场景表

本表对应冻结应用 `9f5150db3acbd9e39333c41523101fada4910cb9`，不是正式1.0通过声明，也不是增加Preview发布前置门槛。先复用已有原件；只有出现新变化、失败或具体未知项时补相应检查。当前日常H版本为未操作/未知；1235测试Preview及签名17已按本版明确授权公开，真人验收仍待。

## 已核原件与仍未知的结论

| 场景 | 已有实际证据 | 尚未由该证据证明 | 下一步范围 |
| --- | --- | --- | --- |
| 工程回归 | 138原XML：Desktop主2582=2563PASS+19SKIP，shared1192/Android586，独立proxy5/NTFS7/native37/TUF14/exit4 | 全部Windows环境、真实Provider、真实资料用户验收 | 复用本轮原件，不因文档整理重跑 |
| 普通安装/升级/重开/冷暖文件投递 | R24普通1225→1235，忙时安装拒绝、pointer/manifest保留，正常关闭且Job排空；独立中文入口548文件、自己的数据/锁和外部bootstrap保留 | 真实生产资料/凭据、特殊数据根、所有外部可执行后代、用户桌面操作 | 用户自行更新后按1235最短清单检查版本/原会话/草稿 |
| 更新器 | R26冻结公开1233真实client，15→17发现，177247185B下载和二验，说明一致、root1不变 | 匿名公网发现/真实应用内确认安装 | 本版公开后核新公开通道；不重签、不代装 |
| 原生窗口/音频 | 138有16个图形桌面、3个物理音频case明确SKIP；R24普通程序窗生命周期另有实际证据 | 这些19项的具体交互；不能把普通窗启动或离屏图当它们通过 | 见下表按功能组合人工步骤，后续具备环境时只补相关项 |
| 长聊天导出 | 1225的67项PNG/PDF/Word报错和单条未测保留；1226有界/分页/可编辑Word工程修复 | 原真实长聊天导出成功、真实资源与阅读体验 | 用户在本版选择原聊天导出，失败时记录格式/数量/阶段，不先归因为360或容量 |
| 玩家/人物/会话布局 | 既有状态归属、启停、拖宽/收起、草稿和Scene回归；1217人工条目及旧版暂定通过按原版本保留 | 本版全部DPI/IME/宽窄交叉操作 | 不覆盖旧验收结果；本版只记实际版本及具体通过项 |
| AI创作/媒体/用量 | 138实际生产controller、合成HTTP、取消/过期草稿/原撤销；辅助模型参数及反推8+4通过 | 真实NAI/Fish/LLM计费、所有模型精确分词、全部Provider兼容 | 可先检查配置/候选界面；真实付费调用由用户决定，不要求为验配置发付费请求 |
| Token容量 | 用户显式容量与实际wire数字来源；固定分词器14行样本比较已执行 | 字符粗估等于tokenizer、误差上界、可用余额或账单精度 | 保留估算标签；273实测偏差不是精度PASS |
| 备份/合并/恢复 | 1224真实大包隔离往返及用户GUI冲突预览；后续typed错误/取消/恢复回归 | 用户最终生产合并、跨账户/物理断电/未覆盖旧快照 | 只由用户选凭据与冲突并提交；不自动合并，不为测试破坏资料 |
| 完整预设/Prompt互换 | 原生产预览/显式应用/撤销、资源custody与Desktop/Fork配对工程证据 | 系统剪贴板、实际外部安装版；官方Android完整Prompt读取或往返 | Studio消费CCB接口；不新增手机Fork安装前提 |
| 社区 | 134工程接线与R20实际Main/注册表限定结果；2026-11-03 04:01JST官方GET仍enabled:false | 真实Discord/OAuth/浏览器调度/前台焦点/远端写入/全部后代退出 | 仅挂具体真实联调；其他工作照常，不新建替代社区 |
| 平台/长期使用 | 云端Windows NTFS/普通生命周期、Linux共享逻辑；各人工旧反馈保留 | 用户Win10/11全组合、DPI、输入法、多屏、真实长程与断电 | 每次结果记录OS/应用版本/场景；没有结果写未测，不要求重新启用退役VM |

## 138原件明确跳过的19项

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
