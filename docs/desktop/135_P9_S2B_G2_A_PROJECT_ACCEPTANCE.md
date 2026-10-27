# P9-S2B-G2-A Project Acceptance

日期：2026-10-18。Project 独立裁定：**G2-A CLOSED / QUALIFIED ENGINEERING ACCEPTED**。本文件登记既有 Project 决定，不重新实施或验收；只接受独立、严格只读、无业务准入的 Inspector 及其限定验证，不授权生产 Writer/Recovery。

## Verified checkpoints and parent chain

| Checkpoint | Exact commit | Direct parent |
| --- | --- | --- |
| 初始 G2-A 源码及测试 | `e362eeac82864a990febc1bb3bb8619447da3f29` | `9908d4d8a16cb14860f27356e3ce83d4bdc95fd6` |
| 初始 G2-A 原始证据 | `732e0e0ea9fece5295a864d170e011001b10351e` | `e362eeac82864a990febc1bb3bb8619447da3f29` |
| R1 独立固定 Golden 测试 | `23acfda3b0aa19a0167dc747337561f42085ec17` | `732e0e0ea9fece5295a864d170e011001b10351e` |
| R1 原始验证证据 | `0e8c68fd03f8053a501d99bd78ecc77944b9c36c` | `23acfda3b0aa19a0167dc747337561f42085ec17` |

以上为真实单父线性链。初始源码 **7 文件 +1747/-0**（三个生产 Kotlin、四个测试/夹具文件）。R1 **22 文件 +129/-0**（一份既有测试及21份固定资源），不修改三个生产 Kotlin。R1 证据 **47 文件 +1611/-0**，此处按所有原始文件文本计行；证据目录将原始日志和 unified patch 声明为 binary 以保全字节，Git 默认 diffstat 仅统计其余文本，显示 +1263/-0。两种计数不表示文件或内容发生变化。

Project 已独立确认初始源码范围、7/7 源码身份、116/116 证据身份及最终原始 XML；R1 固定 Golden、原始验证及46/46 manifest 成员亦通过独立复审。本次只读核对 Git 父链和既有 manifest 身份，不重跑工程验证。

## Independent evidence and frozen Golden

| Evidence | Files / manifest members | Manifest SHA-256 |
| --- | --- | --- |
| [初始 G2-A](evidence/p9-s2b-g2-a-inspector-20261017/README.md) | 117 / 116 | `9ae8b2e6206445bd4b08f61fd83b042c3f96d9842edeadf9b1698488caebfb1d` |
| [R1 Golden 验证](evidence/p9-s2b-g2-a-r1-golden-20261017/README.md) | 47 / 46 | `3de6ab10b99faf98e58eb557f59ad5d8b1ad9d75864c4fd00d9fe47701a78e2a` |

各 evidence manifest 均不包含自身；原始日志/XML、源码/资源身份、完整补丁及 provenance 保留在各自独立目录，不覆盖候选 README、初始 RED 或阶段状态。

R1 固定 Golden **18/18 原始文件**：首代 domain、intent、old/stage、两个向量、JSONL、seal、marker、receipt、HEAD 及 live card/vector/source。资源 manifest 为5037 bytes，SHA-256 **`b4afa563dc58b9830188d0d8d76b1d08a42259cfba795aacebaa2d4d8ad09bde`**，由测试内字面常量固定。路径、字段、JSONL 行顺序、长度及原始字节哈希独立冻结；不由生产 JWire/Inspector 或当前待测输出即时生成 expected。一次性标准库 authoring 脚本仅为历史来源，不在测试运行时生成 oracle。

完整 Golden 被识别为 **COMPLETED_STRUCTURE_ONLY / businessAdmission=BLOCKED**，生产 authority 缺失不被夹具清除。单字节篡改及仅改变合法 JSON 空白均不能沿用旧原始哈希，独立清单与 Inspector 均拒绝；解析后逻辑相等不替代实际字节身份。

## Reviewed compilation, JUnit and zero writes

| Independent final run | Suites | Tests | Reported PASS | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 初始 G2-A 最终聚焦 | 10 | 179 | 177 | 0 | 0 | 2 |
| R1 最终三个 G2-A 类 | 3 | 46 | 45 | 0 | 0 | 1 |

两轮独立，**不得相加冒充单次验证**。R1 先行 wildcard 执行1 suite/4 tests（3项新 Golden 加1项既有测试）全部通过；之后完整三个类的46 tests才是本轮最终统计，不重复累计先行运行。

