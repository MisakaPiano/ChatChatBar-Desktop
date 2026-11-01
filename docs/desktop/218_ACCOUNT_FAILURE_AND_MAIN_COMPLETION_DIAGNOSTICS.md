# 账户错误分类与主聊天结束原因

2026-11-01；FB20261031-04 的 NovelAI 账户分支与 FB20261031-05。源码检查点，不是新包、安装、真人验收或公开发行。现用／公开仍1224；SF07/08与217源码成果保留。

## 原意与实际缺口

账户请求失败必须保持未确认免费资格、保守成本与原凭据；刷新额度失败不等于所有生图操作都必须禁用。原 shared `NovelAiAccountService.fetchCancellable` 丢弃网络异常类别、HTTP状态与解析类别，Desktop再次统一提示。现用1224库的独立六场景拦截复现已确认分类丢失；未发真实网络、读取真实凭据或改日常程序。

主聊天完成元数据与正文可保存性是两件事：非空截断正文原本可保存，length会使自动生图跳过；不能为了修空正文而丢弃这些已有部分回复。旧 `DesktopRealChatRuntime` 在记录completion前先验证正文，reasoning-only＋length先抛空正文错误，任务诊断结束原因变为null。r01完整主聊天→TaskRuntime合成SSE测试实际RED：expected length, got null。

## 本次行为

- 共享账户同步／可取消读取均返回 `NovelAiAccountException`：固定kind与可选HTTPcode，区分401/403、429、5xx／其他HTTP、DNS、超时、安全连接、连接／其他网络、无效响应。不保留原异常／cause／suppressed close异常、响应正文、地址或Token；已分类异常也重新生成安全对象，避免关闭响应附带私密异常。取消不转为业务错误，响应照常关闭。
- Desktop只展示该安全类型的固定提示，未知错误仍用固定通用提示。原“免费资格未确认”、保守费用、凭据存在性准入、Stop和Key保存保持，不清空Key来解决读取失败。
- 主聊天先保存completion元数据，再检查正文。空正文＋length使用既有 `ModelResponseTruncatedException`；任务FAILED，提示截断、保留结束原因，不自动重试、调Token预算或改Prompt。非空length正文仍保存、任务保留既有完成语义，自动生图共享策略继续拒绝；blank stop／Done、显式用户Stop与一般取消仍按原规则。

没有改现有Prompt、持久Entity结构、原备份或真实用户资料。没有引入整条录音重生成或自由正文分段绑定。

## 验证与失败历史

原件目录 `I:/CCB-Desktop-SmallFixes-20261028/`，独立证据在 `I:/CCB-Desktop-Continuity/updates/`。原失败不覆盖。

| 轮次 | 结果与范围 |
| --- | --- |
| reasoning-completion r01 | 1项真实RED：主流结束length在任务中丢失。 |
| network-completion r02 | 新账户3项通过；Desktop39中38过，旧content-partial-stop因CLOSED失败。其他新length／UI分类通过，整轮FAIL。 |
| r03 | 共享6项通过（账户5＋图片请求1），Desktop39中38过；修夹具结构化子任务所有权后，CLOSED掩盖改为真实3秒等待首段超时。没有删除产品数据保护。 |
| r04 | 共享账户5通过，Desktop40中39过。受控服务器完整消费POST再保持SSE，新增非空length保全检查通过；原首段超时仍保留，整轮FAIL。 |
| r05 | 新诊断测试误用参数名，测试编译失败，无测试执行。 |
| r06 | 更正诊断参数，精确首个失败方法1/1通过，不扩大为7项或整组。 |
| r07 | 标准Gradle SUCCESS：Desktop三类40项新执行全通过（取消7、Studio14、Task19）；共享账户5复用相同输入缓存并读回5项PASS；0 fail/error/skip。不是45项全部新执行，不是完整CI。 |

停止夹具先结构化取消／等待所有子任务，再关闭container；SSE服务器先读完整POST再等待释放。保留原3秒等待和停止落盘断言，并在实际等待分支记录有界阶段／线程信息。此前首段超时的全部原因没有据此逐一重建，不能归因360或宣称仅flaky。独立冷／热运行与类加载I/O采样仅作辅助，不能替代标准r07；见 `20261101-stop-first-delta-diagnostic/RESULT.json` 与 `20261101-network-completion-r07-independent-review.json`。

账户5项经公开service入口的合成interceptor，覆盖同步／可取消状态与网络类别、正常usage、无效JSON、HTTP主失败叠加close私密异常的安全性；不调用provider、DNS或socket。Desktop确认不同提示、未确认免费资格和原生图准入。结束原因检查走真实合成HTTP/SSE→主聊天→TaskRuntime→持久消息／诊断状态，确认单次请求、无隐藏重试，非空length仍被共享自动生图策略拒绝。

## 后续和交付边界

c32ad911的SF07/08＋217已正常推送，GitHub CI `41254361132`已SUCCESS；该CI不是本文件新增源码的验证。新增源码完整CI、普通候选、隔离生命周期与用户本版验收继续分别记录，未用开发app-image代换现用1224。

FB04备份已有encrypted/passwordRequired/phase分类复用，只补具体残余通用路径；SF12继续处理Studio composition／refresh的账户identity安全边界，不改真正StartupGate启动拒绝，不静默fallback旧Key或清文件。SF14精确本机捕获仍未据离屏图登记通过。完成当前B0后依216补原P12实际缺口，社区最后。
