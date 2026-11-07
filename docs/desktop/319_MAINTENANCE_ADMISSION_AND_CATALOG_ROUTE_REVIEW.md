# 数据维护准入与词库网络路径补齐

2026-11-07。固定上游目标仍为12e；公开1244/db7/签26、A1真人保存FAIL、315未采纳的既有Prompt组保持。本批不建立新安装包、公发或真人验收结论。

## 实际缺口与最小修正

`ChatRepository` 已有会话设置的 gate→mutex 顺序，但消息追加、索引和阅读位置仍先占仓库mutex再进入storage准入。维护排队后，后来调用可能占住mutex等待准入，挡住已经准入、需要同一mutex才能结束的旧调用；存档在exclusive发布内重载缓存也可能被后来冷索引读取挡住。`SettingsRepository` 同样先占singleton mutex，账户变更内已准入的设置重载存在相同顺序冲突。

`0b95c58c` 复用既有 `JsonFileStorage.withNormalOperation`，把这几类仓库mutex放在准入之后。实际协调器保留嵌套normal/exclusive上下文复用；没有把normal升级为exclusive，也没有取消仓库锁、CAS、失败读回、事务日志或缓存同步。`DesktopChatRepositoryAdmissionTest` 使用真实协调器、真实JSON仓库、已准入旧操作、维护排队和后来调用，分别覆盖追加、exclusive重载、阅读位置新旧capture和设置刷新；等待有界，finally取消并join所有测试Job，原件只使用合成根。

词库普通入口 `DesktopNovelAiStudioController.checkCatalog/installCatalog` 没有注入client。其共享checker与Desktop installer原默认都是普通OkHttp，遗漏应用的HTTP/SOCKS/DIRECT选择、配置不可用时的拒绝和platform socket hook；不能据此说系统JVM代理也完全无效。`0b95c58c` 改用既有 `ProxyAwareClient.builder`；`55b7cc31` 将installer期限对齐上游connect15/read60/write15秒，checker仍为10/20/10。可注入client、不可变数据库/索引、最终authority指针发布与失败清理保留。

新默认路径测试构造合法的本地catalog及ranked index，直接构造与普通Studio入口一样的无client覆盖实例。它检查两实例的platform socket hook、动态HTTP/SOCKS/DIRECT选择，并在routing unavailable时实际调用checker和installer、断言拒绝且authority不变；测试socket factory禁止联网。它不是物理CONNECT/SOCKS字节证明，既有物理路由测试负责另一范围。

## 原始检查与修正夹具

R01：正常测试源59b8、控制377f065e、run42323491109。Desktop测试编译在两个ID断言失败：`getMessageIds` 返回Set，新夹具误用了List。原artifact13554326816仅320字节source.json，**没有XML或业务红例**。原job log保留。

R02：正常源380b38c0、控制bda93065、run42323711300。共享五套原XML共44PASS、0FAIL/ERROR/SKIP：条件消息更新24、仓库基础10、设置草稿合并3、设置存储安全4、共享代理3。随后Desktop仍因相同夹具编译错误停止；没有Desktop业务结果。原artifact13554866080为23412字节，SHA256 `0d1ae8074e076aa8ce88e6efb7cb014a9e87207f2182616ba8fa21f177f9498d`，CRC/source.json/逐方法核对完成。

条件更新故障夹具继续在实际消息写入、索引准入/实际写入和预览准入/读取/写入前后注入失败；新增外层与索引准入改变了计数，故调整对应位置，并补齐原实际索引写入IOException。24方法原有持久化内容、重开、预览未写、取消和有界不确定读回断言没有删除。

`2e3cc9b1` 按真实Set接口修正两处断言。R03以59b8旧生产树为父，仅更换两份与最新相同的合法测试夹具（Set和有效catalog index），形成1cd069ec；控制138b3838、run42324264543，原七方法2PASS+5FAIL、0ERROR/SKIP。四个准入用例分别在实际旧操作结束/exclusive重载等待处触发5秒超时，源码中的mutex→gate等待环与有界排程对应；另外新默认client用例在首个checker的socket hook同一性断言失败，未执行到后面的installer/failclosed断言，不能把未到达的路径也记为旧版反例。原有两项catalog方法通过。

