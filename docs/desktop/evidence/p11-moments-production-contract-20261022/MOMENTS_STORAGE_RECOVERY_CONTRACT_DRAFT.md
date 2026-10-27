# Moments 持久化、资源和恢复合同

2026-10-22。DRAFT — NOT APPROVED IMPLEMENTATION。依据 [固定源码审计](UPSTREAM_145_146_147_IMPACT_AUDIT.md) O04–06/O13/O15–19、D04–14 和旧 [封存设计](../p11-migration-design-20261022/P11_MOMENTS_OPAQUE_PRESERVATION_DRAFT.md)。仅设计，不新增生产schema或Entity。

## B1：三个不同的保存对象

1. **来源档案**：记录来源说明、接收时间、文件长度、全文件SHA-256、声明版本和加密属性。归档原bytes不改变；这些字段不证明来源APK真实性。保留在有用户访问控制的独立保全位置，不自动放入普通备份。
2. **已验证封存副本**：独立只读域，包含原Entity bytes、资源bytes、引用清单、目标兼容与验证报告。不注册到MomentRepository，不产生任务或UI运行数据。
3. **未来P13正式Entity**：必须经另批映射、来源准入和迁移事务激活；本轮不创建。

拟议容器 `format=desktop.moments.preservation`, `schemaVersion=1`，只是合同命名。包含 sourceDigest、声明版本、validatorVersion、capabilities、records(type/id/rawLength/rawSha)、resources(length/sha/storageKey)、typedReferences、validationState、transactionId 和 exportConstraints。封存原字段含未知字段、JSON空白、字段顺序和原路径字面值全部保留；解释结果写旁路清单，不能重新序列化替代raw。

命名空间为 `(sourceArchiveSha256, recordType, sourceId)`。同包同type重复ID/重复路径拒绝；跨包同ID分别封存，不自动合并。相同来源全SHA和内容清单完全一致可明确跳过；仅相同Post ID/正文不得去重。不同来源相同资源可在未来明确只读共享，但第一实现推荐独立复制，避免硬链接写穿。

状态为 RECEIVED_UNVERIFIED → VERIFIED_SEALED 或 REJECTED_RETAINED。未验证物只能保留原档案，不能出现在“可恢复Moments”计数、正式备份成功范围或官方再导出中。未来字段若无法证明没有新增资源/依赖/执行含义，保留bytes但拒绝晋升sealed；不能用“未知字段原样留着”替代验证。

来源可能有凭据：本轮仍整包拒绝非空外来凭据，不建立秘密档案库。未来原档案长期托管需独立批准访问权限、加密、保留期限与备份排除；明文秘密不能自动复制进普通Desktop根、支持包或日志。文件名也不可用凭据值构造。密码仅在显式解密会话内持有；不得把解密后的临时内容作为正常保全副本。

空间上限必须覆盖压缩/解压bytes、实体数、JSON深度、每资源长度、总配额、保留原包+prepared+snapshot+rollback的峰值。具体产品数值待批准；未确定不得视为无限制。Prepare先估算并留余量，写时再强制预算；不足则拒绝并保留旧状态和原档，不自动删历史腾空间。

## B2：引用图和资源所有权

| 关系 | 验证及保全要求 |
|---|---|
| Post/Task ID、Task.postId | 分别保留type身份；非空引用有明确解析目标或官方允许的历史缺失分类 |
| CharacterCard、Session、sender | 不借用同ID live对象证明来源关系；来源快照/缺失状态旁路保存；姓名不是身份 |
| senderAvatar / imagePath / generatedImageMetadata.imagePath | 三处独立枚举；同文件可共享blob但各边保留；metadata路径可不同于正文图 |
| 卡片与Post共享媒体 | 封存自有副本与live GC无关；删卡/会话/原图片后仍有完整bytes |
| 历史缺头像 | 仅固定官方允许的顶层senderAvatar例外；记录MISSING_AT_SOURCE及原引用，不能伪造图片或扩大到正文图 |
| 远程URL/content/file路径 | 不访问网络/content provider或任意本机路径；只接受受控包内可映射资源；不具备可导出证明则拒绝晋升 |

原路径作为数据保存，绝不作为写入目的地。storageKey按安全生成身份定位，拒绝绝对路径/盘符/UNC/ADS、`..`、编码绕过、大小写/Unicode碰撞、重复ZIP路径、symlink/junction越界和解压炸弹。长度和SHA在复制后重验；1.4.7声明路径/物理路径分离可作为参考，不能去掉containment检查。

封存所有权清单与live pending_deletions隔离，旧删除队列不能命中封存blob；封存删除须用户明确操作及事务更新引用计数，GC只删除本域已提交、零引用、无rollback/snapshot持有的对象。计数可由完整清单重建，不能单靠易损计数器。升级保留旧manifest和raw，验证新索引后原子切换；升级失败仍可只读取回原档。

