package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Opens only the installer-owned stable shortcut, never the versioned JVM executable.
 * Windows owns the actual pin action; opening Explorer is not reported as a successful pin. */
internal fun desktopTaskbarShortcut(home: Path, roaming: Path): Path {
    val packageHome = home.toAbsolutePath().normalize()
    require(packageHome.parent.fileName.toString() == "versions")
    val root = packageHome.parent.parent
    require(Files.readString(root.resolve("install-owner.txt")) == "ccb-desktop-nsis-v1")
    val link = roaming.toAbsolutePath().normalize().resolve("Microsoft/Windows/Start Menu/Programs/ChatChatBar Desktop.lnk")
    require(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(link))
    require(Files.isRegularFile(root.resolve("ChatChatBar.exe"), LinkOption.NOFOLLOW_LINKS))
    require(Files.size(link) in 1..65536)
    val bytes = Files.readAllBytes(link).also { require(it.size in 1..65536) }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    require(Files.readString(root.resolve("programs-shortcut.sha256")).trim().equals(hash, ignoreCase = true))
    return link
}

@Composable internal fun DesktopTaskbarPinEntry() {
    var opened by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var shortcutStatus by remember { mutableStateOf<String?>(null) }
    var shortcutBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ActionRow {
        DesktopShortcutDestination.entries.forEach { destination ->
            BootstrapButton(if (destination == DesktopShortcutDestination.DESKTOP) "创建桌面快捷方式" else "添加到开始菜单",
                secondary = true, enabled = !shortcutBusy) {
                shortcutBusy = true; shortcutStatus = null
                scope.launch {
                    try { shortcutStatus = desktopCreateShortcut(destination) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { shortcutStatus = "无法确认当前安装。请先使用官方安装包安装或修复程序和快捷方式。" }
                    finally { shortcutBusy = false }
                }
            }
        }
        BootstrapButton("固定到任务栏…", secondary = true, icon = DesktopAppIcons.Pin) { opened = true; message = null }
    }
    shortcutStatus?.let { StatusText(it) }
    if (opened) DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 540.dp).background(DesktopBootstrapColors.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopDataSectionHeading("固定到任务栏")
                StatusText("Windows 允许应用请求固定时，会显示系统确认。也可打开快捷方式位置，右键“ChatChatBar Desktop”选择“固定到任务栏”。")
                StatusText("使用安装器创建的固定入口，后续升级会继续打开新版。独立资料版请先使用安装包建立稳定入口。")
                message?.let { StatusText(it) }
                DesktopWindowsTaskbarPin.diagnostic?.let { value ->
                    BootstrapButton(if (details) "收起系统详情" else "查看系统详情", secondary = true) { details = !details }
                    if (details) StatusText(value)
                }
                ActionRow {
                    BootstrapButton(if (busy) "等待 Windows 确认…" else "请求固定到任务栏", enabled = !busy) {
                        busy = true
                        scope.launch {
                            try {
                                val home = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path ?: error("not installed")
                                desktopTaskbarShortcut(home, Path.of(requireNotNull(System.getenv("APPDATA"))))
                                message = when (DesktopWindowsTaskbarPin.request()) {
                                    DesktopPinResult.PINNED -> "Windows 已确认固定到任务栏。"
                                    DesktopPinResult.DECLINED -> "Windows 未确认固定；可再次请求或使用手动入口。"
                                    DesktopPinResult.UNAVAILABLE -> "Windows 当前不允许应用直接请求固定。请打开快捷方式位置，右键选择“固定到任务栏”。"
                                    DesktopPinResult.NOT_FOREGROUND -> "请先切回 ChatChatBar 窗口，再点击请求。"
                                    DesktopPinResult.FAILED -> "系统固定请求未完成；请使用下方手动入口核对。"
                                    DesktopPinResult.CHECKED_UNPINNED -> "当前尚未固定到任务栏。"
                                }
                            } catch (cancelled: CancellationException) { message = "系统请求已结束，请核对任务栏上的固定状态。"; throw cancelled }
                            catch (_: Exception) { message = "无法确认安装入口；请先使用官方安装包修复程序和快捷方式。" }
                            finally { busy = false }
                        }
                    }
                    BootstrapButton("打开快捷方式位置") {
                        try {
                            check(System.getProperty("os.name").startsWith("Windows"))
                            val home = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path ?: error("not installed")
                            val link = desktopTaskbarShortcut(home, Path.of(requireNotNull(System.getenv("APPDATA"))))
                            ProcessBuilder("explorer.exe", "/select,", link.toString()).start()
                            message = "已打开快捷方式位置，请在 Windows 菜单中完成固定。"
                        } catch (_: Exception) { message = "无法确认安装器创建的快捷方式。请先用官方安装包修复程序和快捷方式，再重试。" }
                    }
                    BootstrapButton("关闭", secondary = true, enabled = !busy) { opened = false }
                }
            }
        }
    }
}
