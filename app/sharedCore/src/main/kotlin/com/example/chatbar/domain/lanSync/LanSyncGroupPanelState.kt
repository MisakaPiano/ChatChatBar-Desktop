package com.example.chatbar.domain.lanSync

enum class LanSyncGroupPanelStage { EMPTY, NEEDS_RECOVERY_SAVE, PREPARED_LOCKED, JOINING, READY }
data class LanSyncGroupRouteStatus(val text: String, val lastSuccessAt: Long? = null)
data class LanSyncGroupDeviceRow(val id: String, val name: String, val status: String, val thisDevice: Boolean,
    val managementIdentity: Boolean, val canRevoke: Boolean)
data class LanSyncGroupPeerConfirmation(val binding: String, val name: String, val fingerprint: String)
data class LanSyncGroupRevocationConfirmation(val binding: String, val name: String)

/** Screen projection only. Secret strings are transient, never saveable UI/backup state or diagnostic text. */
data class LanSyncGroupPanelState(val stage: LanSyncGroupPanelStage = LanSyncGroupPanelStage.EMPTY,
    val busy: Boolean = false, val status: String = "尚未设置设备组", val error: String? = null,
    val setupBinding: String? = null, val recoveryCode: String? = null, val managementUnlocked: Boolean = false,
    val localManagementAvailable: Boolean = false,
    val managementRecoveryAvailable: Boolean = false, val managementCanInvite: Boolean = false,
    val invitation: LanSyncGroupInviteOffer? = null, val peerConfirmation: LanSyncGroupPeerConfirmation? = null,
    val revocation: LanSyncGroupRevocationConfirmation? = null, val devices: List<LanSyncGroupDeviceRow> = emptyList(),
    val local: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("尚未开始"),
    val nearby: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("尚未连接"),
    val cloud: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("此设备未配置网盘"),
    val privacyRevision: Long = 0) {
    override fun toString() = "LanSyncGroupPanelState(stage=$stage, busy=$busy, secrets=REDACTED)"
}

/** Required real owner callbacks; the screen never supplies default successful/no-op operations. */
interface LanSyncGroupPanelActions {
    fun createGroup()
    fun recoverySaved(binding: String)
    fun abandonUnusedSetup()
    fun unlockManagement(code: String)
    fun recoverManagement(code: String)
    fun lockManagement()
    fun resumeManagementOperation()
    fun beginJoin(code: String)
    fun retryJoin()
    fun refreshJoinPreparation()
    fun createInvitation()
    fun closeInvitation()
    fun acceptPeer(binding: String)
    fun prepareRevocation(device: String)
    fun confirmRevocation(binding: String)
    fun cancelRevocation()
    fun retry()
    fun openCloudSettings()
    fun prepareSourceReview(view: LanSyncGroupNodeView, unit: String)
    fun prepareIncomingReview(view: LanSyncGroupNodeView, unit: String)
    fun confirmActionReview(binding: String)
    fun cancelActionReview()
    fun refreshRecovery()
    fun openRecovery(transaction: String)
    fun prepareRestore(unit: String)
    fun closeRecovery()
}
