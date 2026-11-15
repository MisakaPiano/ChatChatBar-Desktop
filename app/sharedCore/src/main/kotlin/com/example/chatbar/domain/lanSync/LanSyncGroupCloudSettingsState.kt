package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId

data class LanSyncGroupCloudPartialProgress(val completeUnitPackages: Int, val pendingUnitPackages: Int,
    val pendingControlSlots: Int, val observedObjects: Int, val availableObjects: Int) {
    val text: String get() = "已核验 $completeUnitPackages 项资料版本，$pendingUnitPackages 项待补齐" +
        if (pendingControlSlots > 0) "；设备组恢复资料尚未齐全" else ""
}

data class LanSyncGroupCloudRouteProgress(val text: String, val lastObservedAt: Long? = null,
    val pendingOperations: Int? = null, val selectionObserved: Boolean = false,
    val partial: LanSyncGroupCloudPartialProgress? = null, val lastPartialObservedAt: Long? = null) {
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
