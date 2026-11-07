# 历史朋友圈头像缺失的备份链

当前为工程修复与合成验证，未交付新安装包，不改变公开 1244/db7/sign26 或真人验收结论。

已追到实际 `DesktopFullBackupService → Mapping.export`、`DestinationPlan.resource`、`Mapping.restoreFiles` 三处缺口。只读检查与原文保全已有支持，不能据此称实际导出、合并已支持。

仅现代 code85–88、`moment_posts` 顶层 `senderAvatar` 的已缺失图片引用可以保留缺失状态。外部绝对路径只核对缺失与父目录身份，不读取外部文件字节。已有但无法读取、目录、链接／reparse、正文／版本／元数据图片及角色卡头像仍拒绝。旧84朋友圈拒绝不变。

修复让缺失引用采用稳定的扁平传输身份，不创建文件、不清空头像、不借另一侧同名图片。还原和再次导出保留该身份；预览仅在这个精确缺失字段上归一签名，避免程序改路径制造重复导入冲突。导出成功弹窗、文件检查与合并预览显示缺失记录数量。

R82，普通源 `c42228e562e93ce3f74392755ab493a10e52b192`，控制源 `08b41f2d59006f409d23f49385c2598260ac7116`，run42367848471。原12970B，SHA256 `38e15cc7a7ebe6ba5dee48b039124f84a8585eaea0173933307a21eaffc8e257`，artifact13572089576，已核ZIP CRC/source/唯一XML：实际 Service 方法运行后在旧导出 FILES 阶段失败，非初始化／编译失败。旧包装未透出底层具体资源异常，不把堆栈推断冒充更细运行证据。

初步修复 `dabcbce5` 的 R83/controlf1bf4e97/run42368212330 在验：新实际链与 Controller 警告两个方法、原 Moment 版本备份、DestinationPlan 和原实际凭据组合方法。后继另补同一 incoming 的重复导入断言，结果待原件核实；不提前写 PASS。

同批发现的重名缺口另以 R84/control01345933/run42368323116 运行旧比较逻辑。后继比较同类别非空名称的 trim＋NFKC＋Locale.ROOT 小写形式；保留原名称、ID、正文与所有显式选择，不比较朋友圈发布者名称，不自动重命名或选用手机侧。

教程静态文案补充隐私 PNG、Shift 历史范围选择和动态编辑的实际 Desktop 操作。沿用既有教程阅读器／功能证据，随必要编译覆盖，不为文字写镜像测试。整个上游追齐仍未完成，315 其他 Prompt 差异仍未采纳。

R83／R84后续核实：原件分别318B／319B，均0XML，compileTestKotlin因合成GeneratedImageMetadata漏填negativePrompt停止，非产品运行失败或NFKC旧反例。7b6483ac仅补两测试字段。R85同源错误已请求取消，终态待核。R86普通诊断源6a02ee80从e259仅补同两字段，生产及归一测试不变，控制e3962e08；R87普通源7b6483ac／控制57fb1011覆盖新两avatar、Preview及原定受影响范围。结果仍待原件。

## 最终定向结果

历史头像实际Service/Plan/还原/重开/再导出/重复导入及Controller缺失提示已修；R87原21680B/42ac999a…e5bd3核CRC/source/5XML/空stderr，26方法完整PASS(2新avatar+9Plan+12Preview+2版本+1凭据)。R86原12064B/c81bbb4b…5c3c4旧NFKC实际首对重名未识别FAIL，同方法R87完整PASS；R82导出FILES反例、R83/R84编译夹具0body和R85终态cancelled独立保留。十Import/Models完整delta分源复用0新跑，历史头像14行policy限域映射，314仍false。下一精确缺口是Moment新选sender保存时重查当前卡/人物，原发布者snapshot保留。四身份/全部ID/T11T23/公开1244/db7/sign26及真人原分组不变，315其他未采纳，无本机JVM/真实资料/新包/公发。

R86普通源6a02仅从e259补两个夹具字段，production与NFKC方法未改；只支持旧首对Alice/alice未识别，后续样本未到达。R87同一方法完整经过五类重名、空白、跨类别、发布者排除和歧义/BOTH保留原文。R87 avatar主方法相对R82仅多重复导入尾段，四组合全走完；没有把它算为四个新方法。详见 OWNER_HISTORICAL_AVATAR_AND_NAMES_R87_FINAL_REVIEW.json。
