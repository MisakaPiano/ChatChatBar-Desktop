# Audio Maintenance Safety R1 — 交接

初始 HEAD/parent：`c6d94abd6540416d1a6a90e8d088b0240bd0e13d`。
最终生产源码：`694a8bdd6e48bd6696d2eeb59f141935c67163b0`（13 files,+587/-40）。独立 evidence commit 的直接 parent 为该源码 SHA；evidence SHA 以 Git 对象为准。

- 迁移：app-owned 音频预约→停止/等待真实 close→destination prepare→scheduler stop/join→数据独占→原事务。与聊天页 onDispose 解耦。
- 自动备份：音频占用、暂停或其他预约时，在数据独占之前返回明确可重试 Failed；不打断声音、不冒充 not-due/成功。空闲预约及音频 epoch 防止检查后新播放竞态。预约冲突不持锁等待另一个预约，避免 audio→scheduler→audio 循环。
- 关闭失败：保留 alias、文件及原 normal 登记；停止明确返回失败，只有下一次显式重试确认 close 后才能释放。迁移文件事务/authority计数在此前为零；未知状态 fail closed。
- 取消/重试：排水真实完成后再传播取消；排水失败保持隔离；precommit失败报告真实runtimeDisposition，UI可重试；committed/indeterminate继续保留既有restart seal，音频同步封存。

实际测试：首轮compileTestKotlin RED；第二轮31项29PASS/2FAIL（原日志/XML保留）。最终新增交叉11/11、根切换21/21；受影响14 suites/151PASS；既有UX18 suites/124PASS。全部最终0SKIPPED，范围重叠不能累加成独立总数。未跑full bounded/Robot/Android。详细源码身份、命令、XML SHA、失败原因见本目录 JSON、README、source.patch。

新包212/212核验，manifest SHA-256：`d98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1`。
路径：`H:\ChatChatBar-Desktop\app\desktopApp\build\post-p8-audio-maintenance-r1-20261020\windows-frozen-694a8bdd\runtime-image`。
**未启动、未播种人工环境。** 旧22d04333包及R3包各212文件重核不变。真实Fish GET0/TTS0，收费调用0。最终Java/javaw为空；这不等于新实包Launcher/JVM/根锁人工验收。

原风险保持“条件性无界等待，非已复现死锁”的历史分类；本修复是通过定向回归的候选。MCI物理调用无绝对超时、其他直接exclusive业务入口、真实设备异常/UI恢复矩阵等限制见README，不能用本轮替代全局维护安全证明。所有历史RED/SKIPPED/INCONCLUSIVE及四份用户文件保全。

具备交Project复审并决定是否进入新包集中Windows人工Gate的工程条件；**没有自动启动授权，也没有用户人工PASS。** 不修改CURRENT/parity/baseline，不合并desktop，不发布。STOP。
