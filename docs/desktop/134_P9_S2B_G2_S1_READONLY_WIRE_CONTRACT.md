# P9-S2B-G2-S1 Read-only Wire Contract

日期：2026-10-17。**BOUNDED DOCS-ONLY / NO BUILD / NO IMPLEMENTATION**。

本规范是可据以编写严格只读解析器的 **J-v1 候选 wire profile**，等待 Project 最终冻结。它不注册生产 Entity/schema，不创建事务域、root/config authority、Writer 或恢复器，不改变 Main。文中的 MUST/拒绝规则表示该候选被批准后解析器应执行的规则，不表示已经实现或已经取得生产证明。

## 1. 权威、适用性与不可变边界

冻结 feature：`91ad565c0ca3331b840cec2a168785a3874f7f56`。起始 local、tracking、live origin 一致，index 为空，tracked worktree clean；三个既有未跟踪 JPEG 不读取、不处理、不暂存。本轮只新增134。

主要输入为 Git 中的133（blob `8de2f4f58e19ca72c4c44eb0d1c0c2398a2bd338`，Project SOURCE REVIEW PASS），结合125–128、130–132。133是源码审计，不是已注册协议。125/126的固定数据集测试协议不作为wire格式，也不把“第二笔永久拒绝”移植为产品行为。

正式 validated baseline 仍为 ChatBar **1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。不采用1.4.6新语义，不同步upstream，不改D-036/037/038/040、CURRENT、Feature Parity或历史证据。

**本 profile 的所有结果，包含 NO_DOMAIN，均不发放 business admission。所有存在事务域的结果明确 BLOCKED。** NO_DOMAIN仅是此次可信检查的缺失事实；现有G1B是否继续启动仍由既有门禁决定。G2-A不接Main，不把结构合法叫作“已提交”“可恢复”“业务READY”。

### 1.1 当前源码依据

D=`app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`；S=`app/sharedCore/src/main/kotlin/com/example/chatbar/`。以下均冻结于上述feature，未修改。

| 文件 | 核对行号 / 约束 | Git blob |
| --- | --- | --- |
| D/Main.kt | 24–43；真实StartupGate在business容器前 | `bdd1b68d95df64b6044ab1799ba59ed453db5311` |
| D/DesktopStartupGate.kt | 30–106；任何非NO_DOMAIN拒绝，Portable两次独立检查 | 20c609bb0ac116b556165b057cab5a46c8246cbd |
| D/DesktopIndexStartupPreflight.kt | 22–40、70–181、193–231；G0预算及Windows helper | `03febf66c0022a22aec41a30b3d903e4d90a634c` |
| D/DesktopDataRootOwnership.kt | 68–117；持续FileLock，不是持久lineage | b732c26ecdcb3125f323dc85d4e62a8df2e1c1e8 |
| D/DesktopDocumentRagReader.kt | 17–21、100–145；embeddingKey、DOCUMENT来源和metadata闭集 | `bab47f7276074239e9b0a9cafae8595c6dac2d42` |
| D/DesktopDocumentRagSource.kt | 14–122；源预算、Identity及有限向量检查 | `0666805738cc3a7c811e6274d5d13ad2a2b05b77` |
| S/data/local/entity/CharacterCard.kt | CharacterCard / DocumentInfo原字段authority | 1c30a23cd165ac8ae7aa8c240d8f447ee7eaab3c |
| S/data/local/entity/VectorChunk.kt | 20–52；现有flat Entity，不加generation字段 | `24cc66091fb03a41e19831e357bdc5c3aea67ec1` |
| S/data/local/entity/ModelConfig.kt | 97–119；EmbeddingConfig含apiKey，禁止完整preboot读取 | d77bd4bda03e816bf2fe21df51e6adae8b4cd5d4 |
| S/data/local/JsonFileStorage.kt | 98–153、257–321；normal gate、单文件替换及严格inventory；非本识别器调用入口 | 494c2067012ece17e04b3e36d5788276b0b9f833 |

133中37份源码ledger继续有效；本轮不重复整个生产Writer审计。下列wire字段不修改上述Entity的序列化合同。

## 2. 编码、基础类型与拒绝规则

### 2.1 通用规则

1. 控制JSON必须为无BOM的严格UTF-8、一个完整顶层object；拒绝非法字节、尾随数据、注释、NaN/Infinity、非JSON语法和无配对Unicode surrogate（包括转义后才产生的孤立surrogate）。允许JSON标准空白；不规范化换行、Unicode或空白。
2. 所有表中字段**必填**。`T?`表示字段必须存在，值可为null，不表示可省略。没有默认值、extensions、任意metadata或“忽略未知字段”通道。
3. 每层object在解码后的key上检测duplicate key；重复、ASCII大小写碰撞、未知/错大小写key、缺字段、类型不符均CORRUPT。不能先构成Map再检测，否则重复key已丢失。键顺序不影响字段解释，但影响原文件hash。
4. 控制整数只允许十进制 `0` 或无前导零的正整数；禁止负数、`-0`、小数、指数和越界Long。版本必须先按整数解析。所有加法、乘法、revision递增、计数和预算使用checked Long。
5. 控制文件最大64KiB，JSON深度≤64，每object≤32字段，每数组≤4096项（下文更小上限优先），单字符串UTF-8≤2048 bytes；先预算再分配。Entity另按2.4，JSONL另按3.2。
6. Hash只对实际读到的原始文件/行字节计算。不得以解码对象重新序列化的字节冒充原文件。结构字段相等与原始FileEvidence相等是不同检查。
7. 未知路径/额外文件、大小写路径碰撞、超预算、非法编码、读取错误均不可当作不存在。所有拒绝保留原件，无清理/补写/迁移。
8. 诊断只返回固定enum、phase与有界计数，不输出payload、原异常、完整路径、用户名、Prompt或credentials。

### 2.2 基础类型

