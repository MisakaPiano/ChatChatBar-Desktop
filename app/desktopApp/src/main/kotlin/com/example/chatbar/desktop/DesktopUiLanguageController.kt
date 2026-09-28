package com.example.chatbar.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Desktop display preference only; construction and missing-root reads never persist a default. */
internal class DesktopUiLanguageController(private val store: DesktopSettingsStore) {
    private val lock = Mutex()
    private val mutableLanguage = MutableStateFlow(DesktopUiLanguage.ZH_CN)
    val language: StateFlow<DesktopUiLanguage> = mutableLanguage.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    suspend fun load() = lock.withLock {
        when (val result = store.load()) {
            is DesktopSettingsLoadResult.Loaded -> {
                mutableLanguage.value = result.document.settings.uiLanguage
                mutableError.value = null
            }
            is DesktopSettingsLoadResult.Missing -> {
                mutableLanguage.value = DesktopUiLanguage.ZH_CN
                mutableError.value = null
            }
            is DesktopSettingsLoadResult.Failure -> mutableError.value = result.message
        }
    }

    suspend fun select(language: DesktopUiLanguage) = lock.withLock {
        val document = when (val result = store.load()) {
            is DesktopSettingsLoadResult.Loaded -> result.document
            is DesktopSettingsLoadResult.Missing -> result.document
            is DesktopSettingsLoadResult.Failure -> {
                mutableError.value = result.message
                return@withLock
            }
        }
        try {
            store.save(document, document.settings.copy(uiLanguage = language))
            mutableLanguage.value = language
            mutableError.value = null
        } catch (failure: Exception) {
            mutableError.value = failure.message ?: "Unable to save Desktop language"
        }
    }
}
