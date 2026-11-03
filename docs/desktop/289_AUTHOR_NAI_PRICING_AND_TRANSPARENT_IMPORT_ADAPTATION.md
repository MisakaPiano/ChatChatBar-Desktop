# 上游 Medium 定价与透明导入修复的 Desktop 适配

精确参考原作者 production `892b478b17d3bf0943e779d3f14108b750f3355c`，后继 `f52eeb95b3034ee236afbef25c7766ca15976e9b` 仅测试修正。原326项通过是原作者证据，不代替 Desktop 适配验证。

- 复用独立单图费用函数：Medium 使用未取整23步基值 /1.75，再模型倍率/强度/最终取整与最低2；Generate/Enhance 共用，原画布、免费资格、样本数、额外费用与本地待确认余额机制保留。Desktop 的 isMedium/requestModel 与原作者 API 拼写不同，按实际类型适配。
- 透明 PNG 后缀不凭 hint 推定归属，默认保留原文；实际元数据导入对话出现显式选择，只有交给开关管理才撤回一处精确第一 chunk 后缀。原 PNG/通用解析与 Enhance actual caption 保留。导入继续通过原 controller、normal gate 与持久全局历史。
- Token 与普通 wire 共用逗号归一化→透明→V5 引号展开→Medium UC 准备。既有 Prompt 字面值不改；Medium 角色负面词在准备投影中清空，不改原草稿。
- Desktop Medium sampler 原已走固定说明分支，没有 Android CbSelect 单选自动写回问题，不复制 Android UI 补丁。

复用原作者独立 inline 费用、透明归属与 wire fixtures，新增实际 Desktop 选项→PNG导入→落盘→重开→全局undo/redo回归，检查源图片字节未改。精确云端测试已按下方范围通过；不调用真实 Provider，不操作真实根，不新增公开授权。


ui12/41673515555 shared原35=34PASS+1FAIL/0SKIP，Desktop步骤未执行。唯一失败是原作者fixture期望关闭High透明hint显式false；Desktop原wire关闭选项时省略该hint，basePrompt和v4 caption此前断言已通过。仅把适配fixture改为断言hint不存在及params_version=3，生产请求不改；Desktop新Scene同断言保留旧High wire。后继仅重测该方法及尚未执行的Desktop三suite，34已绿原件复用。

ui13/41674730728原6PASS/0SKIP：共享原失败方法1与实际Desktop元数据/Variety+/V5三方法共5，通过；ui12原34PASS与1FAIL保留。实际三张合成组件PNG已目视核对，不冒称全页或真人Demo。完整156应用304的Desktop主2621PASS、共享1233原件与Linux终结成功进一步覆盖本适配；155的3个无关失败原件保留并由304精确闭合。

1237/R31最终包和R32旧client签名19校验均通过，仍为未公开草稿。该证据证明合成数据、费用函数和wire契约，不证明真实Provider账单、真实用户资料或所有模型精确分词；正式validated141不因此提升为整147。