| 类型 | 精确定义 |
| --- | --- |
| UUID | 小写canonical `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`；本profile另拒绝全零；不以随机性推断可信 |
| EntityId | ASCII `[A-Za-z0-9_-]{1,80}`，且现有safeWindowsName通过；保留原大小写，引用必须精确匹配；同namespace case-fold重复拒绝 |
| Sha | 恰64个小写hex字符，SHA-256；全零形式合法但仍必须匹配实际文件 |
| U0 / P1 | Long范围`0..9223372036854775807` / `1..9223372036854775807`；各字段业务上限另约束 |
| FileEvidence | object恰`{bytes:U0, sha256:Sha}`；bytes必须等于实际字节长度，且满足该引用槽的文件上限；不是路径或签名 |
| RootClaim | object恰`{lineageId:UUID, epoch:P1}`；**NOT YET TRUSTED** |
| WriterClaim | object恰`{authorityId:UUID, epoch:P1}`；**NOT YET TRUSTED** |
| ConfigClaim | object恰`{store:Store, id:EntityId, generation:UUID, revision:P1, publicFingerprint:Sha, credentialGeneration:UUID, dimensions:Int, embeddingKey:Sha}`；Store仅`SINGLETON/LEGACY`，SINGLETON时id恰`default`，dimensions为1..65536；其余身份字段**NOT YET TRUSTED** |
| OwnedDocumentPath | root相对恰两段`documents/<leaf>`；正斜线，无绝对/盘符/反斜线/点段/ADS，leaf通过Windows检查，组件≤255 UTF-16 units、总路径预算见6；不截断或替换文件名 |
| SourceEvidence | object恰`{path:OwnedDocumentPath, bytes:U0, sha256:Sha}`，bytes≤1MiB；path只允许指向上述文档，不可指向其他Entity或配置 |
| HeadLink | object恰`{txId:UUID, revision:P1, file:FileEvidence}`；file描述子bundle保存的`old/head.json`原字节 |
| VectorSet | object恰`{records:U0, payloadBytes:U0, rowsSha256:Sha}`；records≤2048、payloadBytes≤64MiB；rowsSha256是对应原始JSONL行按已存顺序连接后的hash |
| ManifestSummary | object恰`{file:FileEvidence, records:U0, payloadBytes:U0}`；file描述完整JSONL文件；records和payloadBytes必须与逐行计数/文件bytes求和一致 |
| DocBinding | object恰`{documentId:EntityId, generation:U0, producerTxId:UUID?, source:SourceEvidence?, config:ConfigClaim?, set:VectorSet?}`；generation=0时后四项必须全null，表示未建立本协议索引；generation>0（本文简称INDEXED）时后四项全部非null且set.records≥1 |
| HeadState | object恰`{cardId:EntityId, revision:P1, card:FileEvidence, documents:Array<DocBinding>}`；documents≤64，按documentId ASCII序严格递增、无case-fold重复；覆盖stage/card的全部customDocuments ID，不能只列本次目标 |

80字符EntityId是本只读候选子集的限制，比现有源码1..256更窄；更长的合法历史ID在此profile不可解释，拒绝但不重命名/迁移。两个namespace中同名ID不必冲突；同一目录或同一集合的大小写别名必须拒绝。UUID不证明root ownership或writer权限。

ConfigClaim不含baseUrl、modelName、apiKey或密钥hash。`embeddingKey`只是现有检索兼容key的**声明值**；publicFingerprint的真实生成authority尚未建立，G2-A仅检查格式/交叉相等，不发明算法把它变成生产证明。

### 2.3 控制记录公共头与版本

每种控制JSON均包含且仅包含公共头加其专用字段：

| 字段 | 类型 / 唯一允许值 |
| --- | --- |
| format | String，恰`CCB_DOCUMENT_INDEX_J` |
| version | integer，当前仅`1` |
| kind | 按记录分别恰`DOMAIN/INTENT/SEAL/COMMIT_MARKER/HEAD/HISTORY_RECEIPT` |

先用有界严格JSON词法扫描取得format/version这一最低识别头；v1再要求kind及完整公共头，未来版本不要求仍使用v1的kind枚举。重复key/编码/语法错误仍拒绝。format不匹配为UNSUPPORTED_VERSION的`FOREIGN_FORMAT`原因；version为≥2返回UNSUPPORTED_VERSION，不按v1解释其余字段，不跟随未来引用。version缺失/非整数/≤0为CORRUPT。对v1再应用完整闭集；其他记录声明未来版本同样不兼容，不能在同域混用。未来记录的其他字段不当作v1字段解析，任何版本都不因此获得业务准入。

### 2.4 Entity / source payload

`old/card.json`、`stage/card.json`及vector文件是现有CharacterCard/VectorChunk原始JSON，不添加上述公共头，也不添加schema字段。使用1.1冻结serializer作字段authority，外层另做duplicate-key、strict UTF-8、深度≤64、每object≤256字段、单数组≤65536项、单字符串解码前UTF-8表示≤8MiB和整文件≤8MiB的预算。未知Entity字段拒绝；现有serializer有默认值的旧字段可按原合同缺失，**不把默认值写回或据此证明旧身份**。ID、customDocuments及目标DocumentInfo身份等本profile使用的字段须在原JSON中明确存在，不从默认值补造。

card.id、vector.id必须与指定storageId相等；旧/新card ID相同，目标doc均存在且fileName/filePath/fileType/addedAt/id原样保持。本profile仅解释`INDEX_DOCUMENT`；其他正文/角色/资源变更不认作这个操作。除updatedAt、目标DocumentInfo的索引字段（contentHash/indexedHash/ragStatus/ragChunkCount/ragIndexedAt/ragError）及card既有ragIndex字段之外，旧新card其余字段/存在性须保持一致；索引状态值及计数必须符合冻结Entity类型。此检查是候选结构约束，不能代替尚缺的Writer合法性证明。

`source.bin`是对应文档的原始UTF-8字节副本，≤1MiB，按严格UTF-8读取而不归一化CRLF/Unicode；source可包含正文中的U+FEFF，不套用控制JSON的BOM规则。它与intent.source的bytes/hash一致，不经重新编码伪造原身份。

目标vector必须DOCUMENT、sourceId=cardId、messageId=null、metadata恰有冻结Reader的六个key：`documentId/sourceLabel/fileName/originalDocId/contentHash/embeddingKey`；originalDocId=目标docId。stage向量还必须匹配stage doc的fileName、source hash、ConfigClaim.embeddingKey/dimensions；有限非零norm按现有shared规则检查，不另写相似度算法。旧向量可具有旧的合法embeddingKey/hash，不能因与新配置不同而删除；旧来源无法解释则拒绝。新content及分块metadata按现有ChunkingEngine核对，先执行现有source plan预算（1MiB、fileName256、heading128、label1024、sections/chunks2048、plan2Mi字符），不创建新chunker。

