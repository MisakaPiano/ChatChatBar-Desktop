package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun DesktopUpdatePanel(
    onExit: () -> Unit = {},
    copySupport: (String) -> Unit = { text ->
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    },
) {
    val identity = remember { DesktopBuildIdentity.load() }
    val checker = remember { DesktopUpdateChecker() }
    val scope = rememberCoroutineScope()
    val notices = LocalDesktopUpdateNotices.current ?: remember {
        DesktopUpdateNotices(scope, identity.version, null, startupAllowed = false) { checker.check(identity.version, it) }
    }
    val noticeState by notices.state.collectAsState()
    val previews = noticeState.includePreview
    val busy = noticeState.busy
    val result = noticeState.result
    var browserError by remember { mutableStateOf<String?>(null) }
    val error = browserError ?: noticeState.error
    val checkFailed = noticeState.error != null
    var supportStatus by remember { mutableStateOf<String?>(null) }
    val updater = LocalDesktopUpdater.current
    val download = updater?.state?.collectAsState()?.value
    var confirmInstall by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopDataSectionHeading("版本与更新")
        DesktopTaskbarPinEntry()
        StatusText("Desktop ${identity.version} · ${identity.commit.take(12)}")
        StatusText("已验证上游基线：ChatBar 1.4.1。Desktop 使用独立版本和发行通道。")
        BootstrapButton("复制基本诊断", secondary = true) {
            val update = when {
                busy -> DesktopSupportSummary.UpdateState.CHECKING
                checkFailed -> DesktopSupportSummary.UpdateState.FAILED
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
            BootstrapButton(if (previews) "包含 Preview" else "仅稳定版本", secondary = true,
                enabled = !busy && download?.busy != true) {
                browserError = null; notices.setIncludePreview(!previews)
            }
            BootstrapButton("检查更新", enabled = !busy && download?.busy != true) {
                browserError = null; notices.check()
            }
        }
        StatusText("启动后自动检查一次；下载与安装仍由你确认。")
        noticeState.cacheNotice?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (busy) StatusText("正在检查 Desktop 官方发行信息…")
        error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        result?.let { checked ->
            val release = checked.latest
            if (release == null) StatusText("当前通道尚无可识别的 Desktop 发行版本。") else {
                StatusText(if (checked.newer) "发现新版本：${release.version}" else "未发现比当前版本更新的发行版")
                StatusText((if (release.preview) "Preview" else "稳定版") + (release.published?.let { " · $it" } ?: ""))
                StatusText(release.notes.ifBlank { "该发行版尚未提供更新说明。" })
                BootstrapButton("在浏览器打开官方发行页", secondary = true) {
                    try {
                        check(DesktopUpdatePolicy.trustedPage(release.url, release.version))
                        java.awt.Desktop.getDesktop().browse(java.net.URI(release.url))
                    } catch (_: Exception) { browserError = "无法打开默认浏览器。当前程序和数据未改变。" }
                }
            }
        }
        result?.latest?.takeIf { result?.newer == true }?.let { release ->
            if (updater?.supportsInstall == true) BootstrapButton("下载更新", enabled = download?.busy != true) { updater.download(release.version) }
        }
        if (download?.busy == true) {
            StatusText(when (download.phase) {
                "DOWNLOADING" -> "正在下载更新 · ${download.received / (1024 * 1024)} / ${download.total / (1024 * 1024)} MB"
                "VERIFYING" -> "正在验证更新签名和完整性…"
                "WAITING_FOR_NORMAL_EXIT" -> "正在正常退出；完成后安装并重新打开…"
                else -> "正在核对可信更新信息…"
            })
            if (download.phase != "WAITING_FOR_NORMAL_EXIT") BootstrapButton("停止下载", secondary = true) { updater?.stop() }
        }
        download?.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        download?.notice?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (updater != null && !updater.supportsInstall) StatusText("当前入口是独立资料版或未安装程序。请使用官方安装包升级安装版；独立资料版请保留 UserData，并使用新版独立资料包。")
        download?.ready?.let { ready ->
            StatusText("已下载并校验：${ready.version}")
            BootstrapButton("确认更新并重启", enabled = !download.busy) { confirmInstall = true }
        }
        StatusText("安装版可在此下载、校验并确认更新；安装前正常保存和退出，保留所选数据目录及本机凭据。独立资料版请保留 UserData，并使用对应新版入口。")
    }
    if (confirmInstall) DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).background(DesktopBootstrapColors.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopDataSectionHeading("更新并重启")
                StatusText("确认后会保存并正常关闭当前应用，再安装已校验的新版本。正在执行的生成会按正常退出流程停止。用户资料与本机凭据保留。")
                ActionRow {
                    BootstrapButton("取消", secondary = true) { confirmInstall = false }
                    BootstrapButton("确认更新并重启") { confirmInstall = false; updater?.install(onExit) }
                }
            }
        }
    }
}
