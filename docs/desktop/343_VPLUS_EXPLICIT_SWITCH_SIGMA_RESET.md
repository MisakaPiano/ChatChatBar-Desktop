# 343 V+显式开关重置导入的自定义Sigma

固定a678d71c及d21e的Studio开关在用户操作时同时设置varietyPlus并清空varietyPlusSigma。Desktop原入口只切布尔值：合法PNG导入自定义Sigma后，用户关再开仍沿用旧值，不能回到普通V+的默认Sigma。

只在唯一Studio开关的原draft transform加 `varietyPlusSigma=null`；共享导入／JSON／完整预设／历史与未操作时的customSigma继续保留，角色卡applyTo原本已清且不改，计价和序列化算法不改。沿现有持久撤销恢复原自定义值。

在已有实际Studio UI方法追加合法832×1216自定义Sigma PNG导入→无操作保持与仓库回读→点击关闭／开启清Sigma→最终请求回默认58→两步撤销恢复原37请求值及原PNG不变。原方法的草稿／预设／历史／模型邻接保留，仅定向此类；不重跑整个Studio。R75待原件，不提前记通过或旧运行反例。
