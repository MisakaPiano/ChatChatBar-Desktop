package com.example.chatbar.domain.lanSync

enum class LanSyncGroupPanelStage { EMPTY, NEEDS_RECOVERY_SAVE, PREPARED_LOCKED, JOINING, READY, MEMBERSHIP_CONFLICT }
data class LanSyncGroupLedgerConflictBranch(val id: String, val added: List<String>, val revoked: List<String>)
data class LanSyncGroupLedgerConflict(val commonSequence: Long, val totalHeads: Int, val branches: List<LanSyncGroupLedgerConflictBranch>)
data class LanSyncMembershipRecoveryHead(val id: String, val bodyHash: String, val sequence: Long)
enum class LanSyncMembershipRecoveryAuthority { REQUIRED_ACTIVE, CHOICE, REVOKED }
data class LanSyncMembershipRecoveryMember(
    val fingerprint: String,
    val displayName: String,
    val authority: LanSyncMembershipRecoveryAuthority,
    val activeAtCommon: Boolean,
    val activeAtHeads: List<String>,
    val revokedAtHeads: List<String>,
    val defaultActive: Boolean? = null,
    val thisDevice: Boolean = false,
)
data class LanSyncMembershipRecoveryReview(val binding: String, val heads: List<LanSyncMembershipRecoveryHead>,
    val members: List<LanSyncMembershipRecoveryMember>, val retainedPrimaryHeadId: String? = null) {
    override fun toString() = "LanSyncMembershipRecoveryReview(REDACTED)"
}
data class LanSyncMembershipRecoveryImportReview(val binding: String, val commonSequence: Long,
    val heads: List<LanSyncMembershipRecoveryHead>, val primaryHeadId: String, val members: List<LanSyncMembershipRecoveryMember>,
    val activeDevices: Set<String>, val remainingFork: Boolean) {
    override fun toString() = "LanSyncMembershipRecoveryImportReview(REDACTED)"
}
data class LanSyncGroupRouteStatus(val text: String, val lastSuccessAt: Long? = null)
enum class LanSyncGroupNearbyStage(val label: String) {
    UNOBSERVED("尚未核对附近连接"),
    NOT_DISCOVERED("本轮未发现同网连接"),
    DISCOVERY_FAILED("本轮未能查找此设备"),
    INDEX_FAILED("本轮未能读取加密索引"),
    INDEX_READ("已连接并读取加密索引"),
    READ_INCOMPLETE("已连接，部分加密内容未能读取")
}
data class LanSyncGroupDeviceNearby(val stage: LanSyncGroupNearbyStage = LanSyncGroupNearbyStage.UNOBSERVED,
    val lastSuccessAt: Long? = null)
data class LanSyncGroupDeviceRow(val id: String, val name: String, val status: String, val thisDevice: Boolean,
    val managementIdentity: Boolean, val canRevoke: Boolean, val nearby: LanSyncGroupDeviceNearby? = null)
data class LanSyncGroupPeerConfirmation(val binding: String, val name: String, val fingerprint: String)
data class LanSyncGroupRevocationConfirmation(val binding: String, val name: String, val fingerprint: String)

/** Screen projection only. Secret strings are transient, never saveable UI/backup state or diagnostic text. */
data class LanSyncGroupPanelState(val stage: LanSyncGroupPanelStage = LanSyncGroupPanelStage.EMPTY,
    val busy: Boolean = false, val paused: Boolean = false, val status: String = "尚未设置设备组", val error: String? = null,
    val setupBinding: String? = null, val recoveryCode: String? = null, val managementUnlocked: Boolean = false,
    val localManagementAvailable: Boolean = false,
    val managementRecoveryAvailable: Boolean = false, val managementCanInvite: Boolean = false,
    val invitation: LanSyncGroupInviteOffer? = null, val peerConfirmation: LanSyncGroupPeerConfirmation? = null,
    val revocation: LanSyncGroupRevocationConfirmation? = null, val devices: List<LanSyncGroupDeviceRow> = emptyList(),
    val local: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("尚未开始"),
    val nearby: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("尚未连接"),
    val cloud: LanSyncGroupRouteStatus = LanSyncGroupRouteStatus("此设备未配置网盘"),
    val privacyRevision: Long = 0, val recoveryJoining: Boolean = false,
    val canResumeExternalRecovery: Boolean = false, val canAbandonUnactivated: Boolean = false,
    val invitationRetainedWhileLocked: Boolean = false, val invitationApprovalInProgress: Boolean = false,
    val joiningDevice: String? = null, val canAbandonJoin: Boolean = false,
    val canRefreshJoinPreparation: Boolean = false, val invitationAddresses: List<String> = emptyList(),
    val canShelveJoin: Boolean = false, val shelvedJoins: List<LanSyncShelvedJoin> = emptyList(),
    val canArchiveRevokedJoin: Boolean = false, val invitationFileOnly: Boolean = false,
    val invitationFileBinding: String? = null, val joiningLedgerConflict: Boolean = false,
    val nearbyNotice: String? = null, val membershipConflict: LanSyncGroupLedgerConflict? = null,
    val membershipRecovery: LanSyncMembershipRecoveryReview? = null,
    val membershipRecoveryPackageImportAllowed: Boolean = false,
    val membershipRecoveryExportBinding: String? = null,
    val membershipRecoveryImport: LanSyncMembershipRecoveryImportReview? = null,
    internal val fileRecoveryJoinScope: String? = null,
    val joiningMembershipRevoked: Boolean = false) {
    val canEnterInvitation: Boolean
        get() = stage == LanSyncGroupPanelStage.EMPTY ||
            stage == LanSyncGroupPanelStage.JOINING && !recoveryJoining && !joiningLedgerConflict && !joiningMembershipRevoked
    val canEnterExternalRecovery: Boolean
        get() = !joiningLedgerConflict && !joiningMembershipRevoked &&
            (stage == LanSyncGroupPanelStage.EMPTY || stage == LanSyncGroupPanelStage.JOINING ||
                stage == LanSyncGroupPanelStage.READY && canResumeExternalRecovery)

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
    fun prepareMembershipRecovery(code: String)
    fun confirmMembershipRecovery(binding: String, primaryHeadId: String, activeDevices: Set<String>)
    fun lockMembershipRecovery()
    fun prepareMembershipRecoveryImport(open: () -> java.io.InputStream)
    fun confirmMembershipRecoveryImport(binding: String)
    fun cancelMembershipRecoveryImport()
    fun saveMembershipRecoveryPackage(binding: String?, write: (ByteArray) -> Unit)
    fun beginJoin(code: String)
    fun beginJoinFromFile(open: () -> java.io.InputStream)
    fun abandonUnprovedJoin()
    fun shelveUnsettledJoin()
    fun archiveRevokedJoin()
    fun restoreShelvedJoin(operation: String)
    fun retryJoin()
    fun refreshJoinPreparation()
    fun createInvitation(host: String? = null, forFile: Boolean = false)
    fun saveInvitationFile(binding: String?, write: (ByteArray) -> Unit)
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
    fun prepareResolution(view: LanSyncGroupNodeView, choices: Map<String, LanSyncGroupResolutionChoice>)
    fun resumeResolution(operation: String)
    fun previewImage(context: LanSyncGroupFrozenImageContext, record: LanSyncRecord, reference: String)
    fun closeImagePreview()
    fun readImagePreview(id: String): ByteArray
}
