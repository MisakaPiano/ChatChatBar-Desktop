# 冷仓库维护准入与选定上游行为复核

2026-11-07。固定141→12e追齐中的独立补修；整体仍未完成。公开1244/db7/签26、A1真人FAIL、315未采纳的已有Prompt组均保持。本批没有本机JVM/程序运行、新包、公开发布或真实资料操作。

## 冷模型仓库

`DesktopApiAccountController.change` 已有normal准入，随后调用 `ModelRepository.getAllModels`。普通模型列表/账户选择器也可以在无外层lease时首次读取同一冷仓库。旧初始化先取initializationMutex再等待storage准入：维护排队后，后来读者可能占锁等待维护，已准入的账户操作等该锁，维护又等账户操作退出。

820f只新增实际仓库/协调器夹具；原Chat/Settings四方法保留。R01旧生产原五方法4PASS+1FAIL，新冷模型用例在5秒界限内未能排空。5c77把normal准入前置到初始化锁，仍在锁内只执行一次迁移、缓存刷新和受保护凭据hydration。R02原六套40PASS=shared8+Desktop32、0FAIL/ERROR/SKIP，包含实际账户/模型设置及冷仓库用例。不是全仓或真人验收。

| 检查 | 普通源 / control / run | 原件artifact / 字节 / SHA256 |
| --- | --- | --- |
| Model R01 | 820f7bde / 683159f7 / 42327501199 | 13555652742 / 11208 / 4c05c792f23a590906f46f169f514c25b9a78a9b453dcaf82bf12e3e04f0acda |
| Model R02 | 5c77d226 / 9d6306c9 / 42327591050 | 13556322648 / 32400 / d39e7666b723c96b707f4eb65057b814a541f10618eb97ea9c28595a0e93366d |

## Studio仓库防护与调用链边界

共享draft/undo/history和Desktop编辑历史也存在mutex→storage准入顺序。e577增加冷draft读取和独立history写者两个仓库级有界反例；R01原七方法5PASS+2FAIL，均为这两个新增方法的5秒等待失败，0ERROR/SKIP。它证明真实repository/coordinator组合的锁顺序风险，不代表已从用户界面复现。

c71把这些持久化锁置于normal准入之后，保留pending/committed journal、NonCancellable reconciliation、重开恢复、undo/redo和纯内存快速读取。R02在Desktop编译阶段失败：共享模块 `JsonFileStorage.withNormalOperation` 为internal，Desktop子类不能直接调用；原318B归档仅source.json、无XML，不能给任何业务PASS。a914以共享仓库protected `withStudioDataAccess` 窄入口修正，底层storage helper仍internal。后继R03原六套33PASS、0FAIL/ERROR/SKIP：Admission7、DraftHistory9、InputHistoryScene4、PresetController4、PresetExchange1、TextHistory8。两条仓库等待反例均通过，且实际预设/输入历史场景保留；不扩大为普通UI死锁复现或真人验收。

普通 `setDesignRequirement`、字段编辑和预设操作都通过同一controller action mutex；此前“saveDesignRequirement可绕过action”的说法错误，当前无该方法，普通编辑/预设两条本身不构成独立写者。设计任务的旧imageDescription清理确在独立TaskRuntime job写draft；importMetadata/importGuidance则在normal准入内更新，且没有预设的requirePresetIdle检查。这是源码可到达的候选交错，尚无实际controller/UI有界复现，和仓库级证明分开。未把整个action套上normal lease。

| 检查 | 普通源 / control / run | 原件artifact / 字节 / SHA256 |
| --- | --- | --- |
| Studio R01 | e5779cda / fabe8153 / 42328220912 | 13555713778 / 11691 / e1a34c7c4a0673d6892f7599ef41451427e5b40cd28a73da03dfae9d428c94de |
| Studio R02 | c71a0170 / 8f82698b / 42328359943 | 13556760410 / 318 / 76c9a02fc8de093bf6be85e06961fbe236c986e62190278e3288a0572b94dc48 |
| Studio R03 | a9146b8d / ec45775e / 42328925067 | 13557170710 / 23618 / d74832a8f48ec1f2c23111a60f8f1258d3e071bb685f3d23ce93306f08cfe06a |

原ZIP均已核长度、SHA256、CRC、source.json与全部实际XML；编译日志另存。旧失败不因后继修改而抹除。

## 选定既有行为复用

`OWNER_SELECTED_RESEARCH_VOICE_AND_EDIT_REUSE.json` 逐份重核当前Git blob和原162归档SHA/CRC/XML；首组在314映射九条明确生产delta，随后角色位置四条及formal TTS两条合计十五条，混合UI/Prompt行继续待核。

