package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ProviderTransportDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID

/** Captures the existing physical transport; never creates a second provider request. */
internal suspend fun <T> withDesktopAuxiliaryUsage(
    owner: DesktopTransportDiagnosticsOwner?,
    model: ModelConfig,
    displayGroup: String,
    promptContext: DesktopPromptDiagnosticContext? = null,
    block: suspend (ProviderTransportDiagnostics) -> T,
): T {
    val recorder = owner?.begin(UUID.randomUUID().toString(), displayGroup, model,
        currentCoroutineContext()[DesktopUsageParent]?.value ?: DesktopUsageContext(DesktopUsageSource.OTHER), promptContext)
    return try {
        block(recorder ?: ProviderTransportDiagnostics.NONE).also {
            recorder?.finish(DesktopTaskStatus.COMPLETED, null, null)
        }
    } catch (cancelled: CancellationException) {
        recorder?.onCancelled()
        recorder?.finish(DesktopTaskStatus.USER_STOPPED, null, null)
        throw cancelled
    } catch (error: Exception) {
        recorder?.finish(DesktopTaskStatus.FAILED, "辅助模型请求未完成", null)
        throw error
    }
}
