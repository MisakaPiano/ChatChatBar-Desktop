# Moments 任务安全准入合同

2026-10-22。DRAFT / 仅决策，不修改Task、Scheduler、Prompt或运行开关。固定源码 O16及旧POC来源见 [审计](UPSTREAM_145_146_147_IMPACT_AUDIT.md)。

## 事实与证据限制

旧合成POC：Moments关闭时原5Task不变；仅将momentsAutoStartConfirmed重置false不构成永久隔离；开启后原到期PENDING进入FAILED，RUNNING等既存状态没有因此全部恢复执行。重新核对已提交原始scheduler-after文件得到总24Task，其中19个新增未来PENDING。这是对旧概要的补充，不修改旧报告/原始数据。

MomentScheduler在1.4.5→1.4.7没有变化：runOnce检查momentsEnabled，之后ensureSchedules/processDueTasks/ensureSchedules；确认标记不是执行准入屏障。旧实验没有真实模型凭据，ServerSocket用于侦测禁止连接，不是成功生成的fake provider；未证明付费路径、冷启动后各状态或真实网络请求均安全。未知状态不能推测成不会执行。

## 三种方案

| 方案 | 保存与运行保证 | P11定位 | 代价/阻断 |
|---|---|---|---|
| C-A 独立封存，不回注运行Task | 原Task bytes、状态、关联全部保留；不进入Repository、scheduler或alarm | 推荐P11先实现的有限保全能力，需另批生产域 | 不能交付官方完整可运行全量恢复；Android再导出含这些Task时仍拒绝发布 |
| C-B 用户主动选择受限转换副本 | 原包保留；派生档关闭相关运行开关，Task仍原样 | 可选有限导出研究，不作为默认安全答案 | 将来打开Moments仍可能执行旧Task并新建未来任务；不是永久安全或原样运行语义 |
| C-C 接收端持久隔离+显式激活 | Task导入后隔离且启动前强制屏障，逐项批准才可执行 | 需要接收端协作；建议P13/独立跨平台项目 | 固定官方1.4.5/6/7没有该合同，Desktop单方面旁路标记不能约束官方应用 |

不得把Task改为SKIPPED/FAILED、删除、清时间或丢postId后称为无损。C-A不能偷偷只导出Posts然后宣称全量；有意排除Task仅能是用户批准、列明排除项的有限子集，原Task仍封存。

## C-A准入细则

sealed Task只能由只读验证/导出规划器访问；不能注册实体store、发出repository事件、恢复alarm或触发AI/NovelAI。schema/新字段包含未知行为或依赖则不晋升验证态。Post-Task/card/session/sender图按来源命名空间保持；运行对象与封存对象不得共享可变记录。

原包取回需显式标示“原始任务可能在目标启用后执行”，不能把原包取回按钮称安全迁移完成。未来正式接管应复制为候选运行实体，通过接收端屏障验证后激活，原封存不修改。异常不触发补偿生成或自动重调度。

## C-B转换边界

必须单独确认：目标版本、修改的每个开关、未修改的Task集合、未来重新启用的风险、原包保留位置及哈希。派生档记录来源SHA、转换器版本、变更清单和新SHA；不能伪造源app版本以绕校验。导入后还需确认接收端实际保留关闭状态；选项合并模式可能使目标既有开关继续开启，不能只检查输出JSON。

试验要覆盖全量替换与选择性三模式、旧目标已开启、冷启动、手动开关、未来时钟推进、进程中断；真实付费端点不在默认测试中。任何路径不能保证首次导入不调度，则拒绝该模式。后续用户主动启用的风险仍应明确，不能宣称测试一次即永久屏障。

## C-C必要接收端合同

拟议来源标记包括sourceArchiveDigest/importBatch/sourceTaskId/admissionVersion，另有持久QUARANTINED/APPROVED/ACTIVE/REVOKED准入记录。不可仅在Desktop封存清单写标记而让官方忽略。启动前、恢复alarm前、repository扫描和每次任务领取时均检查准入；未识别版本fail-closed。

确认需展示角色/会话、状态、到期时间、输出/费用类别、关联Post与重复执行风险；账号/端点变化使批准失效。历史RUNNING不能自动当安全可重试，须有稳定execution token和领取/完成事务，避免崩溃重复付费。激活、租约、Task状态与结果引用必须可恢复；撤销后尚未提交的网络工作如何取消需另订合同，不能承诺追回已经发生的费用。

恢复整根和root switch保留隔离态；回滚不能让旧批准重新授权不同任务。新调度产生的未来Task同样受来源/策略准入，不能仅封住导入的旧PENDING而放行ensureSchedules生成的新任务。需要改变官方Android行为或使用明确标识的协作构建；不能把它称固定官方APK兼容通过。

## 验收与决策

每个状态PENDING/RUNNING/COMPLETED/FAILED/SKIPPED、未知状态、past/future时间、关联Post存在/缺失、重复导入、开启/关闭、冷启动、clock advance、cancel/restart都需合成矩阵。观察原raw和图不变、所有scheduler/AI/图片入口零执行、无新增alarm/任务；不能只检查网络日志无连接。

批准C-A只批准保全，不自动满足D-038全量运行迁移。C-B需要用户接受有限行为变更；C-C需接收端能力与P13范围批准。推荐当前保持生产整包拒绝，完成设计审查后只实施获批切片。全部新运行结果PENDING_TEST，本轮未执行。