- 8c的五个research/brief/appearance/world-book参数delta：选定模型的thinking/effort/output及custom字段传到实际请求，不再强制low/关闭thinking。复用原shared8+Desktop2=10个方法；当前transport/container有后续实验模式接线，不能称整blob未变，相关后继另见316。没有改已有Prompt文字。
- ed5的独立VoiceResponsePolicy和FishAudioTagService两条：完整JSON envelope、独立传输/输出重试预算、取消和永久错误拒绝。两个实际owner与三个测试blob精确未变，原15PASS复用；未知TTS接受情况不重放，试听gateway另有32方法证明，不推广全语音UI或真实服务。
- 559的候选编辑策略和segment helper两条：编辑当前候选保留兄弟候选和durable版本身份。当前共享策略已有149模式保全八方法证明；实际Desktop编辑方法及原测试方法相对原162未变，原controller41方法归档可复用。当前完整controller/test因增加模式设置而不同，不冒称整文件相同，也不把316中只选跑一个新模式方法当41方法重跑。

原162共享归档SHA256为 `94dbb24756e3e03ba2cd96f56f3c4204f13e24a6cf0c109c3941d8e74f4d17a1`，Windows归档为 `6060fda49658efc08fe19b14d55a83c94ea6731bf430ffc481adac1ccd2af5f3`。这些是匹配原件复用，不是本批新增测试总数。

随后补核较近的316/R04原件：其SelectedAuxiliaryModelParametersTransport4和DesktopMemorySelectedModelParameters2均原PASS；当前transport、Desktop memory client、MemoryAiGateway与RetrievalPlanner和该已测2e8源精确相同。因此这六个方法还有149实验接线后的实际wire证据，世界书四方法仍按原162和当前函数调用复核分列，不能称316也跑了它们。

354的四条角色位置运行delta另已核：shared request builder、metadata reader、import、draft/recipe模型、position policy及原测试六份blob相对162精确未变。原 `NovelAiCharacterPositionTest` 五方法全PASS，覆盖正负共享坐标/各层use_coords、自动与空角色关闭定位、V4.5网格/V5连续坐标、序列化/历史恢复/重排、PNG导入与未勾选保留。Desktop现有位置编辑器经controller edit持久化且保留实际请求画布比例；这里只映射运行链四行，未把源码查看当新指针UI测试，也不关闭已有Prompt或三个Android UI混合delta。原件回执 `OWNER_CHARACTER_POSITION_CHAIN_REUSE.json`。

ed5其余FishAudioModels/Android Service两条由共享类型和实际Desktop `DesktopFishVoiceTransport` 等位承接。transport、类型及原 `DesktopFishRejectedRetryTest` 三份blob精确未变；原四方法通过真实loopback HTTP检查429明确拒绝后的同body/冻结凭据重试、401及已接受坏音频不重放、backoff取消、POST后断连未知接受不重放。原四PASS见 `OWNER_FORMAL_TTS_REJECTION_REUSE.json`，和共享policy15及试听32各自分源，不冒称新19/全语音一次运行或付费服务验收。

## 后续七条精确映射

`OWNER_CARD_MEMORY_STARTUP_REUSE.json` 另复核七条，仍无新增运行：

- 8c的CharacterCard/Package/transfer三行：实际共享Core双向copy默认生图设置、按所选模型验证，旧缺省null和非法值在资源写前拒绝；五份owner/test blob和原Core22/Desktop typed JSON/PNG2一致。仅复用其中明确参数保全的两条Core方法及两条实际Desktop集成范围，275已有原边界，不改schema或已有Prompt。
- 8c的MemoryAiGateway/RetrievalPlanner两行：模型identity原样继承、关闭强制disable/isolation，planner保留120秒上限。当前四个owner与316已测2e8精确相同，原实际Memory/RAG wire2方法通过。两个条目是前述research较近证明的具体映射，不是额外再跑两例。
- d3的两个QQ服务行只新增备份启动ready后attach/启动及未初始化player保护；Desktop先StartupGate、再await initializePersistentState，之后才构造business/voice/external owner，实际lifecycle方法及三份owner/test保持原162身份。原StartupGate21和ProcessLifecycle15方法匹配通过。只给这两条新增启动guard平台等位，不把QQ自动发语音全功能标为N/A/已验，也不从启动证明推导MP3保存或手动转交。

本轮320两组共22个delta，314整体仍未完成。原有公开、候选、运行身份和真人FAIL均保持。