stage目标doc必须显式为`ragStatus=INDEXED`，其contentHash/indexedHash等于source.sha256，ragChunkCount等于newSet.records，ragError为null；ragIndexedAt须显式为P1整数，stage/card.updatedAt须显式为U0整数；不推断真实墙钟或强制时钟单调。不得只看INDEXED字符串而省略集合/正文证明。非目标doc的索引字段和binding须保全；首次head若遇到已有INDEXED来源却无本协议producer，报告LEGACY_INDEX_ORIGIN，不从默认值认领它。

## 3. 路径闭集与清单

令`D=entities/document_index_transactions`；下列路径均相对root，大小写精确。无通配式“忽略tmp/隐藏文件”。目录不可用文件替代，文件不可用目录替代。

| 路径 | 类型 / 存在条件 |
| --- | --- |
| `D/` | 域目录；若可信缺失可NO_DOMAIN；存在但空不是NO_DOMAIN |
| `D/domain.json` | DOMAIN，域存在时必需；缺失为INCOMPLETE_EVIDENCE |
| `D/active/`、`D/history/`、`D/heads/` | v1必需目录；缺失为INCOMPLETE_EVIDENCE；未知同级条目拒绝 |
| `D/active/<txId>/` | 0或1个transaction bundle |
| `D/history/<txId>/` | 0..128个完整archive bundle；不是只留receipt丢payload |
| `D/heads/<cardId>.json` | 0..128份当前HEAD；完整history必须能接到对应当前head |
| `B/intent.json`、`B/old-set.jsonl`、`B/new-set.jsonl`、`B/unrelated.jsonl`、`B/source.bin` | bundle基础文件；`B`只能是上面active/history的txId目录 |
| `B/old/`、`B/old/vectors/`、`B/stage/`、`B/stage/vectors/` | bundle固定目录，即使旧集合为空也存在 |
| `B/old/card.json`、`B/stage/card.json` | 完整原始Entity文件 |
| `B/old/vectors/<storageId>.json`、`B/stage/vectors/<storageId>.json` | 分别由old-set/new-set闭集指定；不能多出或缺少 |
| `B/old/head.json` | intent.expectedHead非null时必需且唯一；为null时禁止出现 |
| `B/seal.json` | sealed所需；缺失仍保留并分类未完成 |
| `B/commit.json` | COMMIT_MARKER，可缺失；存在不等于提交成功 |
| `B/receipt.json` | HISTORY_RECEIPT；active可缺失，history必需；不允许其他完成标志 |

同txId不能同时位于active/history。bundle内引用使用固定文件名或由已校验storageId推导，不接受任意path字段导航。history保留完整bundle是对133简写的明确化：旧字节不能仅凭一份摘要receipt恢复；本轮不执行archive移动或GC。active中的receipt表示交接尚未收束，不因此当completed。

### 3.1 路径、类型与集合身份

路径文本必须原样匹配；文件系统比较另检查case-fold碰撞、别名及重复物理身份。Entity ID不得控制父路径。原始目录完整枚举，不使用只筛`*.json`而跳过其他条目的方式证明闭集。

old/new集合必须各自ID唯一，old与unrelated不相交；new与old及unrelated均不相交（J-v1为新块分配新ID，不覆盖旧块同ID）。合法的空old集合用0字节old-set和空old/vectors表达；new至少1项。live库可以包含其他来源，但不能混入old/new target集合。

### 3.2 三种JSONL的唯一行格式

每个非空文件以LF结尾，无BOM、CR、空行、尾部空白行或跨行object。每行（含LF）≤256 bytes，是一个object，字段恰为：

| 字段 | 类型 / 校验 |
| --- | --- |
| storageId | EntityId；按ASCII ordinal严格递增；同文件case-fold唯一 |
| bytes | U0；实际Entity文件bytes，1..8MiB |
| sha256 | Sha；实际Entity文件hash |

字段顺序不强制，行内JSON空白计预算；不重排或重序列化后hash。old-set/new-set各≤2048行，文件各≤524,288 bytes；unrelated≤100,000行且≤25,600,000 bytes。ManifestSummary.records等于行数，payloadBytes等于各行bytes checked求和；old/new payload各≤64MiB，unrelated payload≤4GiB。

unrelated记录当时非目标vector的身份摘要，不把其全部原始payload复制入bundle。seal只能证明这份历史清单的字节身份，**不能单凭它证明从前不存在其他文件**。active的观测可与当前live inventory比较；历史receipt的unrelated清单不永久约束其他card/CHAT_MEMORY的合法后续变化。

## 4. 控制记录专用字段闭集

以下每表均加2.3公共头；除明确`?`外值不可null，所有字段必需。不允许任意字段或未来writer添加“verified=true”。

### 4.1 domain.json — kind=DOMAIN

| 字段 | 类型 / 约束 |
| --- | --- |
| root | RootClaim |
| writer | WriterClaim |

这是格式/声明owner的记录，不是root adoption证书。域里所有root/writer声明须与之精确相等；声明值有效不等于authority有效。

### 4.2 intent.json — kind=INTENT

| 字段 | 类型 / 约束 |
| --- | --- |
| txId / taskId | UUID / UUID；txId匹配bundle名；taskId是运行身份声明，不是持久TaskRuntime许可 |
| root / writer | RootClaim / WriterClaim；等于DOMAIN |
| operation | String，恰`INDEX_DOCUMENT`；无CARD_EDIT/REMOVE/CONFIG_CHANGE等隐式扩展 |
| cardId / documentId | EntityId / EntityId |
| source | SourceEvidence；path匹配旧新目标doc.filePath，hash/bytes匹配source.bin |
| config | ConfigClaim |
| expectedHead | HeadLink?；null表示该card的首个本profile head |
| oldCard / newCard | FileEvidence / FileEvidence，分别绑定old/card、stage/card |
| oldSet / newSet / unrelated | ManifestSummary，分别绑定三个JSONL |
| next | HeadState；cardId相等，card= newCard；目标DocBinding为INDEXED且producerTxId=txId |

