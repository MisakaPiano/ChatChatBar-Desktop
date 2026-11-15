package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream

/** Only encrypted management material. It contains neither the recovery code nor conversation data. */
internal fun lanSyncReadManagementBackup(store: CiphertextStore, context: SyncManagementVaultContext,
    descriptor: CiphertextDescriptor): SyncManagementVaultBlob {
    require(descriptor.length in 1..272L * 1024) { "SYNC_GROUP_MANAGEMENT_BACKUP_BUDGET" }
    val bytes = ByteArrayOutputStream()
    val output = object : java.io.OutputStream() {
        override fun write(value: Int) { require(bytes.size() < descriptor.length) { "SYNC_GROUP_MANAGEMENT_BACKUP_CHANGED" }; bytes.write(value) }
        override fun write(block: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && bytes.size().toLong() + length <= descriptor.length) { "SYNC_GROUP_MANAGEMENT_BACKUP_CHANGED" }
            bytes.write(block, offset, length)
        }
    }
    require(store.readTo(context.key, output, descriptor)?.descriptor == descriptor) { "SYNC_GROUP_MANAGEMENT_BACKUP_MISSING" }
    val value = bytes.toByteArray()
    require(value.size.toLong() == descriptor.length && lanSyncHash(value) == descriptor.sha256.hex) { "SYNC_GROUP_MANAGEMENT_BACKUP_CHANGED" }
    return SyncManagementVaultBlob(context, value)
}
