# 任务中心与托盘 / 2026-11-02

原216 P15-04的独立后续源码。此检查点已有TaskRuntime／Memory投影、基础标题栏入口及任务窗和精确导航源码；未编译／运行，不混私有1231（冻结5d01）。外部导入fb6／CI93完整成功，不能覆盖本工作树。

DesktopTaskCenter观察现有owner，条目保存固定任务种类、状态、实际目标ID、可用操作和可选真实计数。停止请求只显示等待收尾，原owner终态才停止活跃显示。仅观察到真实活跃→终态才发进程内固定kind/status事件；这还不是Windows通知送达。正文、Prompt、候选及provider异常不被拷成任务条目。

TaskRuntime的文档／格式卡生成原先已登记真实child Job而不在tasks列表；现在同UUID／同Job投影到ownedWork，原准入、父任务和关闭排水保持。真实child completion cause决定终态，候选完成不冒称卡片保存。聊天／生图／修复仍读取原tasks与repair owners；可重试动作仍回原checkpoint门禁。

Memory只观察runningSessions。它包含读/手动操作，不能解释成单次付费AI请求；行停止调用该行session的coordinator.stopAndJoin，保留原恢复/临时准入语义，不调面板当前session.stop，也不调永久删除。该集合退场没有完整成功证明，故不生成成功通知或伪造历史。

应用按lazy owner实际创建时挂观察者，不为任务窗初始化其他业务。关闭观察者不停止未被用户请求停止的工作；AppContainer沿原顺序排空业务，之后关闭观察者。标题栏入口有独立原生点击排除矩形；任务窗避让已有modal/root门禁。会话导航复用原离开/设置守卫。目标上下文名字只在本地显示并有界缓存，与系统通知内容分开。

新增精确导航：CHAT消息经实际归属核对、原有有界页与viewport定位，保留输入草稿；角色文档只在原卡和当前编辑草稿均含精确ID时展开并滚动，同卡不重新加载；格式候选只恢复仍绑定当前草稿的原窗口，不新建或套用候选。MANAGE沿既有分栏离开守卫，传输忙则保留请求待空闲，不静默消费。名称之外保留短任务ID区分重名。新增回归仍待云端。

withCardDesign也被角色／世界书／图片／朋友圈使用，默认只标为创作任务且不伪造格式卡导航；格式卡调用点明确标识。文档owner保存实际usage session，聊天历史索引不误认作角色文档。其他owner的精确类型／导航仍待各owner接线。

后续工作树已加DesktopTrayController及AWT backend：实际attach成功才允许主动隐藏；Window只改visible，不销毁composition或业务。托盘提供打开／任务中心／隐藏／退出；退出仍回原requestClose与排空，X行为保持。提醒只用固定kind/status，本地设置taskNotifications默认开、可取消并走原updateLatest；坏设置不覆盖。系统拒绝提醒可见，提交displayMessage不冒称已送达。AppContainer在既有业务排空后detach，再关任务观察者。三项新假backend回归覆盖不支持时不隐藏、显隐不退任务／明确退出、偏好保留其他字段与初始历史不通知，尚未编译／运行，不由14dd／CI94覆盖。

当前仍待：其他语音／创作／RAG／备份／存档／朋友圈owner接线；托盘源码的云端编译／运行、宽窄Scene与实际原生能力／通知送达。上述不改变已有编辑草稿或默认关闭动作。

新增未执行回归：真实两文档工作仅停止选中项且等原NonCancellable收尾；观察者关闭不取消格式卡候选child且不保存卡；真实Memory面板在B时只停行A，B工作与两边正文／草稿保持；真实终态通知与停止请求的区别。后续精确源云端验证，不在本机运行JVM。
