package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

internal const val STUDIO_ACCOUNT_READ_FAILURE =
    "无法读取安全凭据；原账户配置和资料保留，请重试或打开 API 账户设置。"

/** A fixed failure signal; never retains an underlying key, ciphertext, path or exception. */
internal class DesktopStudioCredentialReadException : IllegalStateException(STUDIO_ACCOUNT_READ_FAILURE)

internal fun <T> studioCredentialRead(read: () -> T): T = try { read() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { throw DesktopStudioCredentialReadException() }

internal sealed interface DesktopStudioAccountRead {
    data class Available(val identity: String?) : DesktopStudioAccountRead
    data object Unavailable : DesktopStudioAccountRead
}

internal fun readDesktopStudioAccountIdentity(read: () -> String?): DesktopStudioAccountRead =
    try { DesktopStudioAccountRead.Available(studioCredentialRead(read)) }
    catch (_: DesktopStudioCredentialReadException) { DesktopStudioAccountRead.Unavailable }

@Composable
internal fun DesktopStudioCredentialProblem(canOpenSettings: Boolean, onRetry: () -> Unit,
    onSettings: () -> Unit, onDismiss: () -> Unit) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().background(DesktopBootstrapColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ManageHeading("NovelAI 账户读取失败") }
                    DesktopIconAction("关闭账户提示", DesktopAppIcons.Close, onClick = onDismiss)
                }
                StatusText(STUDIO_ACCOUNT_READ_FAILURE)
                StatusText("不会改用旧密钥或自动清空文件。需要使用备份时，可前往“数据”页面。")
                ActionRow {
                    BootstrapButton("重试读取", icon = DesktopAppIcons.Refresh, onClick = onRetry)
                    BootstrapButton("API 账户设置", secondary = true, enabled = canOpenSettings, onClick = onSettings)
                }
            }
        }
    }
}
