# Desktop Windows 安装身份修复

问题来自实际 Desktop 构建配置：`desktopVersion.substringBefore('-')` 会让 preview.1 与 preview.2 都成为 Windows 0.1.0。没有把源码发现说成已经发生的真实安装丢失。

现在应用 SemVer 与显式 Windows installer version 分开；EXE/MSI（含 Release）依赖统一的身份验证任务。数字版本校验三段语法与 Windows 范围；提供 previous version 时必须严格递增。缺少 previous 的回执明确 UNSPECIFIED，不证明已发布版本的升级合法性。固定 Desktop 与 synthetic 两套产品名/upgrade UUID，per-user 安装配置；没有借用 Studio 产品或签名身份。

## 独立运行

- `verify_installer_identity.py`：6 项 Python unittest，0 failures/errors/skips，实际12次离线 Gradle 身份验证调用；负例按预期 exit1，合法四次验证 exit0。原始 RUNS、逐次日志、成功回执、构建文件与验证脚本快照均保留。不是6项 JVM/JUnit，更不是安装成功次数。
- 覆盖缺显式版本、四段/前导零/major与build越界、同版本/倒退、不同Preview的递增native版本、产品隔离、未知profile。
- 首次任务图验证 exit1：PowerShell 未引号的 dotted property 被拆为 `-PdesktopInstallerVersion=0` 与 `.1.1000`，不是产品验证器错误。powershell-argument-red.log 是独立 argv 复现。TASK_GRAPH_ARGUMENTS 记录第一次想传的参数；实际 shell 展开以此复现及该次原始日志为准。
- 用精确参数数组重试任务图 exit0；日志证明 packageMsi 和 packageReleaseExe 均有 verifyDesktopInstallerIdentity 依赖。`--dry-run` 没有创建/运行安装器，不能冒充 native installer 测试。

RAW_INDEX 绑定原始字节/SHA；没有用后续成功覆盖原失败。构建源快照与提交代码只有行尾归一化差异时单独在 SOURCE_IDENTITY 标记，不改原始证据。

## 未证明

没有运行 EXE/MSI、安装/卸载、真实 A→B、注册表、GUI、签名服务或 SecretStore。per-user 安装属性与固定产品身份仍须从真实包核对；单独 native profile 不自动隔离资料根。真实安装应使用新建专属资料/LOCALAPPDATA 与独立产品目标，遵守已有用户授权，保护日常安装。首次公开 Preview 版本及 native 序列在实际发布时核对，不从本合成 1000/1001 号推导公开标签。

最低手动 GitHub 更新发现路径不变，无下载/自动执行；本轮没有引入 Studio updater、TUF、信任根、DPAPI 配置或循环依赖。后续继续正式 P11 合并及可运行包验证。
