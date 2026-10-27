# Post-P8 Chat UX — Project 独立复审交接

状态：**A / B / C 已实现工程候选；集中 Windows 人工 Gate 待执行。** 不提升 P8/Fish parity，不代表发布验收。

固定基线 `f5d9015deb0c485461d42231767dd26060750d96`。正式上游仍为 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

| 切片 | 源码 checkpoint | 独立证据 checkpoint | 状态 |
|---|---|---|---|
| A 三行图文菜单、共享右键/…、左右工具栏、User 右对齐 | `68bed580c4e073ba980b3993e7fa0653fbc9745e` | `a7511e3fe9f6fa007bbc51908ff62a15f0af1269` | 聚焦 30/30 PASS；后续集成改进见最终源码 |
| B 单行 Deck、角色音色、真实暂停续播、跨 User 已有语音、通知 | `c05bb0bce3148ea02ad3d4b4de82a4d264d78724` | `6044011e0fd6757e393bf309a051c33d328c256a` | 聚焦 50/50 PASS；后续所有权及旁白约束见最终源码 |
| C 独立截图选择/渲染、预览、PNG 新文件保存 | `b423e7c286e87258bc4892b7fae59eae63630ef0` | `3b68cf5a6a2eb18b3c2d823320324657f4a61e73` | 聚焦 30/30 PASS；保留原 Escape RED |
| 最终集成、焦点、旁白及迟到播放所有权 | `22d043335f7b1433d3b484ce9abcf5c74d9aba61` | 本目录的独立 evidence commit | 最终 Desktop 18 suites /124 tests，124 PASS，0 FAIL，0 SKIPPED |

各直接 parent 与实际 diffstat 见 `checkpoints.json`，最终集成 parent 是 `3b68cf5a6a2eb18b3c2d823320324657f4a61e73`。最终 evidence parent 是 `22d043335f7b1433d3b484ce9abcf5c74d9aba61`；其自身 SHA 以 Git 提交对象为准，不在自身文件内自引用。

共享语音 Golden 7 suites/34 项为既有缓存结果：final02 FROM-CACHE，final03 UP-TO-DATE；**不冒充新执行**。Desktop final03 确实执行。Windows 原生静音合成 MP3 暂停位置 111ms，暂停中保持，续播增至 152ms；同 alias 恢复并释放文件句柄，**不冒充用户听觉确认**。JPEG 旋转/尺寸单测 1/1 PASS。

精确源码 Windows 包已构建、复制及重新核验 **212/212** 文件，manifest SHA-256 `2c132878d966f72cf1fa1e47a69c76c4cd47fdbb8304f333a53044f7dfff8ddd`。

`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-chat-ux-20261020\windows-acceptance-22d04333\runtime-image\`

**本轮没有启动该包、fake 或人工 runner，也没有播种新人工数据根。** 后续人工启动前需新隔离夹具、真实安全凭据 hydrate 检查、loopback readiness 和一次性锁，再由用户准备确认；不得在真实数据上直接启动。旧 R3 包 212 文件本轮只读重核通过，原数据/证据不变。

集中人工清单见 `WINDOWS_MANUAL_GATE.md`：宽窄菜单三行、外部/Escape/键盘；Deck 与角色音色显示条件；真正暂停后续播、二次停止、跨 User 已有语音；片段/整条/多选截图、预览/PNG；用户正常关闭后核验 Launcher/JVM、根锁、fake/TLS/临时音频。**所有新人工项均 NOT TESTED，没有用户 PASS。**

本轮所有编译/测试 RED 和原 XML 留存，详情见 README；历史 cmd.exe/Robot RED、R6 INCONCLUSIVE、symlink SKIPPED、WIN-AUTO-001、WIN-BRAND-001 和 Studio 凭据异常恢复问题均不被覆盖。未跑 full bounded、Robot 或历史鼠标诊断。真实 Fish GET0/TTS0，付费模型0；合成 mock 的逐用例计数断言保留，未建立整个回归集的统一请求总计，不伪造总数。

源文件 diff-check PASS；正式 Prompt、Entity/schema、共享模块、语音仓库、P11、CURRENT/parity/baseline 未修改。四份用户文件只查路径状态，保持 KEEP/UNREAD/UNMODIFIED/UNSTAGED。没有 merge、发布或自动推进后续阶段。

**STOP，交 Project 审查精确 Git diff、原始证据及安排集中人工 Gate。**
