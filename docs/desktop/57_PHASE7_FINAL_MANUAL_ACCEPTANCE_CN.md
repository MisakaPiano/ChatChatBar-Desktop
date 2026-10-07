# 57 — Phase 7 最终产品人工验收（给用户）

不要执行真实 NovelAI 生图。

这次只验最终产品闭合，不要求你读技术审计文件。

本轮新包及哈希由 `59_PHASE7_FINAL_PRODUCT_REVIEW.md` 记录；旧 presentation/R2 包不作为本轮验收包。

本地 AI Design 验收窗口（无需任何 key；fake transport；临时 profile；关闭后清除）：从 `app/` 使用既有 JDK17 运行 `./gradlew.bat :desktopApp:runPhase7FinalProductFixture`。窗口顶部可选 SUCCESS / 安全失败类别，再打开 AI 设计输入中文、发送、Copy、Apply、分支、切换失败后 Retry。真实 EXE 不带此测试模式。不要在任何窗口输入真实凭据或执行真实生成。下方 checklist 仍由用户填写，自动测试不代签 Windows IME 人工结果。

## 1. AI Design

- [ ] 中文输入法输入正常，caret 不跑到正在输入文字前面。
- [ ] 新对话输入和编辑旧需求都正常。
- [ ] 失败时不是只有“设计失败”，能看懂是模型配置、网络、Provider、响应解析还是取消。
- [ ] 错误信息不泄露 key/token。
- [ ] fake/local 成功结果按“基础 Prompt / 角色 Prompt”结构展示。
- [ ] 每个 Prompt 模块有明显 Copy。
- [ ] Apply to Studio 明显。
- [ ] Regenerate / Edit & branch 明显。
- [ ] 附加当前 Studio Prompt 时能看出“基础 + N 角色”。

## 2. CharacterCard Prompt Import

- [ ] 标题是“导入角色卡 Prompt”。
- [ ] 说明写清“填充画风；角色 Prompt 仅供 AI 设计参考，不参与实际生图”。
- [ ] 选择后不再把内部人物来源列表摊在主界面。

## 3. Studio 顶部

- [ ] Prompt Inspector 不再和 NovelAI Studio 作为普通同级入口。
- [ ] model / Anlas / V5 allowance 聚在一起容易读。
- [ ] Refresh 是小工具动作。
- [ ] AI设计 / 图像引导 / 导入 / 历史 / 设置容易找。

## 4. Buttons / icons

- [ ] 普通按钮一眼能看出是可点击控件。
- [ ] Primary / Secondary / Icon 层级明显。
- [ ] Portrait / Square / Landscape 有图形提示。
- [ ] Clear / Undo / Redo / Copy / Reset / History / Refresh 有典型 icon。

## 5. Generate / Tokens

- [ ] 当前免费时按钮显示“生成免费”。
- [ ] 有消费时显示“生成消耗 N Anlas”。
- [ ] 额外编码/Vibe cost 如果存在能看到。
- [ ] 正向/负向 Tokens 有条状进度，不只是数字。
- [ ] 接近 limit 时有明显提醒。

## 6. Result preview

- [ ] 右侧大图会随窗口变大，不再固定很小。
- [ ] 保持 aspect-fit。
- [ ] 点击大图可以进 viewer。
- [ ] 下方有横向缩略图 filmstrip。
- [ ] 能用缩略图切 recent/current 图片。
- [ ] 选中项明显。
- [ ] 横向滚动顺手。

## 7. Studio copy/import actions

- [ ] Copy positive Prompt 容易找到。
- [ ] Paste overwrite 容易找到。
- [ ] PNG metadata selective import 容易找到。
- [ ] AI Design module Copy 存在。
- [ ] History Full/New Seed/Seed Only/Use as 仍然正常。

注意：
**完整 Studio configuration preset 导入/导出不属于本轮 P7 parity，已明确交 P17。**

## 8. Chat composer

- [ ] 图片按钮在输入框 action rail 内，不单独占一行。
- [ ] fullscreen editor 仍在输入框附近。
- [ ] Send/Stop 位置自然。
- [ ] 自动生图和背景不再占 composer toolbar。
- [ ] 无附件时完全没有附件空行。

## 9. Pending images

- [ ] 缩略图足够大，能看清。
- [ ] 点击缩略图可以看大图。
- [ ] hover 时右上角出现 ×。
- [ ] 点 × 只移除对应附件。
- [ ] 多图可横向浏览。

## 10. Session Settings

- [ ] 不再是一列到底。
- [ ] 至少有“基础 / Prompt上下文 / 图片 / 高级”或同等清晰 tabs。
- [ ] FormatCard / WorldBook 在上下文 tab。
- [ ] 背景 / opacity / NAI model / 自动生图 / image Prompt requirement 在图片 tab。
- [ ] dirty state 跨 tab 正常。
- [ ] Save / Cancel 始终容易找到。
- [ ] session background immediate-save 行为仍和以前一致。

## 11. Responsive

- [ ] 宽窗口能合理把同类控件排一行。
- [ ] 窄窗口自动换行。
- [ ] 不出现整页水平滚动。
- [ ] 主操作不会被无关动作挤走。

## 12. 回归

- [ ] Prompt 中文注释仍通过。
- [ ] Guidance / History / image tools 入口仍正常。
- [ ] Background library 仍正常。
- [ ] Character → Start Chat 仍正常。
- [ ] 普通聊天发送/编辑/多附件仍正常。
- [ ] 关闭重开状态仍正常。

## PASS

只有你认为：

> “这些东西终于像一个正常的 CCB Desktop 产品，而不是调试界面”

才算 Phase 7 manual PASS。

PASS 后才允许：
- Phase 7 ACCEPTED
- docs close
- FF-only merge feature → desktop
