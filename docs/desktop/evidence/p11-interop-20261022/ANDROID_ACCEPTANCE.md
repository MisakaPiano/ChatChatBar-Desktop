# Android 集中验收材料与权限边界

本轮自动化使用全新隔离 AVD、Android 34、官方 1.4.5 精确源码构建的 debug 应用及独立 instrumentation APK。没有官方发行签名，没有真人 Android GUI PASS。物理 Android：**NOT TESTED**。

## 工程复现

1. 使用 JDK 17、已安装 Android SDK/API 34 x86_64 系统镜像。创建仓库 `app/desktopApp/build/` 下全新 scope；勿复用真实应用根。将本目录合成 `artifacts/seed.cbsave` 复制为 `<scope>/seed.cbsave`。
2. 从 `app/` 之外运行 `tools/desktop/interop/Run-DesktopInterop.ps1 -Scope <absolute scope> -Phase produce`。它会执行正式 Desktop SaveSlot import/restore/create/export，并生成 `desktop-materials/desktop.cbsave` 和 `desktop-supported.cbbackup`。必须看到对应 JUnit 1 test / 0 failures，不能只看 Gradle 成功。
3. 用 `git archive 1198a13cf3008de1358b8a69aa5e40b64797c8d9` 导出到 `<scope>/official`；不 checkout/merge 到工作分支。运行 `verify_official_source.py <repo> <scope>` 核对主源码。复制 `Official145InteropTest.kt` 至官方目录 `app/app/src/androidTest/java/com/example/chatbar/domain/backup/`，把 `desktop-materials` 两档案复制到其 `app/app/src/androidTest/assets/`。
4. 在官方 `app/` 构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，使用 `--no-daemon --no-build-cache --no-configuration-cache --max-workers=1 --no-parallel` 及 `-Pkotlin.compiler.execution.strategy=in-process`。三个构建属性 `CHATBAR_SILICONFLOW_API_KEY`、`CHATBAR_SUPABASE_URL`、`CHATBAR_SUPABASE_ANON_KEY` 显式为空；不允许继承真实配置。此步骤只构建，不安装手机。
5. 创建全新专用 AVD，`ANDROID_AVD_HOME`、`ANDROID_USER_HOME` 指向 scope，ADB server 独立端口（本轮 5043），emulator 独立端口（本轮 5580）。启动加 `-no-window -no-audio -no-snapshot`；应用只能访问测试内部 127.0.0.1 fake。检查 AVD 名称和 `sys.boot_completed=1`，所有后续 ADB 操作必须带 `-P 5043 -s emulator-5580`。不得对未确认序列号运行安装。
6. **仅此新 AVD** 安装两个 APK。执行 `shell am instrument -w -r -e class com.example.chatbar.domain.backup.Official145InteropTest com.example.chatbar.test/androidx.test.runner.AndroidJUnitRunner`。必须读到两个测试的真实通过结果；ADB 进程退出 0 不代表 instrumentation PASS。首次测试数据保留，失败日志不能覆盖。
7. 从该 AVD 的 `/sdcard/Android/data/com.example.chatbar/files/p11-interop/` 拉取到 `<scope>/android-materials`。运行 `Run-DesktopInterop.ps1 -Scope <scope> -Phase consume`，随后 `python tools/desktop/interop/verify_roundtrip.py <scope>`。前者通过正式 Desktop 恢复并续聊，后者逐项对比版本、次序、引用及媒体 SHA-256。
8. 正常关闭本轮 AVD，停止仅本轮的独立 ADB server，保存档案、进程与测试日志。不删除原档案或恢复 journal。

资源不足时分开执行构建与模拟器；不要因慢而结束未知进程。本轮所有失败尝试均保留，不将后续 GREEN 覆盖历史 RED。

## 等待单独授权的真机流程

开始前由用户明确指定设备和隔离方式：全新测试设备或独立测试用户。不得自动安装、重装、读取既有 CCB 数据、改变手机设置或启用 USB 调试。若不能隔离，停止真机步骤，不建议卸载/清数据。

一次集中复核只做以下连续路径：

1. 核验官方 1.4.5 应用身份、测试账户无真实 Token、合成材料哈希和离线端点可达性。手机的 `127.0.0.1` 是手机自身；不能直接填写电脑的 loopback。若需 USB 端口转发，必须把精确端口和操作列入新增授权。
2. 导入 `desktop.cbsave`，打开合成会话；查看原消息、两个备选版本、图片和录音，确认可用。使用已批准的离线端点续聊一轮，导出新 `.cbsave`。记录用户 PASS/FAIL，与自动化记录分开。
3. Desktop 在新隔离根导入该 Android 导出物，恢复、检查媒体和版本、继续一轮；核对 RAG/当前 Memory 支持范围。草稿不属于 `.cbsave`，不可据此要求它跟随存档。
4. `.cbbackup` 只尝试明确声明的官方兼容材料。对 Moments/凭据备份应看到整包拒绝、原数据未变；这不是完整 G gate 通过。完整官方全应用恢复与 Desktop 回传必须在产品/安全阻断解决后另行按已批准合同执行。

不重测已获 GUI R1 接受且未受影响的普通聊天、语音、RAG、Memory 操作；本轮只核对跨平台迁移后的数据与继续使用能力。
