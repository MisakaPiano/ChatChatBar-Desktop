package com.example.chatbar.domain.syncStoreProof

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

/** Opt-in synthetic HTTP proof. Generated resources, credentials and keys never leave its temp root. */
class SyncGroupCloudLargeNativeProofTest {
    private fun <T> write(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve("entities/$type/$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, serializer, value)
    }

    private fun document(path: Path, length: Long, seed: Int): String {
        Files.createDirectories(path.parent)
        val hash = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteArray(256 * 1024) { ((it + seed) % 251).toByte() }; var copied = 0L
            while (copied < length) {
                val count = minOf(buffer.size.toLong(), length - copied).toInt()
                val bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) channel.write(bytes)
                hash.update(buffer, 0, count); copied += count
            }
            channel.force(true); assertEquals(length, channel.size())
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /** STORED entries prevent compression from reducing the physically written v8 input. */
    private fun archive(root: Path): Pair<SaveSlot, String> {
        val directory = Files.createDirectories(root.resolve("save_slot_packages"))
        val lines = directory.resolve("messages.jsonl"); val crc = CRC32(); val count = 4096
        val content = "x".repeat(65_536)
        Files.newOutputStream(lines, CREATE_NEW).buffered(64 * 1024).use { output -> repeat(count) { index ->
            val row = ChatMessage("archived-$index", "session", MessageRole.USER, content, createdAt = index + 1L, updatedAt = index + 1L)
            val bytes = (lanSyncJson.encodeToString(ChatMessage.serializer(), row) + "\n").toByteArray(Charsets.UTF_8)
            output.write(bytes); crc.update(bytes)
        } }
        val slot = SaveSlot(schemaVersion = 8, id = "large-slot", sessionId = "session", name = "合成云大存档",
            imagePolicy = SaveSlotImagePolicy.NONE, includeAudio = false,
            packageRef = SaveSlotPackageRef("large.cbsave", messageCount = count), createdAt = 1)
        val path = directory.resolve("large.cbsave")
        ZipOutputStream(Files.newOutputStream(path, CREATE_NEW)).use { zip ->
            fun entry(name: String, length: Long, checksum: Long, body: () -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = length; compressedSize = length; this.crc = checksum })
                body(); zip.closeEntry()
            }
            val manifest = lanSyncJson.encodeToString(SaveSlot.serializer(), slot).toByteArray(Charsets.UTF_8)
            entry("manifest.json", manifest.size.toLong(), CRC32().apply { update(manifest) }.value) { zip.write(manifest) }
            entry("messages.jsonl", Files.size(lines), crc.value) { Files.newInputStream(lines).use { it.copyTo(zip, 64 * 1024) } }
            entry("rag.jsonl", 0, 0) {}
        }
        Files.delete(lines)
        val full = slot.copy(packageRef = requireNotNull(slot.packageRef).copy(byteLength = Files.size(path)))
        assertTrue(Files.size(path) > 256L * 1024 * 1024)
        LanSyncSlotPackages.validate(path, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), full).jsonObject)
        return full to LanSyncResourceStore.hashFile(path, Files.size(path))
    }

    @Test fun cloudChunkRuntimeColdResumeAndHpkeNativeActivationPreserveOverOneGiBOfIndependentResources() = runBlocking {
        org.junit.Assume.assumeTrue("requires explicit synthetic cloud large proof runner",
            System.getenv("CCB_GROUP_CLOUD_LARGE_PROOF") == "true")
        val root = Files.createTempDirectory("synthetic-cloud-group-large-").toRealPath()
        val started = System.nanoTime(); val peakHeap = AtomicLong(); val minimumFree = AtomicLong(Files.getFileStore(root).usableSpace)
        var sampler: Job? = null
        var administration: LanSyncGroupAdministrationTest.Fixture? = null
        val enrollments = mutableListOf<LanSyncGroupEnrollment>()
        val runtimes = mutableListOf<LanSyncGroupCloudRuntime>()
        var provider: CloudFileDav? = null; var receiveTransport: WebDavHttpTransport? = null
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).build()
        try {
            require(minimumFree.get() >= 14L * 1024 * 1024 * 1024) { "SYNTHETIC_CLOUD_GROUP_PROOF_NEEDS_14_GIB_FREE_DISK" }
            sampler = launch(Dispatchers.IO) { while (isActive) {
                val vm = Runtime.getRuntime()
                peakHeap.accumulateAndGet(vm.totalMemory() - vm.freeMemory(), ::maxOf)
                minimumFree.accumulateAndGet(Files.getFileStore(root).usableSpace, ::minOf)
                delay(100)
            } }
            fun directory(name: String) = Files.createDirectory(root.resolve(name))
            val admin = LanSyncGroupAdministrationTest.Fixture(directory("founder")).also { administration = it }
            admin.seed()
            val (invitation, peer) = admin.prove()
            val approved = admin.service.prepareMember(peer, invitation)
            val memberReceipt = admin.service.addMember(admin.service.resume(admin.code), approved.acknowledge(approved.request.approvalBinding))
            assertEquals(2, memberReceipt.ledger.members.size)
            assertTrue(memberReceipt.ledger.members.getValue(peer.device).active)
            invitation.close()

            val state = directory("source-workspace")
            val history = LanSyncRecoveryVault(directory("source-independent-custody"))
            fun workspace() = LanSyncWorkspace(admin.data, state, { Path.of(it) }, recoveryVault = history)
            val reloads = AtomicLong()
            fun enrollment(): LanSyncGroupEnrollment = LanSyncGroupEnrollment(admin.profile, workspace(), object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) = work()
                override suspend fun reload(sessionIds: Set<String>) { reloads.incrementAndGet() }
            }, admin.identity, { it.reversedArray() }, { it.reversedArray() }, { kind, _, _ ->
                require(kind in setOf("documents", "packages")) { "SYNTHETIC_CLOUD_GROUP_RESOURCE_KIND" }
            }).also(enrollments::add)
            val firstEnrollment = enrollment()
            var access = requireNotNull(firstEnrollment.runtimeAccess())
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor).nextCursor } while (cursor != null)
            val length = 268_500_992L; val expected = linkedMapOf<String, Long>()
            val documents = (0..3).map { index ->
                val path = admin.data.resolve("documents/full-$index.bin")
                expected[document(path, length, index + 1)] = length
                DocumentInfo("document-$index", path.fileName.toString(), path.toString(), "application/octet-stream", addedAt = 1)
            }
            write(admin.data, "character_cards", "card", CharacterCard.serializer(),
                CharacterCard("card", "合成云加密大资料", customDocuments = documents, createdAt = 1, updatedAt = 1))
            write(admin.data, "chat_sessions", "session", ChatSession.serializer(),
                ChatSession("session", "card", "合成云会话", modelId = "synthetic-private-model", createdAt = 1, updatedAt = 1))
            val (slot, packageHash) = archive(admin.data)
            expected[packageHash] = requireNotNull(slot.packageRef).byteLength
            write(admin.data, "save_slots", slot.id, SaveSlot.serializer(), slot)
            val total = expected.values.fold(0L, Math::addExact)
            assertEquals(5, expected.size); assertTrue(total > 1024L * 1024 * 1024)
            val initial = access.node.inspectLocal()
            val expectedHashes = requireNotNull(initial.plan).expected
            val review = initial.review(expectedHashes.keys)
            assertTrue(review.requiredBindings.isEmpty())
            val source = access.node.commit(review)
            val version = source.entry.version
            val local = access.node.upload(source.entry.request.operation, access.replica, access.localTarget)
            assertEquals(access.localTarget, local.target)
            cursor = null
            do { cursor = access.node.discoverLocal(cursor).nextCursor } while (cursor != null)
            val sourceNative = access.node.inspectLocal().nativeState
            assertEquals(expectedHashes.keys, sourceNative.units.keys); assertTrue(sourceNative.completedSequence > 0)
            val custody = requireNotNull(history.read(source.entry.request.sourceCheckpoint))
            assertEquals(expected, history.snapshot(custody).resources); history.verify(custody)
            val originalEntities = linkedMapOf<String, ByteArray>()
            history.originals(custody) { original, path -> originalEntities[original.relative] = Files.readAllBytes(path) }
            assertEquals(3, originalEntities.size)
            val originPath = admin.profile.resolve("node/author/origin.protected")
            val authorOrigin = Files.readAllBytes(originPath)
            fun author() = SyncAuthorJournal(admin.profile.resolve("node/author"), access.trust, access.certificate,
                { it.reversedArray() }, { it.reversedArray() })
            assertEquals(1L, author().state().committedSequence)

            val username = "synthetic user"; val password = "synthetic password"
            val dav = CloudFileDav(directory("provider"), Credentials.basic(username, password, Charsets.UTF_8)).also { provider = it }
            val catalogRoot = directory("catalog")
            fun catalog() = LanSyncGroupCloudRoutes(catalogRoot, access.trust, access.device,
                { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            val secret = password.toByteArray(Charsets.UTF_8)
            val route = try { catalog().add(LanSyncGroupCloudDraft("synthetic disk DAV", dav.endpoint, username, secret, enabled = true)) }
                finally { secret.fill(0) }
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
            val targetRoot = access.work.resolve("cloud-runtime/${route.value}/target")
            var reports = emptyMap<SyncStoreId, LanSyncGroupCloudRouteProgress>()
            fun runtime() = LanSyncGroupCloudRuntime(access, { true }, { error("SYNTHETIC_OUTGOING_ONLY_MUST_NOT_WAKE_NATIVE") },
                { reports = it }, catalog(), client, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(runtimes::add)
            val firstRuntime = runtime()
            val interruptThreshold = 64L * 1024 * 1024 + 123
            dav.interruptNextPutAfter(interruptThreshold)
            assertFalse(firstRuntime.cycle(recoverNative = false, outgoingOnly = true))
            assertFalse(reports.getValue(route).selectionObserved)
            val interrupted = requireNotNull(dav.interruptedPath.get())
            val partial = dav.bodyPath(interrupted); val partialLength = Files.size(partial)
            assertTrue(dav.putBytes.get() >= interruptThreshold)
            assertTrue(partialLength > 0 && partialLength < dav.interruptedExpectedBytes.get())
            val partialHash = LanSyncResourceStore.hashFile(partial, partialLength)
            val completeChunks = dav.completedPaths.filter { "/weak-chunks-v1/" in it }.associateWith { dav.puts.getValue(it).get() }
            assertTrue(completeChunks.isNotEmpty())
            val putBeforeResume = dav.putBytes.get(); val getBeforeResume = dav.getBytes.get()
            assertNull(access.targetPublication(targetRoot, target, recoverNative = false, prepare = false).chunkReceipt())
            firstRuntime.close(); firstRuntime.drain(); firstEnrollment.close()

            // Independent custody and encrypted queues remain. No original attachment or warm cache can supply resume.
            assertTrue(admin.data.resolve("documents").toFile().deleteRecursively())
            assertTrue(admin.data.resolve("save_slot_packages").toFile().deleteRecursively())
            assertTrue(state.resolve("resources").toFile().deleteRecursively())
            assertFalse(Files.exists(admin.data.resolve("documents")))
            assertFalse(Files.exists(admin.data.resolve("save_slot_packages")))
            access = requireNotNull(enrollment().runtimeAccess())
            assertFalse(Files.exists(state.resolve("resources")))
            assertTrue(requireNotNull(access.node.reconcileForPrefetch().history).contains(version.reference()))
            assertEquals(local, access.node.upload(source.entry.request.operation, access.replica, access.localTarget, recoverNative = false))
            val resumedRuntime = runtime()
            assertTrue(resumedRuntime.cycle(recoverNative = false, outgoingOnly = true))
            val observed = requireNotNull(reports.getValue(route).lastObservedAt)
            assertTrue(reports.getValue(route).selectionObserved)
            val receipt = requireNotNull(access.targetPublication(targetRoot, target, recoverNative = false, prepare = false).chunkReceipt())
            assertEquals(receipt.objects.maxOf { it.readbackObservation.observedAtMillis }, observed)
            assertTrue(receipt.objects.any { it.objectRequest.descriptor.length > length })
            completeChunks.forEach { (path, count) -> assertEquals(count, dav.puts.getValue(path).get(), "completed chunk must not be PUT again") }
            assertEquals(1L, dav.puts.getValue(interrupted).get())
            assertEquals(partialLength, Files.size(partial)); assertEquals(partialHash, LanSyncResourceStore.hashFile(partial, partialLength))
            val partialRequestPrefix = interrupted.substringBeforeLast('-')
            assertEquals(1, dav.completedPaths.count { it != interrupted && it.substringBeforeLast('-') == partialRequestPrefix })
            assertTrue(dav.puts.values.all { it.get() == 1L }, "a physical attempt nonce is never overwritten")
            val resumedPutBytes = dav.putBytes.get() - putBeforeResume; val resumedGetBytes = dav.getBytes.get() - getBeforeResume
            val beforeRepeatPut = dav.putBytes.get(); val beforeRepeatGet = dav.getBytes.get(); val beforeRepeatRequests = dav.requests.get()
            assertTrue(resumedRuntime.cycle(recoverNative = false, outgoingOnly = true))
            val repeatedPutBytes = dav.putBytes.get() - beforeRepeatPut; val repeatedGetBytes = dav.getBytes.get() - beforeRepeatGet
            assertEquals(0L, repeatedPutBytes); assertEquals(0L, repeatedGetBytes)
            assertEquals(beforeRepeatRequests, dav.requests.get()); assertEquals(observed, reports.getValue(route).lastObservedAt)
            assertEquals(receipt, access.targetPublication(targetRoot, target, recoverNative = false, prepare = false).chunkReceipt())
            resumedRuntime.close(); resumedRuntime.drain()

            // Receiver has no source FileStore route or preinstalled epoch keys. All reads use actual HTTP assemblies.
            val transport = WebDavHttpTransport(dav.endpoint.toHttpUrl(), client,
                { Credentials.basic(username, password, Charsets.UTF_8) }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also { receiveTransport = it }
            val parts = WeakWebDavChunkAssembly(transport, directory("receiver-parts"), route)
            val receive = LanSyncGroupChunkCloudReadRoute(parts)
            val receiverRoot = directory("receiver-custody")
            fun receiverCustody() = SyncGroupCustody(receiverRoot, peer.trust.group, admin.joiningKey,
                { it.reversedArray() }, { it.reversedArray() })
            val inbox = SyncGroupInbox(directory("receiver-inbox"), receive, receiverCustody(), admin.joiningKey)
            cursor = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            val bootstrap = inbox.prepareEnrollment(peer.trust)
            assertEquals(SyncGroupBootstrapStage.READY, bootstrap.stage)
            assertTrue(bootstrap.observations.any { it.stage == SyncInboxStage.ENROLLMENT_KEY_VERIFIED })
            assertTrue(bootstrap.observations.any { it.stage == SyncInboxStage.ENROLLMENT_LEDGER_VERIFIED })
            val enrolled = requireNotNull(bootstrap.evidence)
            receiverCustody().enrollWithKeys(enrolled.trust, enrolled.records, enrolled.keys)
            val reconciled = inbox.reconcile(); assertFalse(reconciled.trustBlocked)
            assertEquals(SyncVersionState.ADMITTED, requireNotNull(reconciled.history).state(version.id))
            val receivedVersion = reconciled.authenticatedVersions.single { it.envelope.version?.reference() == version.reference() }
            val receiverData = directory("receiver-data"); val receiverState = directory("receiver-workspace")
            fun receiverWorkspace() = LanSyncWorkspace(receiverData, receiverState, { Path.of(it) })
            val receiverWorkspace = receiverWorkspace()
            val dependencies = LanSyncGroupDependencies(peer.trust.group, receive, receiverCustody(), directory("receiver-dependencies"),
                receiverWorkspace.resourceStore, { kind, _, _ -> require(kind in setOf("documents", "packages")) })
            val evidence = LanSyncGroupRecordEvidence(requireNotNull(reconciled.history), requireNotNull(reconciled.ledgerCheckpoint))
            val inspected = version.changes.keys.associate { unit ->
                val membership = dependencies.membership(reconciled, receivedVersion, unit)
                assertEquals(LanSyncGroupDependencyStage.RECORD_MEMBERSHIP_AVAILABLE, membership.stage, membership.code)
                evidence.offer(requireNotNull(membership.membership))
                val available = dependencies.inspect(reconciled, receivedVersion, unit)
                assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, available.stage, available.code)
                LanSyncGroupMembershipRequest(version.reference(), unit) to available
            }
            val nativePlan = receiverWorkspace.capture(admin.joiningIdentity.fingerprint).use { before ->
                assertTrue(before.snapshot.records.isEmpty())
                LanSyncGroupNativePlanner.prepare(before, reconciled, emptyMap(), inspected, evidence)
            }
            assertEquals(version.changes.keys, nativePlan.advances.keys)
            val receiverControl = directory("receiver-native-control")
            val receiverAuthorRoot = directory("receiver-author")
            fun receiverAuthor() = SyncAuthorJournal(receiverAuthorRoot, peer.trust,
                Base64.getEncoder().encodeToString(admin.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
            receiverAuthor().initializeNewEnrollment()
            fun native() = LanSyncGroupNativeApplyOwner(receiverControl, receiverWorkspace(), peer.trust,
                Base64.getEncoder().encodeToString(admin.joiningIdentity.certificate.encoded), LanSyncProjection(),
                { receiverCustody().ledger() }, { inbox.reconcile() }, { it.reversedArray() }, { it.reversedArray() }, authorJournal = receiverAuthor())
            native().initializeNewEnrollment()
            val nativeReceipt = requireNotNull(native().apply(nativePlan))
            assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, nativeReceipt.status)
            assertEquals(version.changes.keys, nativeReceipt.advancedUnits)
            assertEquals(nativeReceipt, native().apply(nativePlan)); assertEquals(1L, native().state().completedSequence)
            assertEquals(0L, receiverAuthor().state().committedSequence)
            receiverWorkspace().capture(admin.joiningIdentity.fingerprint).use { capture ->
                assertEquals(expectedHashes, capture.snapshot.hashes()); assertEquals(expected, capture.snapshot.resources)
                capture.blobs.forEach { (hash, path) -> assertEquals(hash, LanSyncResourceStore.hashFile(path, expected.getValue(hash))) }
            }
            val restored = JsonFileStorage(receiverData).loadAll("save_slots", SaveSlot.serializer()).single()
            val restoredPackage = receiverData.resolve("save_slot_packages").resolve(requireNotNull(restored.packageRef).fileName)
            LanSyncSlotPackages.validate(restoredPackage, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), restored).jsonObject)
            assertEquals(packageHash, LanSyncResourceStore.hashFile(restoredPackage, Files.size(restoredPackage)))
            assertEquals(1L, author().state().committedSequence)
            assertEquals(source.entry, author().entry(source.entry.request.operation))
            assertEquals(listOf(source.entry.request.operation), access.node.sourceOperations())
            assertContentEquals(authorOrigin, Files.readAllBytes(originPath))
            originalEntities.forEach { (relative, raw) -> assertContentEquals(raw, Files.readAllBytes(admin.data.resolve(relative))) }
            history.verify(custody); assertEquals(expected, history.snapshot(custody).resources)
            assertEquals(0L, reloads.get()); assertNull(receive.latestFailure.value)
            assertTrue(dav.putBytes.get() > total); assertTrue(dav.getBytes.get() > total)
            assertEquals(dav.requests.get(), dav.authorizedRequests.get())
            sampler?.cancelAndJoin(); sampler = null
            println("CCB_GROUP_CLOUD_LARGE_PROOF " + buildJsonObject {
                put("fullyWrittenResourceBytes", total); put("ordinaryDocumentCount", documents.size)
                put("ordinaryDocumentBytesEach", length); put("v8StoredArchiveBytes", requireNotNull(slot.packageRef).byteLength)
                put("aggregateExceedsOneGiB", total > 1024L * 1024 * 1024)
                put("singleResourceExceedsOneGiB", expected.values.any { it > 1024L * 1024 * 1024 })
                put("actualLoopbackHttp", true); put("syntheticBasicAuthenticationVerified", true)
                put("httpRequests", dav.requests.get()); put("serverReceivedPutBodyBytes", dav.putBytes.get())
                put("serverPersistedPutBodyBytes", dav.storedPutBytes.get()); put("serverWrittenGetBodyBytes", dav.getBytes.get())
                put("interruptionThresholdBytes", interruptThreshold); put("retainedPartialAttemptBytes", partialLength)
                put("completedChunkPathsBeforeInterruption", completeChunks.size); put("completedChunkReputCount", 0)
                put("resumeServerReceivedPutBodyBytes", resumedPutBytes); put("resumeServerWrittenGetBodyBytes", resumedGetBytes)
                put("partialAttemptRetainedUnchanged", true); put("coldRuntimeAndEnrollmentReopened", true)
                put("originalResourcesAndWarmCacheRemovedBeforeResume", true); put("independentSourceCustodyVerified", true)
                put("chunkTypedReceiptVerified", true)
                put("repeatedCompletedSendPutBytes", repeatedPutBytes); put("repeatedCompletedSendGetBytes", repeatedGetBytes)
                put("receiverOnlyChunkCloudRoute", true); put("actualHpkeEnrollment", true); put("inboxAndDependenciesVerified", true)
                put("nativeAppliedUnitReceipts", nativeReceipt.advancedUnits.size); put("wholeResourceHashesVerified", true)
                put("v8PackageValidated", true); put("nativeWorkspaceAndProtectedControlReopened", true)
                put("sourceAuthorSequence", author().state().committedSequence); put("receiverAuthorSequence", receiverAuthor().state().committedSequence)
                put("sourceRawOriginals", originalEntities.size); put("nativeRepeatedApplyObservedOnly", true)
                put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                put("sampledPeakHeapBytes", peakHeap.get()); put("minimumSampledFreeDiskBytes", minimumFree.get())
            })
        } finally {
            sampler?.cancelAndJoin()
            try {
                runtimes.forEach { it.close() }; runtimes.forEach { it.drain() }
                receiveTransport?.close(); enrollments.forEach { it.close() }; administration?.close()
            } finally {
                try { provider?.close() } finally {
                    client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
                    check(root.toFile().deleteRecursively())
                }
            }
        }
    }
}
