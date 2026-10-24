# Codex continuous delivery bootstrap

执行原则：[01_PROJECT_INSTRUCTIONS.md](01_PROJECT_INSTRUCTIONS.md)，D-046（2026-10-25 JST）。当前目标是连续完成 Preview 必要工程、七项 Gate 与发布；无需逐 slice 等 Project 审批。

## 接手

1. 读取 root AGENTS.md、01、Source Map、10_UPSTREAM_BASELINE.json、21_CURRENT_STATE.md；再定向读取相关官方 Skill、parity/compat 和源码。
2. 核对真实 branch/HEAD、工作区和已有任务，保留未提交工作。沿用适用的活动分支；没有已有开发分支时从 verified desktop 创建 feature。
3. 可用时 fetch origin/upstream；记录 validated baseline 和观察到的漂移，按实际影响处理。网络不可用不阻止与之无关的本地工作。
4. 历史 CURRENT、任务合同的阶段示例与 NOT AUTHORIZED/HOLD/STOP 不是最新执行许可。执行权限按用户最新指令及 01，技术结论按实际源码/证据。

## 连续实现

自行拆解必要依赖，跨 phase 完成代码、平台接线、迁移、测试、打包和发布准备；可提交多个恢复检查点，但不要在每个检查点停等 Project。已授权测试/API/密码/真实存档按任务需要使用，不重复索取许可。必要人工验收直接给用户一份简短步骤及产物，继续其余工程。

按影响做最小充分验证，复用未失效证据；确切失败修复后只重跑相关项。不默认重新做完整审计或测试矩阵。缺失环境覆盖记 NOT_TESTED，不冒称已测；具体已知破坏应修复或隔离后继续独立工作。

## 不变量

只写 Fork，不写官方 upstream；master 仅镜像。保留 Package/Entity/Prompt 区分、共享逻辑、原子写入、数据恢复和既有 Prompt ownership。正式 baseline/parity 不能靠执行授权自动提升。

真实 API/密码/.cbsave/.cbbackup/.cbback 及解包内容不上 GitHub，包括提交、日志、附件、Actions、artifact、Release。使用本地忽略目录和受保护存储；提交前检查。

## 进度与交接

报告已完成、当前工作、下一步、确切阻塞与需用户做的事。交付给出实际 commit/产物、必要验证、已知问题及剩余人工步骤。工程完成与 Project 后置审计分开；不自行宣称 Project PASS，也不因尚无 Project 签字停止后续工作。