R04以包含所有生产修正及15/60/15期限的2e3cc9b1为父；控制676b454a、run42324271381，原13套114PASS=shared44+Desktop70、0FAIL/ERROR/SKIP。桌面原方法包含准入4、默认/既有catalog3、协调器15、SaveSlot service24、聊天维护5、设置一致7、账户store6、受保护设置6。新增用例实际执行到known synthetic file读回、后续排队调用继续、较新阅读位置不被覆盖、两种默认client路由与配置不可用拒绝，原SaveSlot/凭据/草稿保护没有删减。该原件不包含后来b2e2/72de的外部备份密码入口改动。

| 原件 | artifact / 字节 | SHA256 |
| --- | --- | --- |
| maintenance-catalog-r03-original.zip | 13554212853 / 17855 | 48e989f1e15cb08eb5bdf220f0a3df035cf78e8a66c65b12f3ec0d83074c47a8 |
| maintenance-catalog-r04-original.zip | 13555007252 / 55753 | 3de26180f4a314e71ad780dbc2b6e17ede0355fce3e5084f52f44c211f1fa367 |

原件位于Continuity `updates/20261106-upstream-catchup-to149/maintenance-catalog-r0*-original.*`，控制分支只变更workflow；R03的独立测试基线额外保留其59b8父树和两份test-only变化记录。原编译失败不覆盖、不改为成功。

## RAG与朋友圈已有维护语义复核

`OWNER_RAG_MOMENT_ADMISSION_REUSED162_REVIEW.json` 重新核对原full162 Windows ZIP长度/哈希/CRC、八份当前实际owner/test blob和13个原方法。文档发布6、历史索引3、朋友圈controller4原方法均PASS、无skip；这些是原run42085881455的复用证据，不是当前HEAD新跑13PASS。

- 普通文档索引走 `DesktopAppContainer`→`DesktopDocumentIndexService`，TaskRuntime注册完整工作，短exclusive获取源快照，向量生成期间不持exclusive，最终exclusive重验源/配置并发布journal；取消、失败、过时源保留原索引。
- 普通历史索引走 `DesktopChatMemoryIndex`，实际源/向量快照和发布有audio/exclusive屏障，排除当前上下文并核对来源。它不是调用未包装的共享 `RagManager.index*`；上游Android LocalDataMaintenance包装不能直接按文件名复制。
- Desktop朋友圈工作走 `withCardDesign`→normal gate，本机调度授权绑定资料根，导入或恢复带来的未批准计划保持暂停；Android Alarm/Receiver不作为Windows运行时复制。

上述实际owner和测试源码相对原162精确相同；变化的Chat/Settings准入与词库默认网络路径由本批独立检查负责。314整体仍 `complete:false`，其他混合UI/备份/Prompt增量继续逐项处理。

## 外部备份入口的独立后继

源码复核确认另一个实际UI流程缺口：数据页已经先选文件、检测加密再问密码，外部队列Host仍提前显示密码框，且调用不保留密码重试路径的旧 `inspectFile`。同进程原本可以重试检查，不能夸大为每次必须重选或丢失文件。

`b2e2ab39` 复用原merge密码owner，新增selected external path入口，外部队列按需进入PASSWORD。错密重试仍绑定当前item/attempt和controller-private source，取消回同一READY头部；skip先join discard再ack。明文直接进入检查，仍不自动合并。共享password dialog在外部模式不提供另选文件按钮；presentation仅增加归属标志，密码仍仅在原volatile参数/finally清零边界，不进入队列、日志或持久state。数据页不重复承载队列密码窗，也不因自身离开取消它。

`72de1730` 将external owner标志前置到busy发布之前，防数据页离开先于检查dispatcher执行时取消队列工作；新有界dispatcher测试专门覆盖此顺序。外部Host、Controller和原数据页合并/文件检查/密码展示流程正在独立检查；不纳入前述114结果。旧source与后继source、每轮原件分别保留，不依据job运行中状态预记通过。
