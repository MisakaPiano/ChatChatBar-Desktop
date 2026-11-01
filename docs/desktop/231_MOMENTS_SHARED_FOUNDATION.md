# P13 朋友圈共享基础

2026-11-01，在WorldBook／D042新源CI63运行期间独立准备。原 `MomentEntities`、`MomentRepository`、`MomentPolicy` 从Android目录移到sharedCore，FQN、序列化、文件内容与Git blob逐字相同；原Android调用与测试继续使用同一权威。这三处Android原路径缺失是计划内源码移动，不是360隔离，应从新路径读取，不能把旧文件恢复成重复类。

原 `MomentScheduler.ensureSchedules` 及其活跃会话选择提到共享 `MomentSchedulePreparation`。只把repository调用改为同签名的suspend端口；逆向替换机械比对原完整body相等。保持原48小时、2–13小时默认、4／18日限、UTC计数去重、7天范围、原每卡guard、顺序保存与失败传播。Android仍拥有原mutex、全局开关判断、旧alarm取消、到期任务执行与日志；没有把这些Android平台调用带入shared。

新增三个内联共享回归覆盖多卡日限／重复准备、停用与不活跃卡历史保留、保存失败后的已提交前缀及重试；待未来新源云端，不能由CI63覆盖。本提交不接Desktop时间线、生成、调度、启动、凭据或真实资料根，不算P13完成。默认关闭及原Prompt没有修改。

后续Desktop接线要由用户明确开启／恢复本地运行。保留升级或导入的历史和PENDING任务，不因首次加载或旧全局enabled就自动执行。应用关闭不生成，不新增系统开机／后台任务；原判定、最近3条完整消息、长记忆、失败占位与checkpoint、重试、发文与图片元数据继续复用。
