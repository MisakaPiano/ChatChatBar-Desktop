# Project Design Review 交接

2026-10-22。READ-ONLY SOURCE AUDIT + DOCS-ONLY PRODUCTION CONTRACT，完成设计后停止；不是实现批准或P11验收签署。

## 控制点与diff范围

起点 `bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5`，分支 `feature/phase8-fish-audio`。最终文档提交SHA由本文件所在Git提交标识，并在交付消息提供；不在提交内写自引用SHA。diff仅本目录以下八份新Markdown，不修改生产源码、skills、CURRENT、历史报告或原始证据：

1. [版本影响审计与固定源码索引](UPSTREAM_145_146_147_IMPACT_AUDIT.md)
2. [v1/v2兼容矩阵](BACKUP_V1_V2_COMPAT_MATRIX.md)
3. [封存、所有权与恢复合同](MOMENTS_STORAGE_RECOVERY_CONTRACT_DRAFT.md)
4. [Task准入合同](MOMENTS_TASK_ACTIVATION_CONTRACT_DRAFT.md)
5. [严格只新增合并合同](ADD_ONLY_MERGE_CONTRACT_DRAFT.md)
6. [实施切片和负例矩阵](IMPLEMENTATION_AND_TEST_SLICES.md)
7. [待批准决定](DECISIONS_REQUIRED.md)
8. 本交接。

## 最重要的发现

- 当前Desktop仍只接受v1和code<=84；新版普通导出走v2，需真正适配scope及恢复语义，不能只放宽版本。Desktop v1进入1.4.7仅格式门槛SOURCE_PROVEN_ONLY，完整运行互通PENDING_TEST。
- 官方ADD_COPIES为主要实体创建副本，但全局设置、选中偏好和部分单例仍可改变；不能叫严格“只合并、不覆盖”。同ID模型alias的first归一化也不是全字段无损证明。
- 官方新版有按类省略偏好，仍可能因依赖自动带入Model/Embedding密钥；不能称统一无凭据包。现有整包安全拒绝保持。
- 1.4.7路径修复保留声明相对路径，与canonical物理来源分离；没有取消路径安全验证。该版本对应测试仅阅读，本轮未执行。
- Moments历史媒体helper未单列metadata独立路径；封存必须覆盖三处引用。Task准入仍无永久屏障：原POC启用后除旧PENDING变FAILED，还新增19个未来PENDING。新补充保留旧报告原件。

## 推荐与阻断

C-A独立只读封存是推荐候选；它能保全Task，不能承诺官方运行回导无自动执行。C-B主动关闭开关的派生副本是有限转换，后来启用仍有风险；C-C需接收端持久隔离及显式激活，通常等待P13/官方协作。

生产封存需要新Desktop持久化域、资源拥有权、完整备份/快照/root switch/Portable/恢复事务接入。旧二进制不认识新标记的防写问题尚未解决，是投产阻断，不是加一个version字段即可。

严格合并需独立图映射、旧目标不变校验、统一事务和单例冲突策略；秘密C1有限排除与C2加密迁移分别审批。当前外来秘密、未理解Moments和未知类型继续拒绝；不实施部分成功恢复。

## 验证与本轮没有做的事

本轮验证仅文档范围、固定Git对象/链接和来源事实核对：八份文件，89条固定源码链接均可解析到本地Git对象，文档相对链接存在；旧合成scheduler-after共24Task（19 PENDING、2 FAILED、各1 COMPLETED/RUNNING/SKIPPED）。这不是重新运行调度。没有功能测试GREEN，没有Windows GUI、模拟器或物理设备操作，没有真实用户档案/凭据/SecretStore访问。没有构建、安装、Prompt修改、生产schema注册或调度行为变更。四份既有用户文件继续KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED；已有实验__pycache__不纳入提交。

官方固定来源：1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`；1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d`；1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17`；路径修复 `81f5449803698a179549530c404e11018f9a62f8`。正式baseline仍1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；D-038目标不变。

## 请Project独立审查

审查R01–R10，尤其新域/旧版防写、任务回导与严格合并单例边界；决定是否另发S0/S1生产准备授权，不因文档提交自动进入实施。Moments可保全、凭据可有限排除、严格合并可设计、完整D-038已通过是四个不同命题，最后一项目前BLOCKED。

P11、Post-P8 HOLD、Public Preview Gate A–G保持原状态；不合并desktop、不更新baseline、不公开发布。真机执行条件/真实数据权限尚未取得，本轮NOT TESTED。到此停止在Project Design Review Gate。
