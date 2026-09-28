package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHistoryPolicy
import com.example.chatbar.domain.appearance.ThemeColorHsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopAppearanceState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeColor: ThemeColorHsv = DefaultThemeColorHsv,
    val error: String? = null,
)

/** Container-lifetime appearance owner. No hydrated credential enters its observable state. */
internal class DesktopAppearanceController(private val settings: SettingsRepository) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(DesktopAppearanceState())
    val state: StateFlow<DesktopAppearanceState> = mutableState.asStateFlow()

    suspend fun load() = lock.withLock {
        try {
            val persisted = settings.readExistingAppSettings() ?: AppSettings()
            mutableState.value = DesktopAppearanceState(persisted.themeMode, persisted.themeColor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(error = "Unable to load appearance (${failure::class.simpleName})")
        }
    }

    suspend fun setMode(mode: ThemeMode) = mutate { it.copy(themeMode = mode) }

    suspend fun setColor(color: ThemeColorHsv) = mutate { latest ->
        val normalized = color.normalized()
        latest.copy(
            themeColor = normalized,
            themeColorHistory = ThemeColorHistoryPolicy.update(
                latest.themeColor, normalized, latest.themeColorHistory,
            ),
        )
    }

    private suspend fun mutate(change: (AppSettings) -> AppSettings) = lock.withLock {
        try {
            val saved = settings.updateAppSettings(change)
            mutableState.value = DesktopAppearanceState(saved.themeMode, saved.themeColor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(error = "Unable to save appearance (${failure::class.simpleName})")
        }
    }
}
