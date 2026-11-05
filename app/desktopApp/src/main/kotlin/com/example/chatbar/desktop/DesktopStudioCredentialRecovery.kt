package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.DesktopSecretStoreException
import com.example.chatbar.desktop.security.UnsafeDesktopPlaintextCredentialException

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

internal enum class DesktopStudioModelReadFailure(val text: String) {
    UNSAFE_CREDENTIALS("模型配置未通过凭据安全检查；原文件和安全凭据已保留。请检查模型设置，或前往数据页面检查备份与恢复资料。"),
    PROTECTED_STORAGE("无法读取模型的安全凭据；原配置已保留。请检查模型设置，或前往数据页面检查备份与恢复资料。"),
    UNAVAILABLE("模型列表读取未完成；原配置已保留。请重试或检查模型设置。")
}

internal sealed interface DesktopStudioModelCatalog {
    data object Loading : DesktopStudioModelCatalog
    data class Available(val models: List<ModelConfig>) : DesktopStudioModelCatalog
    data class Unavailable(val failure: DesktopStudioModelReadFailure) : DesktopStudioModelCatalog
}

internal suspend fun readDesktopStudioModels(read: suspend () -> List<ModelConfig>): DesktopStudioModelCatalog = try {
    DesktopStudioModelCatalog.Available(read().map { it.copy(apiKey = "") })
} catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) {
    DesktopStudioModelCatalog.Unavailable(when (failure) {
        is UnsafeDesktopPlaintextCredentialException -> DesktopStudioModelReadFailure.UNSAFE_CREDENTIALS
        is DesktopSecretStoreException -> DesktopStudioModelReadFailure.PROTECTED_STORAGE
        else -> DesktopStudioModelReadFailure.UNAVAILABLE
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopStudioModelProblem(failure: DesktopStudioModelReadFailure, onRetry: () -> Unit,
    onModelSettings: () -> Unit, onDataSettings: (() -> Unit)?, onDismiss: () -> Unit) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().background(DesktopBootstrapColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ManageHeading("模型列表读取失败") }
                    DesktopIconAction("关闭模型提示", DesktopAppIcons.Close, onClick = onDismiss)
                }
                StatusText(failure.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton("重试模型读取", icon = DesktopAppIcons.Refresh, onClick = onRetry)
                    BootstrapButton("模型设置", secondary = true, onClick = onModelSettings)
                    onDataSettings?.let { BootstrapButton("备份与恢复", secondary = true, onClick = it) }
                }
            }
        }
    }
}

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
