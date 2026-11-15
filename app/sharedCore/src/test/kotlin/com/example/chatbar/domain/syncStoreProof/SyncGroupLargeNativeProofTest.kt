package com.example.chatbar.domain.syncStoreProof

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

/** Cloud opt-in only. Fully written synthetic bytes; reports only, no generated artifacts. */
class SyncGroupLargeNativeProofTest {
    private fun <T> write(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve("entities/$type/$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, serializer, value)
    }
    private fun document(path: Path, length: Long, seed: Int): String {
        Files.createDirectories(path.parent); val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteArray(256 * 1024) { ((it + seed) % 251).toByte() }; var total = 0L
            while (total < length) {
                val count = minOf(buffer.size.toLong(), length - total).toInt(); val bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) channel.write(bytes)
                digest.update(buffer, 0, count); total += count
            }
            channel.force(true); assertEquals(length, channel.size())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun archive(root: Path): Pair<SaveSlot, String> {
        val directory = Files.createDirectories(root.resolve("save_slot_packages")); val lines = directory.resolve("messages.jsonl")
        val count = 4096; val content = "x".repeat(65_536); val crc = CRC32()
        Files.newOutputStream(lines, StandardOpenOption.CREATE_NEW).buffered(64 * 1024).use { output -> repeat(count) { index ->
            val message = ChatMessage("archived-$index", "session", MessageRole.USER, content, createdAt = index + 1L, updatedAt = index + 1L)
            val bytes = (lanSyncJson.encodeToString(ChatMessage.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)
            output.write(bytes); crc.update(bytes)
        } }
        val slot = SaveSlot(schemaVersion = 8, id = "large-slot", sessionId = "session", name = "合成加密大存档",
            imagePolicy = SaveSlotImagePolicy.NONE, includeAudio = false,
            packageRef = SaveSlotPackageRef("large.cbsave", messageCount = count), createdAt = 1)
        val path = directory.resolve("large.cbsave")
        ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)).use { zip ->
            fun entry(name: String, length: Long, checksum: Long, body: () -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = length; compressedSize = length; this.crc = checksum })
                body(); zip.closeEntry()
            }
            val manifest = lanSyncJson.encodeToString(SaveSlot.serializer(), slot).toByteArray(Charsets.UTF_8)
            entry("manifest.json", manifest.size.toLong(), CRC32().apply { update(manifest) }.value) { zip.write(manifest) }
            entry("messages.jsonl", Files.size(lines), crc.value) { Files.newInputStream(lines).use { it.copyTo(zip, 64 * 1024) } }
            entry("rag.jsonl", 0, 0) {}
        }
        Files.delete(lines); assertTrue(Files.size(path) > 256L * 1024 * 1024)
        val outer = slot.copy(packageRef = slot.packageRef!!.copy(byteLength = Files.size(path)))
        LanSyncSlotPackages.validate(path, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), outer).jsonObject)
        return outer to LanSyncResourceStore.hashFile(path, Files.size(path))
    }

    @Test fun actualEncryptedGroupObjectsAndNativeUnitsReopenOverOneGiBAfterInterruptedUploadAndSourceDeletion() = runBlocking {
        org.junit.Assume.assumeTrue("requires explicit group large proof runner", System.getenv("CCB_GROUP_LARGE_PROOF") == "true")
        val root = Files.createTempDirectory("synthetic-encrypted-group-large-").toRealPath()
        val started = System.nanoTime(); var peakHeap = 0L; var minimumFree = Files.getFileStore(root).usableSpace
        require(minimumFree >= 10L * 1024 * 1024 * 1024) { "SYNTHETIC_GROUP_PROOF_NEEDS_FREE_DISK" }
        val sampler = launch(Dispatchers.IO) { while (isActive) {
            val runtime = Runtime.getRuntime(); peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
            minimumFree = minOf(minimumFree, Files.getFileStore(root).usableSpace); delay(100)
        } }
        fun directory(name: String) = Files.createDirectory(root.resolve(name))
        var network: LanSyncGroupLoopbackTransport? = null
        try {
            val source = directory("source"); val sourceState = directory("source-state"); val work = directory("work")
            val queue = directory("queue"); val publications = directory("publications")
            val owner = LanSyncTlsIdentity.create(); val phone = LanSyncTlsIdentity.create(); val cipher = SyncStreamingCipher.create()
            val recipients = listOf(owner, phone).associate { identity ->
                val custody = SyncDeviceKeyCustody(directory("recipient-${identity.fingerprint}"), identity.fingerprint, { it.reversedArray() }, { it.reversedArray() })
                identity.fingerprint to custody.createIfAbsent()
            }
            val members = listOf(owner, phone).associate { it.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(it.certificate.encoded),
                recipients.getValue(it.fingerprint).publicKey.encoded) }
            val genesis = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(),
                LanSyncScope.schemas.keys, members, emptySet(), "unsigned").let { row ->
                row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
            }
            val trust = SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)
            val authorRoot = directory("author-journal")
            fun authorJournal() = SyncAuthorJournal(authorRoot, trust, members.getValue(owner.fingerprint).certificate, { it.reversedArray() }, { it.reversedArray() })
            authorJournal().initializeNewEnrollment()
            val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
            val outbox = SyncEncryptedOutbox(queue, cipher); val fileBackend = FileCiphertextStore(root.resolve("backend"))
            network = if (System.getenv("CCB_GROUP_LARGE_TLS_PROOF") == "true")
                LanSyncGroupLoopbackTransport(directory("mutual-tls"), owner, ledger, fileBackend) else null
            val backend: CiphertextStore = network?.remote(owner, "owner") ?: fileBackend
            val receiveBackend: CiphertextStore = network?.remote(phone, "phone") ?: fileBackend
            val codec = SyncGroupObjectCodec(work)
            val keyRoot = directory("key-queue")
            fun keyQueue() = SyncKeyDeliveryQueue(keyRoot, trust, owner.fingerprint, { ledger })
            val keyOperations = members.keys.associate { recipient ->
                "${cipher.epoch.value}:$recipient" to SyncStoreId.random().also { keyQueue().prepare(it, cipher, recipient, owner::signSyncGroupPayload) }
            }
            val rootMetadata = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), cipher,
                SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = genesis))
            val rootPublisher = LanSyncGroupPublicationQueue(publications, ledger, outbox, owner.fingerprint, keyQueue = keyQueue())
            val rootPlan = rootPublisher.prepare(rootMetadata, emptyList(), emptyMap(), emptyList(), keyOperations)
            rootPublisher.upload(rootPlan.metadata.operation, backend)
            val receiver = recipients.getValue(phone.fingerprint)
            val keyMetadata = requireNotNull(receiveBackend.metadata(rootPlan.keyDeliveries.getValue("${cipher.epoch.value}:${phone.fingerprint}").key))
            val tentative = SyncKeyDeliveryCodec.read(receiveBackend, keyMetadata, trust, receiver)
            val rootRead = codec.read(receiveBackend, tentative.cipher, rootMetadata.context, rootMetadata.request.descriptor)
            val receivedLedger = SyncDeviceLedger(trust).also { it.offer(requireNotNull(rootRead.envelope.deviceLedger)) }
            val groupCustodyRoot = directory("group-custody")
            fun custody() = SyncGroupCustody(groupCustodyRoot, cipher.group, receiver, { it.reversedArray() }, { it.reversedArray() })
            custody().enroll(trust, listOf(genesis)); custody().rememberKey(tentative.admit(receivedLedger))

            val length = 256L * 1024 * 1024 + 65_536; val expected = linkedMapOf<String, Long>()
            val documents = (0..3).map { index ->
                val path = source.resolve("documents/full-$index.bin"); expected[document(path, length, index + 1)] = length
                DocumentInfo("document-$index", path.fileName.toString(), path.toString(), "application/octet-stream", addedAt = 1)
            }
            write(source, "character_cards", "card", CharacterCard.serializer(), CharacterCard("card", "合成加密大资料", customDocuments = documents, createdAt = 1, updatedAt = 1))
            write(source, "chat_sessions", "session", ChatSession.serializer(), ChatSession("session", "card", "加密大资料", modelId = "synthetic-private-model", createdAt = 1, updatedAt = 1))
            val (slot, packageHash) = archive(source); expected[packageHash] = slot.packageRef!!.byteLength
            write(source, "save_slots", slot.id, SaveSlot.serializer(), slot)
            val total = expected.values.fold(0L, Math::addExact); assertTrue(total > 1024L * 1024 * 1024)
            val sourceHistory = LanSyncRecoveryVault(directory("source-history"))
            fun sourceWorkspace() = LanSyncWorkspace(source, sourceState, { Path.of(it) }, recoveryVault = sourceHistory)
            val workspace = sourceWorkspace()
            val sourceControl = directory("source-native-control"); val sourceWriterRoot = directory("source-writer")
            val emptyHistory = SyncCausalHistory(cipher.group, ledger.admission(emptyList(), emptyList()))
            var sourceView = SyncInboxReconciliation(emptyList(), emptyHistory, emptyList(), false, genesis.reference())
            fun sourceNative(ws: LanSyncWorkspace) = LanSyncGroupNativeApplyOwner(sourceControl, ws, trust,
                members.getValue(owner.fingerprint).certificate, LanSyncProjection(), { ledger }, { sourceView },
                { it.reversedArray() }, { it.reversedArray() }, authorJournal = authorJournal())
            sourceNative(workspace).initializeNewEnrollment()
            val initialPlan = workspace.capture(owner.fingerprint).use { capture ->
                LanSyncGroupNativePlanner.prepare(capture, sourceView, emptyMap(), emptyMap(), LanSyncGroupRecordEvidence(emptyHistory, genesis.reference()))
            }
            val expectedHashes = initialPlan.expected
            val review = LanSyncGroupSourceReview.prepare(initialPlan, sourceView, expectedHashes.keys, emptyMap(),
                LanSyncGroupRecordEvidence(emptyHistory, genesis.reference()))
            assertTrue(review.requiredBindings.isEmpty())
            fun sourceWriter(ws: LanSyncWorkspace) = LanSyncGroupSourceWriter(sourceWriterRoot, ws, owner, authorJournal(), { ledger },
                { epoch -> require(epoch == cipher.epoch); cipher }, { epoch -> require(epoch == cipher.epoch); SyncEncryptedOutbox(queue, cipher) },
                { sourceView }, { sourceNative(ws).state() })
            val sourceOperation = SyncStoreId.random()
            val sourceCommit = sourceWriter(workspace).commit(review.authorize(), sourceOperation)
            val version = sourceCommit.entry.version
            val ownHistory = SyncCausalHistory(cipher.group, ledger.admission(listOf(version), listOf(sourceCommit.metadata)))
            assertEquals(SyncVersionState.ADMITTED, ownHistory.offer(version))
            sourceView = SyncInboxReconciliation(emptyList(), ownHistory, listOf(sourceCommit.metadata), false, genesis.reference())
            val sourceReceipt = sourceNative(workspace).recordSource(sourceCommit)
            assertEquals(LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED, sourceReceipt.status)
            assertEquals(expectedHashes.keys, sourceReceipt.advancedUnits)
            val sourceCheckpoint = requireNotNull(sourceHistory.read(sourceCommit.entry.request.sourceCheckpoint))
            assertEquals(1L, authorJournal().state().committedSequence)
            val plan = sourceCommit.publication
            fun publisher() = LanSyncGroupPublicationQueue(sourceWriterRoot.resolve("publications"), ledger, SyncEncryptedOutbox(queue, cipher), owner.fingerprint,
                currentLedger = { ledger })
            check(source.toFile().deleteRecursively()); check(sourceState.toFile().deleteRecursively())
            val recoveredWorkspace = sourceWorkspace()
            val resumedSource = sourceWriter(recoveredWorkspace).resume(sourceOperation)
            assertEquals(sourceCommit.entry, resumedSource.entry); assertEquals(plan, resumedSource.publication)
            assertEquals(sourceReceipt, sourceNative(recoveredWorkspace).recordSource(resumedSource))
            assertEquals(expectedHashes.keys, sourceNative(recoveredWorkspace).state().units.keys)
            assertFalse(Files.exists(source))
            sourceHistory.verify(sourceCheckpoint)
            assertEquals(expected, sourceHistory.snapshot(sourceCheckpoint).resources)
            var rawOriginals = 0
            sourceHistory.originals(sourceCheckpoint) { original, path ->
                rawOriginals++
                if (original.relative == "entities/chat_sessions/session.json") assertEquals("synthetic-private-model",
                    LanSyncJsonFiles.read(path, ChatSession.serializer())?.modelId)
            }
            assertEquals(3, rawOriginals)
            var inject = true; val attempted = AtomicLong(); val committed = AtomicLong()
            val interrupted = object : CiphertextStore by backend {
                override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                    val fail = request.key.kind == CiphertextObjectKind.BLOB && inject && request.descriptor.length > 256L * 1024 * 1024
                    if (fail) inject = false
                    var copied = 0L
                    val counted = object : FilterInputStream(source) {
                        override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255 }
                        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                            if (fail && copied >= 64L * 1024 * 1024) throw IOException("SYNTHETIC_INTERRUPTED_LARGE_UPLOAD")
                            val result = source.read(bytes, offset, count)
                            if (result > 0) { copied += result; if (request.key.kind == CiphertextObjectKind.BLOB) attempted.addAndGet(result.toLong()) }
                            return result
                        }
                    }
                    val result = backend.put(request, counted)
                    if (request.key.kind == CiphertextObjectKind.BLOB && result.disposition == CiphertextPutDisposition.CREATED) committed.addAndGet(request.descriptor.length)
                    return result
                }
            }
            assertFailsWith<IOException> { sourceWriter(recoveredWorkspace).upload(sourceOperation, interrupted) }
            assertFalse(inject); assertNull(backend.metadata(plan.metadata.key)); assertNull(publisher().receipt(plan.metadata.operation))
            val completed = sourceWriter(recoveredWorkspace).upload(sourceOperation, interrupted)
            assertEquals(plan.blobs.values.sumOf { it.descriptor.length }, committed.get())
            assertTrue(committed.get() > total); assertTrue(attempted.get() > committed.get())
            val firstAttempted = attempted.get(); val networkBeforeRepeat = network?.uploadBytes?.get()
            assertEquals(completed, sourceWriter(recoveredWorkspace).upload(sourceOperation, interrupted)); assertEquals(firstAttempted, attempted.get())
            assertEquals(networkBeforeRepeat, network?.uploadBytes?.get())
            network?.let { assertTrue(it.maximumUploadResume.get() >= 64L * 1024 * 1024) }
            val inbox = SyncGroupInbox(directory("inbox"), receiveBackend, custody(), receiver)
            var cursor: CommitCursor? = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            val reconciled = inbox.reconcile(); assertFalse(reconciled.trustBlocked)
            assertEquals(SyncVersionState.ADMITTED, reconciled.history?.state(version.id))
            val receivedVersion = reconciled.authenticatedVersions.single { it.envelope.version?.reference() == version.reference() }
            val target = directory("target"); val targetState = directory("target-state")
            val targetWorkspace = LanSyncWorkspace(target, targetState, { Path.of(it) })
            val dependencies = LanSyncGroupDependencies(cipher.group, receiveBackend, custody(), work, targetWorkspace.resourceStore, { kind, _, _ ->
                require(kind in setOf("documents", "packages")) { "SYNTHETIC_GROUP_PROOF_RESOURCE_KIND" }
            })
            val evidence = LanSyncGroupRecordEvidence(requireNotNull(reconciled.history), requireNotNull(reconciled.ledgerCheckpoint))
            val dependencyResults = version.changes.keys.associate { unit ->
                val membership = dependencies.membership(reconciled, receivedVersion, unit)
                assertEquals(LanSyncGroupDependencyStage.RECORD_MEMBERSHIP_AVAILABLE, membership.stage, membership.code)
                evidence.offer(requireNotNull(membership.membership))
                val result = dependencies.inspect(reconciled, receivedVersion, unit)
                assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, result.stage, result.code)
                LanSyncGroupMembershipRequest(version.reference(), unit) to result
            }
            val available = dependencyResults.values.map { requireNotNull(it.available) }
            val decoded = available.map { it.decoded }
            val nativePlan = targetWorkspace.capture(phone.fingerprint).use { before ->
                LanSyncGroupNativePlanner.prepare(before, reconciled, emptyMap(), dependencyResults, evidence)
            }
            assertEquals(version.changes.keys, nativePlan.advances.keys)
            val control = directory("native-control")
            fun nativeOwner() = LanSyncGroupNativeApplyOwner(control, targetWorkspace, ledger.trustAnchor,
                Base64.getEncoder().encodeToString(phone.certificate.encoded), LanSyncProjection(), { custody().ledger() }, { inbox.reconcile() },
                { it.reversedArray() }, { it.reversedArray() })
            nativeOwner().initializeNewEnrollment()
            val nativeReceipt = requireNotNull(nativeOwner().apply(nativePlan))
            assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, nativeReceipt.status)
            assertEquals(version.changes.keys, nativeReceipt.advancedUnits)
            assertEquals(nativeReceipt, nativeOwner().apply(nativePlan))
            assertEquals(1L, nativeOwner().state().completedSequence)
            assertEquals(version.changes.keys, nativeOwner().state().units.keys)
            LanSyncWorkspace(target, targetState, { Path.of(it) }).capture(phone.fingerprint).use { reopened ->
                assertEquals(expectedHashes, reopened.snapshot.hashes()); assertEquals(expected, reopened.snapshot.resources)
                reopened.blobs.forEach { (hash, path) -> assertEquals(hash, LanSyncResourceStore.hashFile(path, expected.getValue(hash))) }
            }
            val restored = JsonFileStorage(target).loadAll("save_slots", SaveSlot.serializer()).single()
            val packageFile = target.resolve("save_slot_packages").resolve(restored.packageRef!!.fileName)
            LanSyncSlotPackages.validate(packageFile, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), restored).jsonObject)
            assertEquals(packageHash, LanSyncResourceStore.hashFile(packageFile, Files.size(packageFile)))
            network?.let { assertTrue(it.uploadBytes.get() > total); assertTrue(it.downloadBytes.get() > total) }
            sampler.cancelAndJoin()
            println("CCB_GROUP_LARGE_PROOF " + buildJsonObject {
                put("fullyWrittenResourceBytes", total); put("largestOrdinaryResourceBytes", length); put("v8Bytes", slot.packageRef!!.byteLength)
                put("committedBlobCiphertextBytes", committed.get()); put("attemptedBlobCiphertextBytes", attempted.get()); put("repeatedPublicationBlobBytes", 0)
                put("sourceAndCaptureDeletedBeforeUpload", true); put("interruptedUploadRetried", true); put("nativeUnits", decoded.size)
                put("rootRecipientCount", members.size); put("wholeResourceHashesVerified", true); put("sharedNativeWorkspaceReopened", true)
                put("inboxReconciled", true); put("perUnitDependenciesVerified", true)
                put("perUnitNativePlanVerified", true); put("protectedNativeControlReopened", true)
                put("nativeAppliedUnitReceipts", nativeReceipt.advancedUnits.size); put("nativeRepeatedApplyObservedOnly", true)
                put("independentSourceCheckpointVerified", true); put("sourceRawOriginals", rawOriginals)
                put("nativeSourceWriterVerified", true); put("nativeSourceReceiptReopened", true)
                put("sourceWorkspaceCacheRebuiltAfterDeletion", true)
                put("actualMutualTlsCiphertextTransport", network != null)
                network?.let { put("tlsUploadedCiphertextChunkBytes", it.uploadBytes.get()); put("tlsDownloadedCiphertextChunkBytes", it.downloadBytes.get())
                    put("tlsLargestVerifiedUploadResume", it.maximumUploadResume.get()); put("tlsRepeatedPublicationChunkBytes", 0) }
                put("approvedAuthorSequence", authorJournal().state().committedSequence)
                put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); put("sampledPeakHeapBytes", peakHeap); put("minimumSampledFreeDiskBytes", minimumFree)
            })
        } finally { sampler.cancelAndJoin(); try { network?.close() } finally { check(root.toFile().deleteRecursively()) } }
    }
}
