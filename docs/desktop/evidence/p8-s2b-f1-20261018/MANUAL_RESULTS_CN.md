# F1 Windows 人工验收记录

仅本轮精确包、隔离合成环境。九条原始用户文字记录见 [user-signatures.jsonl](user-signatures.jsonl)，一张用户自愿截图见 [user-f1-preview-entry.png](user-f1-preview-entry.png)。不是自动截图。

| 项目 | 结果 | 实际证据与边界 |
|---|---|---|
| F1-W01 结构化角色预览入口 | PASS | 用户看到免费文本框；截图中 Alice 未绑定 |
| F1-W02 对应中文语音、停止 | PASS | 用户听到指定合成短句，停止后不继续；缓存文件随后独占打开成功 |
| F1-W03 首次缓存复用 | PASS | 修改文字后仍播放第一段，明确显示新输入未发送；正常 fake POST 始终为 1 |
| F1-W04 挂起生成关闭 | PASS | 用户关闭成功、Alice 未绑定；两次用户明确重试对应两个挂起 POST，两连接结束，临时文件清零 |
| F1-W05 原 W22 挂起目录查询关闭 | PASS（本轮限定场景） | 真实 Windows picker 显示正在查询时关闭；用户确认编辑器可操作，fake GET 连接结束 |
| F1-W06 自由文本入口 | PASS | 用户确认同样文本框及生成按钮；本模式没有重复生成／试听 |
| F1-W07 正常关闭与实际进程 | PASS | 用户第二条关闭澄清后 Launcher/JVM 为 0/0；独立锁重获 0、fake 退出 0 |
| 真实物理设备拔除／默认设备切换 | NOT TESTED | 自动化只覆盖注入的设备/close 失败；没有改系统设备配置 |
| 隐藏／无窗口而 Compose 仍不退出 | NOT ESTABLISHED | 一度观察到进程仍在，但用户随后明确主窗口尚在；不能据此宣称发现或修复进程泄漏 |
| 完整 Windows TOCTOU／阻塞原生 I/O 物理绝对超时／跨 Windows 版本 | NOT TESTED | 不从本机成功推导通用保证 |

## 请求与声音来源

真实 API helper 使用生产 transport/store 生成一次合成短句。Windows 精确包以合成凭据连接 loopback TLS fake；fake 返回同一份真实合成 MP3。两条验证链分开记录，不能冒充用户界面直接使用真实 Key 发起了一条完整线上请求。Windows 人工期间真实 API 增量为 0。

真实 GET 0、TTS 1、HTTP 200、model=s2.1-pro-free；没有自动重试。fake POST 3：正常 1、挂起 2。用户确认挂起测试做过重复生成/重试。缓存采样时下一阶段已经开始，原 `cache-replay-fake-count.json` 的 total/expected 不能作为独立缓存阶段判定；保留原件，以有序事件分类和用户说明核对。

## 关闭时间的限制

首次“已关闭”文字后仍有主窗口。保留当时只读线程快照；它显示 Compose application 尚未返回，音频线程空闲。用户随后明确“仍有主窗口，现已正常关闭”，之后两进程退出。`closeObservedUtc` 是观察器收到标记的时间，不是实际点击时间；不能将约 71 秒差值称为关闭/排水延迟。

角色卡两文件原始 hash 前后相同。运行包 212 文件前后全部相同。fake 已排水，临时 TLS private/trust 文件删除；测试数据根和音频证据保留。