next目标binding的source/config等于intent对应值，set.records/payloadBytes等于newSet，rowsSha256=newSet.file.sha256。expectedHead=null时next.revision=1；非null时严格等于expectedHead.revision+1，溢出拒绝。目标generation从父head的同doc generation加1（首代为1），其他doc binding完整不变；首head的其他doc只能generation=0，无法从legacy索引推定已有generation。

expectedHead非null必须存在old/head.json，其原始FileEvidence等于expectedHead.file，解析出的txId/revision/cardId与link相等，旧head.state.card=oldCard。当expectedHead=null且oldSet.records>0时，即使旧块可读，也只报告LEGACY_INDEX_ORIGIN缺失证明，不进入sealed/completed高阶结构，不adopt旧库。未提供结构一致的父HEAD却声称已有generation/索引来源时同样处理；oldSet为空的正常首代不触发这一条件。

存在父head时，oldSet还必须与父head目标DocBinding所指producer bundle的new-set逐项对应（storageId、bytes、sha256三元组及计数/总bytes），generation=0则oldSet为空。两个manifest的原始字节可因JSON空白/键顺序不同而hash不同；各自FileEvidence必须独立匹配原件，逻辑集合比较不能替代文件身份。其他doc binding引用的producer与其new-set也须完整可解析，不接受悬空或跨card/doc来源。

### 4.3 seal.json — kind=SEAL

| 字段 | 类型 / 约束 |
| --- | --- |
| txId | UUID，匹配bundle |
| intentFile | FileEvidence，实际intent.json |
| oldManifestFile / newManifestFile / unrelatedManifestFile | FileEvidence，实际三个JSONL，同时等于intent相应summary.file |
| oldCardFile / newCardFile / sourceFile | FileEvidence，实际固定文件；等于intent.oldCard/newCard/source的bytes/hash |
| oldHeadFile | FileEvidence?；等于expectedHead.file，或在expectedHead=null时为null |

seal通过清单hash**间接绑定**每个vector，不在64KiB seal中塞入4096条文件数组。只有所有payload、清单、intent、old head均完整读回并匹配，才得到sealed结构证据。seal不引用自身、commit、receipt或current head，因此无自身hash循环。

### 4.4 commit.json — kind=COMMIT_MARKER

| 字段 | 类型 / 约束 |
| --- | --- |
| txId / root / writer | UUID / RootClaim / WriterClaim，匹配bundle、DOMAIN与intent |
| intentFile / sealFile | FileEvidence，实际对应文件 |
| predecessor | HeadLink?，等于intent.expectedHead |
| next | HeadState，逐字段/数组顺序等于intent.next |

marker中没有“success=true”、无任意阶段字符串，也不引用尚未产生的receipt hash或新HEAD文件hash。有效marker只是结构证据；原子写入顺序、force/fsync、恢复选择及真正commit point仍须G2-B/C批准。

### 4.5 receipt.json — kind=HISTORY_RECEIPT

| 字段 | 类型 / 约束 |
| --- | --- |
| txId / root / writer | 匹配bundle、DOMAIN与intent |
| intentFile / sealFile / commitFile | FileEvidence，实际固定文件 |
| predecessor | HeadLink?，等于intent.expectedHead |
| next | HeadState，等于intent.next及marker.next |

receipt为不可变历史事实摘要，不含未来才知道的supersededBy，也不改写旧receipt来退役。后继receipt通过predecessor及old/head原字节建立接力。无单独“completed.json”或不受约束的文字状态。

### 4.6 heads/<cardId>.json — kind=HEAD

| 字段 | 类型 / 约束 |
| --- | --- |
| txId / root / writer | UUID / RootClaim / WriterClaim，当前producer txId及DOMAIN声明 |
| intentFile / sealFile / commitFile / receiptFile | FileEvidence，解析到唯一producer bundle中的实际文件 |
| predecessor | HeadLink?，等于该intent/receipt的predecessor |
| state | HeadState，state.cardId匹配文件名，且等于producer的next |

producer由txId在active/history中**恰好一个位置**解析；无路径字符串自由指向。HEAD不包含自身hash；只有下一代old/head保存上一代HEAD的原始字节后，HeadLink才引用它。

### 4.7 Hash依赖与多代图

依赖图为：原始payload/JSONL/old-head → intent → seal → marker → receipt → current HEAD → 下一代old-head。箭头是“后者引用前者的实际FileEvidence”；intent.next/marker.next/receipt.next/HEAD.state共享结构值，不通过相互hash制造循环。

每card的history必须形成一条有限链：从当前HEAD回溯producer、predecessor和下一代保存的old/head，revision每步减1，首代revision1且predecessor=null。拒绝循环、分叉、重复revision、同tx多位置、跨card/root/writer引用、缺失parent或孤立无法接入当前链的history。一个已归档card没有HEAD也不当completed。

每个generation>0的DocBinding还须解析到其producer bundle：producer.intent.cardId/documentId与binding所属card/doc一致，producer.next的目标binding逐字段等于该binding，binding.set的计数/bytes/rowsSha256分别匹配producer.newSet。不能只验证UUID长得合法就接受悬空generation。

对history只验证其保存字节、链和当时结构；不要求当前live永久等于最老card/source hash。只有当前HEAD宣称的live card、当前doc producer集合与实际资源作有限重证；未被合法handoff解释的变化报告不一致，**不自动回滚今天的数据**。

本v1只描述INDEX_DOCUMENT的多代、同card第二doc和再索引。CARD_EDIT、改文档名/来源、删除/覆盖/preset/config/root adoption等其他writer协议**未定义且NOT YET TRUSTED**；发生相应变化时保持拒绝，不通过放宽head相等假装兼容。以后扩展须新版本/新独立批准，不偷偷增加v1字段。

## 5. 有限结果、证据优先级与准入

结果候选的固定字段：`code`（下表enum）、`phase`（ROOT/HEADER/INVENTORY/PAYLOAD/GRAPH/LIVE/FINAL_RECHECK）、`domainPresence`（ABSENT/PRESENT/UNDETERMINED）、`businessAdmission`（唯一值BLOCKED）、`missingProofs`（去重enum集合）、`observedLive`（NOT_CHECKED/EXPECTED_OLD/EXPECTED_NEW/KNOWN_MIXTURE/MISMATCH）、有界counters。没有raw异常/路径或可调用retry/recovery函数。

