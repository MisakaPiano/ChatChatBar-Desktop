# 朋友圈普通备份入口补齐与保留行为复核

2026-11-07。上游固定目标12e、公开1244/db7/签26、A1真人保存FAIL与315未采纳组均保持。不是新普通包或公发记录。

## 为什么原来的版本测试没有发现

`DesktopMomentBackupExtension`、mapping和writer已经支持本机朋友圈动态、图片版本与作用域。既有 `DesktopMomentVersionBackupTest` 手工构造 `AppBackupSelection(moments=true)`，通过了真实归档、检查、恢复和切换旧图；但普通导出对话框既没有朋友圈开关，`BackupExportCatalog.allSelection()` 又仍然设置 `moments=false`，并显示早期“桌面版不把这些内容作为可运行朋友圈”的说明。普通用户的“全选”无法走到已经实现的底层能力。

`8132a2e23f0e6b787d8c693d8956e289c0de065b` 仅改测试：原版本备份测试改用真实catalog的全选/轻量预设，认证归档后检查动态范围、全部图片版本、恢复后的实际store、任务排除和源字节保留，并增加取消朋友圈后的归档排除。实际对话框测试在窄/宽Scene操作朋友圈及图片开关，沿原保护页到真实export callback；没有用直接构造flag代替入口交互。

生产修正 `364727c5bf02b08c20f622c2a0484ce54bba8515` 只做两项：全选包含本机朋友圈；总览显示动态数量、朋友圈开关及其独立图片开关。轻量继续沿既有projection省略正文和版本图片，保留文字/提示词等字段。手机原始保全内容仍要求完整选择；它没有被转换为活跃任务，原任务调度准入没有改变。相关skill已更新。

## 云端检查

修正前控制 `efe2f0abc25cd1cdab4100000cffedb095512a4a` / run42263330923，以8132测试后继保留原生产代码；原四方法为1PASS+3FAIL、0ERROR/SKIP。两个实际Dialog失败分别是轻量回调的朋友圈未选中、缺少可点击的朋友圈图片控件；归档方法原先的“修改图片范围须拒绝”断言没有触发，因为这次全选根本没有带入朋友圈。该失败保留，不冒称它已执行到后面的store恢复断言。

修正后控制 `d2473146817b5fa2210574028793b72a451df448` / run42263523280，以3647生产修正跑普通入口、版本归档、选择、原始保全、合并准备与原service六套检查，原XML共47PASS、0FAIL/ERROR/SKIP：Selection5、RootPreparation10、MomentVersion2、Preservation8、实际Dialog2、FullBackupService20。新版本方法执行到全选/轻量认证归档和恢复后的实际store、全部图片版本切换、任务不自动恢复及取消选择后的无动态/无图片归档；源字节检查也执行完成。

两份原ZIP均核长度、SHA256、CRC、source.json及逐方法结果，保存在 Continuity `updates/20261106-upstream-catchup-to149/`：

| 原件 | artifact / 字节 | SHA256 |
| --- | --- | --- |
| moments-export-r01-original.zip | 13526212637 / 19084 | 27ec30abddff7e2fca2a399fcf3402c2ce7d8abfc3f83f277f92d2fc8a1b97e2 |
| moments-export-r02-original.zip | 13525798093 / 27173 | cb82aef6721932089bc3723a1f081a8603fda63ef405f80f79d29b157900c995 |

## 同期有效证据复用

`OWNER_PRESET_INLINE_HISTORY_REUSED162_REVIEW.json` 记录已重新查阅的实际行为、当前source/test blob及原完整162对应XML：

- 大型Studio Vibe内联值由共享有界pull decoder投影为内容哈希引用；实际存储reader只对原四种Studio类型启用，源JSON不改写。源文件缺失与派生目录/哈希故障分开，后者不伪装为Missing或默认值。原shared4+3+3方法通过；当前 `JsonFileStorage` 相对162只把 `withNormalOperation` 可见性从private改为internal，读取函数体未变，不能写成整blob相同。
- 预制卡使用共享依赖resolver，Desktop启动、显式恢复及整套恢复都经同一 `DesktopPresetSource`。声明的世界书带来源身份，已有内嵌格式卡优先，否则查默认格式键；缺依赖在写卡之前失败。当前owner/caller和测试blob未变，复用原shared2与实际Desktop集成3方法。
- 历史批删经实际controller重新读取所有受影响项，再由原storage writer锁一次提交元数据，随后才做引用保全清理。坏的后续项不导致前面项目或媒体部分删除；未选历史仍引用的图片保留，新生图写入与同一锁排序。复用原实际controller三方法；不把捕获错误回滚说成新增崩溃恢复journal。

原件run42085881455/checkout57011ef9与SHA记录沿317；这18个原方法是复用，不是本轮新执行，不覆盖后来的朋友圈入口改动。

同期新建对话图标源ac03143b的实际编译及原输入界面邻接三套32方法已通过：run42261803705/control9d7b913b，artifact13525303366，16828B，SHA256 `6842af72181221b5d7ba43b7f36884fce54b19ab8160221069d2999c3700f73d`。原ZIP的CRC、source.json和32方法已核；没有为图标常量新增镜像测试，也不冒称原生真人验收。
