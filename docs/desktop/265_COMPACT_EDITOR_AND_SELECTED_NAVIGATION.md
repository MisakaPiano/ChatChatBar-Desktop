# 265 — 长编辑页与窄屏选中导航

2026-11-02实际117/29fc：Windows2541=2521PASS+19SKIP+1FAIL，两个长Format/WorldBook实际保存/条目/中英短窗例PASS；唯一Data初始330×420外部UPDATE请求后，原5秒内未满足consumed及selected完整可见断言，发生在截图及resize之前，首因待精确状态。不归因resize/旧RAG/纯慢，不延长5秒或删可见性要求；补相同失败点的consumed/五tab选中与bounds、原Scene失败图。共享1142/Android582/Python3通过；资源archive四个原失败已全部通过。完整文本不在117；仍不声称全应用IA已完成。

- Format 编辑保持原标题/关闭和保存/取消/放弃草稿可见，中间问题提示、正文及工具滚动。原预算、AI入口、排序、移除、校验、来源冲突/另存/清理重试均保留；窄屏工具及离开选项换行，不裁掉原按钮。
- WorldBook 编辑固定头尾，中间原设置/AI/条目列表滚动。条目 modal 单独固定原保存/取消区；条目先应用到编辑草稿，再按原整本保存流程发布。WBDialog 固定标题、有限高度可滚正文、可选独立 actions，不改变叠层和原确认回调。
- Workspace 使用原 selected ID 和视口尺寸的 BringIntoViewRequester，在窄横栏或宽侧栏把当前选项滚入视口。原等宽按钮、保留 pane、隐藏页焦点/语义屏蔽、busy/migration 页面优先和 request consumed 保持；不新增导航控制器或替换数据页。

新增 `DesktopCompactEditorLayoutTest` 三例：真实容器 Format 长正文/12工具、真实 WorldBook 20条目及长条目内容，分别在330×420、420×900、1100×760和额外英文330×420确认原离开/继续/保存与48dp完整按钮边界、实际仓库保存和未改条目。真实 Data 五页通过 openUpdateRequest 选第四项，随后键盘/语义切页；同一 Scene 在330→1100→330→700→699跨分支 resize，核对当前选中项完整可见且 consumed 不重复，无文件选择/任务/迁移。截图复用已有 `CCB_MEDIA_USAGE_REVIEW_DIR` 输出，不在本机渲染；Scene不代替 native窗口验收。

不改现有 Prompt、实体字段、保存/撤销、草稿、冲突或清理机制。社区最后、同卡多入口等原用户决定继续保留。
