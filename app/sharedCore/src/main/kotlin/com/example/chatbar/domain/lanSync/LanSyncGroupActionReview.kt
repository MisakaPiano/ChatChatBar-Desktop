package com.example.chatbar.domain.lanSync

/** One frozen, explicit local decision. Neither presentation nor preparation authorizes a mutation. */
class LanSyncGroupActionReview internal constructor(val binding: String,
    val source: LanSyncGroupSourceReview? = null, val incoming: LanSyncGroupIncomingReview? = null,
    val recovery: LanSyncRestorePreview? = null,
    val incomingBindings: Map<String, String> = emptyMap(), val canConfirm: Boolean,
    val heldReason: String? = null) {
    init { require(listOf(source, incoming, recovery).count { it != null } == 1) }
    override fun toString() = "LanSyncGroupActionReview(binding=$binding, canConfirm=$canConfirm, contents=REDACTED)"
}
