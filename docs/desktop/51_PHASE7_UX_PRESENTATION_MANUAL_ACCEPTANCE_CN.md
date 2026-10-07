# 51 — Phase 7 UI 表现层最终人工验收（中文版）

这份给用户看。

本轮验收包（2026-10-07；旧 R2/R1 包不用于本轮）：

`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-ux-presentation-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

Production source：`4a02cbafe74605d7f14e54ee67d2b4bc846f219e`。Desktop full 109 suites / 990 tests、compile、隔离包及启动检查已通过；详细证据与哈希见 `53_PHASE7_UX_PRESENTATION_REVIEW.md`。下方均为待用户确认的人工项目，自动测试不会代签。打开 Studio，使用本地图片及普通文本，先按 1280 宽、再缩窄窗口检查；邻近回归关注聊天文本发送/展开编辑、History 缺失来源提示、Guidance Use-as 与背景选择。

不要执行真实 NovelAI Generate / Retry。

## A. 第一眼

- [ ] Studio 不再像“后台配置表单”。
- [ ] Prompt 是主工作区，结果预览是辅助工作区。
- [ ] 常用控制没有被大面积空白和巨型按钮淹没。
- [ ] 窗口 1280 宽时信息密度自然。
- [ ] 缩窄窗口后仍能操作，不需要水平滚动整个页面。

## B. 生成设置

- [ ] Small / Normal / Large / Wallpaper 是直接按钮。
- [ ] Portrait / Square / Landscape 是直接按钮。
- [ ] 当前像素尺寸能一眼看到。
- [ ] 自定义宽高只有点“编辑尺寸”时才出现。
- [ ] 数量 1 / 2 / 3 / 4 是直接按钮。
- [ ] Steps 是滑杆，并能看到准确数值。
- [ ] CFG 是滑杆，并能看到准确数值。
- [ ] CFG Rescale 是滑杆，并能看到准确数值。
- [ ] Sampler 是普通下拉，不打开大窗口。
- [ ] Random Seed 是开关。
- [ ] 关闭 Random 后才出现 Seed 输入。
- [ ] Advanced 默认可收起，并能从摘要看到当前设置。

## C. Prompt

- [ ] Paste / Clear / Translate 是 Prompt 标题附近的小动作，不占一整行大按钮。
- [ ] 翻译打开后，中文注释能和对应英文 tag 对得上。
- [ ] 中文注释不会写进原 Prompt。
- [ ] 编辑 Prompt 后注释会跟着更新，不残留旧位置。
- [ ] 全屏编辑也能看到同类中文注释。
- [ ] Tag suggestion 在当前输入框附近出现，不把整个页面撑得很长。
- [ ] suggestion 同时能看英文 tag 和中文含义。
- [ ] 角色负面 Prompt 默认不抢占大量空间。
- [ ] 每个 Prompt 不再重复出现一个很大的“展开编辑”按钮。

## D. 顶部工具

- [ ] 当前模型 / Anlas 是状态，不像普通操作按钮堆在一起。
- [ ] AI设计 / 图像引导 / 导入 / 历史 / 设置容易找。
- [ ] 禁用的图像工具不会和主操作占同样视觉重量。
- [ ] 简单选择不会弹一个 880×780 的大窗口。

## E. 结果区

- [ ] 当前图片是视觉主体。
- [ ] 上一张 / 下一张 / Seed / 新种子 / 仅 Seed 容易理解。
- [ ] Metadata / Use as / Guidance 属于次级动作，不喧宾夺主。
- [ ] 右侧不会因为动作按钮过多显得像控制面板。

## F. Chat composer

- [ ] 图片附件入口像聊天软件的附件按钮。
- [ ] 没选图时不永久占一整行附件区域。
- [ ] 自动生图 / 背景入口是紧凑 toolbar/menu。
- [ ] 输入框本身占主要空间。
- [ ] 整个 composer 不再因为图片功能多出两三层大按钮。

## G. 翻译

这是 Phase 7 必验项，不是 future phase。

- [ ] 开启中文注释后确实有视觉变化。
- [ ] 关闭后注释消失，Prompt 原文保持。
- [ ] 常见 Danbooru tag 能显示本地中文。
- [ ] 未知 tag 不会破坏输入。
- [ ] 词库/翻译读取失败时有提示，但 Prompt 仍可编辑。

## H. Provider capability 不在这轮验收

本轮不要因为 NovelAI Web 的 sampler/model 列表不同而要求 Desktop 私自增加选项。

只检查：
- [ ] 当前 CCB sampler 用一个正常下拉展示。
- [ ] UI 没宣称它是“NovelAI 当前全部 sampler”。

Sampler/model provider drift 由 `49` 单独审计。

## PASS 规则

如果功能都存在，但用户仍然需要：
- 找隐藏入口；
- 打开大弹窗做简单选择；
- 手输本应滑动/点选的常用参数；
- 在拥挤按钮堆中寻找主操作；

则 UI Presentation 仍然 FAIL。

只有这一轮通过后，才继续 Phase 7 最终 ACCEPTED / merge。
