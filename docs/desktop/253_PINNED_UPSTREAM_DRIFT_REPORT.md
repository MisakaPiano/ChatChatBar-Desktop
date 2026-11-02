# P16 固定版本差异清单

最新增量：2ba39c显式云端入口R1／41381711267实际SUCCESS，原artifact21567B／4c4c4b9f…dec09d、CRC与两JSON及四输入hash独立核对；221项分类保持，不变成语义兼容。原正式监控执行对话已于12:23报告只追加prompt并二次peek一致：automation 6ac89edd86008191b3f58b37ddf29361及每天／condition_watch／时区／ACTIVE／关联保持，出现未处理且重要的新正式Release时按真实三SHA复用或提出报告请求，无新任务或同版本补跑。协调端已读真实completed回执；底层模型／通知字段未暴露且未提交，下次运行时间未暴露、delivery unknown，未来自然新版本端到端仍未验证。原执行者对话ID为6ac89e9b-a1a8-83e8-a73c-6baad3dbaad4，不能与automation ID混用。正式baseline仍141，报告不自动合并／发布。

2026-11-02：离线工具40ff/CI105的3个合成Python单元测试实际Ran3/OK，Linux1135/582与完整Windows主2492及独立检查均通过；实际日志和报告已核。它补 P16-02 的可复现清单首片；正式 Release 触发、自动生成报告与兼容结论编排仍未完成。

## 输入与结果的含义

`tools/desktop/upstream/pinned_drift.py` 要求显式的 baseline、target、Desktop 三份完整 commit SHA，直接读取本地 Git 对象。validated baseline 从指定 Desktop commit 的 `10_UPSTREAM_BASELINE.json` 中 `upstream` 读取，不能把历史 `upstreamObservation` 当当前版本。比较起点不一致会明确标记，不改清单。

raw NUL diff 保留增删、改名的双路径、mode 和 blob；Desktop tree 提供同路径、迁入 sharedCore 的 main/test package 路径及其他相同 blob 的候选。`docs/desktop/evidence/` 里的旧官方源码副本只列为归档引用，排除当前候选计数。其他 blob 相同也不能单凭它判定 Desktop 行为等价；无同名候选不等于功能缺失。

Memory/RAG、WorldBook、Prompt、聊天上下文、Provider、SaveSlot/持久化、媒体、Android UI、社区、Moments、测试和构建均有仅按路径的复核标记。高风险是“需优先审查的路径候选”，不是已确认的 Prompt 字面变动或行为风险；仍复用原手工影响核对、共享 owner 和240–244等已做适配。

报告绑定 raw diff、Desktop tree、baseline manifest 与报告工具自身 SHA256。读取不使用 replacement objects、外部 diff/textconv，不 fetch、不改源码／Prompt／baseline、不 merge 或发布；输出使用新文件，不覆盖既有结果。没有新增或调整原正式 Release 监控 `6ac89e9b-a1a8-83e8-a73c-6baad3dbaad4`。

## 当前只读实跑

已用固定141 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`、已知147 `b4fa75d296680f777be095f3121ad0c4db979f17` 和 Desktop `59a7b3cefd40f972315005993a3597d293963d50` 生成本地清单，不是在线最新版本断言。

实际221项（85新增／136修改）。先前全树相同 blob 的17项中有3项只在归档证据：MomentRepository、FishAudioCredentialStore、NovelAiCredentialStore；后补已剥离，保留14项当前路径/blob候选和3项归档参考。补齐共享测试路径后无路径或 blob 候选为67项，不能称67个功能未做。当前有66项高风险路径候选，分组标记互相重叠，不能相加当文件总数。R01–R04诊断结果保留，最终带工具hash的R05另存 I 盘，不上传本地报告或私有资料。

## 验证范围

`test_pinned_drift.py` 使用完全内联的合成 Git 仓库与 NUL 记录：制表符／换行／Unicode 改名、共享 main/test 迁移、Android 同路径不同 blob、归档副本不充当实现、缺少候选不充当功能缺失、显式SHA、现有输出拒绝、HEAD和原文件不变，以及原领域高风险标记。既有Linux平台CI的小型Python step已105实际通过；本机只生成固定Git对象清单，未运行测试、JVM或构建。

## 显式云端报告入口（后补，实际执行待核）

`desktop-upstream-report.yml` 为现有离线工具增加云端编排。`codex/upstream-report-*` 控制分支仅新增带固定kind及baselineSha／targetSha／desktopSha的请求文件，Desktop必须是其直接父提交，diff仅允许该请求文件；也提供同输入的手动dispatch。三份SHA须完整小写40位，官方地址固定为SaltyFishOTL/ChatChatBar，仅抓取两个指定commit，不执行上游代码或复用登录凭据。

临时runner调用现有CLI，输出新的清单及绑定实际workflow／Desktop／上游身份、字节数和SHA256的provenance。前后核对HEAD和tracked工作树不变，上传的仅两个公共Git身份／路径清单JSON。首次固定141→147云端结果仍待实际run，不用现有离线R05或单元测试冒认已运行。该入口尚未接入原正式Release监控，后续仍需将实际新正式版本观察与报告请求接线；不新增监控、自动合并、改Prompt或升级正式baseline，也不直接给兼容结论。
