# 同名采用的身份与依赖冲突

旧反例：合成 PC 卡 `pc-card/Shared` 与导入卡 `phone-card/ shared ` 选择采用导入后，实际 Plan 仍输出 phone-card，而保留电脑会话仍依赖 pc-card。R90/run42370104615 控制源329bbf／普通5fb，原12486B，SHA256 `e46a6b028feb62e3bdafaed5e2f07b11668cecdaeadd651780c722e4d38efd56`，唯一实际方法首身份断言失败（line25）；后半所属卡域、歧义与双采用未到，不算多条运行反例。新 modal 本地错误提示已随该次实际编译，但未作新 UI 运行验收。

修复方向与当前实现：

- 编号命中优先于名称，不连带删除其他同名对象。无编号命中且多个名称候选时保留原件，提供保留／副本，不能采用并删除多个目标。
- 唯一名称目标的保留电脑／跳过／采用导入携带显式 typed alias，采用只删除一个目标；入方引用映射到保留的目标身份。BOTH 继续原完整 fork，不改名字或 Prompt 文本。
- 先按当前角色卡选择派生有效会话冲突；未定卡选择只延后依赖它的同名会话，真正编号／跨归属碰撞照常显示。改变选择只清除目标或可用选项已变化的旧决定，提交再次重算。
- RootPreparation 核对 alias 两侧原记录；被明确匹配的内嵌人物／文档／条目按编号或唯一名称绑定，保留明确玩家人物关联。冲突、歧义或重新绑定不猜测覆盖。
- 未选媒体仍按原始导入 session key 读取范围，再用映射后归属匹配 PC 媒体。PC 原记录不改归属。历史语音原件不因新正文自动可播；仅确有新会话身份时，以有界 version2 inert custody 索引保存显式会话 alias 和双方原字节，旧 version1 默认字节不变。

R91：普通180c1011／控制a88f244d／run42372257537，原ZIP22775B，SHA256 `694dc40895841ffea8e4537db9e4618ffdbcff0249ddf0a8e3fc27341ec48877`。已核CRC/source/全部6XML及空stderr，28完整方法通过：Named4、Preview12、Plan9、原凭据1、语音保全1（原8+新1内部案例）、后处理1。凭据方法只覆盖原同ID，不能借作新不同ID归属证明。旧R90首身份断言失败继续保留。

后继补查：未匹配的新卡／世界书也需避开电脑内嵌身份，BOTH已声明的重写只执行一次；空白displayTitleOverride按上游回退title，仅改变冲突投影；新不同ID同名模型的四种选择需实际保护写入及默认引用验证。R92原12128B/SHA256 `bd83269de6d0889ac31447c1389af36ebe94d586e62ffda54ec2e1c8b5cfe284` 已核CRC/source/全部2XML及空stderr，Named5与新不同ID凭据1共6完整方法通过；四选择均到实际新根保护写入与default引用，原fake store／备份字节不变，新根不激活。新碰撞夹具实测人物ID，文档／world entry为同一typed helper源码路径，不谎称另有独立运行案例。选择面板在检查／丢弃源的busy阶段只保留禁用投影，非busy缺Preview仍明确拒绝。

当前工作仅合成资料和代码，未操作真实根、自动合并或发布。不能把 R87 的26项或 R89 的2项借作本次验证，也不以此提升整个647行 Engine。315 其余 Prompt 差异仍未采纳。

## 独立身份与记忆两项差异

1a的Identity97行／Memory210行完整增量已逐项对应Desktop typed identity与独立历史proof图。原162 Identity／MemoryProofs／GraphRemapperTest逐blob相同；GraphRemapper只新增叙述模式保留，原9方法仅复用原身份／记忆分支，0新跑、不声称新字段也由旧测试证明。程序重算父级source/coverage证明，只有明确子coverage占位替换，正式摘要与叶source-turn原文保留；有界迭代算法代替Android scratch spool，singleton embedding命名空间有意独立。两项限定EQUIVALENT，不覆盖整个Engine／Catalog。详见 OWNER_MERGE_IDENTITY_MEMORY_TWO_DELTAS_REUSE.json。

R92普通0a43ef46／控制8bd9fc16／run42373290759，详见 OWNER_NAMED_ADOPTION_R92_BOUNDARY_FINAL_REVIEW.json。R91的28与R92的6有重叠Named4，不加总为34个独立测试。所查同名绑定边界工程通过，整个Engine／Catalog仍须完整对应，真实用户合并和发布验收另计。
