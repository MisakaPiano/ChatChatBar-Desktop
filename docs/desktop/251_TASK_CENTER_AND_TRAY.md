# 任务中心与托盘 / 2026-11-02

原216 P15-04的独立后续源码。此检查点只有TaskRuntime／Memory投影、基础标题栏入口及任务窗；未编译／运行，不混私有1231（冻结5d01）。外部导入补正fb6／CI93独立运行，不能覆盖本工作树。

DesktopTaskCenter观察现有owner，条目保存固定任务种类、状态、实际目标ID、可用操作和可选真实计数。停止请求只显示等待收尾，原owner终态才停止活跃显示。仅观察到真实活跃→终态才发进程内固定kind/status事件；这还不是Windows通知送达。正文、Prompt、候选及provider异常不被拷成任务条目。

TaskRuntime的文档／格式卡生成原先已登记真实child Job而不在tasks列表；现在同UUID／同Job投影到ownedWork，原准入、父任务和关闭排水保持。真实child completion cause决定终态，候选完成不冒称卡片保存。聊天／生图／修复仍读取原tasks与repair owners；可重试动作仍回原checkpoint门禁。

Memory只观察runningSessions。它包含读/手动操作，不能解释成单次付费AI请求；行停止调用该行session的coordinator.stopAndJoin，保留原恢复/临时准入语义，不调面板当前session.stop，也不调永久删除。该集合退场没有完整成功证明，故不生成成功通知或伪造历史。

应用按lazy owner实际创建时挂观察者，不为任务窗初始化其他业务。关闭观察者不停止未被用户请求停止的工作；AppContainer沿原顺序排空业务，之后关闭观察者。标题栏入口有独立原生点击排除矩形；任务窗避让已有modal/root门禁。会话导航复用原离开/设置守卫。目标上下文名字只在本地显示并有界缓存，与系统通知内容分开。

当前仍待：MANAGE card/document/draft与CHAT消息目标的精确定位、其他语音／创作／RAG／备份／存档／朋友圈owner接线、托盘能力与显隐／正常退出、系统提醒偏好和实际通知；宽窄Scene与原生能力检查。不能把打开管理页算定位完成，不改变已有编辑草稿或默认关闭动作。

新增未执行回归：真实两文档工作仅停止选中项且等原NonCancellable收尾；观察者关闭不取消格式卡候选child且不保存卡；真实Memory面板在B时只停行A，B工作与两边正文／草稿保持；真实终态通知与停止请求的区别。后续精确源云端验证，不在本机运行JVM。
