package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.PrivateSyncFile
import com.example.chatbar.domain.syncStore.SyncAuthenticatedPlaintext
import com.example.chatbar.domain.syncStore.SyncPlaintextIdentity
import com.example.chatbar.domain.syncStore.SyncUnitChange
import java.io.Closeable
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Collections
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream

@Serializable data class LanSyncGroupUnitPayload(val format: Int, val unit: String, val coveredTypes: Set<String>,
    val records: List<LanSyncRecord>, val resources: Map<String, Long>)

class LanSyncDecodedGroupUnit internal constructor(val payload: LanSyncGroupUnitPayload,
    val plaintext: SyncPlaintextIdentity, val valueHash: String, internal val metadataIndexCost: Long = 0) {
    val dependencies: Set<SyncPlaintextIdentity> get() = payload.resources.map { (hash, size) -> SyncPlaintextIdentity(size, hash) }.toSet()
    val membership: SyncPlaintextIdentity = LanSyncGroupMembership.identity(payload.records)
}

class LanSyncPreparedGroupUnit internal constructor(private val file: PrivateSyncFile, private val members: PrivateSyncFile,
    val decoded: LanSyncDecodedGroupUnit) : Closeable {
    val path: Path get() = file.path
    val membershipPath: Path get() = members.path
    override fun close() { try { file.close() } finally { members.close() } }
}

/** Native unit/resource manifest. Cross-unit graph validation still precedes Workspace publication. */
class LanSyncGroupUnitCodec(private val privateDirectory: Path,
    coverage: Set<String> = LanSyncScope.schemas.keys,
    private val metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()) {
    private val coverage = Collections.unmodifiableSet(coverage.toSet())
    private val recordBodies by lazy { LanSyncRecordBodies(privateDirectory.resolve("decoded-records"), metadataBudget) }
    init {
        PrivateSyncFile.checked(privateDirectory, true)
        require(coverage.isNotEmpty() && LanSyncScope.schemas.keys.containsAll(coverage)) { "SYNC_GROUP_SCOPE" }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun prepare(capture: LanSyncCapture, unit: String): LanSyncPreparedGroupUnit {
        require(unit !in capture.missingImageUnits) { LanSyncMissingImage.CODE }
        require(unit !in capture.blockedResourceUnits) { "SYNC_GROUP_LOCAL_RESOURCE_BLOCKED" }
        capture.snapshot.validate(); LanSyncScope.validateGraph(capture.snapshot, allowLocalMissingImages = true)
        require(capture.snapshot.coveredTypes == coverage) { "SYNC_GROUP_SCOPE" }
        val records = requireNotNull(capture.snapshot.units()[unit]) { "SYNC_GROUP_UNIT_MISSING" }
        return prepareValidated(capture.snapshot, unit, records)
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun prepareValidated(snapshot: LanSyncSnapshot, unit: String, records: List<LanSyncRecord>): LanSyncPreparedGroupUnit {
        require(snapshot.coveredTypes == coverage) { "SYNC_GROUP_SCOPE" }
        val references = LanSyncScope.referencedResources(records)
        val payload = LanSyncGroupUnitPayload(1, unit, coverage.toSortedSet(), records.sortedBy { it.key },
            references.associateWith { snapshot.resources.getValue(it) }.toSortedMap())
        val expectedValue = validate(payload)
        val file = PrivateSyncFile.create(privateDirectory)
        var members: PrivateSyncFile? = null
        try {
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                lanSyncJson.encodeToStream(LanSyncGroupUnitPayload.serializer(), payload, Channels.newOutputStream(channel)); channel.force(true)
            }
            val length = Files.size(file.path)
            val identity = SyncPlaintextIdentity(length, LanSyncResourceStore.hashFile(file.path, length))
            val decoded = parse(file.path, identity)
            require(decoded.valueHash == expectedValue) { "SYNC_GROUP_UNIT_SOURCE_CHANGED" }
            val memberFile = PrivateSyncFile.create(privateDirectory); members = memberFile
            require(LanSyncGroupMembership.write(memberFile.path, payload.records) == decoded.membership) { "SYNC_GROUP_MEMBERSHIP_CHANGED" }
            return LanSyncPreparedGroupUnit(file, memberFile, decoded)
        } catch (failure: Throwable) { try { file.close() } finally { members?.close() }; throw failure }
    }

    fun read(plaintext: SyncAuthenticatedPlaintext, expectedUnit: String, change: SyncUnitChange): LanSyncDecodedGroupUnit {
        require(change.content == plaintext.identity && change.valueHash != null) { "SYNC_GROUP_UNIT_PLAINTEXT" }
        return checked(plaintext.path, plaintext.identity, expectedUnit, change)
    }
    fun readCached(store: LanSyncResourceStore, expectedUnit: String, change: SyncUnitChange): LanSyncDecodedGroupUnit {
        val identity = requireNotNull(change.content) { "SYNC_GROUP_UNIT_PLAINTEXT" }
        val path = requireNotNull(store.completed(identity.sha256, identity.length)) { "SYNC_GROUP_UNIT_MISSING" }
        return checked(path, identity, expectedUnit, change)
    }
    private fun checked(path: Path, identity: SyncPlaintextIdentity, expectedUnit: String, change: SyncUnitChange): LanSyncDecodedGroupUnit {
        val decoded = parse(path, identity)
        require(decoded.payload.unit == expectedUnit && decoded.valueHash == change.valueHash) { "SYNC_GROUP_UNIT_BINDING" }
        require(change.membership == null || change.membership == decoded.membership) { "SYNC_GROUP_MEMBERSHIP_CHANGED" }
        return decoded
    }

    private fun parse(path: Path, identity: SyncPlaintextIdentity): LanSyncDecodedGroupUnit {
        var indexCost = 0L
        val payload = requireNotNull(LanSyncJsonFiles.readRecords(path, LanSyncGroupUnitPayload.serializer(), recordBodies,
            listOf("records"), identity.sha256, identity.length) { indexCost = it.indexCost }) { "SYNC_GROUP_UNIT_MISSING" }
        val hash = validate(payload)
        val frozen = payload.copy(coveredTypes = Collections.unmodifiableSet(payload.coveredTypes.toSet()),
            records = Collections.unmodifiableList(payload.records.toList()), resources = Collections.unmodifiableMap(payload.resources.toMap()))
        return LanSyncDecodedGroupUnit(frozen, identity, hash, indexCost)
    }
    private fun validate(payload: LanSyncGroupUnitPayload): String {
        require(payload.format == 1 && payload.unit.length in 1..600 && payload.coveredTypes == coverage && payload.records.isNotEmpty()) { "SYNC_GROUP_UNIT_SCOPE" }
        val snapshot = LanSyncSnapshot(deviceId = "group-unit-validation", records = payload.records, resources = payload.resources, coveredTypes = coverage)
        snapshot.validate()
        payload.records.forEach { row ->
            LanSyncScope.validateRecord(row)
            require(row.body == LanSyncScope.normalize(row.type, row.body)) { "SYNC_GROUP_UNIT_DEVICE_FIELDS" }
        }
        require(snapshot.units().keys == setOf(payload.unit)) { "SYNC_GROUP_UNIT_MEMBERSHIP" }
        require(LanSyncScope.referencedResources(payload.records) == payload.resources.keys) { "SYNC_GROUP_UNIT_RESOURCES" }
        LanSyncScope.validateResourceReferences(snapshot)
        return snapshot.hashes().getValue(payload.unit)
    }
}
