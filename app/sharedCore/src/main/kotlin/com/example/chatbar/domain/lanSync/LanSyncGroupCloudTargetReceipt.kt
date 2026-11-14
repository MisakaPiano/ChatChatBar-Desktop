package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncDeliveryTarget
import com.example.chatbar.domain.syncStore.SyncVersionRef
import kotlinx.serialization.Serializable

/** Actual route-local physical locator and historical complete-read observation. */
@Serializable sealed interface LanSyncGroupCloudTargetObservation {
    @Serializable data class Whole(val observation: LanSyncGroupWeakTargetObservation) : LanSyncGroupCloudTargetObservation
    @Serializable data class Chunks(val observation: LanSyncGroupChunkTargetObservation) : LanSyncGroupCloudTargetObservation
    val observedAtMillis: Long get() = when (this) {
        is Whole -> observation.observedAtMillis
        is Chunks -> observation.observedAtMillis
    }
}

/** Existing never attributes another publisher's operation to this relay's local append request. */
@Serializable sealed interface LanSyncGroupCloudTargetEvidence {
    val readback: LanSyncGroupCloudTargetObservation
    @Serializable data class Appended(val append: LanSyncGroupCloudTargetObservation,
        override val readback: LanSyncGroupCloudTargetObservation) : LanSyncGroupCloudTargetEvidence
    @Serializable data class Existing(override val readback: LanSyncGroupCloudTargetObservation) : LanSyncGroupCloudTargetEvidence
}

@Serializable data class LanSyncGroupCloudTargetObjectReceipt(val objectRequest: LanSyncGroupTargetObject,
    val evidence: LanSyncGroupCloudTargetEvidence)

/** Complete authenticated selection on one route; neither a freshness nor native-activation claim. */
@Serializable data class LanSyncGroupCloudTargetReceipt(val planHash: String, val target: SyncDeliveryTarget,
    val checkpoint: SyncVersionRef, val objects: List<LanSyncGroupCloudTargetObjectReceipt>,
    /** Bounded payload-free count of mismatched, unavailable or corrupt provider candidates. */
    val rejectedCandidates: Int = 0)
