package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.withCurrentWebSearchDefaults
import com.example.chatbar.data.local.entity.withNormalizedAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设置仓库 - 管理应用全局设置和玩家角色设定（以单例形式存储）
 */
class SettingsRepository(private val storage: JsonFileStorage) {
    private val settingsMutex = Mutex()

    companion object {
        private const val APP_SETTINGS_TYPE = "app_settings"
        private const val PLAYER_SETTING_TYPE = "player_setting"
    }

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: Flow<AppSettings> = _appSettings.asStateFlow()
    val currentAppSettings: AppSettings
        get() = _appSettings.value

    private val _playerSetting = MutableStateFlow(PlayerSetting())
    val playerSetting: Flow<PlayerSetting> = _playerSetting.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: Flow<Boolean> = _isInitialized.asStateFlow()

    private var initialized = false

    suspend fun initialize(forceReload: Boolean = false) = settingsMutex.withLock {
        if (initialized && !forceReload) return@withLock
        _appSettings.value = getAppSettingsLocked()
        _playerSetting.value = getPlayerSettingLocked()
        initialized = true
        _isInitialized.value = true
    }

    suspend fun getAppSettings(): AppSettings = settingsMutex.withLock { getAppSettingsLocked() }

    private suspend fun getAppSettingsLocked(): AppSettings {
        val loaded = storage.loadSingleton(APP_SETTINGS_TYPE, AppSettings.serializer())
            ?: AppSettings().also { saveAppSettingsLocked(it) }
        return migrateAppSettings(loaded)
    }

    suspend fun saveAppSettings(settings: AppSettings) {
        settingsMutex.withLock { saveAppSettingsLocked(settings) }
    }

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        initialize()
        return settingsMutex.withLock {
            saveAppSettingsLocked(transform(_appSettings.value))
            _appSettings.value
        }
    }

    suspend fun saveAppSettingsDraft(baseline: AppSettings, draft: AppSettings): AppSettings =
        updateAppSettings { latest -> mergeSettingsDraft(AppSettings.serializer(), baseline, draft, latest) }

    private suspend fun saveAppSettingsLocked(settings: AppSettings) {
        val normalized = settings.withNormalizedAppearance()
        storage.saveSingleton(APP_SETTINGS_TYPE, normalized, AppSettings.serializer())
        _appSettings.value = normalized
    }

    private suspend fun migrateAppSettings(settings: AppSettings): AppSettings {
        val migrated = settings
            .withCurrentWebSearchDefaults()
            .withNormalizedAppearance()
        if (migrated == settings) {
            return settings
        }
        saveAppSettingsLocked(migrated)
        return migrated
    }

    suspend fun completeTutorial(version: Int) {
        updateAppSettings { current ->
            if (current.tutorialVersion < version) current.copy(tutorialVersion = version) else current
        }
    }

    suspend fun getPlayerSetting(): PlayerSetting = settingsMutex.withLock { getPlayerSettingLocked() }

    private suspend fun getPlayerSettingLocked(): PlayerSetting {
        return storage.loadSingleton(PLAYER_SETTING_TYPE, PlayerSetting.serializer())
            ?: PlayerSetting().also { savePlayerSettingLocked(it) }
    }

    suspend fun savePlayerSetting(setting: PlayerSetting) = settingsMutex.withLock {
        savePlayerSettingLocked(setting)
    }

    private suspend fun savePlayerSettingLocked(setting: PlayerSetting) {
        val updated = setting.copy(updatedAt = System.currentTimeMillis())
        storage.saveSingleton(PLAYER_SETTING_TYPE, updated, PlayerSetting.serializer())
        _playerSetting.value = updated
    }
}
