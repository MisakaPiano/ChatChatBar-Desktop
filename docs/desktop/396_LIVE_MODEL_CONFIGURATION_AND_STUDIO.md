# 模型配置快照与Studio即时刷新

2026-11-08。固定1.4.11的模型同步链路分批适配，本批源码准备完成、必要检查待执行，尚未交付。

原Studio模型列表只在controller/account/retry变化时冷读，认证只按所选ID、工具页与手动修订冷读。同一模型改名称、地址、能力后ID不变，保留中的Studio不会获知；仅改默认模型或本地免认证策略时，模型列表甚至可能完全相等。

- 共享`EffectiveModelConfiguration`沿用固定官方配置投影：同一输入模型/设置快照给出对话、辅助、多模态、默认与embedding视图。`observeConfiguration()`订阅现有三个仓库流，冷解析API复用同一投影。保留Desktop原preset来源/供应商能力、实际API密钥解析、明确辅助绑定和fallback规则，不改请求正文或已有Prompt。
- `controller.observeModels()`在IO上读取此流，进入原安全`DesktopStudioModelCatalog`前清空所有模型API密钥。只传观察者本地的递增配置修订，不把AppSettings或含凭据的领域快照放进UI。失败经原固定分类投影，取消继续抛出，重试重新订阅；不变成成功空目录。
- 实际Panel使用`rememberDesktopStudioModels`保留模型订阅；模型/默认/策略变化触发原安全认证冷读取。保留account/read/auth修订和auxiliary入口条件。显式选择删除后的ID保持原选择并显示不可用，只有取消绑定才跟随默认；不改草稿Prompt、正在运行请求的模型/账户快照。

必要检查使用合成模型和内联目录：共享流的增改删、默认/能力/embedding变化及捕获快照保持；实际保留的Studio组合owner和生产模型控件验证名称/地址更新、增加、删除后ID不被偷换、默认切换、模型列表完全相等时免认证策略仍刷新、UI无API密钥及零付费请求，并保留原安全仓库/恢复/模型设置/相邻Studio回归。

本批没有改变`ModelRepository`的逻辑键、保护凭据事务、观察提交、取消与资料准入。官方1.4.11另有串行落盘/发布和写后取消语义，须继续对照Desktop现有事务做定向适配与验证，不能因本批新增配置流就关闭该差异；其他长期消费者也继续逐项检查。395的模式停用/null-mode传输、314未完与315文字采用边界保持。394已完成检查的三项UI修复仍积累到同一后继普通候选，不重复宣布用户验收或公发。