初始 Desktop main/test 曾实际编译 PASS，最终同输入任务的 UP-TO-DATE 与实际执行按原日志区分。R1 首轮 compileTestKotlin、processTestResources 实际执行 PASS；main/sharedCore/fixtures 为 UP-TO-DATE，第二轮测试编译 UP-TO-DATE、test 实际执行。两次 R1 offline Gradle exit code 均0，既有 JDK17、单 worker、低优先级、in-process、2GB heap，不 clean/rerun-tasks。七个旧回归类未在 R1 无理由重跑；Android、全量、GUI/EXE、打包和收费 API 未在该轮执行。

R1 最终 **69 份完整零写入清单、3 份跨进程清单**的 before/after 一致，保全文件/目录集合、大小、哈希及已记录身份；夹具建立和故意篡改先于各自检查基线，不冒充 Inspector 写入。六个自有子 JVM（3次检查/取消、3次独立锁重获）均正常退出、exit0/alive=false，无强杀或删锁。固定 Golden 在测试 JVM 执行；子 JVM 使用既有动态合成夹具，不冒充固定 Golden 的新进程验证。

初始编译 RED（测试公开函数返回内部类型、局部变量遮蔽命名空间）及子进程测试 RED（缺少 kotlin-test 依赖）均保留，不倒改为 PASS；早期失败子进程身份未完整归档的限制不补造。R1 无新增测试 RED；原始 unified patch 空白上下文引起的发布 diff-check 诊断亦保留，补丁/日志/XML 未通过裁剪改写取得通过。

初始 **2项 symlink JUnit SKIPPED** 与 R1 **1项 SKIPPED**属于各自运行；四次旧 G0 symlink fixture early-return 仍单独为 **NOT TESTED**，不算额外 skipped 或成功覆盖。资源数值仅为采样，不证明绝对峰值或 Chrome/NAI 前台响应通过。

## Accepted scope and remaining boundaries

仅接受已持有真实 root ownership 的独立只读 Inspector：有界路径/编码/闭集 JSON/JSONL、原始字节证据、old/stage、history/HEAD 图关系及可检查 live 数据的结构验证；有限结果和缺失证明保留，**所有结果（包括 NO_DOMAIN）businessAdmission 均为 BLOCKED**。完成结构不等于可信提交、可恢复、READY 或 VERIFIED_SAFE。

**不注册正式生产事务 schema，不创建生产事务域，不自动恢复、不写入、不调用或接入 Main。** 现有 G1B 对候选域的生产启动阻断保持原样，G0/G1A/G1B 的既有合同不变。

仍未证明或未验收：真实 symlink、真实8.3别名、持锁实际根替换、完整 Windows TOCTOU/ABA、所有 ACL 分支、实际 CloseHandle 失败、阻塞 I/O 物理绝对超时、断电耐久性、真实用户数据迁移/跨端恢复、完整 GUI/EXE、打包及全量回归。结构化取消/句柄收尾和有限操作预算不构成物理绝对超时保证。

**G2-B、G2-C、正式 Writer/Recovery、可信 root/config/Writer authority、多代生产交接、D-038 兼容及完整 P9 均未完成或未获后续实施授权。** 134 的结构 profile 不建立可信 authority，不授权 G0→G2 production dispatch、生产 Journal 或跨端导出。完整 F4、P8 Fish、P10/P11、Public Preview 及 D-036/D-037/D-038/D-040 状态不因本结案提升。

正式 validated upstream baseline 仍为 ChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。不修改134、Kotlin、Prompt、Package、Entity/schema、Feature Parity、baseline、历史证据或三个未跟踪 JPEG。

**Tests rerun: NO — reused Project-reviewed G2-A and R1 evidence.** 本轮仅三文档静态登记；不运行 Gradle、JUnit、GUI、EXE 或打包，不启动 G2-B/G2-C、不合并 desktop。

2026-10-18 D-CLOSE：用户已认可截至 `d8798d4275d415aad0db3604a1cf14c90a01293a` 的限定控制面审计结案。这是原三文档静态忠实性复审，不是新的工程验收；没有重跑测试、重新核验全部证据 manifest 或实施 Windows 实包验收，也不产生业务准入、集成或发布授权。审计范围、资料来源补正及遗留事项见 [136 限定控制面审计结案](136_LIMITED_CONTROL_AUDIT_CLOSEOUT_20261018.md)。本次 D-CLOSE 文档提交仍交 Project 复审实际 diff。

**G2-A ACCEPTANCE DOCUMENTED / PROJECT DOC DIFF REVIEW PASS**
