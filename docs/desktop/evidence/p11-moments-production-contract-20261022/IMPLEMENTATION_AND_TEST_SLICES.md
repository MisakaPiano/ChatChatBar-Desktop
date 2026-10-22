# 候选实施切片与验收矩阵

2026-10-22。DRAFT，全部生产改动与执行测试须另批。依据 [审计](UPSTREAM_145_146_147_IMPACT_AUDIT.md) 固定O/D源码索引；本轮仅阅读，没有执行下列测试。旧POC的21/21、7/7、3/3只属于旧实验，绝不是以下GREEN。

## 顺序与交付边界

| 切片 | 目标/候选允许文件（获批后） | 独立验收与依赖 |
|---|---|---|
| S0 格式只读 | Desktop backup/AppBackupCodec、AppBackupModels、DesktopFullBackupArchive旁的独立inspect适配与测试；不能直接放宽现有恢复入口 | v1/v2 envelope/manifest/scope/版本、选择意图和秘密类别可解释；未知语义拒绝。只读成功不能恢复 |
| S1 封存验证 | 新独立Moments原bytes验证器、资源图/预算/来源清单及测试 | 覆盖三类图片路径、历史缺头像、原包秘密边界；输出只为验证报告，尚不注册域。依赖S0目标格式合同 |
| S2 生产保全域 | 新容器/拥有权；D04 policy、D05 mapping、D06 journal、D07 startup、D08 coordinator、D09 ownership、D10/11 root migration、自动快照/Portable相关路径 | 新域+备份+GC+root switch+旧版本防写必须作为同一投产门槛；不能只上线目录后补恢复。依赖用户schema/配额/降级决策 |
| S3 安全再导出 | 独立输出planner/adapter和合成官方测试工具；不修改官方生产代码 | 原包取回、sealed派生、当前全量三种输出分开；145v1及候选146/147v2各验。无Task安全接收合同则拒绝运行输出，不能为过测删Task |
| S4 严格合并 | 新NON_DESTRUCTIVE_ADD_ONLY planner、图映射、结果manifest与S2统一提交事务；UI另批 | 完整目标不变+来源完整两套断言；全局单例/秘密/模型fallback冲突必须闭合；不替换现有REPLACE模式 |
| S5 C1/C2独立项目 | C1类别预览/主动排除适配；C2另需SecretStore与数据协调事务 | C1有限迁移不等于D-038；C2未授权前不改D12/13、不写秘密。不可混入S0–4偷渡 |
| S6 集中运行验收 | 已冻结候选、Windows GUI步骤、官方Android模拟器/真机集中材料与新证据 | 工程GREEN后再申请一次明确执行授权；隔离根/账号/网络。物理设备和真实数据条件分别确认，未测NOT TESTED |

“候选允许文件”不是本轮授权，也不是预先要求重写所有文件。实施者应先复核固定起点与技能，最小改动，仍不得改官方Prompt、正式baseline或官方APK行为来使测试通过。S2可拆内部checkpoint，但所有域和旧版防写未闭合不得发布。

## 每个切片共同的证据合同

- 保存RAW RED：未修复/拒绝基线、失败阶段与安全错误码、原始测试XML/进程退出结果；不能改旧历史。GREEN包含实际生产路径测试，不只Python模型。
- 记录生产SHA、测试SHA、夹具SHA/长度、官方源码SHA、构建/发行APK身份、操作系统/运行环境、命令、真实退出码（不可得UNKNOWN）及证据路径。不能把模拟器结果称真机PASS。
- 本轮无功能测试，未来每片结果分CURRENT_SUPPORTED/SOURCE_PROVEN_ONLY/REQUIRES_ADAPTER/PENDING_TEST/BLOCKED；用户反馈另分USER PASS/FAIL，不由工程测试替代。
- 每片都提交独立可审diff和真实SHA交Project Code Review；未批准的下一片不因前片GREEN自动实施。新schema/所有权/事务对项目skill产生事实变化时同步更新，草案本身不改变现技能。

## 可执行测试设计

| 组 | RAW RED / 负例 | GREEN需要证明 | 适用 |
|---|---|---|---|
| 格式/密码 | v2缺scope、未来版本、非法option、envelope/manifest不一致、错误密码、末块损坏/截断、header超限 | 完整流认证后才接受；原包不变；安全错误不含正文或密钥 | S0/S3 |
| 范围 | 缺default/override、明确选空与未选、Moments图缺省、独立SaveSlot媒体、未选依赖携密钥 | 展开的选择计划准确，默认不等于静默遗漏；已选/保留/拒绝均可解释 | S0/S4 |
| JSON/资源 | 重复ID/key/entry、未知字段含潜在路径、绝对/UNC/ADS/..、alias逃逸、大小写碰撞、炸弹、资源长度/hash错 | 无越界读写；未知运行结构拒绝；原raw字段与资源逐项完整 | S1/S2/S3 |
| Moments图 | 缺Post/Task关联、metadata第三路径、共享图、历史缺头像/正文缺图、跨包同ID | 只允许版本明确的历史缺失例外；不借同ID live对象补证；删卡后封存不损 | S1/S2 |
| 生命周期 | GC与导入竞态、root switch/Portable、自动备份漏域、旧版直开新根、重复导入 | 域清单与锁闭合；不支持旧版写入前阻断；全量成功不能漏域 | S2 |
| 事务 | 每个journal durable写前后终止、snapshot/rename/commit磁盘满、seal篡改、跨root journal、重复apply | 完整旧或新，未恢复不能开业务；原档/历史/旧根保留；清理只属本事务 | S2/S4/S5 |
| 官方再导出 | 145v1与146/147v2、明文/加密、选择性三模式、v8 filesDir alias | 原bytes保全与必要ID/path转换分别对照；真实官方导入/打开/再导出，未测不签PASS | S3 |
| Task屏障 | 各状态、past/future、enable、cold boot、clock推进、ensureSchedules新任务 | C-A零运行注册/网络/任务创建；C-B只证明限定转换，不能永久安全；C-C另项目 | S2/S3 |
| 严格合并 | alias同ID异endpoint、重名歧义、已存单例/草稿/旧包、Memory/voice引用冲突 | 目标保护清单不变，新增图/资源闭合，旧secret无读写/误绑定；未知不部分成功 | S4 |
| Secrets | 偏好未选但模型依赖仍含apiKey、selected null、源账户与目标冲突、失败回滚 | 类别不漏报；本轮仍拒绝，未来C1主动排除有限，C2跨域失败不伪成功 | S0/S5 |

资源峰值需要真实容量实验而不是任意保守阈值；空间测试用受限合成卷/故障注入，不填满用户磁盘。错误注入只针对专用进程与根，不结束未知程序。

## 授权边界

后续本地主机合成测试可随实施执行令批准；本轮未批准运行。Windows GUI新启动、官方Android模拟器安装/启动/ADB、物理设备、USB调试/ADB reverse、真实档案和真实账户均必须有明确范围。手机127.0.0.1不是电脑；优先仅loopback合成服务，网络路由变更逐项写入执行令。不得自动安装到已有正式CCB环境。

所有切片使用隔离合成数据与fake/禁止外联检测；不接真实LLM/Fish/NovelAI，不读取四份受保护用户文件。本轮推送仅八份文档，停止在Design Review Gate。
