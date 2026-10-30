package com.example.chatbar.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class DesktopSendKey { CTRL_ENTER, ENTER }

@Serializable
data class DesktopPinnedPerson(val sessionId: String, val cardId: String, val personId: String)

@Serializable
data class DesktopChatUiPreferences(
    val sendKey: DesktopSendKey = DesktopSendKey.CTRL_ENTER,
    val inlineStatuses: Boolean = false,
    val peopleWidthDp: Float? = null,
    val peopleHidden: Boolean = false,
    val allPeople: Boolean = false,
    val pinnedPeople: List<DesktopPinnedPerson> = emptyList(),
)

/** Display preferences only. No entity, prompt, residency or participation writes. */
internal class DesktopChatUiController(private val store: DesktopSettingsStore) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(DesktopChatUiPreferences())
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    suspend fun load() = lock.withLock {
        try {
            mutableState.value = when (val result = store.load()) {
                is DesktopSettingsLoadResult.Loaded -> result.document.settings.chatUi
                is DesktopSettingsLoadResult.Missing -> result.document.settings.chatUi
                is DesktopSettingsLoadResult.Failure -> throw DesktopSettingsMutationException(result)
            }
            mutableError.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "无法读取聊天显示偏好；未覆盖原配置" }
    }

    suspend fun update(change: (DesktopChatUiPreferences) -> DesktopChatUiPreferences) = lock.withLock {
        try {
            val saved = store.updateLatest { it.copy(chatUi = change(it.chatUi)) }
            mutableState.value = saved.settings.chatUi
            mutableError.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "聊天显示偏好未保存，请重试" }
    }

    suspend fun pin(identity: DesktopPinnedPerson, pinned: Boolean) = update {
        it.copy(pinnedPeople = it.pinnedPeople.filterNot { item -> item == identity } + if (pinned) listOf(identity) else emptyList())
    }
}
