# 336 现代朋友圈备份与凭据组合

## 实际缺口与最小修复

`DesktopFullBackupService.exportModern` 已按现代格式冻结和筛选源数据，但包含凭据时再次调用 `DesktopBackupPolicy.entity` 没有传 `modern`，落入旧 v1 读取规则。含 `moment_posts`／`moment_tasks` 的合法现代快照因此在凭据捕获前被拒绝为 `MOMENTS_UNSUPPORTED`；同时错误使用旧 JSON 大小预算。普通无凭据现代导出不经过此分支。

只在这一次实际调用传递 `modern = modern`。不改变 Policy 默认值、不放宽旧格式、未知字段、图引用或凭据保护；凭据选择、快照、发布失败清理和密码归零边界保留。

## 必要检查

新增 `DesktopFullBackupServiceTest.modern credentials include retained Moments without changing source or protected values`，使用合成受保护存储与真实 Service：同一选中朋友圈／卡／会话数据，在明文／加密及包含／不含凭据四种组合下导出并通过实际 Source 回读，检查原 Moment、任务、scope、凭据选择、源实体字节、原保护值和临时快照清理；旧 v1 仍拒绝朋友圈。

R62／42355938042原12704B／SHAf39cd82e…31350，同一实际方法在包含凭据分支报MOMENTS_UNSUPPORTED，精确到Policy90→Service63；无凭据组合此前已完成。R63／42355978991原9273B／SHA9f6f60ef…42eb1，完整单方法PASS、0FAIL/ERROR/SKIP；明文／加密和包含／不含四组合、原数据与保护值、快照清理和旧v1拒绝均执行，CRC/source/全部XML已核。详见 `OWNER_MODERN_MOMENT_CREDENTIAL_EXPORT_FINAL_REVIEW.json`，不把一个方法的四组合当四个新测试。既有 Service 取消／发布失败清理和 SecretStore 保护测试不因这一次参数透传而全套重跑。本轮无本机 JVM、真实资料或凭据操作，无新安装包或公开发布。