| code | 判定 |
| --- | --- |
| NO_DOMAIN | 真实ownership有效，祖先与domain缺失复核一致；仅缺失事实 |
| OWNERSHIP_MISSING | 没有当前真实owner，或扫描中owner失效 |
| UNSUPPORTED_VERSION | format不支持，或version≥2；不解释未来payload |
| INCOMPLETE_EVIDENCE | 空域、缺domain/固定目录/必要文件、未seal、legacy来源无head；附固定缺失种类 |
| SEALED_PENDING | active的intent/seal/完整old/stage全部校验一致，commit缺失；不判定可恢复或尚未写入 |
| MARKER_WITHOUT_HANDOFF | active有有效marker但HEAD/receipt/archive未收束；即使有HEAD也因active保留而不当completed |
| HEAD_HISTORY_INCONSISTENT | HEAD/receipt/parent跨记录的链/引用矛盾、分叉、missing已声明parent、当前head/live不一致；同bundle payload/seal自身错误归CORRUPT，不自动回滚 |
| COMPLETED_STRUCTURE_ONLY | 无active，所有history/HEAD/保存payload/当前live的可检查结构一致；生产authority仍缺失，BLOCKED |
| CORRUPT | 非法JSON/UTF-8、重复/未知/缺必填字段、未知文件形状、错误Entity身份/数据、payload与自身声明hash不符 |
| UNSAFE_PATH | 不安全链接/别名/路径、不可证明必要的single-link/same-handle身份等 |
| BUDGET_EXCEEDED | 任一entry/bytes/深度/内存/算术预算不能满足 |
| READ_ERROR | ACL/IO/枚举/句柄关闭失败等导致所需读取未确认；不伪装Missing |
| CHANGED_DURING_CHECK | 已观察对象/owner之外的文件或目录身份、大小、时间等漂移、读前后hash不同；不复用半份证明 |

CancellationException继续传播，不吞成上述成功/普通失败；调用方另记CANCELLED，等待实际I/O及句柄收尾，不把cancel当完成证明。

判定按阶段执行：ROOT→HEADER→v1 INVENTORY→PAYLOAD→GRAPH→必要LIVE→FINAL_RECHECK。HEADER不支持时不遍历未来布局。每阶段先处理已经观察到的ownership/路径/变化/读错/预算/损坏，再处理缺失；同阶段多错误按本句顺序择code，保留固定原因集合。不能为凑高阶状态跳过错误，也不为“找齐所有错误”无界继续扫描。已知布局的缺失文件先收集，读完现有的有界控制记录/引用关系后再分流，不在已知HEAD关系检查前提前误报INCOMPLETE；编码/危险路径等硬错误仍立即拒绝。已声明文件缺失不等于损坏JSON：前者是INCOMPLETE；文件存在但截断/坏hash是CORRUPT；一个HEAD已宣称其parent/receipt存在却缺失，则为HEAD_HISTORY_INCONSISTENT。

LIVE只读扫描命名空间为entities/character_cards、entities/vector_chunks，文档只按已校验SourceEvidence引用读取，不遍历读取无关媒体或配置；root/entities其他条目仅作必要metadata定位，不能据此声称整个root安全。有效sealed active可观测到old/new已知混合，但仍仅SEALED_PENDING；出现不在允许old/new集合的文件或marker与live矛盾，转HEAD_HISTORY_INCONSISTENT。有active时HEAD可仍为父head，LIVE按该active允许的old/new组合观察，不因预期安装混合就强套旧HEAD的精确live相等；marker宣称的new若不匹配则拒绝。completed重证按当前doc producer的new-set，不按历史unrelated清单永久锁定其他数据。无法证明其他来源关系，不可略过，返回缺证据或相应错误。

所有已识别的present v1结果，其生产missingProofs至少包含`ROOT_LINEAGE_AUTHORITY/CONFIG_WRITER_AUTHORITY/WRITER_HANDOFF/READINESS_CACHE_EPOCH`；observedLive匹配也不能清除这些项。其他固定项为`DOMAIN_HEADER/FIXED_DIRECTORY/INTENT/OLD_PAYLOAD/STAGE_PAYLOAD/SEAL/PARENT_HEAD/COMMIT_MARKER/RECEIPT/CURRENT_HEAD/LEGACY_INDEX_ORIGIN/LIVE_APPLICABILITY/WINDOWS_LINK_IDENTITY`。纯缺文件与生产未建立的authority分别记录。允许测试断言结构匹配，**不允许通过fixture字段或测试注入把生产missingProofs清空后开放业务**。

## 6. G0与G2独立预算及可计算上界

MiB=1,048,576 bytes；KiB=1024 bytes。所有上限为本S1新候选，待Project冻结，不改133或G0参数。多个上限取交集，不保证所有局部最大值可同时取满。

### 6.1 上限表

