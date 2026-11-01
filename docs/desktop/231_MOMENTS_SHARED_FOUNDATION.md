# P13 朋友圈共享基础

后续Desktop运行层源码已接但尚待云端：`DesktopMomentsController`／`DesktopMomentGeneration`复用Core、编译长记忆、原模型默认参数和流式／非流式模式；懒加载挂AppContainer关闭排水。`DesktopMomentStore`在请求前持久化task→postId与占位，阶段检查点先保存；中断RUNNING不重置PENDING，显式对账只关联已存在的最终动态，不发AI请求。编辑／点赞／删除与生成提交串行，晚到结果不能恢复已删除动态。`DesktopMomentImageCleanup`在删除／图片落盘前写精确回执，退出normal准入后复用全实体引用保护的exclusive清理；失败回执保留。新增共享真实合成HTTP与桌面持久／取消／production cleaner用例待云端。当前没有桌面时间线入口；本地调度暂为每进程明确恢复，尚不当作完整持久启动恢复等价，后续继续根绑定且隔离导入世代的本机准入。

2026-11-01，在WorldBook／D042新源CI63运行期间独立准备。原 `MomentEntities`、`MomentRepository`、`MomentPolicy` 从Android目录移到sharedCore，FQN、序列化、文件内容与Git blob逐字相同；原Android调用与测试继续使用同一权威。这三处Android原路径缺失是计划内源码移动，不是360隔离，应从新路径读取，不能把旧文件恢复成重复类。

原 `MomentScheduler.ensureSchedules` 及其活跃会话选择提到共享 `MomentSchedulePreparation`。只把repository调用改为同签名的suspend端口；逆向替换机械比对原完整body相等。保持原48小时、2–13小时默认、4／18日限、UTC计数去重、7天范围、原每卡guard、顺序保存与失败传播。Android仍拥有原mutex、全局开关判断、旧alarm取消、到期任务执行与日志；没有把这些Android平台调用带入shared。

新增三个内联共享回归覆盖多卡日限／重复准备、停用与不活跃卡历史保留、保存失败后的已提交前缀及重试；待未来新源云端，不能由CI63覆盖。本提交不接Desktop时间线、生成、调度、启动、凭据或真实资料根，不算P13完成。默认关闭及原Prompt没有修改。

后续Desktop接线要由用户明确开启／恢复本地运行。保留升级或导入的历史和PENDING任务，不因首次加载或旧全局enabled就自动执行。应用关闭不生成，不新增系统开机／后台任务；原判定、最近3条完整消息、长记忆、失败占位与checkpoint、重试、发文与图片元数据继续复用。

后续原文／类型提取（本地待云端）：五个 `MOMENT_*` literal、五个公开builder及其原私有summary helper移入唯一 `MomentPromptAuthority`，完整源块保持不变，Android保留原符号／目录并新增fingerprint源；两个NovelAI输入facade原体保留，不转移NAI Prompt ownership。`MomentGenerationModels` 提取原decision／draft／result／checkpoint／debug／progress类型，原生成服务的调用流程仍留Android。

固定141与所选官方147差异已另核：Entities／Policy相同，五个Moment literal也相同；147 Repo支持改作者、Generation移除judge／shouldPost准入并使用卡片默认生图设置，Scheduler有维护访问包装。本轮机械提取当前141源，没有自动改调用流程或批量提升baseline；后续采用对应增量时按精确源与受影响回归记录，不能把“原文相同”当作整个147功能兼容。

后续提取 `MomentGenerationCore`／`MomentTextGateway`：保留141判断、文案、图片设计、两次外层图片尝试和检查点流程，图片所有者使用惰性端口；Android facade继续在公开调用外包原withAiTaskRun并映射原任务类型。四个共享定向回归检查最近3条完整消息／编译长记忆／无开场白、纯文字不打开图片端口、完成阶段续作、失败取消只保留已完成检查点及141拒绝准入；尚待新源云端验证，不由CI64代验。Desktop运行与UI仍未接线。新反馈会话删除／升级后代理连接优先处理，本任务保留未交付。

CI64的863df8ae源Android582／shared1096实际通过，包含未来记录准备3项和WorldBook取消修正；Windows结果另行读回。上述生成Core不在863源内。
