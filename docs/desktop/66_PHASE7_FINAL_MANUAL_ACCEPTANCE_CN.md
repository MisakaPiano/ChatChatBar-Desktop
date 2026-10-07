# 66 — Phase 7 Current-Upstream Image Closure 最终人工验收

这份给用户。

原则：
- 不需要真实烧 NovelAI 生图额度；
- Enhance/Upscale 不要求真实调用；
- AI Design 如果使用真实模型测试会消耗文本模型额度，但不是 NovelAI 生图；优先先做本地/fake验收包能覆盖的部分。

## A. AI Design

- [ ] 打开后明显是聊天界面，不是表单。
- [ ] 顶部直接看见实际设计模型名、Provider、认证状态。
- [ ] 没有凭据时发送按钮不可用，并能直接去设置。
- [ ] 有有效模型时能正常进入生成流程，不再先发请求再报认证失败。
- [ ] 用户消息和 AI 回复按对话顺序出现。
- [ ] AI 回复按基础 Prompt / 角色 Prompt 模块显示。
- [ ] 每个 Prompt 模块有 Copy。
- [ ] Apply to Studio 明显。
- [ ] Edit & branch 属于用户消息动作。
- [ ] Regenerate 属于 AI reply 动作。
- [ ] Tag/Codex research 过程可展开看，默认不淹没回复。
- [ ] 中文 IME/caret 仍正常。

## B. 角色 Prompt 卡

- [ ] header：
  `角色 N  已启用 ... [↑][↓][✓][删除][⌃/⌄]`
- [ ] 停用：
  `角色 N  已停用 ... [↑][↓][×][删除][⌃/⌄]`
- [ ] 停用卡整体灰。
- [ ] 停用后卡不消失。
- [ ] 停用后仍可移动/删除/启用/展开折叠。
- [ ] 停用角色实际不计入生成角色数/token/request。
- [ ] 重新启用内容完整恢复。
- [ ] 折叠与启用完全独立。
- [ ] 普通折叠最多约三行，不影响生图。
- [ ] Base Negative 在 Extra 后面。
- [ ] 各同级 Prompt 区块都有独立卡片边界。

## C. 角色位置

- [ ] 可以选择 AI 自动 / 自定义位置。
- [ ] 可以点/拖画布。
- [ ] V4.5 吸附 5×5。
- [ ] V5 可自由位置。
- [ ] disabled 角色不进入位置列表/最终 request。
- [ ] 排序后角色位置仍跟角色 ID。

## D. V5 Usage

- [ ] 顶部有 V5 usage bar。
- [ ] 显示百分比。
- [ ] 显示约可生成张数。
- [ ] exhausted/unknown 有清楚状态。
- [ ] 不伪造 “14.3%/day” 等当前 API 没有 authority 的信息。

## E. Tag 库

Completion：
- [ ] 输入半个 Tag 自动出现候选。
- [ ] 显示英文/中文/category/count。
- [ ] 上下键 + Enter/Tab 可用。

Inspection：
- [ ] 只移动 caret 到已有完整 Tag 中间也会查询。
- [ ] exact Tag 优先。
- [ ] 不会因为 inspection 自动改文字。
- [ ] 明确选择候选才替换整个 Tag。
- [ ] `1.5::tag::`、source#/target# 正常。
- [ ] 全屏编辑同样可用。

## F. Studio result pane

- [ ] 默认选最新。
- [ ] 新生成结果自动选最新。
- [ ] 大图随右 pane 尺寸变化。
- [ ] pane 可调宽。
- [ ] 可折叠成右侧 filmstrip。
- [ ] 可进入 preview-focus。
- [ ] 窄窗口变成顶部 compact image panel，而不是把预览挤到很下面。
- [ ] selected-image actions 不重复两套。

## G. Viewer

在聊天、Studio、History、背景库分别抽查：

- [ ] 鼠标滚轮放大/缩小。
- [ ] 放大后拖动看局部。
- [ ] Reset/Fit 正常。
- [ ] 可 resize。
- [ ] 可 maximize/restore。
- [ ] 标题不重复。

## H. History

- [ ] 主历史是自适应网格。
- [ ] newest first。
- [ ] 折叠相册仍正常。
- [ ] 搜索/日期正常。
- [ ] 范围选择可用。
- [ ] 选中 badge/范围状态清楚。
- [ ] viewer 正常。
- [ ] History 窗口可 resize/maximize。

## I. 图片导入 / metadata

- [ ] 主 Prompt 区没有孤立 PNG metadata import 按钮。
- [ ] Copy structured positive Prompt 清楚。
- [ ] Paste structured positive Prompt 清楚。
- [ ] 导入图片后在工具内选择 metadata。
- [ ] 角色 metadata 有 关 / 覆盖 / 新增。
- [ ] 新增不会破坏已有角色 ID/enabled/center。
- [ ] 普通 PNG metadata 和 alpha stealth metadata 都能读。

## J. Enhance / Upscale

仅 UI/fake 路径验收，不需要真钱调用：

- [ ] Image Tools 有 Enhance/Upscale。
- [ ] before/after 可拖分隔线比较。
- [ ] 参数/费用说明清楚。
- [ ] Stop 存在。
- [ ] result 可以保存/分享/继续作为工具输入。
- [ ] 不会因为打开工具就改 Studio Prompt/history。

## K. Privacy

- [ ] Image Tools 有明确 privacy export。
- [ ] 文案说明会清除 metadata + stealth carrier。
- [ ] 原图保留。
- [ ] animation 不会被偷偷压平。

## L. Chat attachments

- [ ] 图片按钮/全屏/Send/Stop 靠输入框右下。
- [ ] picker 可一次选多张。
- [ ] 多图超宽可横向滚动。
- [ ] pending 图片可拖动排序。
- [ ] Ctrl+V 图片可加入附件。
- [ ] 拖文件到 composer 可加入附件。
- [ ] 点击看大图；hover × 删除。
- [ ] APNG 正常上传，不重复错误。
- [ ] 换图/删图后旧 APNG 错误消失。
- [ ] ChatBar disguise APNG 会自动还原 owned copy。
- [ ] 普通第三方 APNG 不会被错误当 disguise。

## M. Message image actions

- [ ] Assistant 的“生成图片”变成清楚的 compact image action。
- [ ] “生图要求/设定”变成 compact settings action。
- [ ] 不再是两个巨大按钮。

## N. Advanced / Diagnostics

- [ ] 和工具主 tabs 同一行。
- [ ] 在最右侧。
- [ ] 不再独占一行。
- [ ] Prompt Inspector 仍在里面。

## O. Session Settings

- [ ] tabs 可用。
- [ ] dirty state跨 tab 正常。
- [ ] Save/Cancel 始终可见。
- [ ] 图片 tab 内容正确。
- [ ] background immediate-save 语义未破坏。

## P. 回归

- [ ] 中文 Prompt annotation 仍通过。
- [ ] Guidance 仍通过。
- [ ] CharacterCard Prompt import 仍通过。
- [ ] Background library 仍通过。
- [ ] Character → Start Chat 仍通过。
- [ ] 普通聊天发送/编辑仍通过。
- [ ] 重启 persistence 仍通过。

只有用户明确 PASS 后才允许 Phase 7 ACCEPTED / merge。
