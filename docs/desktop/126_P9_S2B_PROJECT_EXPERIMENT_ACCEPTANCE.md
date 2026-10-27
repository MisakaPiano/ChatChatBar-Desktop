# P9-S2B J Isolated Transaction Experiment — Project Acceptance

日期：2026-10-16。Project 独立裁定：**P9-S2B J ISOLATED TRANSACTION EXPERIMENT — QUALIFIED EXPERIMENT ACCEPTED**。本文件登记既有决定，不重新运行实验，不授权生产写入。

## Reviewed checkpoints and evidence

| Checkpoint | Exact commit | Project verification |
| --- | --- | --- |
| 初始测试源码 | `728ed212fcf70aff82a011443a4e7046ecd4f9a5` | 3/3 测试源码 Git blob |
| 初始证据 | `6fb37e3a9ce9349436865d13db655e1f0628d630` | 28/28 证据哈希；最终6 suites/79 tests PASS；32次子JVM、12次受控中断 |
| R1 测试源码 | `2368280a54f206b999cce6ba57eee8d7fb69be3a` | 3/3 Git blob／SHA-256；完成态live重证与第二笔publish提前拒绝 |
| R1 证据 | `484894a15afbe126083f5d919084d7b61989b33e` | 24/24证据SHA-256、7/7原始JUnit XML哈希及计数；最终6 suites/82 tests PASS，0 failures/errors/skips；57次子JVM，12次中断、45次正常退出，57/57退出 |

证据：`evidence/p9-s2b-transaction-prototype-20261016/` 与 `evidence/p9-s2b-r1-completed-20261016/`。初始进程锁重获RED、R1前HOLD、原始XML／日志／资源采样及候选当时的README全部保留，不覆盖或重分类。各轮测试与进程数量独立，不合并成一轮执行声明。

## Qualified scope

只接受固定合成临时数据根中的J测试专用协议：已覆盖进程中断点、真实文件替换、旧／其他数据字节保全、恢复幂等、完成收据与live来源／card／vector重证，以及单事务协议在任何新副作用前拒绝第二次publish。异常完成态保留文件并关闭业务门禁，不自动回滚、清理或发起Embedding。

原型的严格完成态相等及单事务限制不是正式产品规则。生产必须允许经过验证的后续角色编辑、文档变化和多次重建，并独立设计历史收据退役及写入身份交接。

**不包括：**生产事务／正式journal schema、多代真实索引更新、真实App启动恢复、实际Embedding API、断电耐久性、真实用户数据、全量回归、Windows GUI、打包。JVM进程中断不等于断电；目录fsync／Windows电源故障保证仍UNKNOWN。

## Current supersession and next gate

本裁定在上述限定范围内取代125及两份候选证据中的PROJECT REVIEW PENDING／HOLD；旧文本保持原时点历史。125的生产阻断仍有效，生产接入及启动恢复须另行设计、独立审查和再次授权。

`13_FEATURE_PARITY.md` 的完整P9／格式修复PENDING不提升。正式validated baseline仍为ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；D-036不变；D-038仅以官方1.4.5作为全应用备份的选择性兼容目标。Fish、P10、P11、D-040及Public Preview不获得新的完成声明。