| 计量 | J-v1只读候选值 / 计账规则 |
| --- | --- |
| G0 | 原值不变：累计256 entries、depth4、metadata普通文件size64MiB；names8192/path32768等原约束不改；G0错误不能当G2合法凭证 |
| locator entries | root/entities必要目录枚举累计≤4096项；ancestors≤64；缺失须可信父目录和末次复核，不能直接Files.exists |
| domain entries | **≤16,384**，含D本身、全部子文件/子目录；hardlink别名不合并成一个entry规避预算 |
| depth | 相对D最大8（D=0）；本闭集最深vector文件为5；JSON深度独立≤64 |
| 组件/路径 | 组件≤255 UTF-16 units；root绝对路径≤512，root相对路径≤384，完整绝对路径≤1024 units；不启用系统长路径设置、不truncate；超过任一项拒绝 |
| 累计名称/路径 | domain每个唯一entry的basename units合计≤1,048,576，相对path units合计≤6,291,456；locator相应≤1,048,576/1,572,864。算术先检查后保存 |
| 控制JSON / Entity / source | 每控制文件64KiB；card/vector各8MiB；source.bin及任一原文档1MiB；JSON/数组/字符串预算见2 |
| JSONL | 每行含LF256 bytes；old/new各2048行和512KiB；unrelated100,000行、25,600,000 bytes；全域唯一JSONL行数累计≤262,144 |
| old/new集合 | 每bundle各≤2048 vectors、各payloadBytes≤64MiB；old可空，new非空 |
| bundles / heads | active≤1；history≤128；heads≤128。全部archive payload仍计entries/bytes，不只计receipt |
| domain unique file bytes U_d | metadata声明size逐文件checked求和≤536,870,912（512MiB）；实际读入长度必须一致 |
| domain累计I/O reservation | ≤1,073,774,592 bytes = 2×512MiB + 2×16,384；每文件最多两次数据读取/哈希，EOF增长探测每open预留1byte。重复引用不重置预算 |
| live inventory | 唯一普通文件≤100,000（card/vector/实际读取doc均计），目录及文件entries≤100,256；metadata声明size合计≤4GiB；被扫描的命名空间中未知/损坏条目不得宽松跳过 |
| live累计I/O reservation | **≤4,294,967,296 bytes（4GiB）**，含所有重复读取/EOF探测；完整双读前必须满足`2×U_l + 2×N_l ≤4GiB`，故可双读的有效live集合通常小于2GiB，不能承诺读完4GiB再免费读第二遍 |
| 全局metadata | 唯一观察路径≤120,000；每次目录枚举拉取、open-existing、属性/identity/link/name查询及复核均各计一次，尝试累计≤1,000,000；重复重查也计，不按文件数掩盖无限循环 |
| compact memory | inspector自有compact arena≤64MiB，含路径/ID/hash/文件stamp/图索引；超过即拒绝，不spill写盘 |
| 暂态payload | 同时至多一个8MiB Entity或一个1MiB source、一个64KiB控制记录；JSONL streaming；不得保留整库embedding或128份解码card |

实际读取的请求先从对应reservation预留，失败、取消、短读不把已经消耗预算退回用于retry；可将未发生的预留标为未使用，但总次数/已消耗不得重置。对已检查内容证据的文件，第一遍解析并hash、第二遍完整hash重证；未知版本/提前拒绝不强制多读。HEADER读domain算第一遍，不另赠第三遍。所有引用使用第一次的compact证据与按bundle顺序处理，不能重复打开父文件直到超限后仍继续。预算耗尽不得省略已经打开资源的close：每个句柄仍执行一次结构化close尝试，另记cleanup计数/错误，不用无限retry。总JSONL行访问≤524,288（两遍均按行访问时）；任何重读均计总bytes/operations。

若实际增长，最多在预留的EOF探测字节发现超长后拒绝；长度/mtime变化先返回CHANGED，不读取增长后的全文件。读错不内部重试。G2-A不宣称墙钟绝对超时；有限字节/操作预算与可取消检查不能中断所有真正阻塞IO。

### 6.2 为什么8192需要修正，512MiB如何成立

完整最大bundle包含4个内部目录加bundle根共5个目录，最多11个非vector文件（含old/head、commit、receipt），old/new vectors共4096个，因此每bundle最多**4112 entries**。两个最大bundle（一份历史、一份active）加D、domain.json、active/history/heads三目录及一个HEAD，共 **8230 entries**，超过8192的38项。16384消除这个最小两代交接冲突；128份history不是128份都可取最大payload的承诺，仍受全域上限限制。

JSONL采用三字段固定行格式而非无限来源标签；80字符ID、Long最大十进制和64hex的紧凑行仅201 bytes（含LF），256保留有限空白余量。old/new各2048×256=524,288 bytes；unrelated最多100,000×256=25,600,000 bytes。

按同时取满的保守估算，单bundle：
`128MiB vectors + 16MiB cards + 1MiB source + 1MiB old/new manifests + 25,600,000 unrelated bytes + 5×64KiB controls/old-head = 179,019,776 bytes（170.7265625MiB）`。
这里5份为intent/seal/commit/receipt/old-head，HEAD另计。两份这样的bundle、128份HEAD及domain.json合计 **366,493,696 bytes（349.515625MiB）**，低于512MiB，剩余history仍必须逐项纳入全域预算。不存在“每bundle64MiB”重置后累计无界的路径。

因此**512MiB保留为唯一文件字节上限，不再模糊称所有读取的总量**；完整双读domain需要1GiB加有限EOF预留。live保留4GiB累计读取硬上限，双读有效集合缩小并明确拒绝更大数据。这是相对133的精确修订，尚未冻结。

### 6.3 有限内存与算术证明义务

- 不用大Map保存所有解码Entity、完整JSON树或embedding。目录以有界索引表示：共有prefix+短ID/路径，Windows identity/stamp和SHA为固定大小packed记录。live 100,000条可采用每条≤128 bytes的ID/hash/length主记录（12,800,000 bytes）；domain相对路径UTF-16最坏12,582,912 bytes；其余stamp/图索引也必须纳入64MiB arena，不能漏算对象header后宣称精确heap。
- 实现须在分配前对arena逐项预扣；不满足时提前拒绝。上述packed布局是实现约束，不代表普通Kotlin字符串/HashMap自然满足；若不能兑现，先报告，不改成磁盘spool或取消预算。
- 控制图最多129个bundle与128个HEAD，历史payload逐bundle释放；manifest排序+merge/compact索引比较，不能把262,144行原JSON和引用路径全部驻留。
- 单8MiB JSON解码可能同时占原byte、UTF-16 text、token/Entity对象；明确给出实现的有限放大上界/测试计数，不把64MiB arena称整JVM峰值。每Entity数组/字段/文件限额使暂态有限，但不保证机器不会OOM；未实测前标UNKNOWN。
- `Long.MAX_VALUE`等畸形声明在checked加/乘/预算比较时拒绝，不把negative溢出结果当小文件；revision递增溢出同样CORRUPT/BUDGET且无写入。分配/readNBytes前才验证，而非事后检查已创建的大集合。

## 7. Windows只读安全与剩余平台证明

