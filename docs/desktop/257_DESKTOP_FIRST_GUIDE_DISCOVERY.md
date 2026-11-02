# 257 Desktop 首次发现指南与本地提醒选择

原P17-01的独立补充；手动指南见254，恢复入口仍属251。不是自动生成、模型设置向导、Android教程进度迁移或完成整个P17。

## 行为与原owner

正式Main将同一AppContainer的 `DesktopGuideReminderController` 交给原Shell。第一次读取后，尚未选择“不再提醒”的指南版本可显示紧凑发现提示；缺字段的旧Desktop设置按0处理。读取和构造不写文件，不访问业务Entity、模型或网络。手动帮助仍为静态、同一GENERAL帮助modal。

- “打开使用指南”直接进入原阅读器；关闭后本次不再提醒，顶栏问号仍从当前页短帮助进入。
- “本次稍后”／小关闭仅内存暂缓，下一次进程启动仍可提醒。
- “不再提醒”才通过原 `DesktopSettingsStore.updateLatest` 和normal-operation gate原子保存可选 `guideAcknowledgedVersion`。不降级更高版本，保留最新其他设置及未知字段；原格式version仍1。导入的Android `tutorialVersion`不改，未增加逐页强制完成或已读推断。
- 读取失败固定提示／可重试，不覆盖坏设置；写入失败不冒充已保存，可重试或本次稍后。取消沿原Job传播，保存busy在finally恢复，不将取消记成永久选择。

原启动更新提醒优先。Shell在根切换／备份／合并锁、其他modal、编辑选择、原任务／修复／任务中心活跃、显示中的外部导入面前暂缓；没有消费待提醒选择。打开动作重核当前锁和任务，不为了介绍指南导航到其他业务页、提交草稿或确认冲突。

窄屏提醒正文可滚动、按钮可换行，header和动作保留。指南开关目录时，同主题阅读ScrollState在分支外保留；切换主题仍从顶部开始。不会将已公开1230或108/7621的结果算本源码通过。

## 必要验证

CI109／57272实际完整SUCCESS，主2505=2486PASS+19SKIP，4个controller、提醒Panel、真实Shell与指南同页阅读扩展均PASS；实际双语宽窄图已核。后补短窗可用性修正：失败原因排在通用介绍前面，沿原Scene增加首行初始可见性检查；这个增量尚待下一精确源，不将109改写为失败。

后续CI110／2ba39c完整SUCCESS，同主2505及独立检查通过；8张中英文330／1100 READ／SAVE实图已核，原英文最矮窗现在首屏可读失败原因。未放宽原动作完整bounds、5秒或滚动断言。该源码进入1233普通私有候选，不由后续NAI媒体源码覆盖。

- `DesktopGuideReminderTest`四例：缺失／旧字段只读与本次暂缓，显式保存与后续重开、最新字段／未知字段及更高版本保留，坏字段／原子替换失败及重试，取消／已关闭gate保全。
- `DesktopGuideReminderPanelTest`一例：中英文330×420／1100×650，blocked未消费，READ／SAVE状态及按钮全可达、disabled、版本已记后消失；实际图走原 `CCB_CONTEXT_HELP_REVIEW_DIR`。
- 原B1C增加真实Shell一例：已有modal→启动更新→指南顺序，直接开原指南、关闭再从问号开短帮助，原会话草稿／角色字节／任务／请求／路由保留；经真实SettingsRepository保存的tutorialVersion=42及entities/app_settings.json原字节保持，不把旁路哨兵文件当实体导入证据。
- 原GuideScene补最矮窗口中英文长主题真实滚到末尾，目录开关后同页位置保留，换主题重置。原完整目录行bounds／5秒与 stale-next保护保持。

本机不编译、测试、渲染，不修改日常H程序／真实数据。仅必要受影响源码送云端；原106／107失败保留，未以新字段或提示消除旧证据。
