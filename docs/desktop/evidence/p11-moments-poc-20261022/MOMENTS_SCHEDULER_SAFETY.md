# POC-C：回导任务调度风险

关闭条件下结论：FEASIBLE_WITH_PROVEN_SCOPE。仅靠确认标志就保证未来启用不执行的假设：INFEASIBLE_WITH_EVIDENCE。生产完整回导安全准入：BLOCKED。

[真实官方测试输出](raw/20261022-180229-622-scheduler/instrumentation-output.log)、[结构化结果](raw/20261022-180236-184-pull/artifacts/scheduler.json)、[运行后的合成数据](raw/20261022-180236-184-pull/artifacts/scheduler-after/)。

实验通过反射调用未修改的 AppBackupService.resetDeviceConfirmations，执行官方 PreparedSeal、RestoreTransaction.schedule/bootstrap/commit，并从独立临时 root 构建正式 Repository 与 Scheduler。偏好回调只确认没有秘密，不向真实偏好/SecretStore 写入。不是完整应用冷启动恢复。

| 问题 | 运行事实与结论 |
|---|---|
| Moments 关闭时是否执行 | 真正 runOnce 后全部 Task 对象不变。仅限关闭保持成立期间 |
| 重置确认是否足够 | momentsAutoStartConfirmed 变 false；源码 runOnce 的总开关是 momentsEnabled，不以确认标志阻止生成 |
| 后来启用的旧 PENDING | 设置开启后真正 runOnce 处理 scheduledAt=1 的旧任务；无模型准入使其转 FAILED，证明旧任务会被接管 |
| RUNNING 如何处理 | 本次 RUNNING 保持 RUNNING；不能假设官方会自动安全恢复所有中断任务 |
| 配置可用模型时 | 源码 processTask 进入 RUNNING/生成服务；真实请求分支 NOT TESTED，本轮没有为覆盖测试配置 Provider |
| 初始化和设置入口 | ChatBarApp startup 与 ManageViewModel settings 均会 kick；此项有固定源码证据，完整冷启动行为 NOT TESTED |

生成配置明确关闭，images=false，模型准入在运行前断言关闭；loopback ServerSocket 是零连接哨兵，不是成功返回模型结果的 fake Provider。非 loopback IPv4/IPv6 出口拒绝规则另行留证。零连接仅证明该哨兵没有收到连接，不冒充全网络抓包。没有付费模型、TTS、NovelAI 请求。

已证明的正式机制只有全局 momentsEnabled=false 下不调度；没有发现保留 PENDING 状态同时逐项冻结待确认的正式机制。把任务改 SKIPPED、删除任务或强行改 settings 都属于语义变化，不能称原样无损回导。

建议下一阶段合同（DRAFT，未批准实施）：在导入前显示待执行任务数量、到期情况、全局开关与开启后可能执行的风险；原包保持不变。要求官方接收侧提供可持久化的导入隔离/显式任务准入确认，且启动路径先检查再调度；或在用户单独授权下提供清楚标为“全局关闭 Moments 的转换副本”，同时保留原档案。后者仍不能保障用户未来启用时不会处理旧任务。

若要求“字段原样 + 任意未来开启也不会执行”，固定官方 1.4.5 无本轮已证明的实现方式。必须由 Project/用户批准新的接收侧策略、持久化与事务合同，并验证重启、取消、并发、回滚及未来启用。现阶段继续整包拒绝的生产策略不变。
