# Windows → Android → Windows 合成资料链

此夹具仅在云端、独立合成root中运行。它证明实际workspace、资源adapter与repository之间的跨操作系统文件往返，不运行真实网络发现、TLS配对、二维码、付费生成或用户资料导入。三个腿必须使用同一固定源码SHA，逐腿保留完整JUnit/XML、原ZIP与报告，不能只按workflow绿灯认定。

## 数据与范围

当前协议5声明19类；本夹具只有以下7类各1条非空：`character_cards`、`chat_sessions`、`chat_messages`、`moment_posts`、`novelai_generation_history`、`player_setting`、`desktop_image_post_process_results`。其余12类为空：`format_cards`、`world_books`、`vector_chunks`、`memory_nodes`、`memory_states`、`memory_tier_revisions`、`memory_compression_transactions`、`memory_commits`、`generated_voice_messages`、`save_slots`、`voice_anchor_states`、`moment_tasks`。不得据此宣称完整19类已跨平台验收。账户、app_settings、设备配置不在本夹具中；模型绑定只使用两端独立的合成标记，不上wire。

资料包括两张Windows实际PNG（8×6蓝色、16×12暖色）、带两个稳定版本ID和两图的消息、两张朋友圈候选及选中图片/metadata、生图批次全配方/休眠High-DDIM/透明设置/角色ID/图像参考与大于4KiB的Vibe、本机原ID玩家单例，以及实际Desktop后处理owner持久化的结果。相同B图字节在普通图片和后处理结果中保持不同引用身份。ZIP只有`contract.json`、`snapshot.json`与`blobs/<sha256>`；没有原始资料根、identity、Keystore、API Key或账户文件。三份内容hash分别对应A图、B图、大Vibe；解包检查重复成员、CRC、字节上限、固定source/phase、snapshot摘要、完整coverage和每份blob摘要。

## Windows第一腿

类/方法：`com.example.chatbar.desktop.DesktopCrossPlatformLanFixtureTest.exportSyntheticWindowsSnapshot`。

环境：`GITHUB_ACTIONS=true`、`CCB_LAN_CHAIN_PHASE=EXPORT`、`CCB_LAN_CHAIN_SOURCE_SHA=<40位固定SHA>`、`CCB_LAN_CHAIN_ROOT=<新建的canonical TEMP或RUNNER_TEMP子目录>`。保留原Windows NTFS/临时路径验证路线，不使用用户安装根。运行命令：

```text
:desktopApp:test --tests com.example.chatbar.desktop.DesktopCrossPlatformLanFixtureTest.exportSyntheticWindowsSnapshot --no-build-cache
```

使用真实`DesktopCharacterResourceStore`、`DesktopPostProcessResults`及共享repository建立资料，workspace使用生产的`validateDesktopLanSyncResource`和`desktopLanSyncResourceReference`。验证实际图片解码/颜色和相对路径，产出root下`windows-origin.zip`与`windows-origin-report.json`。必须恰好1方法、0失败/错误/跳过，报告、ZIP与source一致；要求ZIP实际存在于本腿新建root，不能用缓存XML代替执行。

## Android中间腿

类/方法：`com.example.chatbar.lanSync.LanSyncCrossPlatformFixtureTest#importEditAndReturnWindowsSyntheticData`。

仅在隔离云AVD安装该固定源码的lanPreview与匹配测试APK。Runner参数：`lanSyncCrossPlatformProof=true`、`lanSyncChainSourceSha=<同一SHA>`，明确选择上述单方法。将原Windows ZIP以二进制方式放入目标app私有`files/lan-cross-platform-exchange/input.zip`；可使用ADB `run-as`标准输入复制，不能按文本重写。目录由编排创建；测试要求原input已存在，且拒绝覆盖既有return。

测试另建独立cache合成data/state，使用实际Android资源路径函数、图片校验、workspace/WAL和已初始化的真实repository reload入口消费。检查原版本ID、原PNG hash与实际Bitmap像素、绝对私有资源路径、原配方/休眠参数/Vibe原文与玩家ID，随后通过repository反向修改角色、消息当前版本、朋友圈正文/点赞、玩家名称和生图配方。后处理记录只保留，不声称Android有该查看界面。输出私有exchange下`return.zip`、`android-report.json`；以`adb exec-out run-as <appId> cat ...`保全二进制原件。必须恰好1实际方法、0失败/错误/assumption/跳过。

## Windows返回腿

类/方法：`com.example.chatbar.desktop.DesktopCrossPlatformLanFixtureTest.verifyAndroidReturnWithRealDesktopOwners`。

环境同第一腿，`CCB_LAN_CHAIN_PHASE=VERIFY`；另给`CCB_LAN_CHAIN_ORIGIN=<第一腿原ZIP>`与`CCB_LAN_CHAIN_RETURN=<Android原return.zip>`。对单方法运行与第一腿相同的测试命令。校验return记录的parent ZIP摘要与源facts完全一致。

先从origin恢复一份已有Windows合成root，设回该设备的本地模型标记，再以真实三方planner、原共同hash和Moment成员证据接纳Android改动；随后在第二个全新Windows root消费return，确保后处理等未改记录也实际重新落盘，而不是因相同记录跳过。两份root都使用实际Desktop resource adapter、repository、PNG解码以及`DesktopPostProcessResults.load/read`，核对全部返回编辑、版本/媒体/Vibe、玩家单例和本地模型归属。再次capture须与Android返回business hashes一致。产出`windows-return-report.json`，必须1实际方法、0失败/错误/跳过。

三个腿的产物通过独立编排传递，仅有合成公开输入。普通工程CI只编译这些入口，不执行跨平台fixture；尚未取得三个腿的源固定原件前，状态保持未实跑。该链不代替后续真机Wi-Fi/二维码与人工界面验收，也不改变冻结08候选或首37交付。
