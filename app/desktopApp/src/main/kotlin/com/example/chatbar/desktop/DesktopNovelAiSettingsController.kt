package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopNovelAiSettingsState(
    val credentialPresent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val settings: AppSettings = AppSettings(),
)

/** The editable token belongs only to the password field. State never contains a credential. */
internal class DesktopNovelAiSettingsController(
    private val secrets: DesktopSecretStore,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DesktopNovelAiSettingsState())
    val state = mutableState.asStateFlow()

    suspend fun load() = operation {
        val present = !secrets.load(DesktopCredentialKey.NovelAiToken).isNullOrBlank()
        val app = settings.getAppSettings().copy(siliconFlowApiKey = "")
        mutableState.update { it.copy(credentialPresent = present, settings = app) }
    }

    suspend fun saveCredential(value: String) = operation {
        require(value.isNotBlank() && value.length <= 8192 && value.none { it.isWhitespace() })
        withContext(NonCancellable) {
            secrets.save(DesktopCredentialKey.NovelAiToken, value)
            check(secrets.load(DesktopCredentialKey.NovelAiToken) == value)
            mutableState.update { it.copy(credentialPresent = true, status = "已保存到 Windows 受保护存储；未发送网络请求") }
        }
    }

    suspend fun clearCredential() = operation {
        withContext(NonCancellable) {
            secrets.delete(DesktopCredentialKey.NovelAiToken)
            mutableState.update { it.copy(credentialPresent = false, status = "NovelAI 凭据已移除") }
        }
    }

    suspend fun selectModel(model: NovelAiImageModel) = update { it.copy(novelAiImageModel = model) }
    suspend fun selectAspectRatio(ratio: String) = update { it.copy(novelAiImageAspectRatio = ratio) }
    suspend fun selectDesignModel(id: String?) = update { it.copy(defaultImageModelId = id) }
    suspend fun setPreference(text: String) = update { it.copy(imagePromptToolPreference = text) }

    private suspend fun update(change: (AppSettings) -> AppSettings) = operation {
        val app = settings.updateAppSettings(change).copy(siliconFlowApiKey = "")
        mutableState.update { it.copy(settings = app, status = "生图设置已保存") }
    }

    private suspend fun operation(block: suspend () -> Unit) = mutex.withLock {
        mutableState.update { it.copy(busy = true, error = null, status = null) }
        try { withContext(Dispatchers.IO) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        // Never propagate an OS/provider exception (including its cause) into diagnostics/UI.
        catch (_: Exception) { mutableState.update { it.copy(error = "NovelAI 安全存储或设置操作失败，请检查输入与存储权限") } }
        finally { mutableState.update { it.copy(busy = false) } }
    }
}
