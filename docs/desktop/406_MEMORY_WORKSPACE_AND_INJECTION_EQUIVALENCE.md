# 长期记忆功能、注入与会话工作区

用户追加范围为原版对应功能及实际注入链，不能仅调整标签。本批与405一起完成必要检查后集中候选交付，公开仍为1249／签名31；17项旧人验通过保留。本文件当前为源码准备／待验证，不能作为新通过结论。

## 固定源码依据

对照官方 `d200148e7ea43d8aed69022bd1ea9c47d9343d66`：ChatSettingsDialog.kt 的会话长期记忆五页及其 current/edit/history；实际 LongTermMemoryService.kt、MemoryBudgetPolicy.kt 和 MemoryHeadUpdatePolicy.kt。三份原始公开代码已按 Git blob 核验，私有工作记录只保存其身份与差异，不混入用户四张图／正文。

预算策略与 HEAD 更新策略逐字节一致。服务差异已逐段核对：Desktop AI/数据门适配、来源文件检查、设置合并防覆盖、派生数据重建日志，以及本批只读检查入口；原 Archive/HEAD 格式和模型 Prompt 文本保持。此检查不自动提升正式上游基线，也不等于全功能运行验证。

## 功能到实际调用

| 功能 | 实际 owner / 本批处理 | 验证边界 |
| --- | --- | --- |
| HEAD 更新／手工编辑 | 共享 MemoryHeadUpdatePolicy、LongTermMemoryService；页面显示真实来源轮，显式保存 | 复用已有生命周期检查，补 UI 草稿与只读一致性 |
| Episode／Arc／Era、预算与压缩选择 | 共享生成／覆盖／压缩／预算策略不重写，Desktop 使用同一应用级 coordinator | 既有预算、覆盖、扩容与压缩检查按受影响范围复用 |
| 当前／编辑／历史／恢复 | 同一会话五页，三归档层各三子页；原 service edit/restore；只读历史增加分批读取 | 补实际页面导航、来源跳转、dirty 留页与保存后读回 |
| 清空／重建／暂停／重试 | 显式确认，原 service.clear 永久清空水位与 coordinator 全量重建／补录／来源修复保持 | 不清除原聊天；后台工作离页不取消 |
| 来源变更排除与修复 | 原 fingerprint、删除投影、stale safe frontier、独立 HEAD 规则 | 补只读检查不写入且与正常校验结果一致；保存原不安全摘要以供修复 |
| 实际发送 | DesktopRealChatRuntime 捕获已选聊天模型→Planner memoryView→共享 request assembler→OpenAiStreamingTransport | 补 MockWebServer 最终 JSON，分别检查归属、仅一次、顺序、关闭、stale 与重生成 |
| RAG 模型门控 | 生产 runtime 把实际聊天模型放入 retrievalModel；不能仅由 Planner 的 null 条件断言记忆被丢弃 | RAG OFF 仍应发送启用的有效记忆，按真实 provider 请求证明 |
| 注入预览 | 新 inspectPrompt 复用实际格式器；来源校验的投影只在内存；不写缓存／节点／迁移，不入队 | 待迁移、待删除投影或未恢复日志明确拒绝，正常打开／显式刷新沿原 owner；无静默降级 |
| 会话设置分类 | 参数与设定／长期记忆／RAG／存档；快捷记忆进入相同工作区 | RAG 操作从 Prompt 配置分开，存档原独立管理窗口与导入导出保留 |
| 兼容与保存 | Legacy Reference 移至维护区；既有备份／SaveSlot／派生状态日志不改协议 | 不改用户真实资料、不代合并、不以预览冒充真实请求或跨端人验 |

## 只读与正常维护

预览读取当前已提交状态，拒绝待恢复提交日志；普通开页仍保留原有迁移与维护激活。切页只检查，不另外排队维护；显式刷新调用原 refreshForCurrentConditions，它可整理持久化来源条件，但不直接调用 AI。发送仍走有相应副作用的原 promptView，不能为使预览容易一致而禁用应有的后台需求。

只读投影遇到尚未正常迁移或待全轮删除投影时，保留原数据并提示正常刷新；不绕过覆盖证明或猜测删除结果。实际 formatter 的文本保持原样，新开关只截断缓存／旧指纹写入。provider 测试冻结合成已提交快照以排除后台竞争；它不替代既有维护并发、真实用户／供应商环境或全项目回归。
