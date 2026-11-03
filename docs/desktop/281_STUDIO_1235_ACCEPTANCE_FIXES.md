# 281 — Studio 1235验收修复

1235已公开包及签名17不改。本轮新修复仅准备后继私有候选；实际验收回执由协调端保留，不能将参数数字/滑条子项通过当成整个Studio通过。用户截图和原Prompt只在本机，不进入仓库/云端；测试只用合成文字和资料。

## 已定位与首片源码（待精确云验）

- S10：Desktop Tools把`DesktopPromptInspectorPanel`内嵌到`DesktopStudioWorkspace`，未给可选关闭回调；外层只有页签返回，预览专注/窄屏抽屉还会遮蔽编辑区及导航。现增加固定局部返回和未被子控件消费的Escape，诊断时暂时让出遮挡；关闭后保留原预览模式/抽屉偏好，不切顶层、不清草稿、不绕过真实Studio离开协议。这个检查器是Desktop自有工具，不冒认Android同名入口。
- S01：普通Prompt、人物、参数都走`controller.edit`，原本只updateDraft，载入/清空的`replace`才保存undo。现普通有效编辑也先保存原单步持久快照；no-op不覆写，原redo及资源引用保全路径继续使用。未擅自改成无限历史或增加持久格式。
- 新增实际Workspace窄抽屉/宽专注的局部返回与偏好保留检查、真实controller Prompt/参数/删角色/no-op/undo/redo检查；既有footer几何断言保持，仅修改符合实际范围的按钮名称。只能由后续精确云端结果确认通过。

## 继续项

S02双语光标、S03官方重置、S04按模型/effort的sampler、S05 V5 effort、S06透明背景、S07人物Prompt展开与正负页签、S08展开编辑窗native chrome、S09账户header融合、S11操作失败具体原因继续，不能因本片两项源码宣称关闭。所有Prompt authority原文保持。

V5能力以NovelAI公开原始资料及实际API字段为依据；文档确认不是实际序列化/资源alpha/预设/历史验证。无需真实Provider调用，不在用户机器重构建或代装。后继公开仍需本版授权。