O15 helper只覆盖两个路径；封存三路径合同不能直接复用它作为完整验证器。未来P13接管时生成新live拥有权，不把封存文件变成可写共享；验证成功仍保留来源记录与可撤销映射。

## B3：同一逻辑事务纳入新域

必须接入 D08 coordinator 的独占维护、D09正式root ownership和D06/D07启动前恢复。新增目录白名单本身不算接入。自动快照、完整备份、Portable Mode、root switch、恢复前快照、升级/降级检测都必须理解该域后才允许生产写入。

| 阶段 | 持久状态与失败安全 |
|---|---|
| Prepare | 来源稳定副本/句柄绑定SHA；只写所属staging，live不变；取消清理自有临时物，原档保留 |
| 校验/密封 | 完整图、资源、秘密类别、容量与能力校验；seal包含清单、目标root generation和恢复计划；失败不生成可调度计划 |
| 用户确认 | 展示来源、范围、缺失、冲突、运行限制、空间和排除项；确认绑定seal，任何变化需重校验/重确认 |
| Schedule | 排空写入并持有exclusive/root lock；恢复前完整快照含旧封存域；先durable journal再发布pending；目标变化拒绝 |
| Restart/Apply | repository、scheduler、GC初始化前读取journal；按已密封计划切换live+sealed所有域；多目录不是一个文件rename，必须可重放/回滚 |
| Commit | 所有域hash/图和bootstrap成功后标commit；结果仅此时可称成功；保留来源与历史证据 |
| 清理 | commit后只清事务拥有的scratch；保留rollback按批准策略，不删未知文件；清理失败单独报错 |

每个durable边界前后断电/进程终止/磁盘满，都必须恢复为完整旧状态或完整新状态；恢复未完成禁止业务写入，不允许一半live一半sealed继续运行。计划/资源篡改、跨root journal、重复apply、rename失败和快照校验失败均拒绝启动受影响根，保留可诊断的无正文证据。Schedule以后取消是否可用遵守现有阶段合同；不能删pending冒充取消。

**旧版本防写是阻断项。** 新建capability标记不能让已发布旧二进制自动识别它。不能在原根加目录后假设旧版本不会忽略/丢弃；必须证明所有允许打开该根的旧版本在写入前失败，或批准独立不向旧版暴露的新根及受控启动/迁移方案。仅Launcher版本检查不能阻止用户直接运行旧程序。此保证未闭合前不得投产新域，也不得支持降级写入。

普通全备应覆盖所有已验证且无秘密的新封存对象；带秘密来源档不随之夹带。root switch复制并校验清单和拥有权，旧根保留到新根commit；Portable移动后不依赖旧绝对路径。自动快照必须给出包含域/失败状态，不能在漏封存时仍报全量成功。

## B4：三种输出，不能混称

| 输出 | 可达到的保证 | 限制 |
|---|---|---|
| 来源原包取回 | 全包原SHA/长度一致 | 不包含Desktop新增状态；原安全风险仍存在，不等于可安全导入 |
| sealed内容重新封装为官方包 | 已知实体字段/资源和声明映射可验证 | 包SHA必变；未知语义、缺依赖、冲突或Task准入未解决则拒绝 |
| Desktop当前全量迁移 | 当前全部正式域及引用迁移 | 尚未闭合，不能把前两种称D-038完成 |

1.4.5 v1仍是批准目标：只输出目标登记和可解析字段，不向官方注入Desktop封存schema。无法无损表示者整次拒绝或经另批明确有限子集；原包仍保存。1.4.6/7 v2是候选目标，需scope精确构造、依赖闭合、媒体省略意图、三模式/加密/路径测试与用户批准扩展目标，不能自动更换D-038。

再导出不覆盖live记录：构造离线派生树；同ID不同原bytes/依赖不能选一份；输入未知字段即使raw可取回也未必可安全运行。Task遵守 [独立准入合同](MOMENTS_TASK_ACTIVATION_CONTRACT_DRAFT.md)。默认拒绝将未隔离的可运行任务发布给会自动调度的Android。任何C-B转换必须标明行为改变，不叫原样无损恢复。

## 验收与待批准决定

验证矩阵必须包括：跨包同ID、同包重复、未知嵌套资源、metadata独立图片、历史缺头像/缺正文图、共享图片后删卡、GC竞争、root switch、Portable迁移、原包带秘密、配额超限、每阶段崩溃/磁盘满、旧二进制直开和回滚后再试。比较原bytes/资源SHA、完整关系图、目标旧状态和网络零调用，不能只比较实体数量。

详见 [切片](IMPLEMENTATION_AND_TEST_SLICES.md) 与 [决策](DECISIONS_REQUIRED.md)。独立封存技术上有条件可行；新持久化域、资源所有权、恢复事务和降级策略都需生产授权。当前仍BLOCKED，不实现Python POC移植、不承诺D-038已满足。
