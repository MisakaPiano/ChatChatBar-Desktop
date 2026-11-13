package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncJsonFiles
import com.example.chatbar.domain.lanSync.LanSyncMetadataBudget
import com.example.chatbar.domain.lanSync.lanSyncJson
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream

@Serializable enum class SyncGroupObjectKind { VERSION, DEVICE_LEDGER }
@Serializable data class SyncGroupEnvelope(val format: Int, val kind: SyncGroupObjectKind,
    val version: SyncCausalVersion? = null, val deviceLedger: SyncDeviceLedgerRecord? = null) {
    init {
        require(format == 1 && when (kind) {
            SyncGroupObjectKind.VERSION -> version != null && deviceLedger == null
            SyncGroupObjectKind.DEVICE_LEDGER -> deviceLedger != null && version == null
        }) { "SYNC_GROUP_ENVELOPE" }
    }
    val group get() = version?.group ?: requireNotNull(deviceLedger).group
    fun reference() = version?.reference() ?: requireNotNull(deviceLedger).reference()
}

/** Construction stays in the complete AEAD/decode owner; a claimed epoch in JSON cannot create it. */
class SyncAuthenticatedGroupObject internal constructor(val context: SyncEncryptionContext,
    val metadata: CiphertextObjectMetadata, val envelope: SyncGroupEnvelope)

/** Metadata envelope owner only. It authenticates data; ledger/history still decide activation. */
class SyncGroupObjectCodec(private val privateDirectory: Path,
    private val metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()) {
    init { PrivateSyncFile.checked(privateDirectory, true) }

    @OptIn(ExperimentalSerializationApi::class)
    fun enqueue(outbox: SyncEncryptedOutbox, operation: SyncStoreId, key: CiphertextObjectKey,
        cipher: SyncStreamingCipher, envelope: SyncGroupEnvelope): SyncOutboxEntry {
        require(key.kind == CiphertextObjectKind.COMMIT && envelope.group == cipher.group) { "SYNC_GROUP_ENVELOPE_CONTEXT" }
        val normalized = freeze(envelope, sorted = true)
        PrivateSyncFile.create(privateDirectory).use { source ->
            FileChannel.open(source.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                lanSyncJson.encodeToStream(SyncGroupEnvelope.serializer(), normalized, Channels.newOutputStream(channel)); channel.force(true)
            }
            metadataBudget.validate(source.path)
            return outbox.prepare(operation, SyncEncryptionContext(cipher.group, cipher.epoch, key), source.path)
        }
    }

    fun read(backend: CiphertextStore, cipher: SyncStreamingCipher, context: SyncEncryptionContext,
        expectedDescriptor: CiphertextDescriptor? = null, expectedReference: SyncVersionRef? = null): SyncAuthenticatedGroupObject {
        require(context.key.kind == CiphertextObjectKind.COMMIT) { "SYNC_GROUP_ENVELOPE_CONTEXT" }
        PrivateSyncFile.create(privateDirectory).use { ciphertext ->
            val metadata = FileChannel.open(ciphertext.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val result = requireNotNull(backend.readTo(context.key, Channels.newOutputStream(channel), expectedDescriptor)) { "SYNC_GROUP_OBJECT_MISSING" }
                channel.force(true); result
            }
            require(metadata.key == context.key) { "SYNC_GROUP_ENVELOPE_CONTEXT" }
            Files.newInputStream(ciphertext.path, NOFOLLOW_LINKS).use { input -> cipher.decrypt(input, context, privateDirectory).use { plaintext ->
                metadataBudget.validate(plaintext.path)
                val envelope = requireNotNull(LanSyncJsonFiles.read(plaintext.path, SyncGroupEnvelope.serializer())) { "SYNC_GROUP_ENVELOPE" }
                require(envelope.group == context.group && (expectedReference == null || envelope.reference() == expectedReference)) { "SYNC_GROUP_ENVELOPE_CONTEXT" }
                return SyncAuthenticatedGroupObject(context, metadata, freeze(envelope, sorted = false))
            } }
        }
    }

    private fun freeze(envelope: SyncGroupEnvelope, sorted: Boolean): SyncGroupEnvelope {
        fun refs(values: Set<SyncVersionRef>): Set<SyncVersionRef> = Collections.unmodifiableSet(
            (if (sorted) values.sortedBy { it.id.value } else values.toList()).toCollection(linkedSetOf()))
        val version = envelope.version?.let { row -> row.copy(parents = refs(row.parents),
            changes = Collections.unmodifiableMap((if (sorted) row.changes.toSortedMap() else row.changes).mapValues { (_, change) -> change.copy(previous = refs(change.previous)) })) }
        val ledger = envelope.deviceLedger?.let { row -> row.copy(
            members = Collections.unmodifiableMap((if (sorted) row.members.toSortedMap() else row.members).toMap()), acceptedHeads = refs(row.acceptedHeads)) }
        return envelope.copy(version = version, deviceLedger = ledger)
    }
}
