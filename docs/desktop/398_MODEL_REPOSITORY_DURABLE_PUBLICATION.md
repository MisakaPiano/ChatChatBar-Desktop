# 模型仓库串行落盘与发布

2026-11-08。1.4.11 非 Prompt 适配的下一批源码；本页必要工程检查已闭合，未交付。

现有模型仓库只串行初始化，普通写入与随后缓存刷新可以相互穿插；持久化成功后的取消也可能跳过刷新。模型选择器即使订阅配置流，仍可能收到旧值。固定官方 87d1 的目标是串行持久化与发布，但其整段 NonCancellable 写入不能直接代替 Desktop 的凭据事务和取消边界。

共享 ModelRepository 现在让初始化及模型、旧 embedding、单例 embedding、预置批次、迁移使用同一个状态互斥量。普通资料准入先于互斥量，已有 structured lease 从落盘一直保留到发布完成。私有持久化 helper 避免预置与迁移重入非重入互斥量。读取实际模型、逻辑文件键、观察提交回调和受保护凭据策略仍沿用原 owner。

等待互斥量及原持久化仍可取消；仅 finally 中的实际落盘状态读取/发布使用 NonCancellable。写前取消不变成写入。部分预置批次失败也发布已提交部分。发布失败不能伪装成成功；已有写入异常保留，另一次发布异常附在 suppressed。未改写 Desktop credentialTransaction 的密钥回滚；发布重新经过原 hydrate，不能用入参覆盖实际保护存储结果。

锁序审查：DesktopApiAccountController 先初始化模型，再取得凭据互斥量；释放凭据互斥量后才调用 refreshModelsFromStorage。modelExists 使用严格原始实体读取，不回调状态锁。模板导入在持久化返回后刷新。新 state mutex 不能移到资料准入外，也不能在凭据互斥量内调用公开仓库变更。

新增内联合成检查：共享 9 项涵盖写前/写后取消、观察提交异常、并发发布顺序、部分预置失败、并发复制命名、删除取消、发布二次失败及 embedding 的增删取消；Desktop 2 项使用实际资料协调器/凭据策略验证 maintenance pending 下的已准入发布，以及实际密钥回滚后的缓存/JSON 保真。不读取预置资产，不执行收费请求或真实资料操作。

R06 run42473916694 / control bb503892 / normal593 原52861B、SHA256 0f8c80604cc6b3abd025444870c8ccfdebcaddbfb0e6e5ec2987ade5c012c05d，38唯一成员/CRC/82源blob已核。官方29段下载完整compiler并核长度和双摘要成功。共享10类94方法为92通过/2失败/零error、skip；本类9方法中7通过，两失败为新测试把跨IO协程恢复后的异常副本当原实例比较，及 suppressed 中同类副本的身份比较。沿用仓库既有 cause-chain 断言方式，仍要求精确原异常身份、IOException 类型、唯一 suppressed 与实际落盘/发布断言，不改生产异常机制。Desktop生产已编译，但 retained-choices 新夹具用了接口而构造器需要实际 DesktopPresetModelCatalogSource，导致测试编译失败、零Desktop方法。已改为完全内联合成 manifest/catalog 的真实 source；原失败报告保留，后继仅补跑本类和未执行Desktop组。

396/397 的 Desktop 定向验证目前等待官方编译器依赖恢复。R05 run42469308395 原 1685B / SHA256 de615b1637b7bc85f0060cb21c50f0e88d69f774a7d7f85d699e024d1e41e48b 已保存：可选 repo1 预取与原 Gradle 都 HTTP504，零 XML/方法，显示恢复记录保留。停止无新事实的同类重试。原 shared40 只证明 321/8ac 的旧仓库截点；本次改动后相关仓库与解析检查需要定向重跑，不能沿用为当前源码通过。

其他长期模型消费者、模式停用/null 传输与 314/315 仍待推进。公开仍 1246 / 签名28，原15项人工范围通过、自然下一次应用内下载待验和真实迁移边界保持；没有新包、公开发布、本机 JVM 或日常程序根写入。

最新实际结果：R07原163857B/ef473c01、106唯一/CRC/77源blob已核，本页共享9及Desktop实际保护策略/maintenance准入2方法完整通过，零F/E/S，闭合R06两异常身份夹具失败；其他共享85源/测试blob未变按原R06结果复用。R09补验原26294B/5b05a77b、24唯一/CRC/52源blob与17完整方法通过；源2ba的共享/桌面生产树及复用测试逐一等价核对，分源94共享/285Desktop定向闭合。R08 first-range超时零执行保留；R09总600s/最多两波只补缺段，完全固定长度及双摘要才入Gradle，缓存仅公开官方compiler文件且使用前重核。未改变业务依赖仓库/版本，不在本机下载或运行compiler，也不把缓存保存成功称跨OS命中。
