# F4-B4 手动格式修复交互层 — Project 限定工程验收

日期：2026-10-15。裁定来源：Project 独立 GitHub review 及本轮明确授权。

**F4-B4 CLOSED / QUALIFIED ENGINEERING ACCEPTED。** 这是手动交互层的限定工程结案，不是生产派生安全准入、完整 F4 parity、Windows 人工验收或正式集成。

## 真实提交与验证范围

唯一工作分支：`feature/phase8-fish-audio`。结案前 local、tracking 与 live origin 均为 `e37b94495b01bdbc5b4162ce7ada06b42942fa1a`；工作区及暂存区 clean。live `origin/desktop` 为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本轮不更新本地 desktop。

| 阶段 | 完整提交 | 范围与结果 |
| --- | --- | --- |
| B4 初始源码 | `686aea0c35ae09159a44c3b10c623a1abff44668` | 15 文件，+658/-65 |
| B4 初始证据 | `955d3bf05d039bc022e33e4b54db06cfc7c9732e` | Desktop 17 suites / 191 tests；SharedCore 6 suites / 71 tests；全部 PASS |
| B4 R1 修复 | `e9901a0ce7a3f764d74d3d83f0518a18e9bb8640` | COMMIT_UNCERTAIN 菜单安全准入窄修复 |
| B4 R1 测试与证据 | `e37b94495b01bdbc5b4162ce7ada06b42942fa1a` | 最终 4 suites / 52 tests PASS，0 failures / errors / skips；其中离屏 Compose 6/6 |

初始限定测试合计 23 suites / 262 tests PASS，但 Project 首轮源码审查发现状态栏隐藏恢复入口时，菜单仍可能提供恢复或再次修复，因此当时裁定 HOLD。不能将该次绿色测试解释为已覆盖 R1 问题。

R1 统一状态栏、菜单和 Controller 准入；未核对的 COMMIT_UNCERTAIN 保留警告，禁止直接恢复、再次修复及过期菜单点击。显式重新加载核对必须严格读取持久化消息，并与当前显示的完整快照、候选身份及 notice 相符；普通刷新、失败路径读回和过期核对均不能解除保护。核对不清除原 commit 的原因、阶段、attempted message 或 durable evidence，也不宣称索引/预览事务已完整成功或回滚。重新准入仍受 CAS 和派生数据门禁约束。

Project 独立核实了上述提交父链、R1 九个文件的 **9/9 SHA-256**、源码逻辑及四份最终原始 JUnit XML。Codex 本轮只记录 Project 裁定，没有重新运行测试。

## 证据与历史保全

- 初始证据：[f4-b4-manual-repair-20261014/README.md](evidence/f4-b4-manual-repair-20261014/README.md)。
- R1 证据：[f4-b4-r1-menu-admission-20261015/README.md](evidence/f4-b4-r1-menu-admission-20261015/README.md)，包含最终 XML、首轮失败 XML、编译错误摘录、源码 manifest、执行与资源记录。
- 初始 B4 RED、R1 编译 RED 和 R1 测试 RED 均保持原始历史身份；不覆盖日志、XML、失败记录或旧 README 的候选 PENDING 状态。本文件记录后续 Project 决定。
- B3B 已验收边界继续见 [115_F4_B3B_R1_PROJECT_ACCEPTANCE.md](115_F4_B3B_R1_PROJECT_ACCEPTANCE.md)。

## 限定结案及未完成门禁

手动整条 Assistant 修复、状态与内存预览、显式 Stop、有效 notice 恢复入口、模型设置、任务身份/准入，以及 R1 未确认提交保护获得限定工程验收。**生产派生安全 gate 仍默认 UNKNOWN → REJECTED**，不得为开放按钮注入 VERIFIED_SAFE。

自动格式修复、完整用户可用性、生产派生协调、Windows GUI 人工验收、全量回归、打包及实际付费 Provider：本次验收未完成/NOT RUN。离屏 Compose PASS 不等于 Windows 人工验收；历史 FrameArrived BLOCKED 保留。阻塞文件 I/O 无物理绝对超时保证，安全关闭与数据根所有权要求不变。

`13_FEATURE_PARITY.md` 的完整格式修复 PENDING 不提升为 EXACT/EQUIVALENT。Fish S2A、S2B–S7、P9/P10、P11、Public Preview Gate 均不因本次获得新的完成声明。正式 validated upstream baseline 仍是 ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；D-036 Prompt 与 D-039 恢复点保护不变；不合并 desktop，不同步 upstream。

下一项 [117_F4_B5A_DERIVED_DATA_SOURCE_AUDIT.md](117_F4_B5A_DERIVED_DATA_SOURCE_AUDIT.md) 是源码审计及待审实施合同，不能代替生产安全验收。
