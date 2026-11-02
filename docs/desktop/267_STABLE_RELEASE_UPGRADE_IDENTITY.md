# 267 — Preview 到正式版的普通升级身份

2026-11-02：原P18升级基建正在实现，尚未在精确新源运行，不属于117/118、公开1233或真人验收结论。保留真实稳定SemVer、单调build、Windows installer版本、TUF revision、reader与trust-root版本的独立含义。

## 旧入口兼容

原固定根入口先验证current目录的 `x.y.z-preview.N-commit12` 形状，随后才委托当前目录manager；普通安装保留已有根EXE。新manifest因此显式携带实际 `version`、`buildNumber`、`channel`、`storageVersion`，内部目录仍为 `<SemVer core>-preview.<build>-<commit12>`。例如显示1.0.0/build1235/stable，对应内部1.0.0-preview.1235目录；界面、JAR build identity、READY、Release/tag/asset和签名display均为真实1.0.0，不把Preview标签伪装成正式版。

原无新字段manifest只接受精确旧0.1.0-preview.N，可从N派生build。该旧语法一旦显式给build也必须等于N，确保必要bridge仍被已发布旧客户端接受。后续正式版/beta/rc的display与build独立；两条序轴都不得下降，不允许用更大build覆盖相同/更低语义版本。相同包修复仍需原精确manifest收据及源身份，不放宽为同名包。

新manager用显式storage构造目录、真实display/source确认READY。稳定版manifest损坏时，修复不能再从内部目录猜真实发布类型，因此stage记录独立版本identity与sha seal，包含产品、格式1、完整源、display/build/channel/storage及原manifest收据hash。单独修改、未知格式或不匹配收据拒绝；同一可信stage输入和原manifest hash证明的半记录可精确补完缺失seal，反向半对/改动记录保留拒绝。完整清理只删已核同版本identity/seal，不扫描未知文件。

## 更新、签名与包装

`version_identity.py`统一Python生产者/读取者的版本标签、channel、build和storage规则。标签沿用现有Kotlin与HTTP读取者共同支持的SemVer子集：总长5–80、三段core各至多九位且无前导零、预发布标识各至多20字符，纯数字预发布标识不得前导零且不超过Int64；不接受`+metadata`，build由独立字段携带。`attach_runtime`、NSIS producer和离线metadata producer逐项核对，不更改data reader或trust-root。Gradle/候选workflow显式传递 `desktopBuildNumber`；旧Preview请求仍可由N推导，正式版必须明确build。Windows numeric installer版本继续独立受原范围约束。

新客户端按用户已选版本选择对应签名通道，仍严格核对产品/平台/source/hash/reader/location/所选display及两序轴。旧Preview的 `cache/metadata` rollback状态保留；stable使用独立 `metadata-stable`，避免把一个通道的timestamp序号当作另一个通道的回滚。下载仍共享原严格stage目录，重新签名认证后二次验包；不存在跨通道跳过签名或凭Release hash引入新信任。

安装版启动检查没有预选display时，bridge先认证stable通道，再按需要检查原Preview通道。只有从未见过stable timestamp且该timestamp明确返回404，才显示尚未发布stable并继续Preview；已认证stable在两序轴都不新时也可继续Preview。stable其他成员缺失、签名/到期失败，或曾有stable状态后timestamp消失均保留失败，不能用Preview成功掩盖。显式选择版本、下载和安装前二验继续绑定对应通道与精确版本。

必须先通过旧客户端可接受的bridge，再验证真实stable及nextstable。冻结旧客户端在未装bridge时无法被新源码追溯改造：正式发行后若GitHub发现直接选中了新stable，而旧helper只懂Preview，会明确拒绝；不能声称这些旧二进制已自动学会新协议。bridge应先交付并说明，遗留用户还可通过普通安装包保留原根/资料升级，不能要求清资料或把手动Repair伪称普通应用内过渡。发布和安装均按实际现有授权，不在此文件产生授权。

## 必要证据分层

- 标准库identity三例只证明字符串/序轴/反例，协调端已有轻量只读检查，不是签名、安装或普通升级通过。
- 原真实python-tuf增加五个合成例：bridge→stable→nextstable的分通道counter/完整下载二验，反向序轴/source/storage/channel拒绝，真实离线stable metadata生产和installed配置/原bootstrap绑定，启动发现stable/首次未发布与损坏通道区分，以及旧stable不阻挡更新Preview。原八例保留；这些例与新增native例均待新精确源云端执行。
- 原native生命周期新增四例：精确frozen1229旧root及旧manager→bridge→真实stable→失败next回退与成功next；stable同包repair/损坏descriptor拒绝；storage/build/channel/换行与降版拒绝；同包半记录重试及反向半对/改动拒绝。使用冻结公开1233的旧OpenChatBarFile源码，通过stable目录alias实际冷投递合成文件并核原参数/原件/请求收据释放；根三资源字节保持。该混合历史根资源、tiny Fake、冷投递与既有暖投递各按实际证据，不冒称完整真实JAR/GUI或系统Shell关联验收。
- 还需新精确源普通安装包链路、旧client实际桥接、真实JAR升级/重开/失败回退、数据canary/root资源/READY与入口、必要人工验收。当前均不得以纯版本例或旧包证据代验。

无日常H安装、真实凭据写入或新公开；不在本机编译/JVM/运行native或渲染。