| 风险 | 当前可复用事实 | G2-A拒绝规则 / 新适配需求 |
| --- | --- | --- |
| symlink/junction/reparse | NIO NOFOLLOW属性、GetFileAttributes及现有helper的OPEN_EXISTING/OPEN_REPARSE_POINT、FILE_ID_INFO | 所有祖先、目录、文件读前后检查；任何reparse/isOther/符号链接拒绝，不跟随；仅final flag不能证明祖先未变化 |
| ADS/非法名/大小写 | helper.safeWindowsName拒绝冒号、点段、设备名、尾随点/空白、部分短名模式；闭集路径限制 | 增加本profile长度/ASCII-ID/精确大小写检查；同目录case-fold碰撞拒绝，不借Path.normalize放行 |
| short-name alias | helper仅按名称模式拒绝常见`~数字`形式，不是完整别名证明 | 需新增只读适配核对实际长名/解析身份；无法证明必要规范身份则UNSAFE_PATH，不能将regex视为Windows别名不存在证明 |
| hardlink | FILE_ID_INFO可检测本次遍历中不同path的同一物理文件 | 外部硬链接不在本次树中时看不到；需同句柄读取link count等只读事实，普通文件要求single-link。缺适配/计数不可读保持UNSAFE_PATH（WINDOWS_LINK_IDENTITY缺失），不自动退化为safe |
| 数据读取的句柄身份 | 现有helper只开属性句柄，读ID后关闭；稍后NIO流是另一个open | 需新只读平台适配对**同一个读取句柄**取得ID/属性/link count、读取bytes并重证，或提供同等级可验证绑定；单纯“helper前后相同”不能排除中间替换ABA |
| 目录/root替换 | Cooperative FileLock + ancestry/path identity重查可发现部分变化 | 观测漂移立即CHANGED；owner仍有效不等于根不可替换。完整race-free目录相对打开/祖先稳定证明未建立，不宣称完整TOCTOU |
| ACL/访问拒绝/CloseHandle失败 | API错误能观察，不需要枚举用户ACL内容 | READ_ERROR并保全；不提权改ACL/改系统配置；清理异常保留诊断与primary，不能伪造成功 |
| 取消/慢盘 | 结构化use/finally、协程可取消检查 | 取消后等真实读与close结束；无detached coroutine、强杀、删锁。阻塞I/O无物理绝对超时 |

新平台适配只能是open-existing、只读的实现，不获取第二把ownership锁、不创建文件。API绑定和句柄共享flags要在G2-A合同中明确审批/测试；不能依靠修改已验收helper或Core组件绕过边界。

仍然 **NOT TESTED / NOT PROVEN**：真实symlink分支、持锁实际root replacement、完整Windows TOCTOU/ABA排除、全部ACL路径、域外硬链接证明、断电耐久性、阻塞IO物理绝对超时。当前G0的一项symlink SKIPPED和四次fixture early-return NOT TESTED保持，不因本profile定义变成PASS。必要平台证据缺失时可报告结构前置阻断，不能把风险悄悄折算成完成态。

## 8. Secrets-free、root lineage与生产信任缺口

G2-A只识别RootClaim、WriterClaim、ConfigClaim的**格式和一致性**，全部标为NOT YET TRUSTED。UUID/epoch/hash互相相等并不证明由合法writer发布。当前FileLock只证明本次cooperative ownership，不赋予这些字段持久信任。

本profile不读取、反序列化或hash完整EmbeddingConfig，不访问SecretStore/DPAPI；不通过读一遍秘密文件来证明apiKey为空，不以secret hash“脱敏”。缺少实际配置attestation时，ConfigClaim公开字段再完整也只能列出CONFIG_WRITER_AUTHORITY缺失。

本版本没有root/config authority文件路径，也不搜索、创建或迁移任何adoption记录；额外塞入此类文件是未知路径。未来非秘密authority的字面、持久交接、credential-only变化、配置删除重建/失败回滚、whole-root restore和跨端来源均须独立合同。不能用baseUrl+modelName的embeddingKey、设置关闭、暂无UI活动或P9/P10未验收推定安全。

即使当前live字节完全匹配、HEAD链完整，也缺少所有生产writer已交接及cache epoch发布的证明；故结果最多COMPLETED_STRUCTURE_ONLY，businessAdmission仍BLOCKED。合成fixture的“完成态”不生成生产VERIFIED_SAFE，不改变F4 B5B门禁。

## 9. 未来synthetic fixture协议及证据

所有fixture在独立临时合成root中生成，显式ownership-before-inspector；可由test-only builder写入候选目录，但G2-A生产解析器没有写接口。此任务**不生成或运行任何fixture**。

Fixture builder须使用独立固定的字段/路径golden与原始UTF-8字符串，并用标准SHA-256读取实际已写bytes建立FileEvidence；不能调用待测parser/serializer即时生成自己的expected。至少一个完整golden用独立固定清单核对字段/行顺序。JSON对象空白/CRLF差异的测试要证明raw hash跟随实际bytes，而非重新序列化后相同就误判一致。

| Fixture ID | 内容与预期 |
| --- | --- |
| F01 | 无域/空域/只有domain；分别NO_DOMAIN、INCOMPLETE，全部无准入 |
| F02 | 完整首代、第二doc、同doc第二代，完整history+HEAD；COMPLETED_STRUCTURE_ONLY且authority missingProofs保留 |
| F03 | 基础文件未齐、缺seal、缺old/vector、缺old/head；精确INCOMPLETE，不补写 |
| F04 | sealed active无marker；SEALED_PENDING，允许观测已知old/new混合但不宣布回滚/恢复 |
| F05 | 有效marker缺HEAD/receipt或active未归档；MARKER_WITHOUT_HANDOFF；完成历史缺已声明parent则HEAD_HISTORY_INCONSISTENT |
| F06 | 两代历史、合法predecessor；旧receipt不匹配当前card不算损坏。另构造fork、重复revision/txId、循环、跨card/root引用拒绝 |
| F07 | 坏UTF-8/转义孤立surrogate、重复key（含转义后相同）、未知/大小写字段、未来version、截断JSON/JSONL、漏LF/多空行；对应CORRUPT或UNSUPPORTED_VERSION |
| F08 | 原始文件长度/hash变化、额外vector、来源/Entity ID冲突、marker/head矛盾；保留文件且拒绝 |
| F09 | symlink/junction/reparse/ADS/短名/hardlink、祖先/句柄身份改变及取消挂起点；平台不可执行时明确SKIPPED/NOT TESTED |
| F10 | 各预算等于上限、刚超过、checked Long溢出、两大bundle的8230 entry算例、累计多次读取、内存arena预扣；用短预算seam和小数据验证，不制造真实OOM |
| F11 | ACL/枚举/读错/close错误、取消与recheck竞争；不读秘密、不泄漏句柄、不吞原失败，不自动retry |
| F12 | 新子JVM只执行inspect，正常/拒绝/取消后退出；随后另一个新进程获取同根真实ownership；完整结果和锁恢复证据独立保存 |

