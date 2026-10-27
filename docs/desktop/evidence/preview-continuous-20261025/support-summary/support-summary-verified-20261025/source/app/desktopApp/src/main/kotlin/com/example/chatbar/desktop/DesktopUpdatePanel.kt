package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun DesktopUpdatePanel(
    copySupport: (String) -> Unit = { text ->
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    },
) {
    val identity = remember { DesktopBuildIdentity.load() }
    val checker = remember { DesktopUpdateChecker() }
    val scope = rememberCoroutineScope()
    var previews by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DesktopUpdateResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var supportStatus by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopDataSectionHeading("版本与更新")
        StatusText("Desktop ${identity.version} · ${identity.commit.take(12)}")
        StatusText("已验证上游基线：ChatBar 1.4.1。Desktop 使用独立版本和发行通道。")
        BootstrapButton("复制基本诊断", secondary = true) {
            val update = when {
                busy -> DesktopSupportSummary.UpdateState.CHECKING
                error != null -> DesktopSupportSummary.UpdateState.FAILED
                result == null -> DesktopSupportSummary.UpdateState.NOT_CHECKED
                result?.latest == null -> DesktopSupportSummary.UpdateState.NO_RELEASE
                result?.newer == true -> DesktopSupportSummary.UpdateState.AVAILABLE
                else -> DesktopSupportSummary.UpdateState.CURRENT
            }
            supportStatus = try {
                copySupport(DesktopSupportSummary.create(identity, update))
                "基本诊断已复制。只含版本、系统类别和更新检查状态，不含资料路径、聊天或凭据。"
            } catch (_: Exception) {
                "剪贴板暂时不可用，请重试。"
            }
        }
        supportStatus?.let { StatusText(it) }
        ActionRow {
            BootstrapButton(if (previews) "包含 Preview" else "仅稳定版本", secondary = true, enabled = !busy) {
                previews = !previews; result = null; error = null
            }
            BootstrapButton("检查更新", enabled = !busy) {
                busy = true; error = null; result = null
                scope.launch {
                    try { result = checker.check(identity.version, previews) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "未能完成检查：网络或发行信息不可用。当前程序可继续使用，没有下载或安装更新。" }
                    finally { busy = false }
                }
            }
        }
        if (busy) StatusText("正在检查 Desktop 官方发行信息…")
        error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        result?.let { checked ->
            val release = checked.latest
            if (release == null) StatusText("当前通道尚无可识别的 Desktop 发行版本。") else {
                StatusText(if (checked.newer) "发现新版本：${release.version}" else "未发现比当前版本更新的发行版")
                StatusText("${if (release.preview) "Preview" else "稳定版"} · ${release.published}")
                StatusText(release.notes.ifBlank { "该发行版尚未提供更新说明。" })
                BootstrapButton("在浏览器打开官方发行页", secondary = true) {
                    try {
                        check(DesktopUpdatePolicy.trustedPage(release.url, release.version))
                        java.awt.Desktop.getDesktop().browse(java.net.URI(release.url))
                    } catch (_: Exception) { error = "无法打开默认浏览器。当前程序和数据未改变。" }
                }
            }
        }
        StatusText("手动升级前先备份并正常退出。保留选中的数据目录和本机凭据；便携版请保留 UserData。此入口不会自动下载安装或重启。")
    }
}
