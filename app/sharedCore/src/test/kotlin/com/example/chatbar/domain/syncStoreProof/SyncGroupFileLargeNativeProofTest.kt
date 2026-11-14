package com.example.chatbar.domain.syncStoreProof

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

/** Opt-in filesystem proof. No HTTP client/route is constructed; only report fields leave stdout. */
class SyncGroupFileLargeNativeProofTest {
    private data class Fingerprint(val length: Long, val sha256: String)
    private fun tree(root: Path): Map<String, Fingerprint> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            val length = Files.size(path)
            root.relativize(path).toString() to Fingerprint(length, LanSyncResourceStore.hashFile(path, length))
        }
    }

    @Test fun actualEncryptedFileAndColdRecoveryPreserveOverOneGiBThroughOriginalRootJoiningAndNativeOwners() = runBlocking {
        org.junit.Assume.assumeTrue("requires explicit synthetic encrypted-file large proof runner",
            System.getenv("CCB_GROUP_FILE_LARGE_PROOF") == "true")
        val root = Files.createTempDirectory("synthetic-group-file-large-").toRealPath()
        val started = System.nanoTime()
        val peakHeap = AtomicLong()
        val initialFree = Files.getFileStore(root).usableSpace
        val minimumFree = AtomicLong(initialFree)
        var sampler: Job? = null
        var administration: LanSyncGroupAdministrationTest.Fixture? = null
        val enrollments = mutableListOf<LanSyncGroupEnrollment>()
        val recoveries = mutableListOf<LanSyncGroupFileRecoveryOwner>()
        try {
            require(initialFree >= 14L * 1024 * 1024 * 1024) { "SYNTHETIC_GROUP_FILE_PROOF_NEEDS_14_GIB_FREE_DISK" }
            sampler = launch(Dispatchers.IO) {
                while (isActive) {
                    val vm = Runtime.getRuntime()
                    peakHeap.accumulateAndGet(vm.totalMemory() - vm.freeMemory(), ::maxOf)
                    minimumFree.accumulateAndGet(Files.getFileStore(root).usableSpace, ::minOf)
                    delay(100)
                }
            }
            fun directory(name: String) = Files.createDirectory(root.resolve(name))
            val admin = LanSyncGroupAdministrationTest.Fixture(directory("original")).also { administration = it }
            admin.seed()
            val sourceState = directory("source-workspace")
            val custodyRoot = directory("source-independent-custody")
            val originalCustody = LanSyncRecoveryVault(custodyRoot)
            val sourceWorkspace = LanSyncWorkspace(admin.data, sourceState, { Path.of(it) }, recoveryVault = originalCustody)
            val sourceReloads = AtomicInteger()
            val enrollment = LanSyncGroupEnrollment(admin.profile, sourceWorkspace, object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) = work()
                override suspend fun reload(sessionIds: Set<String>) { sourceReloads.incrementAndGet() }
            }, admin.identity, { it.reversedArray() }, { it.reversedArray() }, { kind, _, _ ->
                require(kind in setOf("documents", "packages")) { "SYNTHETIC_GROUP_FILE_RESOURCE_KIND" }
            }).also(enrollments::add)
            val sourceAccess = requireNotNull(enrollment.runtimeAccess())
            var cursor: CommitCursor? = null
            do { cursor = sourceAccess.node.discoverLocal(cursor).nextCursor } while (cursor != null)
            val documentLength = 268_500_992L
            val expectedResources = linkedMapOf<String, Long>()
            val documents = (0..3).map { index ->
                val path = admin.data.resolve("documents/full-$index.bin")
                expectedResources[LargeGroupFileSyntheticInputs.document(path, documentLength, index + 1)] = documentLength
                DocumentInfo("document-$index", path.fileName.toString(), path.toString(), "application/octet-stream", addedAt = 1)
            }
            LargeGroupFileSyntheticInputs.entity(admin.data, "character_cards", "card", CharacterCard.serializer(),
                CharacterCard("card", "合成加密文件大资料", customDocuments = documents, createdAt = 1, updatedAt = 1))
            LargeGroupFileSyntheticInputs.entity(admin.data, "chat_sessions", "session", ChatSession.serializer(),
                ChatSession("session", "card", "合成文件会话", modelId = "synthetic-private-model", createdAt = 1, updatedAt = 1))
            val (slot, packageHash) = LargeGroupFileSyntheticInputs.archive(admin.data)
            val archiveLength = requireNotNull(slot.packageRef).byteLength
            expectedResources[packageHash] = archiveLength
            LargeGroupFileSyntheticInputs.entity(admin.data, "save_slots", slot.id, SaveSlot.serializer(), slot)
            val resourceBytes = expectedResources.values.fold(0L, Math::addExact)
            assertEquals(5, expectedResources.size)
            assertTrue(documentLength > 256L * 1024 * 1024)
            assertTrue(archiveLength > 256L * 1024 * 1024)
            assertTrue(resourceBytes > 1024L * 1024 * 1024)
            assertTrue(expectedResources.values.none { it > 1024L * 1024 * 1024 })

            val initial = sourceAccess.node.inspectLocal()
            val expectedRecords = requireNotNull(initial.plan).expected
            val review = initial.review(expectedRecords.keys)
            assertTrue(review.requiredBindings.isEmpty())
            val source = sourceAccess.node.commit(review)
            val localReceipt = sourceAccess.node.upload(source.entry.request.operation, sourceAccess.replica, sourceAccess.localTarget)
            assertEquals(sourceAccess.localTarget, localReceipt.target)
            cursor = null
            do { cursor = sourceAccess.node.discoverLocal(cursor).nextCursor } while (cursor != null)
            assertEquals(expectedRecords.keys, sourceAccess.node.inspectLocal().nativeState.units.keys)
            val originalLedger = sourceAccess.ledger().current()
            val trust = sourceAccess.trust
            val sourceAuthor = SyncAuthorJournal(admin.profile.resolve("node/author"), trust, sourceAccess.certificate,
                { it.reversedArray() }, { it.reversedArray() })
            val sourceSequence = sourceAuthor.state().committedSequence
            assertEquals(1L, sourceSequence)
            assertEquals(listOf(source.entry.request.operation), sourceAccess.node.sourceOperations())
            val sourceCheckpoint = requireNotNull(originalCustody.read(source.entry.request.sourceCheckpoint))
            originalCustody.verify(sourceCheckpoint)
            assertEquals(expectedResources, originalCustody.snapshot(sourceCheckpoint).resources)
            val rawOriginals = linkedMapOf<String, ByteArray>()
            originalCustody.originals(sourceCheckpoint) { original, path -> rawOriginals[original.relative] = Files.readAllBytes(path) }
            assertEquals(3, rawOriginals.size)
            val sourceSessionRelative = "entities/chat_sessions/session.json"
            assertEquals("synthetic-private-model", lanSyncJson.decodeFromString(ChatSession.serializer(),
                rawOriginals.getValue(sourceSessionRelative).toString(Charsets.UTF_8)).modelId)

            val exportRoot = directory("derived-file-export")
            val portable = root.resolve("actual-portable.cbgroup")
            var exported: CiphertextDescriptor? = null
            sourceAccess.prepareEncryptedFile(exportRoot, {}) { prepared ->
                prepared.use {
                    Files.newOutputStream(portable).use { output -> it.writeTo(output) }
                    Files.newInputStream(portable).use { input -> LanSyncGroupEncryptedFilePackage.verifyReadback(input, it.descriptor) }
                    exported = it.descriptor
                }
            }
            val descriptor = requireNotNull(exported)
            assertEquals(Files.size(portable), descriptor.length)
            assertTrue(descriptor.length > 1024L * 1024 * 1024)
            assertEquals(descriptor.sha256.hex, LanSyncResourceStore.hashFile(portable, descriptor.length))
            assertEquals(1L, sourceAuthor.state().committedSequence)
            assertEquals(originalLedger, sourceAccess.ledger().current())
            assertEquals(0, sourceReloads.get())
            Files.walk(exportRoot).use { files ->
                assertTrue(files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.noneMatch { path -> listOf("receipt-", "weak-receipt-", "chunk-receipt-", "cloud-receipt-")
                    .any { path.fileName.toString().startsWith(it) } })
            }
            val rebuildableBytes = tree(exportRoot).values.fold(0L) { sum, item -> Math.addExact(sum, item.length) }
            enrollment.close(); admin.close()
            val sourceBefore = tree(admin.root)
            val offline = root.resolve("original-offline")
            Files.move(admin.root, offline)
            // This is only the proof-owned derived export spool. All input/author/outbox/custody bytes remain.
            check(exportRoot.isAbsolute && exportRoot.normalize() == exportRoot && exportRoot.toRealPath() == exportRoot)
            check(Files.isDirectory(exportRoot, NOFOLLOW_LINKS) && !Files.isSymbolicLink(exportRoot))
            check(exportRoot == root.resolve("derived-file-export") && exportRoot.parent == root)
            check(listOf(admin.root, offline, sourceState, custodyRoot).none { exportRoot.startsWith(it) || it.startsWith(exportRoot) })
            check(exportRoot.toFile().deleteRecursively())
            assertFalse(Files.exists(exportRoot)); assertTrue(Files.isRegularFile(portable))
            // Recovery owns a separate immutable resource store. This warm capture cache is a rebuildable duplicate.
            val warmCache = sourceState.resolve("resources")
            val warmCacheBytes = tree(warmCache).values.fold(0L) { sum, item -> Math.addExact(sum, item.length) }
            check(warmCache.isAbsolute && warmCache.normalize() == warmCache && warmCache.toRealPath() == warmCache)
            check(Files.isDirectory(warmCache, NOFOLLOW_LINKS) && !Files.isSymbolicLink(warmCache))
            check(warmCache == root.resolve("source-workspace/resources") && warmCache.parent == sourceState)
            check(listOf(admin.root, offline, custodyRoot).none { warmCache.startsWith(it) || it.startsWith(warmCache) })
            check(warmCache.toFile().deleteRecursively())
            assertFalse(Files.exists(warmCache))
            originalCustody.verify(sourceCheckpoint)
            assertEquals(expectedResources, originalCustody.snapshot(sourceCheckpoint).resources)

            val recoveryRoot = root.resolve("file-recovery")
            val dailyRoot = root.resolve("receiver-profile")
            val dailyData = directory("receiver-data")
            val dailyState = directory("receiver-workspace")
            val identityPath = root.resolve("receiver-identity.protected")
            val readyCalls = AtomicInteger()
            val ready = CompletableDeferred<LanSyncGroupRuntimeAccess>()
            val inputOpens = AtomicInteger(); val inputCloses = AtomicInteger(); val inputBytes = AtomicLong()
            val platform = object : LanSyncPlatform {
                private var admitted = false
                override suspend fun exclusive(work: suspend () -> Unit) {
                    check(!admitted); admitted = true
                    try { work() } finally { admitted = false }
                }
                override suspend fun reload(sessionIds: Set<String>) { check(admitted) }
            }
            fun identity(): LanSyncTlsIdentity {
                if (!Files.exists(identityPath)) {
                    val bytes = LanSyncTlsIdentity.create().encode()
                    try { Files.write(identityPath, bytes.reversedArray()) } finally { bytes.fill(0) }
                }
                val bytes = Files.readAllBytes(identityPath).reversedArray()
                return try { LanSyncTlsIdentity.decode(bytes) } finally { bytes.fill(0) }
            }
            fun workspace() = LanSyncWorkspace(dailyData, dailyState, { Path.of(it) })
            fun joining(): LanSyncGroupJoining {
                Files.createDirectories(dailyRoot)
                return LanSyncGroupJoining(dailyRoot, workspace(), platform, identity(),
                    { it.reversedArray() }, { it.reversedArray() }, { kind, _, _ ->
                        require(kind in setOf("documents", "packages")) { "SYNTHETIC_GROUP_FILE_RESOURCE_KIND" }
                    })
            }
            fun owner(observe: (String) -> Unit = {}) = LanSyncGroupFileRecoveryOwner(
                root = { Files.createDirectories(recoveryRoot) }, joining = ::joining, deviceName = "synthetic file large receiver",
                beforeSetup = {}, dataWork = { it() }, onReady = { readyCalls.incrementAndGet(); ready.complete(it) },
                clockMillis = { System.nanoTime() / 1_000_000L }, observe = observe).also(recoveries::add)
            fun managementState(): SyncRootLedgerState = SyncManagementSession.open(admin.code,
                Files.readAllBytes(recoveryRoot.resolve("management/management-vault.bin"))).use { session ->
                SyncRootLedgerJournal(recoveryRoot.resolve("management/root-actions"), session.trust, session.ownerCertificate,
                    session::protectJournal, session::unprotectJournal).state()
            }
            suspend fun review(owner: LanSyncGroupFileRecoveryOwner): LanSyncGroupFileRecoveryReview {
                owner.open(); owner.prepare({
                    inputOpens.incrementAndGet()
                    object : FilterInputStream(Files.newInputStream(portable)) {
                        override fun read(): Int = `in`.read().also { if (it >= 0) inputBytes.incrementAndGet() }
                        override fun read(bytes: ByteArray, offset: Int, count: Int): Int =
                            `in`.read(bytes, offset, count).also { if (it > 0) inputBytes.addAndGet(it.toLong()) }
                        override fun close() { inputCloses.incrementAndGet(); super.close() }
                    }
                }, admin.code)
                val result = withTimeout(20 * 60_000L) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
                assertNull(result.error); assertFalse(result.complete)
                return requireNotNull(result.review)
            }

            val interrupted = AtomicBoolean()
            val first = owner { point -> if (point == "FILE_RECOVERY_DATA_PHASE" && interrupted.compareAndSet(false, true))
                throw IOException("SYNTHETIC_FILE_LARGE_APPROVAL_HANDOFF_INTERRUPTED") }
            val firstReview = review(first)
            assertEquals(trust.group.value, firstReview.group)
            assertEquals(0L, managementState().completedSequence)
            val originPath = recoveryRoot.resolve("management/root-actions/origin.protected")
            val managementOrigin = Files.readAllBytes(originPath)
            val beforeMember = requireNotNull(joining().recoveryMember())
            val recipientBefore = tree(dailyRoot.resolve("joining/recipient"))
            first.confirm(firstReview.binding)
            val failed = withTimeout(20 * 60_000L) { first.state.first { !it.busy && it.error != null } }
            assertTrue(interrupted.get()); assertFalse(failed.complete)
            first.closeAndDrain()
            assertEquals(0, readyCalls.get())
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/author/origin.protected")))
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/native/origin.protected")))
            Files.list(dailyData).use { assertEquals(0L, it.count()) }
            val approved = managementState()
            assertEquals(1L, approved.completedSequence); assertNull(approved.pendingOperation)
            assertContentEquals(managementOrigin, Files.readAllBytes(originPath))
            val signedRoot = tree(recoveryRoot.resolve("management/root-actions")).filterKeys { it.contains("signed") || it.contains("origin") }
            assertTrue(signedRoot.keys.any { it.contains("signed") })

            val second = owner()
            val resumed = review(second)
            assertEquals(firstReview.device, resumed.device)
            assertEquals(beforeMember, joining().recoveryMember())
            assertEquals(recipientBefore, tree(dailyRoot.resolve("joining/recipient")))
            assertEquals(approved, managementState())
            assertContentEquals(managementOrigin, Files.readAllBytes(originPath))
            second.confirm(resumed.binding)
            val access = withTimeout(20 * 60_000L) { ready.await() }
            withTimeout(20 * 60_000L) { second.drain() }
            assertTrue(second.state.value.complete); assertNull(second.state.value.error); assertFalse(second.state.value.pending)
            assertEquals(1, readyCalls.get())
            assertEquals(trust.group, access.trust.group); assertEquals(trust.genesis, access.trust.genesis)
            assertEquals(trust.ownerCertificate, access.trust.ownerCertificate)
            assertEquals(beforeMember.first, access.device); assertEquals(beforeMember.second.certificate, access.certificate)
            assertEquals(beforeMember.second, access.ledger().current().members.getValue(access.device))
            assertEquals(originalLedger.sequence + 1, access.ledger().current().sequence)
            assertEquals(approved, managementState())
            assertContentEquals(managementOrigin, Files.readAllBytes(originPath))
            assertEquals(signedRoot, tree(recoveryRoot.resolve("management/root-actions")).filterKeys { it.contains("signed") || it.contains("origin") })
            assertEquals(recipientBefore, tree(dailyRoot.resolve("joining/recipient")))
            val author = SyncAuthorJournal(dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
                { it.reversedArray() }, { it.reversedArray() })
            assertEquals(0L, author.state().committedSequence); assertTrue(access.node.sourceOperations().isEmpty())
            val nativeSequence = access.node.inspectLocal().nativeState.completedSequence
            assertTrue(nativeSequence > 0)
            workspace().capture(access.device).use { capture ->
                assertEquals(expectedRecords, capture.snapshot.hashes()); assertEquals(expectedResources, capture.snapshot.resources)
                assertEquals(expectedResources.keys, capture.blobs.keys)
                capture.blobs.forEach { (hash, path) -> assertEquals(hash, LanSyncResourceStore.hashFile(path, expectedResources.getValue(hash))) }
            }
            val restored = JsonFileStorage(dailyData).loadAll("save_slots", SaveSlot.serializer()).single()
            val restoredArchive = dailyData.resolve("save_slot_packages").resolve(requireNotNull(restored.packageRef).fileName)
            LanSyncSlotPackages.validate(restoredArchive, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), restored).jsonObject)
            assertEquals(packageHash, LanSyncResourceStore.hashFile(restoredArchive, Files.size(restoredArchive)))
            val restoredSession = JsonFileStorage(dailyData).loadAll("chat_sessions", ChatSession.serializer()).single()
            assertNull(restoredSession.modelId, "An empty receiver follows its local default; the source's private model choice is excluded")
            second.closeAndDrain()
            val cold = requireNotNull(joining().runtimeAccess())
            assertEquals(access.device, cold.device); assertEquals(access.certificate, cold.certificate)
            assertEquals(nativeSequence, cold.node.inspectLocal().nativeState.completedSequence)
            workspace().capture(cold.device).use { capture ->
                assertEquals(expectedRecords, capture.snapshot.hashes()); assertEquals(expectedResources, capture.snapshot.resources)
                capture.blobs.forEach { (hash, path) -> assertEquals(hash, LanSyncResourceStore.hashFile(path, expectedResources.getValue(hash))) }
            }
            assertEquals(approved, managementState())
            assertEquals(0L, author.state().committedSequence)
            assertNull(JsonFileStorage(dailyData).loadAll("chat_sessions", ChatSession.serializer()).single().modelId)
            assertEquals(sourceBefore, tree(offline))
            assertEquals("synthetic-private-model", LanSyncJsonFiles.read(offline.resolve("data/$sourceSessionRelative"), ChatSession.serializer())?.modelId)
            originalCustody.verify(sourceCheckpoint)
            assertEquals(expectedResources, originalCustody.snapshot(sourceCheckpoint).resources)
            originalCustody.originals(sourceCheckpoint) { original, path -> assertContentEquals(rawOriginals.getValue(original.relative), Files.readAllBytes(path)) }
            assertEquals(descriptor.length, Files.size(portable))
            assertEquals(descriptor.sha256.hex, LanSyncResourceStore.hashFile(portable, descriptor.length))
            assertFalse(Files.exists(cold.work.resolve("cloud-connections")))
            assertEquals(2, inputOpens.get()); assertEquals(inputOpens.get(), inputCloses.get())
            assertEquals(Math.multiplyExact(2L, descriptor.length), inputBytes.get())

            sampler?.cancelAndJoin(); sampler = null
            println("CCB_GROUP_FILE_LARGE_PROOF " + buildJsonObject {
                put("fullyWrittenResourceBytes", resourceBytes); put("ordinaryDocumentCount", documents.size)
                put("ordinaryDocumentBytesEach", documentLength); put("v8StoredArchiveBytes", archiveLength)
                put("aggregateExceedsOneGiB", resourceBytes > 1024L * 1024 * 1024)
                put("singleResourceExceedsOneGiB", false); put("actualEncryptedFileBytes", descriptor.length)
                put("fileRecoveryInputOpenCount", inputOpens.get()); put("fileRecoveryInputCloseCount", inputCloses.get())
                put("fileRecoveryInputBytesRead", inputBytes.get())
                put("actualAccessFilePreparation", true); put("wholeExternalFileReadbackVerified", true)
                put("privateStageAndAuthenticatedInventoryUsed", true); put("actualFileRecoveryOwner", true)
                put("actualHpkeJoiningAndNativeApply", true); put("rootApprovalColdReopen", true)
                put("rootApprovalSequence", approved.completedSequence); put("sameManagementOriginAndSignedRootRetained", true)
                put("sameDailyTlsAndRecipientRetained", true); put("sourceAuthorSequence", sourceSequence)
                put("receiverAuthorSequence", author.state().committedSequence); put("nativeCompletedSequence", nativeSequence)
                put("sourceRawOriginals", rawOriginals.size); put("sourceCiphertextAndOriginalsUnchanged", true)
                put("sourceRawModelChoiceRetained", true); put("receiverUsesLocalDefaultModel", true)
                put("independentSourceCustodyVerified", true); put("wholeResourceHashesVerified", true); put("v8PackageValidated", true)
                put("httpTransportConstructed", false); put("cloudConnectionCreated", false); put("externalTargetReceiptMinted", false)
                put("rebuildableExportSpoolBytesRemoved", rebuildableBytes)
                put("rebuildableSourceWarmCacheBytesRemoved", warmCacheBytes); put("sourceWarmCacheRemovedAfterExport", true)
                put("preflightFreeDiskBytes", initialFree); put("minimumSampledFreeDiskBytes", minimumFree.get())
                put("sampledPeakDiskConsumptionBytes", (initialFree - minimumFree.get()).coerceAtLeast(0))
                put("sampledPeakHeapBytes", peakHeap.get()); put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            })
        } finally {
            withContext(NonCancellable) {
                try { sampler?.cancelAndJoin() } finally {
                    var failure: Throwable? = null
                    fun failed(next: Throwable) { if (failure == null) failure = next else if (failure !== next) failure?.addSuppressed(next) }
                    recoveries.forEach { try { it.closeAndDrain() } catch (next: Throwable) { failed(next) } }
                    enrollments.forEach { try { it.close() } catch (next: Throwable) { failed(next) } }
                    try { administration?.close() } catch (next: Throwable) { failed(next) }
                    try { check(root.isAbsolute && root.toRealPath() == root); check(root.toFile().deleteRecursively()) }
                    catch (next: Throwable) { failed(next) }
                    failure?.let { throw it }
                }
            }
        }
    }
}
