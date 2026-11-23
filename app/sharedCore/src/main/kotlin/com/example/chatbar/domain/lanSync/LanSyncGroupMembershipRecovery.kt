package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import kotlinx.serialization.Serializable

/** Separate explicit root authorization. Opening this review never grants ordinary group access. */
internal class LanSyncGroupMembershipRecovery(
    private val access: LanSyncGroupRuntimeAccess,
    private val custody: SyncGroupCustody,
    private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray,
) {
    private val root = access.work.resolve("membership-recovery")
    private val exportPointer get() = root.resolve("export.protected")
    private val ownerDevice = SyncDeviceSignatures.decode(access.trust.ownerCertificate).fingerprint

    @Serializable private data class Intent(
        val author: String, val group: SyncStoreId, val genesis: SyncVersionRef,
        val heads: Set<SyncVersionRef>, val row: SyncDeviceLedgerRecord, val key: String,
    )
    @Serializable private data class Export(val id: SyncStoreId, val binding: String)

    internal class OpenReview(
        val session: SyncManagementSession, val heads: Set<SyncVersionRef>,
        val content: LanSyncGroupNode.MembershipRecoveryContent, val projection: LanSyncMembershipRecoveryReview,
    ) : AutoCloseable {
        override fun close() = session.close()
    }
    internal class ImportReview(
        val bytes: ByteArray, val heads: Set<SyncVersionRef>, val projection: LanSyncMembershipRecoveryImportReview,
    )

    suspend fun prepare(code: String): OpenReview {
        val graph = custody.recoveryGraph()
        val fork = requireNotNull(graph.verifiedFork()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_NO_FORK" }
        val tips = graph.admittedTips()
        val rows = tips.map { requireNotNull(graph.record(it)) }
        val members = members(fork.common, rows, requireDevice = true, names = access.recoveryNames(graph, rows))
        val session = SyncManagementSession.open(code, access.encryptedManagementBackup().bytes())
        try {
            require(session.trust.group == access.trust.group && session.trust.genesis == access.trust.genesis &&
                session.ownerCertificate == access.trust.ownerCertificate) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_ROOT" }
            val content = access.node.membershipRecoveryContent(graph, session, retainedKeys(graph, session))
            require(custody.recoveryGraph().admittedTips() == tips) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
            val retained = readIntent(tips)
            val choices = retained?.row?.members
            val projection = LanSyncMembershipRecoveryReview(SyncStoreId.random().value, heads(rows),
                members.map { if (choices == null) it else it.copy(defaultActive = choices.getValue(it.fingerprint).active) },
                retainedPrimaryHeadId = retained?.row?.recovery?.primary?.id?.value)
            return OpenReview(session, tips, content, projection)
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    suspend fun confirm(review: OpenReview, binding: String, primaryId: String, active: Set<String>,
        requireCurrentReview: () -> Unit): Boolean {
        require(review.projection.binding == binding && review.session.isUnlocked) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        requireCurrentReview()
        val graph = custody.recoveryGraph()
        val tips = graph.admittedTips()
        require(tips == review.heads) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        val primary = requireNotNull(tips.singleOrNull { it.id.value == primaryId }) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PRIMARY" }
        val currentContent = access.node.membershipRecoveryContent(graph, review.session, retainedKeys(graph, review.session))
        require(currentContent.cuts == review.content.cuts) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        val rows = tips.map { requireNotNull(graph.record(it)) }
        val fork = requireNotNull(graph.verifiedFork()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_NO_FORK" }
        val allowed = members(fork.common, rows, requireDevice = true)
        require(active.all { selected -> allowed.any { it.fingerprint == selected && it.authority != LanSyncMembershipRecoveryAuthority.REVOKED } } &&
            allowed.filter { it.authority == LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE }.all { it.fingerprint in active }) {
            "SYNC_GROUP_MEMBERSHIP_RECOVERY_SELECTION"
        }
        val proposedMembers = rows.flatMap { it.members.entries }.groupBy({ it.key }, { it.value }).mapValues { (id, versions) ->
            require(versions.map { it.certificate to it.recipientKey }.distinct().size == 1) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_MEMBER_CHANGED" }
            versions.first().copy(active = id in active)
        }
        val cuts = rows.flatMapTo(linkedSetOf()) { it.acceptedHeads } + currentContent.cuts.getValue(primary)
        return custody.commitMembershipRecovery(tips, authoring = true, prepare = {
            requireCurrentReview()
            val retained = readIntent(tips)
            val intent = if (retained != null) {
                require(retained.row.recovery == SyncGroupLedgerRecovery(tips, primary) &&
                    retained.row.members == proposedMembers && retained.row.acceptedHeads == cuts) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PENDING_INTENT" }
                retained
            } else {
                val cipher = SyncStreamingCipher.create(access.trust.group)
                val record = SyncDeviceLedgerRecord(access.trust.group, SyncStoreId.random(), null,
                    Math.addExact(rows.maxOf { it.sequence }, 1), cipher.epoch, cipher.keyCommitment(),
                    rows.flatMapTo(linkedSetOf()) { it.coveredTypes }, proposedMembers, cuts, "unsigned",
                    recovery = SyncGroupLedgerRecovery(tips, primary))
                graph.validateProposedRecovery(record)
                val keyBytes = cipher.protectedKeyset(protect)
                Intent(access.device, access.trust.group, access.trust.genesis, tips, record,
                    Base64.getEncoder().encodeToString(keyBytes)).also { writeIntent(it) }
            }
            val existing = packagePath(intent.row.id)
            if (Files.exists(existing, NOFOLLOW_LINKS)) return@commitMembershipRecovery lanSyncRead(existing, SyncLedgerRecoveryFileCodec.MAX_BYTES)
            requireCurrentReview()
            val signature = review.session.signRootPayload(intent.row.signingBytes())
            val signed = intent.row.copy(signature = Base64.getEncoder().encodeToString(signature))
            val combined = graph.verifiedCopy()
            require(combined.offer(signed) == SyncVersionState.ADMITTED && combined.current().reference() == signed.reference()) {
                "SYNC_GROUP_MEMBERSHIP_RECOVERY_SIGNATURE"
            }
            val key = SyncStreamingCipher.open(access.trust.group, signed.epoch, Base64.getDecoder().decode(intent.key), unprotect)
            require(key.keyCommitment() == signed.keyCommitment) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_KEY" }
            val capsules = (currentContent.keys.values + key).flatMap { epoch ->
                active.sorted().map { id ->
                    requireCurrentReview()
                    SyncKeyDeliveryCodec.encode(SyncGroupKeyDelivery.create(combined, epoch, id, SyncStoreId.random(), review.session::signRootPayload))
                }
            }
            ByteArrayOutputStream().also { SyncLedgerRecoveryFileCodec.write(it, access.trust, combined.ancestry(), capsules) }.toByteArray()
        }, retain = ::retainPackage)
    }

    fun prepareImport(open: () -> InputStream): ImportReview {
        val graph = custody.recoveryGraph()
        val tips = graph.admittedTips()
        val bytes = open().use { input ->
            ByteArrayOutputStream().also { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count <= SyncLedgerRecoveryFileCodec.MAX_BYTES - output.size()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_INPUT_UNAVAILABLE" }
                    output.write(buffer, 0, count)
                }
            }.toByteArray()
        }
        require(bytes.size in 1..SyncLedgerRecoveryFileCodec.MAX_BYTES) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_INPUT_UNAVAILABLE" }
        val proof = custody.inspectRecoveryFile(bytes)
        val recovery = requireNotNull(proof.recovery.recovery)
        val evidence = SyncDeviceLedger(access.trust)
        proof.records.filter { it.id != proof.recovery.id }.forEach(evidence::offer)
        val common = requireNotNull(evidence.verifiedFork()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_SIGNATURE" }.common
        val rows = recovery.parents.map { requireNotNull(evidence.record(it)) }
        val merged = graph.verifiedCopy()
        proof.records.forEach(merged::offer)
        require(merged.contains(proof.recovery.reference())) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_SIGNATURE" }
        val names = if (evidence.contains(access.trust.checkpoint)) access.recoveryNames(evidence, rows) else emptyMap()
        return ImportReview(bytes, tips, LanSyncMembershipRecoveryImportReview(proof.binding, common.sequence,
            heads(rows), recovery.primary.id.value, members(common, rows, requireDevice = false, names = names),
            proof.recovery.members.filterValues { it.active }.keys.toSet(), merged.admittedTips().size != 1))
    }

    fun confirmImport(review: ImportReview, binding: String, requireCurrentReview: () -> Unit): Boolean {
        require(review.projection.binding == binding) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        return custody.commitMembershipRecovery(review.heads, authoring = false, prepare = {
            requireCurrentReview()
            review.bytes
        }, retain = ::retainPackage)
    }

    fun exportBinding(): String? {
        val selected = readExport() ?: return null
        return selected.binding.takeIf { custody.recoveryGraph().state(selected.id) == SyncVersionState.ADMITTED }
    }
    fun export(binding: String): ByteArray {
        val selected = requireNotNull(readExport()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_OUTPUT_UNAVAILABLE" }
        require(selected.binding == binding && exportBinding() == binding) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        val bytes = lanSyncRead(packagePath(selected.id), SyncLedgerRecoveryFileCodec.MAX_BYTES)
        val proof = custody.inspectRetainedRecoveryFile(bytes)
        require(proof.binding == binding && proof.recovery.id == selected.id) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_OUTPUT_UNAVAILABLE" }
        return bytes
    }

    /** Publish the already signed control record locally so later upgraded invitations can start at this checkpoint. */
    fun publishInstalledCheckpoint() {
        val ledger = custody.ledger()
        val current = ledger.current()
        if (current.recovery == null || current.members[access.device]?.active != true) return
        val cipher = custody.key(current.epoch)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        lanSyncSafe(root, true)
        val directory = root.resolve("signed-controls")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
        lanSyncSafe(directory, true)
        val preparations = directory.resolve("preparations")
        if (!Files.exists(preparations, NOFOLLOW_LINKS)) Files.createDirectory(preparations)
        lanSyncSafe(preparations, true)
        val encrypted = directory.resolve("outbox-${current.epoch.value}")
        if (!Files.exists(encrypted, NOFOLLOW_LINKS)) Files.createDirectory(encrypted)
        lanSyncSafe(encrypted, true)
        val outbox = SyncEncryptedOutbox(encrypted, cipher)
        val exact = current.reference().bodyHash
        val salt = access.device
        val operation = SyncStoreId.parse(lanSyncHash("CCB-MEMBERSHIP-RECOVERY-OP/1:$exact:$salt".toByteArray()).take(32))
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT,
            SyncStoreId.parse(lanSyncHash("CCB-MEMBERSHIP-RECOVERY-OBJECT/1:$exact:$salt".toByteArray()).take(32)))
        val envelope = SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = current,
            ledgerHistory = ledger.ancestry().dropLast(1))
        SyncGroupObjectCodec(preparations).enqueue(outbox, operation, key, cipher, envelope)
        outbox.upload(operation, access.replica)
    }

    private fun heads(rows: List<SyncDeviceLedgerRecord>) = rows.sortedBy { it.id.value }.map {
        LanSyncMembershipRecoveryHead(it.id.value, it.bodyHash(), it.sequence)
    }

    /** A competing recovery can be retained without activating its keys. Only the unlocked root can review those capsules. */
    private fun retainedKeys(graph: SyncDeviceLedger, session: SyncManagementSession): Map<SyncStoreId, SyncStreamingCipher> {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return emptyMap()
        lanSyncSafe(root, true)
        val keys = linkedMapOf<SyncStoreId, SyncStreamingCipher>()
        session.withRecipient { manager ->
            Files.newDirectoryStream(root, "package-*.ccbgroupfix").use { paths ->
                for (path in paths) {
                    val name = path.fileName.toString()
                    val textId = name.removePrefix("package-").removeSuffix(".ccbgroupfix")
                    require(textId.matches(Regex("[a-f0-9]{32}"))) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PACKAGE_CHANGED" }
                    val id = SyncStoreId.parse(textId)
                    if (graph.state(id) != SyncVersionState.ADMITTED) continue
                    val bytes = lanSyncRead(path, SyncLedgerRecoveryFileCodec.MAX_BYTES)
                    val proof = SyncLedgerRecoveryFileCodec.read(bytes.inputStream(), graph.trustAnchor.copy(checkpoint = graph.trustAnchor.genesis), manager)
                    require(proof.recovery.id == id && graph.contains(proof.recovery.reference())) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PACKAGE_CHANGED" }
                    proof.keys.forEach { admitted ->
                        val old = keys.putIfAbsent(admitted.cipher.epoch, admitted.cipher)
                        require(old == null || old.keyCommitment() == admitted.cipher.keyCommitment()) { "SYNC_GROUP_RECOVERY_EPOCH_BINDING" }
                    }
                }
            }
        }
        return keys
    }
    private fun members(common: SyncDeviceLedgerRecord, rows: List<SyncDeviceLedgerRecord>,
        requireDevice: Boolean, names: Map<String, String> = emptyMap()): List<LanSyncMembershipRecoveryMember> =
        rows.flatMap { it.members.keys }.distinct().sorted().map { id ->
            val authorized = rows.filter { it.members[id]?.active == true }.map { it.id.value }.sorted()
            val revoked = rows.filter { it.members[id]?.active == false }.map { it.id.value }.sorted()
            if (requireDevice && id == access.device) require(authorized.isNotEmpty() && revoked.isEmpty()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_DEVICE_REVOKED" }
            val atCommon = common.members[id]?.active == true
            val authority = when {
                revoked.isNotEmpty() -> LanSyncMembershipRecoveryAuthority.REVOKED
                id == ownerDevice || requireDevice && id == access.device -> LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE
                else -> LanSyncMembershipRecoveryAuthority.CHOICE
            }
            val label = when (id) {
                access.device -> "本机"
                ownerDevice -> "组管理身份"
                else -> names[id] ?: "设备 ${id.take(12)}"
            }
            LanSyncMembershipRecoveryMember(id, label, authority, atCommon, authorized, revoked, atCommon, id == access.device)
        }

    private fun retainPackage(bytes: ByteArray, proof: SyncVerifiedLedgerRecoveryFile) {
        val path = packagePath(proof.recovery.id)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            require(lanSyncRead(path, SyncLedgerRecoveryFileCodec.MAX_BYTES).contentEquals(bytes)) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PACKAGE_CHANGED" }
        } else store(path, bytes)
        val selected = Export(proof.recovery.id, proof.binding)
        if (readExport() != selected) store(exportPointer, protect(lanSyncJson.encodeToString(Export.serializer(), selected).toByteArray()), true)
    }
    private fun readExport(): Export? = if (!Files.exists(exportPointer, NOFOLLOW_LINKS)) null else
        lanSyncJson.decodeFromString(Export.serializer(), lanSyncUtf8(unprotect(lanSyncRead(exportPointer, 65_536))))
    private fun packagePath(id: SyncStoreId) = root.resolve("package-${id.value}.ccbgroupfix")
    private fun intentPath(heads: Set<SyncVersionRef>): Path = root.resolve("intent-" +
        lanSyncHash(heads.map { "${it.id.value}:${it.bodyHash}" }.sorted().joinToString("\n").toByteArray()) + ".protected")
    private fun readIntent(heads: Set<SyncVersionRef>): Intent? {
        val path = intentPath(heads)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val plain = unprotect(lanSyncRead(path, SyncLedgerRecoveryFileCodec.MAX_BYTES))
        try {
            val intent = lanSyncJson.decodeFromString(Intent.serializer(), lanSyncUtf8(plain))
            require(intent.heads == heads && intent.author == access.device && intent.group == access.trust.group &&
                intent.genesis == access.trust.genesis && intent.row.group == intent.group && intent.row.signature == "unsigned" &&
                intent.row.recovery?.parents == heads) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_INTENT_CHANGED" }
            return intent
        } finally { plain.fill(0) }
    }
    private fun writeIntent(intent: Intent) {
        val plain = lanSyncJson.encodeToString(Intent.serializer(), intent).toByteArray()
        try { store(intentPath(intent.heads), protect(plain)) } finally { plain.fill(0) }
    }
    private fun store(path: Path, bytes: ByteArray, replace: Boolean = false) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        lanSyncSafe(root, true)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            lanSyncSafe(path)
            require(replace) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_PACKAGE_CHANGED" }
        }
        PrivateSyncFile.create(root).use { temporary ->
            FileChannel.open(temporary.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (replace) Files.move(temporary.path, path, ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(temporary.path, path, ATOMIC_MOVE)
        }
    }
}