### 9.1 零写入与退出

- inspector前后分别记录全部合成文件/目录集合、文件size及SHA-256、身份；数据字节与集合必须一致。读取可能影响atime或系统审计，不将“零数据写入”夸大为所有OS metadata毫无变化。
- ownership获取可能合法创建根/lock，由fixture先做并单列；比对baseline在ownership准备后建立，不把lock创建算G2写入，也不删lock制造重获。持锁文件不可读取内容时只记录既有基础设施身份/状态，不触发sharing violation伪造失败。
- 从inspect前baseline到独立进程锁重获及末次inventory完成的证据窗口内不自动删除fixture、域或旁文件；失败/UNKNOWN现场继续保留，后续处置不属于识别器。
- 对失败和取消也比较坏文件、未知旁文件、域完整清单；不为清理fixture强行释放owner或删除仍可能被后台任务使用的目录。
- 只记录自己创建的Process对象及PID、创建时间、路径、父子关系；waitFor确认真实退出和exit code，再由独立新进程重获锁。MainWindowHandle=0/CPU空闲不是退出证据。
- 子进程启动/退出watchdog候选各120秒；timeout是UNKNOWN/BLOCKED，不证明I/O停止，不自动kill/删锁。父测试failure有界报告并保留owned process/fixture身份，剩余运行不伪装已排水；人工后续处置需单独决定。
- cancel由明确注入gate/CompletableDeferred及结构化等待验证，不靠固定sleep猜竞态。保存原始RED/XML/log/资源采样，正常与失败证据分开；无真实存储、secret或网络。

## 10. G2-A文件边界及剩余裁定

本轮仅134。未来G2-A须另行授权，拟议只允许新增下列文件；相对目录与133相同：

| 新文件 | 唯一职责 |
| --- | --- |
| D/DesktopDocumentIndexTransactionInspector.kt | 有界只读编排、有限code、missingProofs及结构化取消 |
| D/DesktopDocumentIndexTransactionReadContract.kt | 本文闭集类型/严格wire parser/预算；不注册Entity或提供writer |
| D/DesktopDocumentIndexReadEvidence.kt | 如Project批准，只读same-handle identity/link-count/长名适配；复用既有helper基础，不复制或改G0 Probe |
| Desktop test/DesktopDocumentIndexTransactionInspectorTest.kt | 协议/多代/拒绝/预算/零写入 |
| Desktop test/DesktopDocumentIndexTransactionInspectorProcessTest.kt | 自有子JVM、ownership/退出/取消 |
| Desktop test/DesktopDocumentIndexTransactionSyntheticFixture.kt | test-only有界builder和独立golden；不调用生产真实数据 |
| Desktop test/DesktopDocumentIndexReadEvidenceTest.kt | 必要Windows适配测试；不可执行分支单独记录 |

其中Desktop test全路径前缀为`app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/`。新增ReadEvidence是对133原建议范围的明确候选扩展，**须Project批准，不能默认获准**；若不批准且现有helper不足，相关路径UNSAFE/BLOCKED，不修改旧核心来绕过。

绝对不能修改：既有G0/G1B及其算法/结果/预算、Main、StartupGate/BlockedPresenter/ProcessLifecycle、DataRootOwnership、AppContainer、Repository、TaskRuntime、coordinator、JsonFileStorage、Reader/Preparation、F4 safety、生产Entity/schema、Android、Prompt、Gradle与历史evidence。回归可执行既有测试但不得削弱/改写断言。已有纯serializer/ChunkingEngine/向量规则可只读复用，不迁移或重写业务算法。

G2-B恢复写入、G2-C Writer、G0→G2 production dispatch、config/root adoption、D-038跨端导出及任何分享投影均须单独授权。已完成结构识别不能自动开放任何其中阶段。

### 10.1 所有剩余 DECISION REQUIRED

| ID | Project需裁定的事项 | 未裁定时的行为 |
| --- | --- | --- |
| DR-01 | 冻结本文J-v1字面、路径、类型、链规则和INDEX_DOCUMENT-only子集；接受history保存完整bundle、80字符EntityId子集及16384 entries/双读预算修订 | 不编码/创建正式协议；本文仍candidate |
| DR-02 | 新增只读ReadEvidence平台文件及API/flags、same-handle/link-count/长名事实的实现测试范围 | 现有helper不足的路径UNSAFE/BLOCKED；不放宽 |
| DR-03 | 持久root lineage、配置/credential epoch、完整writer handoff/adoption与readiness/cache发布authority | 全部相关claim NOT YET TRUSTED，完成结构仍BLOCKED |
| DR-04 | Writer的seal/marker/head/history发布顺序、每个crash gap、真正commit point、恢复重入及durability保证 | 无恢复/写入/自动retry；不把结构状态说成commit成功 |
| DR-05 | 非INDEX_DOCUMENT的CARD_EDIT/doc rename/replace/remove/import/preset/config/root transitions，多代合法后续编辑及history保留/GC | v1不猜测兼容、不回滚或清理；受影响场景拒绝 |
| DR-06 | 生产G0→G2 locator/dispatch及其独立预算、Portable两次检查衔接 | 真实Main保持G1B，所有存在域仍阻断 |
| DR-07 | D-038一致备份/恢复/迁移、Desktop→Android支持投影及sidecar保全 | 未批准投影REJECT，不丢弃证据后声称全量 |
| DR-08 | 完整Windows TOCTOU/域外链接/ACL、真实阻塞IO/断电等未证明边界的支持范围与独立验收 | 明确NOT TESTED/NOT PROVEN，所需证明不足时BLOCKED，不扩大承诺 |

本文已给出完整只读wire候选；DR-03–08不是让G2-A用布尔值模拟成功的空缺。Project可只冻结DR-01/02并授权独立的结构识别测试，保留其余生产门禁。

**Build / JUnit / GUI / packaging：NOT RUN。Implementation：NOT STARTED。**

**G2-S1 READ-ONLY CONTRACT CANDIDATE / PROJECT REVIEW PENDING**
