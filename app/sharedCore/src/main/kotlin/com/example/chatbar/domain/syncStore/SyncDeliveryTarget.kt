package com.example.chatbar.domain.syncStore

import kotlinx.serialization.Serializable

@Serializable enum class SyncDeliveryKind { LEGACY_SINGLE_BACKEND, LOCAL_REPLICA, NEARBY_DEVICE, CLOUD_STORE }
/** Device-local route identity. The native adapter binds this opaque ID to one actual store/peer. */
@Serializable data class SyncDeliveryTarget(val kind: SyncDeliveryKind, val id: SyncStoreId) {
    init { require((kind == SyncDeliveryKind.LEGACY_SINGLE_BACKEND) == (id.value == "0".repeat(32))) { "SYNC_DELIVERY_TARGET" } }
    companion object { val LEGACY = SyncDeliveryTarget(SyncDeliveryKind.LEGACY_SINGLE_BACKEND, SyncStoreId.parse("0".repeat(32))) }
}
