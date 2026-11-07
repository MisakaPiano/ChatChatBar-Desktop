# 生图参数、导入与界面相邻增量

2026-11-07。继续固定141→12e实际语义核对，保持公开1244/db7/签26、A1真人FAIL和315已有Prompt未采纳。没有新包、公发、代装、本机JVM或真实资料操作。

## 已复用的参数链

逐diff核对8c五行、a963模型/卡参数两行、a678五行与d21五行，共17条生产路径增量：模型能力/六采样器与旧值归一化、V4.5-only Variety+、PNG可选状态与选中导入、自定义sigma按源画布反归一化及最终画布只缩放一次、Enhance排除原sigma覆盖、卡参数清除导入sigma、显式模型采样重置。d21为合并后的相同语义，不计额外新功能。

`OWNER_NAI_SAMPLING_AND_SIGMA_REUSE.json` 重核16份当前owner/test blob与原162一致；原两归档SHA/CRC及八套13方法均PASS、0FAIL/ERROR/SKIP。分别是VarietyPlusImport2、VarietyResolution2、EffectiveSamplerAdmission3、GenerationDefaults1、CharacterV5Defaults1、DesktopCharacterImageDefaults2、DesktopStudioVarietyPlus1和DesktopSamplerAdmission1。这是原件复用，不是本批新增13次执行；不涵盖首次缺草稿的初始化、整个UI或真实Provider。

## 参与开关

1b0把Studio角色参与生图按钮改为紧凑滑动开关，保留角色身份/排序/折叠/固定/正负Prompt与原变更回调。共用StudioSwitch原四参数调用签名保留，只新增Modifier重载，空标签不占额外文字空间。

control118739、run42333474020、artifact13558359735原51599B，SHA256 `a9181712c760d255a0d06ed9abe8c2396e6b403c9e9d1fd52da6e7c387aa36f1`。长度/CRC/source/全部XML已核：CurrentImage18=17PASS+1既有原生图形前置SKIP，BackupDialog2PASS，共19PASS/1SKIP，0FAIL/ERROR。360/760实际Scene图已目视，角色开关、双页签和长负面框无header裁切；不是原生窗口或真人验收。

## 新发现的三个具体缺口

- 透明标签helper的`\\s`边界与quote helper的ECMAScript边界不同，NBSP/BOM等可令标签落入显式文字区。d406共用已有textStart/whitespace识别，不改保存Prompt或标签文字；原case-insensitive任意逗号tag去重保留，比上游精确suffix更宽，不宣称字节完全相等。
- 显式reset已有23步/模型CFG，但shared仓库缺singleton仍建立旧28/6草稿。634只在缺singleton时对两个模型用既有GenerationDefaults，保留实体decoder默认、已保存草稿原bytes、准入/锁/缓存/恢复顺序。
- High界面动作可在effort变化前排队、之后才交给草稿transform。bd8保护sampler，ab840同时保护High-only Steps/CFG Rescale；CFG Scale仍可在Medium变更，显式reset/undo未加限制。新增Scene用例通过真实控件生成四个transform后延迟应用，分别核休眠值、Medium可编辑值及High正向操作；不冒称物理鼠标或真人故障复现。

最初Boundary R01/R02及InitialDefaults R01/R02都被同一个新夹具漏填characterCaptions阻断：四份原归档只有source、零XML，不计业务反例或业务通过。664只补该测试参数。合法对照R03源ee01（父58c，仅两个测试文件与664同blob，旧生产不动）与修正R04源664已排同组有界检查；前者选新三方法，后者shared新3+原13及Desktop原26。延迟控件后继单独按ab840选受影响的八方法。结果待原件，原失败保留。

原件目录：`I:/CCB-Desktop-Continuity/updates/20261106-upstream-catchup-to149/`。314整体仍`complete:false`，未将路径数当功能数，也未用原reset方法代验新首次初始化。
