# 角色补全候选共享策略检查点

最新结果：bf189bada6e18c5cb811b7c634bf00e55e463a50 的完整CI54／41271041983已SUCCESS。shared1071项及Android578项通过，包含新增共享回归和原CharacterAutoFillService18项；Windows job亦成功。仅验证本页共享策略搬迁，不代表Desktop B2生成／研究／图像或候选UI已实现。1226仍冻结d94，不包含bf。

2026-11-01。当前1226交付仍冻结应用d94，后续B2独立源码不混入该包，不因B2未完成推迟当前候选。

按216下一批P12-02／03读取当前1.4.1权威服务及原测试后，先把角色补全的两个Serializable草稿DTO和纯候选策略移入sharedCore：宽容JSON解析／选择较完整候选、目标约束、空字段合并、人物ID／同名去重、locked-context与fill-target机器字段。原函数体按原样搬迁，Android公开facade继续代理。名称、字段与序列化类全名保持；原Android测试不删除。新增一个共享层回归覆盖具备声线、头像和Desktop关闭标志的既有人物，补空字段时原ID和配置保留，重复新增人物只加入一次。

本批不改PromptTemplates或既有Prompt文字，生成／研究／图像／取消／恢复／模型鉴权仍由原Android服务负责，Desktop生成与候选草稿UI仍待后续适配；不能据此宣布B2完成。其他七个研究／头像依赖相对147的差异不在这里自动升级。源码搬迁摘要在I盘`updates/20261101-character-fill-policy-extraction.json`。后续编译与回归只在云端执行；当前尚未验证。
