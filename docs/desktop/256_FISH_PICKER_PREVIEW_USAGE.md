# 256 音色库生成试听：实际请求与归属

原P15-06／07剩余接线。正式回复TTS已有104限定结果（252）；本项是独立的 `DesktopFishPreviewTransport`，不由正式TTS测试代验，也不改变公开1230资产。

## 原有意图与接线

试听由原 `DesktopFishPreviewRuntime.Session` 持有，应用级scope保证弹窗关闭后仍等网络／文件／native播放排水。继续保留模型许可、120码点校验、冻结凭据、one-shot／one-exchange、不自动重试、四lease容量、首次结果缓存及显式绑定。

`DesktopFishBindingField` 打开Session时传入当时可得的card ID、draftSession ID、person ID，Session在自己的后台Job里显式安装该不可变parent。不能只依赖调用方coroutine继承，因为原runtime使用独立scope。独立调用缺少这些信息时source为VOICE_PREVIEW，task/session保持空，不用`voice-preview`日志分组冒充业务会话。

原enqueue、response.code、callback／body结束后接同一个 `DesktopMediaDiagnosticRecorder`。账户ID来自实际冻结凭据；发送码点来自原请求trim后文本。时长和字节来自原 `DesktopFishPreviewStore` MP3验证／完整文件，不再重跑解码；读取测量仍核对原文件身份／hash。不保留正文、reference ID、密钥、请求头、音频内容或路径。HTTP200但坏音频保持失败／未测量，取消不伪造HTTP和输出0。

生成完成和数值统计在原callback排水后收尾；播放设备随后失败不抹去已经生成的请求。清空进程日志不删除数值记录，原数值writer保持失败待存／关闭重试；同步sink故障可见，不失败或重放原合成。原缓存键和官方匿名样本GET保持，各自重播不产生TTS记录。

原音色库右上角增加紧凑“试听日志”，复用现有只读DebugOverlay／媒体详情；每个Session冻结独立随机日志group，与数值业务session ID分开。只能清本次音色库的进程日志，不清其他picker、数值统计或绑定；关闭日志返回原搜索／目标／输入。未挂diagnostics的测试／独立适配不显示空入口。该入口只读，不隐式查询目录或生成试听。

## 待精确云验

`DesktopFishPreviewUsageTest`四项合成fixture：

- 冻结账户、Unicode、有效MP3时长／字节和敏感值省略；真实Runtime.Session的card/editor/person归属及缓存重播只一请求。
- 401／429／503／坏MP3的实际状态、无重试与未知输出；模型拒绝／已关闭准入无请求记录。
- 已发送无响应取消和原callback排水、清日志后统计保留；sink故障后原有效音频仍只生成一次。
- 官方来源校验后的匿名样本GET和缓存播放不写TTS记录。仅连接映射到loopback，不访问真实Fish。

另补 `DesktopFishPickerLayoutTest` 实际360／1100 Scene：精确入口可见，打开／关闭／确认清空只当前lease，另picker日志和全部数值快照保留，搜索query不变、catalog／HTTP／绑定／关闭回调均零；同单调frame时钟捕获 `CCB_MEDIA_USAGE_REVIEW_DIR/fish-picker-debug-*`。源码待新源云验，不从107指南运行推定通过。

旧媒体schema2继续用于本地测量，VOICE_PREVIEW为新source枚举；不声称旧二进制能读取新枚举。不改官方Entity／备份格式或Provider账单，计费量仍UNREPORTED。已有MP3／缓存／关闭测试继续随精确源回归。本机不运行测试／编译／渲染；NAI实际生成／处理／Vibe等仍是后续原范围。
