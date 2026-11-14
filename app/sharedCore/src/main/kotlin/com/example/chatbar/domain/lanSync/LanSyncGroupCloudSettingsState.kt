package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId

data class LanSyncGroupCloudRouteProgress(val text: String, val lastObservedAt: Long? = null,
    val pendingOperations: Int? = null, val selectionObserved: Boolean = false) {
    override fun toString() = "LanSyncGroupCloudRouteProgress(REDACTED)"
}

/** Presentation only. Passwords and credential handles never enter this projection. */
data class LanSyncGroupCloudSettingsState(val visible: Boolean = false, val busy: Boolean = false,
    val rows: List<LanSyncGroupCloudRoute> = emptyList(),
    val progress: Map<SyncStoreId, LanSyncGroupCloudRouteProgress> = emptyMap(),
    val error: String? = null, val notice: String? = null, val canEnable: Boolean = false,
    val revision: Long = 0) {
    override fun toString() = "LanSyncGroupCloudSettingsState(visible=$visible, busy=$busy, REDACTED)"
}

/** Each callback is supplied by the actual application owner. */
interface LanSyncGroupCloudSettingsActions {
    fun closeCloudSettings()
    fun refreshCloudSettings()
    /** Must synchronously copy any retained password bytes before returning. The UI erases them. */
    fun saveCloudRoute(draft: LanSyncGroupCloudDraft)
    fun setCloudRouteEnabled(id: SyncStoreId, enabled: Boolean)
    fun removeCloudRoute(id: SyncStoreId)
}
