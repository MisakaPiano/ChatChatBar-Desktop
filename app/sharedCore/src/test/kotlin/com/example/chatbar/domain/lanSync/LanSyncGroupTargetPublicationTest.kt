package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakFixture
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCandidate
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavChunkAssembly
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import com.example.chatbar.domain.syncStore.webdav.WebDavHttpTransport
import com.example.chatbar.domain.syncStore.webdav.WebDavIoBudget
import com.example.chatbar.domain.syncStore.webdav.WebDavStoreRecord
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavObservationOrigin
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import kotlin.test.*

/** Real enrolled daily/root/AEAD/HPKE owners; targets are independent private file stores only. */
class LanSyncGroupTargetPublicationTest {
    private class Fixture(val root: Path) {
        val admin = LanSyncGroupAdministrationTest.Fixture(root)
        lateinit var access: LanSyncGroupRuntimeAccess
        lateinit var first: LanSyncGroupSourceCommit
        lateinit var origin: ByteArray
        lateinit var raw: ByteArray
        val card get() = admin.data.resolve("entities/character_cards/card.json")
        fun directory(name: String) = Files.createDirectory(root.resolve(name))
        fun author() = SyncAuthorJournal(admin.profile.resolve("node/author"), access.trust,
            Base64.getEncoder().encodeToString(admin.identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        suspend fun scan() {
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor, 1).nextCursor } while (cursor != null)
        }
        suspend fun seed() {
            admin.seed(); access = requireNotNull(admin.service.runtimeAccess()); scan()
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic relay original", createdAt = 1, updatedAt = 1))
            raw = Files.readAllBytes(card)
            first = access.node.commit(access.node.inspect().review(setOf("character_cards/card")))
            access.node.upload(first.entry.request.operation, access.replica, access.localTarget); scan()
            origin = Files.readAllBytes(admin.profile.resolve("node/author/origin.protected"))
        }
        suspend fun owner(directory: Path, target: SyncDeliveryTarget, source: CiphertextStore = access.reader,
            validateResource: (String, Path, LanSyncRecord) -> Unit = { _, _, _ -> }): LanSyncGroupTargetPublication {
            val reconciled = access.node.reconcileForPrefetch()
            val custody = admin.custody()
            val proof = LanSyncGroupTargetExport(access.trust, admin.identity.fingerprint, custody.ledger(),
                reconciled.copy(history = reconciled.history?.snapshot()), access.vault, access.vaultDescriptor,
                source, custody::ledger, custody::key, custody, admin.workspace.resourceStore, validateResource)
            return LanSyncGroupTargetPublication(directory, target, proof)
        }
        fun without(key: CiphertextObjectKey): CiphertextStore = object : CiphertextStore by access.reader {
            override fun readTo(keyToRead: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
                if (keyToRead == key) null else access.reader.readTo(keyToRead, destination, expected)
        }
        suspend fun assertUnchanged() {
            assertEquals(1L, author().state().committedSequence)
            assertNull(author().state().pendingApproved)
            assertEquals(first.entry, author().entry(first.entry.request.operation))
            assertEquals(listOf(first.entry.request.operation), access.node.sourceOperations())
            assertContentEquals(origin, Files.readAllBytes(admin.profile.resolve("node/author/origin.protected")))
            assertContentEquals(raw, Files.readAllBytes(card))
            val entry = requireNotNull(admin.workspace.recovery.read(first.entry.request.sourceCheckpoint))
            admin.workspace.recovery.verify(entry)
        }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-target-publication-").toRealPath()
        val f = Fixture(root)
        try { f.seed(); work(f) } finally { f.admin.close(); check(root.toFile().deleteRecursively()) }
    }
    private fun target(kind: SyncDeliveryKind = SyncDeliveryKind.CLOUD_STORE) = SyncDeliveryTarget(kind, SyncStoreId.random())
    private fun bytes(store: CiphertextStore, item: LanSyncGroupTargetObject): ByteArray = ByteArrayOutputStream().use { output ->
        assertEquals(CiphertextObjectMetadata(item.key, item.descriptor), store.readTo(item.key, output, item.descriptor)); output.toByteArray()
    }
    private fun noReceipt(directory: Path) = Files.newDirectoryStream(directory, "receipt-*.json").use { assertFalse(it.iterator().hasNext()) }
    private fun noWeakReceipt(directory: Path) = Files.newDirectoryStream(directory, "weak-receipt-*.json").use { assertFalse(it.iterator().hasNext()) }
    private fun fileHashes(root: Path): Map<String, String> = Files.walk(root).use { files ->
        files.iterator().asSequence().filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.associate { file ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            root.relativize(file).toString() to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private suspend fun publish(f: Fixture, unit: String): LanSyncGroupSourceCommit {
        val commit = f.access.node.commit(f.access.node.inspect().review(setOf(unit)))
        f.access.node.upload(commit.entry.request.operation, f.access.replica, f.access.localTarget); f.scan()
        return commit
    }
    private suspend fun message(f: Fixture, id: String, order: Long, images: List<String> = emptyList()): LanSyncGroupSourceCommit {
        val session = f.admin.data.resolve("entities/chat_sessions/session.json")
        Files.createDirectories(session.parent)
        if (!Files.exists(session)) LanSyncJsonFiles.write(session, ChatSession.serializer(), ChatSession("session", "card", "synthetic incremental conversation", createdAt = 1, updatedAt = 1))
        val path = f.admin.data.resolve("entities/chat_messages/session_$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, ChatMessage.serializer(), ChatMessage(id, "session", MessageRole.USER, "synthetic message $id",
            images = images, createdAt = order, updatedAt = order, orderKey = order * 1_000_000))
        return publish(f, "conversation/session")
    }
    private fun weakPath(provider: WeakFixture, value: LanSyncGroupWeakTargetObservation) = provider.path(
        WeakWebDavCandidate(value.route, value.key, value.descriptor, value.operation, value.attempt))

    @Test fun coldTargetPreflightImportsRetainedLocalMetadataWithoutWarmNodeStateOrAnExtraAuthorFact() = fixture { f ->
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), CharacterCard("card", "synthetic cold target change", createdAt = 1, updatedAt = 2))
        val second = f.access.node.commit(f.access.node.inspect().review(setOf("character_cards/card")))
        f.access.node.upload(second.entry.request.operation, f.access.replica, f.access.localTarget)
        val cold = requireNotNull(f.admin.service.runtimeAccess())
        assertFalse(requireNotNull(cold.node.reconcileForPrefetch().history).contains(second.entry.version.reference()))
        val origin = Files.readAllBytes(f.admin.profile.resolve("node/author/origin.protected"))
        val raw = Files.readAllBytes(f.card)
        val publication = cold.targetPublication(f.directory("cold-target-without-inspect"), target(), prepare = false)
        val plan = publication.prepare()
        assertEquals(setOf(f.first.entry.version.reference(), second.entry.version.reference()), plan.versions)
        assertTrue(plan.objects.any { it.role == LanSyncGroupTargetObjectRole.VERSION && it.slot == second.entry.version.id.value })
        val destination = FileCiphertextStore(f.directory("cold-target-destination"))
        assertEquals(plan.checkpoint, publication.upload(destination).checkpoint)
        assertNotNull(destination.metadata(second.metadata.context.key))
        assertEquals(2L, f.author().state().committedSequence)
        assertEquals(second.entry, f.author().entry(second.entry.request.operation))
        assertContentEquals(origin, Files.readAllBytes(f.admin.profile.resolve("node/author/origin.protected")))
        assertContentEquals(raw, Files.readAllBytes(f.card))
    }

    @Test fun cloudProbeReusesForeignWholeAndChunkObjectsWithoutPuttingThemAndKeepsRouteBoundHistoricalEvidenceAcrossColdSelections() = fixture { f ->
        val image = f.admin.data.resolve("images/foreign-cloud.png"); Files.createDirectories(image.parent)
        val originalImage = LanSyncPreviewSample.image(); Files.write(image, originalImage)
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), CharacterCard("card", "synthetic relay original",
            avatar = image.toString(), createdAt = 1, updatedAt = 2))
        publish(f, "character_cards/card")
        WeakFixture(f.directory("foreign-cloud-provider")).use { provider -> PartsBackend(provider).use { writerParts ->
            val publisher = f.access.targetPublication(f.directory("foreign-publisher-plan"), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route))
            val publishedPlan = publisher.prepare()
            val foreignOperations = mutableMapOf<CiphertextObjectKey, SyncStoreId>()
            val wholeCandidates = mutableMapOf<CiphertextObjectKey, WeakWebDavCandidate>()
            val assemblies = mutableMapOf<CiphertextObjectKey, WeakWebDavCandidate>()
            val writer = provider.store("foreign-whole-writer")
            for ((index, item) in publishedPlan.objects.withIndex()) {
                val operation = SyncStoreId.random(); foreignOperations[item.key] = operation
                val request = CiphertextPutRequest(item.key, operation, item.descriptor)
                val raw = bytes(f.access.reader, item)
                if (index % 2 == 0) wholeCandidates[item.key] = writer.appendAttempt(request, raw.inputStream()).candidate
                else assemblies[item.key] = writerParts.open().append(request, raw.inputStream()).assembly
            }
            assertTrue(wholeCandidates.isNotEmpty()); assertTrue(assemblies.isNotEmpty())
            val originals = provider.dav.files.mapValues { it.value.copyOf() }; val puts = provider.dav.puts.toList()
            val relayRoute = SyncStoreId.random(); val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, relayRoute)
            val directory = f.directory("foreign-relay-target")
            PartsBackend(provider, relayRoute, "foreign-relay-parts").use { relayParts ->
                val attempts = provider.store("foreign-relay-whole", relayRoute)
                val owner = f.owner(directory, target); val plan = owner.prepare()
                val receipt = owner.uploadCloud(attempts, relayParts.open())
                assertEquals(plan.objects, receipt.objects.map { it.objectRequest }); assertEquals(0, receipt.rejectedCandidates)
                receipt.objects.forEach { row ->
                    val evidence = assertIs<LanSyncGroupCloudTargetEvidence.Existing>(row.evidence)
                    val originalOperation = foreignOperations.getValue(row.objectRequest.key)
                    assertNotEquals(originalOperation, row.objectRequest.operation)
                    when (val readback = evidence.readback) {
                        is LanSyncGroupCloudTargetObservation.Whole -> {
                            val candidate = wholeCandidates.getValue(row.objectRequest.key)
                            assertEquals(relayRoute, readback.observation.route); assertEquals(candidate.operationId, readback.observation.operation)
                            assertEquals(candidate.attempt, readback.observation.attempt)
                            assertEquals(WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name, readback.observation.origin)
                        }
                        is LanSyncGroupCloudTargetObservation.Chunks -> {
                            val candidate = assemblies.getValue(row.objectRequest.key)
                            assertEquals(originalOperation, readback.observation.logicalOperation)
                            assertEquals(relayRoute, readback.observation.manifest.route)
                            assertEquals(candidate.operationId, readback.observation.manifest.manifestOperation)
                            assertEquals(candidate.attempt, readback.observation.manifest.attempt)
                            assertEquals(candidate.descriptor, readback.observation.manifest.manifestDescriptor)
                        }
                    }
                }
                assertEquals(puts, provider.dav.puts.toList(), "Foreign ciphertext needs no new physical BLOB or COMMIT PUT")
                originals.forEach { (path, raw) -> assertContentEquals(raw, provider.dav.files.getValue(path)) }
                assertNull(owner.receipt()); assertNull(owner.weakReceipt()); assertNull(owner.chunkReceipt())
                val requests = provider.server.requestCount
                val cold = f.owner(directory, target); assertEquals(plan, cold.prepare())
                assertEquals(receipt, cold.uploadCloud(attempts, relayParts.open())); assertEquals(requests, provider.server.requestCount)
                val index = Files.walk(directory.resolve("cloud-object-observations")).use { files -> files.filter {
                    it.fileName.toString().endsWith(".obj")
                }.findFirst().orElseThrow() }
                val indexBytes = Files.readAllBytes(index); Files.delete(index)
                assertTrue(cold.recoverObjectObservations()); assertContentEquals(indexBytes, Files.readAllBytes(index))
                assertEquals(receipt, cold.cloudReceipt()); assertEquals(requests, provider.server.requestCount)
                val corrupt = indexBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                Files.write(index, corrupt)
                assertFailsWith<CiphertextStoreException> { cold.uploadCloud(attempts, relayParts.open()) }
                assertContentEquals(corrupt, Files.readAllBytes(index)); assertEquals(requests, provider.server.requestCount)
                Files.write(index, indexBytes)

                // A different local route/account binding must obtain its own complete provider reads.
                val otherRoute = SyncStoreId.random()
                PartsBackend(provider, otherRoute, "another-relay-parts").use { otherParts ->
                    val other = f.owner(f.directory("another-relay-target"), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, otherRoute))
                    other.prepare()
                    val separate = other.uploadCloud(provider.store("another-relay-whole", otherRoute), otherParts.open())
                    assertEquals(otherRoute, separate.target.id); assertTrue(provider.server.requestCount > requests)
                    assertEquals(puts, provider.dav.puts.toList())
                    assertTrue(separate.objects.all { it.evidence is LanSyncGroupCloudTargetEvidence.Existing })
                }
                val oldGets = AtomicLong(); val oldPuts = AtomicLong()
                provider.dav.overrideRequest = { request ->
                    if (request.requestUrl!!.encodedPath in originals) {
                        if (request.method == "GET") oldGets.addAndGet(originals.getValue(request.requestUrl!!.encodedPath).size.toLong())
                        if (request.method == "PUT") oldPuts.addAndGet(request.bodySize)
                    }
                    null
                }
                message(f, "after-foreign-cache", 3)
                val next = f.owner(directory, target); next.prepare()
                val changed = next.uploadCloud(attempts, relayParts.open())
                val oldKeys = plan.objects.map { it.key }.toSet()
                assertEquals(receipt.objects, changed.objects.filter { it.objectRequest.key in oldKeys })
                assertEquals(0L, oldGets.get()); assertEquals(0L, oldPuts.get())
                assertTrue(changed.objects.any { it.evidence is LanSyncGroupCloudTargetEvidence.Appended })
                assertContentEquals(originalImage, Files.readAllBytes(image))
            }
        } }
    }

    @Test fun cloudProbeRetainsBadWholeCandidatesAndPartialNewAttemptsWithoutCompletingAnUnauthenticatedSelection() = fixture { f ->
        WeakFixture(f.directory("bad-existing-cloud-provider")).use { provider -> PartsBackend(provider).use { parts ->
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val owner = f.owner(f.directory("bad-existing-cloud-target"), target); val plan = owner.prepare()
            val item = plan.objects.first(); val raw = bytes(f.access.reader, item)
            val good = provider.store("bad-existing-writer").appendAttempt(CiphertextPutRequest(item.key, SyncStoreId.random(), item.descriptor), raw.inputStream())
            val badPath = provider.path(good.candidate)
            val partial = provider.dav.files.getValue(badPath).let { it.copyOf(it.size - 1) }; provider.dav.files[badPath] = partial
            val alternate = parts.open().append(CiphertextPutRequest(item.key, SyncStoreId.random(), item.descriptor), raw.inputStream())
            val receipt = owner.uploadCloud(provider.store("bad-existing-reader"), parts.open())
            assertTrue(receipt.rejectedCandidates > 0)
            val existing = assertIs<LanSyncGroupCloudTargetEvidence.Existing>(receipt.objects.first().evidence)
            assertEquals(alternate.assembly.attempt, assertIs<LanSyncGroupCloudTargetObservation.Chunks>(existing.readback).observation.manifest.attempt)
            assertContentEquals(partial, provider.dav.files.getValue(badPath)); assertEquals(1, provider.dav.puts.count { it == badPath })
        } }
        WeakFixture(f.directory("partial-new-cloud-provider")).use { provider -> PartsBackend(provider).use { parts ->
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val directory = f.directory("partial-new-cloud-target")
            val owner = f.owner(directory, target); owner.prepare(); provider.dav.partialNextPut = true
            assertFailsWith<CiphertextStoreException> { owner.uploadCloud(provider.store("partial-new-reader"), parts.open()) }
            assertNull(owner.cloudReceipt())
            Files.walk(directory.resolve("cloud-object-observations")).use { files -> assertFalse(files.anyMatch { it.fileName.toString().endsWith(".obj") }) }
            val partial = provider.dav.files.mapValues { it.value.copyOf() }; assertEquals(1, partial.size)
            val cold = f.owner(directory, target); cold.prepare()
            val receipt = cold.uploadCloud(provider.store("partial-new-reader"), parts.open())
            assertEquals(receipt, cold.cloudReceipt())
            partial.forEach { (path, raw) -> assertContentEquals(raw, provider.dav.files.getValue(path)); assertEquals(1, provider.dav.puts.count { it == path }) }
        } }
        f.assertUnchanged()
    }

    @Test fun cloudProbeDoesNotTurnAuthenticationPermissionOrTransientReadFailuresIntoMissingObjectsAndKeepsLegacyTypedReceiptsExact() = fixture { f ->
        for ((status, failure) in listOf(401 to CiphertextStoreFailure.AUTHENTICATION, 403 to CiphertextStoreFailure.PERMISSION,
            429 to CiphertextStoreFailure.TRANSIENT, 503 to CiphertextStoreFailure.TRANSIENT)) {
            WeakFixture(f.directory("cloud-probe-error-$status")).use { provider -> PartsBackend(provider).use { parts ->
                val owner = f.owner(f.directory("cloud-probe-error-target-$status"), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route))
                val item = owner.prepare().objects.first(); val raw = bytes(f.access.reader, item)
                provider.store("error-seed").appendAttempt(CiphertextPutRequest(item.key, SyncStoreId.random(), item.descriptor), raw.inputStream())
                val puts = provider.dav.puts.toList()
                provider.dav.overrideRequest = { request -> if (request.method == "GET") MockResponse().setResponseCode(status) else null }
                assertEquals(failure, assertFailsWith<CiphertextStoreException> { owner.uploadCloud(provider.store("error-reader"), parts.open()) }.failure)
                assertNull(owner.cloudReceipt()); assertEquals(puts, provider.dav.puts.toList())
            } }
        }
        WeakFixture(f.directory("legacy-to-cloud-provider")).use { provider -> PartsBackend(provider).use { parts ->
            val directory = f.directory("legacy-to-cloud-target")
            val owner = f.owner(directory, SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)); owner.prepare()
            val old = owner.upload(provider.store("legacy-writer"))
            val originalFiles = fileHashes(directory); val requests = provider.server.requestCount
            val cloud = owner.uploadCloud(provider.store("legacy-cloud-reader"), parts.open())
            assertEquals(requests, provider.server.requestCount)
            assertEquals(old.objects.map { it.readbackObservation.observedAtMillis }, cloud.objects.map { it.evidence.readback.observedAtMillis })
            assertTrue(cloud.objects.all { it.evidence is LanSyncGroupCloudTargetEvidence.Appended })
            assertEquals(old, owner.weakReceipt()); assertNull(owner.chunkReceipt()); assertNull(owner.receipt())
            val after = fileHashes(directory); originalFiles.forEach { (path, hash) -> assertEquals(hash, after.getValue(path), path) }
        } }
        f.assertUnchanged()
    }

    @Test fun chunkTargetColdResumeHasSeparateAuthenticatedReceiptsAndPreservesOldObservationsAcrossSelections() = fixture { f ->
        WeakFixture(f.directory("chunk-target-provider")).use { provider -> PartsBackend(provider).use { parts ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val directory = f.directory("chunk-target-publication")
            val first = f.access.targetPublication(directory, route); val plan = first.prepare()
            provider.dav.loseNextReply = true
            assertFailsWith<CiphertextStoreException> { first.upload(parts.open()) }
            assertNull(first.chunkReceipt()); assertNull(first.weakReceipt()); assertNull(first.receipt())
            val originalPhysical = provider.dav.files.mapValues { it.value.copyOf() }
            assertEquals(1, originalPhysical.size)
            val cold = f.owner(directory, route); assertEquals(plan, cold.prepare())
            val receipt = cold.upload(parts.open())
            assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
            receipt.objects.forEach { row ->
                assertEquals(row.objectRequest.descriptor, row.readbackObservation.logicalDescriptor)
                assertEquals(row.objectRequest.operation, row.readbackObservation.logicalOperation)
                assertEquals(row.appendObservation.manifest, row.readbackObservation.manifest)
                val manifest = row.readbackObservation.manifest
                val path = "/sync/weak-assemblies-v1/${if (manifest.manifestKey.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${manifest.manifestKey.id.value}/${manifest.manifestDescriptor.sha256.hex}/${manifest.manifestDescriptor.length}-${manifest.manifestOperation.value}-${manifest.attempt.value}.bin"
                val input = provider.dav.files.getValue(path).inputStream()
                assertEquals(manifest.manifestDescriptor, WebDavStoreRecord.readHeader(input, manifest.manifestKey).descriptor)
                WebDavStoreRecord.transfer(input, WebDavStoreRecord.discard, manifest.manifestDescriptor, 8192)
            }
            originalPhysical.forEach { (path, body) ->
                assertContentEquals(body, provider.dav.files.getValue(path)); assertEquals(1, provider.dav.puts.count { it == path })
            }
            assertNull(cold.weakReceipt()); assertNull(cold.receipt())
            val before = provider.server.requestCount
            assertEquals(receipt, cold.upload(parts.open())); assertEquals(before, provider.server.requestCount)
            // Whole-object and chunk receipts are separate even on the same route and logical plan.
            val whole = cold.upload(provider.store("whole-writer"))
            assertEquals(plan.objects, whole.objects.map { it.objectRequest }); assertEquals(receipt, cold.chunkReceipt())
            val priorObjects = provider.dav.files.mapValues { it.value.copyOf() }
            val oldGets = AtomicLong(); val oldPuts = AtomicLong()
            provider.dav.overrideRequest = { incoming ->
                if (incoming.requestUrl!!.encodedPath in priorObjects) {
                    if (incoming.method == "GET") oldGets.addAndGet(priorObjects.getValue(incoming.requestUrl!!.encodedPath).size.toLong())
                    if (incoming.method == "PUT") oldPuts.addAndGet(incoming.bodySize)
                }
                null
            }
            message(f, "chunk-new", 2)
            val next = f.access.targetPublication(directory, route); val changed = next.prepare()
            val nextReceipt = next.upload(parts.open())
            val oldKeys = plan.objects.map { it.key }.toSet()
            assertTrue(changed.versions.size > plan.versions.size)
            assertEquals(receipt.objects, nextReceipt.objects.filter { it.objectRequest.key in oldKeys })
            assertEquals(0L, oldGets.get()); assertEquals(0L, oldPuts.get())
            val record = Files.walk(directory.resolve("chunk-object-observations")).use { stream -> stream.filter {
                it.fileName.toString().endsWith(".obj")
            }.findFirst().orElseThrow() }
            val recordBytes = Files.readAllBytes(record); Files.delete(record)
            val requests = provider.server.requestCount
            assertTrue(next.recoverObjectObservations()); assertContentEquals(recordBytes, Files.readAllBytes(record))
            assertEquals(nextReceipt, next.chunkReceipt()); assertEquals(requests, provider.server.requestCount)
        } }
    }

    @Test fun chunkPrimitiveObservationCannotCompleteTargetWhenItsNextAssemblyReadbackIsWrong() = fixture { f ->
        WeakFixture(f.directory("chunk-readback-provider")).use { provider -> PartsBackend(provider).use { parts ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val requests = provider.server.requestCount
            val wrong = f.access.targetPublication(f.directory("chunk-wrong-route-publication"), target())
            assertEquals("SYNC_GROUP_TARGET_ROUTE_CHANGED", assertFailsWith<IllegalArgumentException> { wrong.upload(parts.open()) }.message)
            assertEquals(requests, provider.server.requestCount)
            val directory = f.directory("chunk-readback-publication")
            val owner = f.access.targetPublication(directory, route); owner.prepare(); var manifests = 0
            provider.dav.overrideRequest = { incoming ->
                if (incoming.method == "GET" && "/weak-assemblies-v1/" in incoming.requestUrl!!.encodedPath && ++manifests == 2) {
                    val body = provider.dav.files.getValue(incoming.requestUrl!!.encodedPath)
                    MockResponse().setResponseCode(200).setBody(Buffer().write(body.copyOf(body.size - 1)))
                } else null
            }
            assertFailsWith<CiphertextStoreException> { owner.upload(parts.open()) }
            assertEquals(2, manifests); assertNull(owner.chunkReceipt()); assertNull(owner.weakReceipt()); assertNull(owner.receipt())
            Files.walk(directory.resolve("chunk-object-observations")).use { stream -> assertFalse(stream.anyMatch {
                it.fileName.toString().endsWith(".obj")
            }) }
            provider.dav.overrideRequest = null
            assertEquals(route, owner.upload(parts.open()).target)
            f.assertUnchanged()
        } }
    }

    @Test fun sharedTwoMiBImageHasOneSourceCiphertextStageAcrossVersionsWhileNativeRecordsRemainChecked() = fixture { f ->
        val image = f.admin.data.resolve("images/shared-large.png"); Files.createDirectories(image.parent)
        val png = largePng(); Files.write(image, png)
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), CharacterCard("card", "synthetic relay original",
            avatar = image.toString(), createdAt = 1, updatedAt = 2))
        publish(f, "character_cards/card")
        val first = message(f, "large-first", 3, listOf(image.toString()))
        message(f, "large-second", 4); val third = message(f, "large-third", 5)
        val blob = first.publication.blobs.getValue(lanSyncHash(png))
        assertEquals(blob, third.publication.blobs.getValue(lanSyncHash(png)))
        val directory = f.directory("shared-stage-publication")
        var imageReads = 0; var peakLargeStages = 0; var nativeImages = 0
        fun countLargeStages() {
            val work = directory.resolve("preparations")
            if (Files.exists(work)) Files.list(work).use { stream ->
                peakLargeStages = maxOf(peakLargeStages, stream.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    Files.size(it) == blob.descriptor.length }.count().toInt())
            }
        }
        val counted = object : CiphertextStore by f.access.reader {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                countLargeStages(); if (key == blob.key) imageReads++
                return f.access.reader.readTo(key, destination, expected).also { countLargeStages() }
            }
        }
        val owner = f.owner(directory, target(), counted) { kind, _, _ -> if (kind == "images") nativeImages++ }
        val plan = owner.prepare()
        assertTrue(plan.versions.size >= 5)
        assertEquals(1, imageReads, "Shared original ciphertext must be staged once, rather than once per historical version")
        assertEquals(1, peakLargeStages)
        assertTrue(nativeImages >= 4, "Per-version native resource validation remains separate from raw crypto memoization")
        Files.newDirectoryStream(directory.resolve("preparations")).use { assertFalse(it.iterator().hasNext()) }
        assertContentEquals(png, Files.readAllBytes(image))
    }

    private class PartsBackend(private val provider: WeakFixture, private val route: SyncStoreId = provider.route,
        private val name: String = "parts-client") : AutoCloseable {
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
        fun open(): WeakWebDavChunkAssembly {
            val root = provider.root.resolve(name); if (!Files.exists(root)) Files.createDirectory(root)
            val transport = WebDavHttpTransport(provider.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
                { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            return WeakWebDavChunkAssembly(transport, root, route, 64L * 1024,
                WebDavIoBudget(minimumFreeBytes = 0))
        }
        override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun largePng(): ByteArray {
        val png = LanSyncPreviewSample.image(); val type = "caCa".toByteArray(Charsets.US_ASCII)
        require(String(png, png.size - 8, 4, Charsets.US_ASCII) == "IEND")
        val payload = ByteArray(2 * 1024 * 1024) { (it % 211).toByte() }
        val crc = java.util.zip.CRC32().apply { update(type); update(payload) }
        return ByteArrayOutputStream().also { bytes -> java.io.DataOutputStream(bytes).use {
            it.write(png, 0, png.size - 12); it.writeInt(payload.size); it.write(type); it.write(payload); it.writeInt(crc.value.toInt())
            it.write(png, png.size - 12, 12)
        } }.toByteArray()
    }

    @Test fun addingANewMessageUsesZeroPutAndGetBytesForOldCiphertextAndKeepsItsOriginalObservedTime() = fixture { f ->
        val image = f.admin.data.resolve("images/retained-attachment.png"); Files.createDirectories(image.parent)
        val originalImage = LanSyncPreviewSample.image(); Files.write(image, originalImage)
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), CharacterCard("card", "synthetic relay original",
            avatar = image.toString(), createdAt = 1, updatedAt = 2))
        publish(f, "character_cards/card"); val firstMessage = message(f, "first", 3, listOf(image.toString()))
        WeakFixture(f.directory("incremental-provider")).use { provider ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val directory = f.directory("incremental-publication")
            val first = f.access.targetPublication(directory, route); val before = first.prepare()
            val original = first.upload(provider.store("writer"))
            val imageHash = lanSyncHash(originalImage)
            val attachment = firstMessage.metadata.envelope.blobs.getValue(imageHash)
            val originalImageBinding = firstMessage.publication.blobs.getValue(imageHash)
            assertTrue(before.objects.any { it.key == attachment.key })
            val index = directory.resolve("weak-object-observations/objects/${attachment.key.id.value}.obj")
            val indexBytes = Files.readAllBytes(index)
            Files.delete(index) // Durable typed receipt survives an unfinished derived-index publication.
            val oldKeys = before.objects.map { it.key }.toSet()
            val oldPrefixes = oldKeys.map { "/${if (it.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${it.id.value}/" }
            val oldPutBytes = AtomicLong(); val oldGetBytes = AtomicLong()
            provider.dav.overrideRequest = { request ->
                val path = request.requestUrl!!.encodedPath
                if (oldPrefixes.any(path::contains)) {
                    if (request.method == "PUT") oldPutBytes.addAndGet(request.bodySize)
                    if (request.method == "GET") oldGetBytes.addAndGet(provider.dav.files[path]?.size?.toLong() ?: 0)
                }
                null
            }
            val secondMessage = message(f, "second", 4)
            assertNotEquals(firstMessage.entry.request.operation, secondMessage.entry.request.operation)
            assertEquals(firstMessage.entry.version.authorSequence + 1, secondMessage.entry.version.authorSequence)
            assertEquals(originalImageBinding, secondMessage.publication.blobs.getValue(imageHash), "The same image keeps its source op/key/descriptor across author operations")
            assertEquals(attachment, secondMessage.metadata.envelope.blobs.getValue(imageHash))
            val queues = fileHashes(f.admin.profile.resolve("node/queues")); val author = f.author().state()
            val next = f.access.targetPublication(directory, route); val after = next.prepare()
            assertTrue(after.versions.size > before.versions.size)
            before.objects.forEach { old -> assertEquals(old.operation, after.objects.single { it.key == old.key }.operation) }
            val receipt = next.upload(provider.store("writer"))
            assertEquals(0L, oldPutBytes.get()); assertEquals(0L, oldGetBytes.get())
            assertContentEquals(indexBytes, Files.readAllBytes(index))
            assertEquals(original.objects, receipt.objects.filter { it.objectRequest.key in oldKeys },
                "Old append/readback times and locators remain historical, not re-observed")
            assertEquals(queues, fileHashes(f.admin.profile.resolve("node/queues"))); assertEquals(author, f.author().state())
            assertContentEquals(originalImage, Files.readAllBytes(image))
            val unavailable = object : CiphertextStore by f.access.reader {
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = error("SYNTHETIC_SOURCE_UNAVAILABLE")
                override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = error("SYNTHETIC_SOURCE_UNAVAILABLE")
            }
            val cold = f.owner(directory, route, unavailable); val requests = provider.server.requestCount
            Files.delete(index)
            assertTrue(cold.recoverObjectObservations())
            assertContentEquals(indexBytes, Files.readAllBytes(index))
            assertEquals(after, cold.prepare()); assertEquals(receipt, cold.upload(provider.store("writer")))
            assertEquals(requests, provider.server.requestCount)
            WeakFixture(f.directory("incremental-second-provider")).use { other ->
                val secondRoute = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, other.route)
                val independent = f.access.targetPublication(f.directory("incremental-second-publication"), secondRoute)
                val independentPlan = independent.prepare()
                assertNotEquals(after.objects.single { it.key == attachment.key }.operation,
                    independentPlan.objects.single { it.key == attachment.key }.operation)
                assertNull(independent.weakReceipt())
                val separate = independent.upload(other.store("writer"))
                assertEquals(independentPlan.objects.size, other.dav.puts.size)
                assertTrue(separate.objects.all { it.readbackObservation.route == other.route })
            }
        }
    }

    @Test fun strictHistoricalObjectsAlsoKeepStableOperationsWithoutASecondPutOrReadback() = fixture { f ->
        val route = target(); val directory = f.directory("strict-incremental-publication")
        val backend = FileCiphertextStore(f.directory("strict-incremental-provider"))
        val first = f.access.targetPublication(directory, route); val plan = first.prepare(); val original = first.upload(backend)
        message(f, "new", 2)
        val next = f.access.targetPublication(directory, route); val changed = next.prepare()
        val old = plan.objects.map { it.key }.toSet(); val puts = mutableListOf<CiphertextObjectKey>(); val gets = mutableListOf<CiphertextObjectKey>()
        val counted = object : CiphertextStore by backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult { puts += request.key; return backend.put(request, source) }
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                gets += key; return backend.readTo(key, destination, expected)
            }
        }
        val receipt = next.upload(counted)
        assertTrue(puts.isNotEmpty()); assertTrue(gets.isNotEmpty())
        assertTrue(puts.none(old::contains)); assertTrue(gets.none(old::contains))
        assertEquals(original.objects, receipt.objects.filter { it.objectRequest.key in old })
        assertEquals(plan.objects.map { it.operation }, changed.objects.filter { it.key in old }.map { it.operation })
    }

    @Test fun damagedPerObjectObservationAndChangedPlanOperationsOrDescriptorsAreRetainedAndRefusedBeforeHttp() = fixture { f ->
        WeakFixture(f.directory("cache-damage-provider")).use { provider ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route); val directory = f.directory("cache-damage-publication")
            val first = f.access.targetPublication(directory, route); first.prepare(); first.upload(provider.store("writer"))
            message(f, "new", 2)
            val owner = f.access.targetPublication(directory, route); val plan = owner.prepare()
            val path = Files.newDirectoryStream(directory, "plan-*.json").use { paths -> paths.single { file ->
                LanSyncJsonFiles.read(file, LanSyncGroupTargetPlan.serializer())?.versions == plan.versions
            } }
            val original = Files.readAllBytes(path); val requests = provider.server.requestCount
            for (item in listOf(plan.objects.first().copy(operation = SyncStoreId.random()),
                plan.objects.first().copy(descriptor = plan.objects.first().descriptor.copy(sha256 = CiphertextSha256.parse("f".repeat(64)))))) {
                LanSyncJsonFiles.write(path, LanSyncGroupTargetPlan.serializer(), plan.copy(objects = listOf(item) + plan.objects.drop(1)))
                val evidence = Files.readAllBytes(path)
                assertFailsWith<IllegalArgumentException> { owner.prepare() }
                assertContentEquals(evidence, Files.readAllBytes(path))
                Files.write(path, original)
            }
            val file = Files.walk(directory.resolve("weak-object-observations")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".obj") }.findFirst().orElseThrow()
            }
            val corrupt = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; Files.write(file, corrupt)
            assertFailsWith<CiphertextStoreException> { owner.upload(provider.store("writer")) }
            assertEquals(requests, provider.server.requestCount); assertNull(owner.weakReceipt())
            assertContentEquals(corrupt, Files.readAllBytes(file))
        }
    }

    @Test fun completeRootVaultAndAdmittedVersionRelayUsesIndependentTargetsAndOriginalColdRetryReceipts() = fixture { f ->
        val cloud = target(); val directory = f.directory("cloud-route")
        // The actual RuntimeAccess/Node facade supplies the frozen admission and fixed vault.
        val owner = f.access.targetPublication(directory, cloud)
        val plan = owner.prepare()
        assertEquals(setOf(f.first.entry.version.reference()), plan.versions)
        assertEquals(1, plan.objects.count { it.role == LanSyncGroupTargetObjectRole.VAULT })
        assertEquals(2, plan.objects.count { it.role == LanSyncGroupTargetObjectRole.CAPSULE })
        assertEquals(1, plan.objects.count { it.role == LanSyncGroupTargetObjectRole.LEDGER })
        assertNull(owner.receipt())
        val destination = FileCiphertextStore(f.directory("cloud-store"))
        var lost = false
        val unreliable = object : CiphertextStore by destination {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                val result = destination.put(request, source)
                if (!lost && request.key == plan.objects.single { it.role == LanSyncGroupTargetObjectRole.VERSION }.key) {
                    lost = true; throw IOException("SYNTHETIC_TARGET_REPLY_LOST")
                }
                return result
            }
        }
        assertFailsWith<IOException> { owner.upload(unreliable) }
        assertTrue(lost); assertNull(owner.receipt()); noReceipt(directory)
        val unavailable = object : CiphertextStore by f.access.reader {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = error("SYNTHETIC_SOURCE_UNAVAILABLE")
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = error("SYNTHETIC_SOURCE_UNAVAILABLE")
        }
        val reopened = f.owner(directory, cloud, unavailable)
        assertEquals(plan, reopened.prepare(), "A cold retry reauthenticates retained ciphertext without the original source route")
        val receipt = reopened.upload(destination)
        assertEquals(cloud, receipt.target); assertEquals(plan.checkpoint, receipt.checkpoint)
        assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
        assertTrue(receipt.objects.all { it.disposition == CiphertextPutDisposition.ALREADY_PRESENT.name })
        val vault = plan.objects.single { it.role == LanSyncGroupTargetObjectRole.VAULT }
        assertContentEquals(f.access.encryptedManagementBackup().bytes(), bytes(destination, vault))
        assertEquals(receipt, reopened.receipt())
        val noNetwork = object : CiphertextStore by destination {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = error("SYNTHETIC_HISTORICAL_RECEIPT_ONLY")
        }
        assertEquals(receipt, reopened.upload(noNetwork))
        val nearby = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, cloud.id)
        assertEquals("SYNC_GROUP_TARGET_ROUTE_CHANGED", assertFailsWith<IllegalArgumentException> { f.owner(directory, nearby) }.message)
        val second = f.access.targetPublication(f.directory("nearby-route"), nearby)
        assertNull(second.receipt())
        val nearStore = FileCiphertextStore(f.directory("nearby-store"))
        assertEquals(nearby, second.upload(nearStore).target)
        assertEquals(receipt, reopened.receipt())
        for (item in plan.objects) assertContentEquals(bytes(destination, item), bytes(nearStore, item))
        f.assertUnchanged()
    }

    @Test fun missingVaultCapsuleOrRawResourceCannotProduceAPlanOrAnyTargetCompletionEvenWithWarmPlaintextCache() = fixture { f ->
        val complete = f.owner(f.directory("complete-preparation"), target()).prepare()
        val absent = listOf(
            complete.objects.single { it.role == LanSyncGroupTargetObjectRole.VAULT },
            complete.objects.first { it.role == LanSyncGroupTargetObjectRole.CAPSULE },
            complete.objects.first { it.role == LanSyncGroupTargetObjectRole.RESOURCE })
        for ((index, item) in absent.withIndex()) {
            val directory = f.directory("missing-$index")
            val owner = f.owner(directory, target(), f.without(item.key))
            assertFailsWith<IllegalArgumentException> { owner.prepare() }
            assertNull(owner.receipt()); noReceipt(directory)
            Files.newDirectoryStream(directory, "plan-*.json").use { assertFalse(it.iterator().hasNext()) }
        }
        f.assertUnchanged()
    }

    @Test fun theNativeFileExportFacadeUsesOnlyRetainedCiphertextAndCreatesNoExternalDeliveryReceipt() = fixture { f ->
        var remoteCalls = 0
        val forbidden = object : CiphertextStore by f.access.replica {
            override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? { remoteCalls++; error("SYNTHETIC_OFFLINE_EXPORT_NETWORK") }
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                remoteCalls++; error("SYNTHETIC_OFFLINE_EXPORT_NETWORK")
            }
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage { remoteCalls++; error("SYNTHETIC_OFFLINE_EXPORT_NETWORK") }
        }
        f.access.reader.routes("synthetic-export-refusal", listOf(LanSyncGroupReplicaReader.Route("synthetic-export-refusal", forbidden)))
        val directory = f.directory("actual-file-export")
        f.access.prepareEncryptedFile(directory) {}.use { prepared ->
            val bytes = ByteArrayOutputStream().also { prepared.writeTo(it) }.toByteArray()
            LanSyncGroupEncryptedFilePackage.verifyReadback(bytes.inputStream(), prepared.descriptor)
            LanSyncGroupEncryptedFilePackage.stage(bytes.inputStream(), f.directory("actual-file-import")).use { staged ->
                val custody = f.admin.custody(); val current = custody.ledger().current()
                val verified = staged.verifyInventory(custody.key(current.epoch), f.access.trust, current.reference())
                assertEquals(setOf(f.first.entry.version.reference()), verified.plan.versions)
                assertEquals(SyncDeliveryKind.LOCAL_REPLICA, verified.plan.target.kind)
                assertTrue(verified.plan.objects.any { it.role == LanSyncGroupTargetObjectRole.VAULT })
                assertTrue(verified.plan.objects.any { it.role == LanSyncGroupTargetObjectRole.RESOURCE })
                assertTrue(verified.plan.objects.none { it.slot.contains("card.json") })
            }
            Files.newDirectoryStream(directory).use { paths -> assertTrue(paths.none { it.fileName.toString().startsWith("receipt-") }) }
        }
        assertEquals(0, remoteCalls)
        f.access.reader.removeRoutes("synthetic-export-refusal")
        f.assertUnchanged()
    }

    @Test fun anAuthenticatedPreparedSourceExpiresAfterItsBorrowWithoutCreatingATargetReceipt() = fixture { f ->
        val owner = f.owner(f.directory("borrowed-export"), target())
        val plan = owner.prepare(); var late: CiphertextStore? = null
        owner.withPreparedSource { frozen, source ->
            assertEquals(plan, frozen); late = source
            assertEquals(CiphertextObjectMetadata(plan.objects.first().key, plan.objects.first().descriptor), source.metadata(plan.objects.first().key))
        }
        assertFailsWith<IllegalStateException> { requireNotNull(late).metadata(plan.objects.first().key) }
        assertNull(owner.receipt()); assertNull(owner.cloudReceipt())
        f.assertUnchanged()
    }

    @Test fun aTerminalSourceReadFailureCannotBecomeAnIncompleteCandidateOrAPartialPreparationHold() = fixture { f ->
        val content = requireNotNull(f.first.entry.version.changes.getValue("character_cards/card").content)
        val key = f.first.metadata.envelope.blobs.getValue(content.sha256).key
        val lost = IOException("SYNTHETIC_SOURCE_READ_FAILED")
        val failing = object : CiphertextStore by f.access.reader {
            override fun readTo(selected: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                if (selected == key) throw lost
                return f.access.reader.readTo(selected, destination, expected)
            }
        }
        val directory = f.directory("terminal-source")
        val owner = f.owner(directory, target(), failing)
        assertSame(lost, assertFailsWith<LanSyncGroupTargetSourceIoFailure> { owner.prepare() }.original)
        assertNull(owner.receipt()); noReceipt(directory)
        Files.newDirectoryStream(directory, "plan-*.json").use { assertFalse(it.iterator().hasNext()) }
        f.assertUnchanged()
    }

    @Test fun aFullyReadBadShortCiphertextHeaderRemainsACandidateAndCannotBlockTheGoodRoot() = fixture { f ->
        val original = f.owner(f.directory("header-base"), target()).prepare()
        val root = original.objects.single { it.role == LanSyncGroupTargetObjectRole.LEDGER }
        val short = bytes(f.access.reader, root).copyOf(32)
        assertTrue(SyncCiphertextHeader.hasPrefix(short))
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val descriptor = CiphertextDescriptor(short.size.toLong(), CiphertextSha256.parse(lanSyncHash(short)))
        val bad = CiphertextObjectMetadata(key, descriptor)
        f.access.reader.put(CiphertextPutRequest(key, SyncStoreId.random(), descriptor), short.inputStream())
        val badFirst = object : CiphertextStore by f.access.reader {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                val page = f.access.reader.listCommits(cursor, pageSize)
                return if (cursor == null) CommitPage(listOf(bad) + page.entries.filterNot { it == bad }, page.nextCursor) else page
            }
        }
        val selected = f.owner(f.directory("short-header-candidate"), target(), badFirst).prepare()
        assertEquals(root.descriptor, selected.objects.single { it.role == LanSyncGroupTargetObjectRole.LEDGER }.descriptor)
        assertTrue(selected.objects.none { it.key == key })
        f.assertUnchanged()
    }

    @Test fun badSameKeyCandidateArrivingFirstNeverPoisonsTheStrictDestinationAndRemainsRetained() = fixture { f ->
        val original = f.owner(f.directory("original-plan"), target()).prepare().objects.single { it.role == LanSyncGroupTargetObjectRole.VERSION }
        val corrupt = bytes(f.access.reader, original).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val descriptor = CiphertextDescriptor(corrupt.size.toLong(), CiphertextSha256.parse(lanSyncHash(corrupt)))
        val bad = CiphertextObjectMetadata(original.key, descriptor)
        f.access.reader.put(CiphertextPutRequest(bad.key, SyncStoreId.random(), descriptor), corrupt.inputStream())
        var cursor: CommitCursor? = null
        do { cursor = f.access.node.discoverLocal(cursor, 1).nextCursor } while (cursor != null)
        val reconciled = f.access.node.reconcileForPrefetch()
        assertTrue(reconciled.observations.any { it.key == bad.key && it.descriptor == descriptor && it.stage == SyncInboxStage.INVALID_OBJECT })
        val badFirst = object : CiphertextStore by f.access.reader {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                if (cursor == null) return CommitPage(listOf(bad), CommitCursor("synthetic-after-bad"))
                return f.access.reader.listCommits(if (cursor.token == "synthetic-after-bad") null else cursor, pageSize)
            }
        }
        val owner = f.owner(f.directory("candidate-route"), target(), badFirst)
        val plan = owner.prepare()
        assertEquals(original.descriptor, plan.objects.single { it.key == original.key }.descriptor)
        val destination = FileCiphertextStore(f.directory("candidate-destination"))
        val observed = object : CiphertextStore by destination {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                assertFalse(request.key == bad.key && request.descriptor == descriptor)
                return destination.put(request, source)
            }
        }
        assertEquals(plan.target, owner.upload(observed).target)
        assertEquals(original.descriptor, destination.metadata(original.key)?.descriptor)
        ByteArrayOutputStream().use { output ->
            assertEquals(bad, f.access.reader.readTo(bad.key, output, descriptor)); assertContentEquals(corrupt, output.toByteArray())
        }
        assertEquals(original.descriptor, f.access.replica.metadata(original.key)?.descriptor)
        f.assertUnchanged()
    }

    @Test fun aSourceReplyWithTruncatedOrSubstitutedBytesNeverCreatesLocalAuthorityOrTargetReceipt() = fixture { f ->
        val valid = f.owner(f.directory("valid-source-plan"), target()).prepare()
        val resource = valid.objects.first { it.role == LanSyncGroupTargetObjectRole.RESOURCE }
        val original = bytes(f.access.reader, resource)
        val damaged = object : CiphertextStore by f.access.reader {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                if (key != resource.key) return f.access.reader.readTo(key, destination, expected)
                destination.write(original, 0, original.size - 1)
                return CiphertextObjectMetadata(key, resource.descriptor)
            }
        }
        val directory = f.directory("damaged-source-route"); val owner = f.owner(directory, target(), damaged)
        assertFailsWith<IllegalArgumentException> { owner.prepare() }
        assertNull(owner.receipt()); noReceipt(directory)
        Files.newDirectoryStream(directory, "plan-*.json").use { assertFalse(it.iterator().hasNext()) }
        f.assertUnchanged()
    }

    @Test fun revocationDuringTheFirstUploadStreamAbortsBeforeVersionMetadataAndPreservesDailyHistory() = fixture { f ->
        val directory = f.directory("revocation-route"); val owner = f.access.targetPublication(directory, target())
        val plan = owner.prepare(); val custody = f.admin.custody(); val before = custody.ledger().current()
        val next = SyncStreamingCipher.create(f.access.trust.group)
        val unsigned = before.copy(id = SyncStoreId.random(), parent = before.reference(), sequence = before.sequence + 1,
            epoch = next.epoch, keyCommitment = next.keyCommitment(), acceptedHeads = setOf(f.first.entry.version.reference()),
            members = before.members + (f.admin.identity.fingerprint to before.members.getValue(f.admin.identity.fingerprint).copy(active = false)), signature = "unsigned")
        val draft = f.admin.service.resume(f.admin.code)
        val revoked = unsigned.copy(signature = Base64.getEncoder().encodeToString(draft.session.signRootPayload(unsigned.signingBytes())))
        draft.close()
        val destination = FileCiphertextStore(f.directory("revocation-destination")); var changed = false
        val during = object : CiphertextStore by destination {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = destination.put(request, object : FilterInputStream(source) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = `in`.read(bytes, offset, length)
                    if (count > 0 && !changed) { changed = true; custody.update(listOf(revoked)) }
                    return count
                }
            })
        }
        assertFails { owner.upload(during) }
        assertTrue(changed); assertFalse(custody.ledger().current().members.getValue(f.admin.identity.fingerprint).active)
        assertFailsWith<IllegalArgumentException> { owner.receipt() }
        noReceipt(directory)
        assertTrue(plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.VERSION }.all { destination.metadata(it.key) == null })
        f.assertUnchanged()
    }

    @Test fun authenticatedOldEpochContainerWithoutACurrentContainerIsExplicitlyIncompleteRatherThanReauthored() = fixture { f ->
        val custody = f.admin.custody(); val before = custody.ledger().current(); val next = SyncStreamingCipher.create(f.access.trust.group)
        val unsigned = before.copy(id = SyncStoreId.random(), parent = before.reference(), sequence = before.sequence + 1,
            epoch = next.epoch, keyCommitment = next.keyCommitment(), acceptedHeads = setOf(f.first.entry.version.reference()), signature = "unsigned")
        val draft = f.admin.service.resume(f.admin.code)
        val rotated = unsigned.copy(signature = Base64.getEncoder().encodeToString(draft.session.signRootPayload(unsigned.signingBytes())))
        custody.updateWithKeys(listOf(rotated), listOf(SyncAdmittedGroupKey(next, f.admin.identity.fingerprint, rotated.reference())))
        f.admin.service.resumeAdministration(draft); draft.close(); f.scan()
        val directory = f.directory("old-container-route")
        val owner = f.owner(directory, target())
        assertEquals("SYNC_GROUP_TARGET_VERSION_INCOMPLETE", assertFailsWith<IllegalArgumentException> { owner.prepare() }.message)
        assertNull(owner.receipt()); noReceipt(directory)
        assertEquals(f.first.metadata.metadata, f.access.replica.metadata(f.first.metadata.context.key))
        f.assertUnchanged()
    }

    @Test fun relayIncludesEveryActiveHistoricalKeySlotAndTheRemovedMembersExactRootNoticeWithoutNewDailySignatures() = fixture { f ->
        val proof = f.admin.prove()
        val preview = f.admin.service.prepareMember(proof.second, proof.first)
        f.admin.service.addMember(f.admin.service.resume(f.admin.code), preview.acknowledge(preview.request.approvalBinding))
        val removal = f.admin.service.prepareRevocation(proof.second.device)
        f.admin.service.revokeDevice(f.admin.service.resume(f.admin.code), removal.acknowledge(removal.request.approvalBinding))
        f.scan(); f.access.node.inspect()
        // Existing SourceWriter re-keying retains the original daily fact, never another signature.
        f.access.node.upload(f.first.entry.request.operation, f.access.replica, f.access.localTarget); f.scan()
        val owner = f.access.targetPublication(f.directory("notice-route"), target(), recoverNative = false)
        val plan = owner.prepare()
        val ledger = f.access.ledger()
        val expected = ledger.history().map { it.epoch }.distinct().flatMap { epoch ->
            ledger.current().members.filterValues { it.active }.keys.map { "${epoch.value}:$it" }
        }.toSet()
        assertEquals(expected, plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.CAPSULE }.map { it.slot }.toSet())
        assertEquals(setOf(proof.second.device), plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.NOTICE }.map { it.slot }.toSet())
        assertFalse(plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.CAPSULE }.any { it.slot.endsWith(":${proof.second.device}") })
        val destination = FileCiphertextStore(f.directory("notice-destination"))
        val receipt = owner.upload(destination)
        assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
        val notice = plan.objects.single { it.role == LanSyncGroupTargetObjectRole.NOTICE }
        val parsed = SyncLedgerNoticeCodec.decode(bytes(destination, notice))
        parsed.verifyOwner(f.access.trust, notice.key)
        assertEquals(SyncLedgerNotice.revocations(ledger).getValue(proof.second.device), parsed.context.revocation)
        assertEquals(setOf(f.first.entry.version.reference()), plan.versions)
        f.assertUnchanged()
    }

    @Test fun aDailyRelayPublishesAnActuallyAdmittedForeignAuthorsOriginalContainerWithoutUsingEitherSigner() = fixture { f ->
        val proof = f.admin.prove()
        val preview = f.admin.service.prepareMember(proof.second, proof.first)
        f.admin.service.addMember(f.admin.service.resume(f.admin.code), preview.acknowledge(preview.request.approvalBinding))
        val custody = SyncGroupCustody(f.directory("foreign-custody"), f.access.trust.group, f.admin.joiningKey,
            { it.reversedArray() }, { it.reversedArray() })
        val bootstrap = SyncGroupInbox(f.directory("foreign-bootstrap"), f.access.replica, custody, f.admin.joiningKey)
        var cursor: CommitCursor? = null
        do { cursor = bootstrap.discover(cursor, 1).nextCursor } while (cursor != null)
        val inspection = bootstrap.prepareEnrollment(proof.second.trust)
        assertEquals(SyncGroupBootstrapStage.READY, inspection.stage)
        val evidence = requireNotNull(inspection.evidence)
        custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        val data = f.directory("foreign-data"); val runtime = f.directory("foreign-runtime")
        val workspace = LanSyncWorkspace(data, f.directory("foreign-workspace"), { Path.of(it) })
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        val remote = LanSyncGroupNode(runtime, workspace, platform, f.admin.joiningIdentity, evidence.trust, custody, f.admin.joiningKey,
            f.access.replica, { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, { _, _, _ -> })
        remote.initializeNewEnrollment()
        cursor = null
        do { cursor = remote.discover(f.access.replica, cursor, 1).nextCursor } while (cursor != null)
        remote.apply(requireNotNull(remote.inspect().plan))
        val file = data.resolve("entities/character_cards/foreign.json")
        Files.createDirectories(file.parent)
        LanSyncJsonFiles.write(file, CharacterCard.serializer(), CharacterCard("foreign", "synthetic foreign authored original", createdAt = 1, updatedAt = 1))
        val foreignRaw = Files.readAllBytes(file)
        val foreign = remote.commit(remote.inspect().review(setOf("character_cards/foreign")))
        remote.upload(foreign.entry.request.operation, f.access.replica, target(SyncDeliveryKind.NEARBY_DEVICE))
        val remoteOrigin = Files.readAllBytes(runtime.resolve("author/origin.protected"))
        val author = SyncAuthorJournal(runtime.resolve("author"), evidence.trust,
            Base64.getEncoder().encodeToString(f.admin.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        f.scan()
        val publication = f.access.targetPublication(f.directory("foreign-relay-route"), target())
        val plan = publication.prepare()
        assertEquals(setOf(f.first.entry.version.reference(), foreign.entry.version.reference()), plan.versions)
        val selected = plan.objects.single { it.role == LanSyncGroupTargetObjectRole.VERSION && it.slot == foreign.entry.version.id.value }
        assertEquals(foreign.metadata.metadata, CiphertextObjectMetadata(selected.key, selected.descriptor))
        val destination = FileCiphertextStore(f.directory("foreign-relay-destination"))
        assertEquals(plan.target, publication.upload(destination).target)
        assertContentEquals(bytes(f.access.reader, selected), bytes(destination, selected))
        assertEquals(foreign.entry, author.entry(foreign.entry.request.operation))
        assertEquals(1L, author.state().committedSequence)
        assertContentEquals(remoteOrigin, Files.readAllBytes(runtime.resolve("author/origin.protected")))
        assertContentEquals(foreignRaw, Files.readAllBytes(file))
        f.assertUnchanged()
    }

    @Test fun weakAttemptsLostReplyColdRetryAndTwoTargetsHaveIndependentAuthenticatedReceiptsWithoutTouchingOutboxFlags() = fixture { f ->
        WeakFixture(f.directory("weak-first-provider")).use { first ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, first.route)
            val directory = f.directory("weak-first-publication")
            val owner = f.access.targetPublication(directory, route)
            val plan = owner.prepare()
            val queues = fileHashes(f.admin.profile.resolve("node/queues"))
            first.dav.loseNextReply = true
            assertFailsWith<CiphertextStoreException> { owner.upload(first.store("writer")) }
            assertNull(owner.weakReceipt()); assertNull(owner.receipt()); noWeakReceipt(directory)
            val previous = first.dav.files.mapValues { it.value.copyOf() }
            assertEquals(1, previous.size)
            val unavailable = object : CiphertextStore by f.access.reader {
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = error("SYNTHETIC_SOURCE_UNAVAILABLE")
                override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = error("SYNTHETIC_SOURCE_UNAVAILABLE")
            }
            val reopened = f.owner(directory, route, unavailable)
            assertEquals(plan, reopened.prepare())
            val receipt = reopened.upload(first.store("writer"))
            assertEquals(route, receipt.target)
            assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
            assertEquals(WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name, receipt.objects.first().appendObservation.origin)
            assertTrue(receipt.objects.all { it.readbackObservation.origin == WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name &&
                it.appendObservation.attempt == it.readbackObservation.attempt })
            assertEquals(plan.objects.size, first.dav.puts.size)
            assertEquals(first.dav.puts.size, first.dav.puts.toSet().size)
            previous.forEach { (path, bytes) -> assertContentEquals(bytes, first.dav.files.getValue(path)) }
            assertEquals(receipt, reopened.weakReceipt()); assertNull(reopened.receipt())
            val before = first.server.requestCount
            assertEquals(receipt, reopened.upload(first.store("writer")))
            assertEquals(before, first.server.requestCount, "A historical authenticated receipt is not a new remote freshness request")
            WeakFixture(f.directory("weak-second-provider")).use { second ->
                val other = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, second.route)
                val another = f.access.targetPublication(f.directory("weak-second-publication"), other)
                assertNull(another.weakReceipt()); assertTrue(second.dav.files.isEmpty())
                val delivered = another.upload(second.store("writer"))
                assertEquals(other, delivered.target); assertEquals(receipt, reopened.weakReceipt())
                assertEquals(plan.objects.size, second.dav.files.size)
                assertTrue(delivered.objects.all { it.appendObservation.route == second.route && it.readbackObservation.route == second.route })
            }
            assertEquals(queues, fileHashes(f.admin.profile.resolve("node/queues")))
            f.assertUnchanged()
        }
    }

    @Test fun aWeakProviderReadbackObservationAloneCannotCompleteAnAuthenticatedTargetWhenTheNextExactGetIsWrong() = fixture { f ->
        WeakFixture(f.directory("weak-wrong-readback-provider")).use { provider ->
            val route = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route)
            val directory = f.directory("weak-wrong-readback-publication")
            val owner = f.access.targetPublication(directory, route); val plan = owner.prepare()
            val queues = fileHashes(f.admin.profile.resolve("node/queues")); var gets = 0
            provider.dav.overrideRequest = { request ->
                if (request.method == "GET" && ++gets == 2) {
                    val original = provider.dav.files.getValue(request.requestUrl!!.encodedPath)
                    MockResponse().setResponseCode(200).setBody(Buffer().write(original.copyOf(original.size - 1)))
                } else null
            }
            assertFailsWith<CiphertextStoreException> { owner.upload(provider.store("writer")) }
            assertEquals(2, gets)
            assertTrue(provider.dav.files.isNotEmpty(), "The append's first full GET already observed a physical object")
            assertNull(owner.weakReceipt()); assertNull(owner.receipt()); noWeakReceipt(directory)
            provider.dav.overrideRequest = null
            val recovered = f.owner(directory, route)
            assertEquals(plan, recovered.prepare())
            val receipt = recovered.upload(provider.store("writer"))
            assertEquals(plan.objects, receipt.objects.map { it.objectRequest })
            assertEquals(WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name, receipt.objects.first().appendObservation.origin)
            assertEquals(provider.dav.puts.size, provider.dav.puts.toSet().size)
            assertEquals(queues, fileHashes(f.admin.profile.resolve("node/queues")))
            f.assertUnchanged()
        }
    }

    @Test fun aDifferentWeakRouteIsRejectedBeforeAnyHttpRequestOrCompletion() = fixture { f ->
        WeakFixture(f.directory("weak-wrong-route-provider")).use { provider ->
            val directory = f.directory("weak-wrong-route-publication")
            val owner = f.access.targetPublication(directory, target())
            val before = provider.server.requestCount
            assertEquals("SYNC_GROUP_TARGET_ROUTE_CHANGED", assertFailsWith<IllegalArgumentException> { owner.upload(provider.store("writer")) }.message)
            assertEquals(before, provider.server.requestCount)
            assertTrue(provider.dav.files.isEmpty()); assertNull(owner.weakReceipt()); noWeakReceipt(directory)
            f.assertUnchanged()
        }
    }

    @Test fun rootSignedRevocationDuringWeakPutStopsLaterObjectsAndCannotRecordAnAuthenticatedTargetReceipt() = fixture { f ->
        WeakFixture(f.directory("weak-revocation-provider")).use { provider ->
            val directory = f.directory("weak-revocation-publication")
            val owner = f.access.targetPublication(directory, SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, provider.route))
            val plan = owner.prepare(); val custody = f.admin.custody(); val before = custody.ledger().current()
            val next = SyncStreamingCipher.create(f.access.trust.group)
            val unsigned = before.copy(id = SyncStoreId.random(), parent = before.reference(), sequence = before.sequence + 1,
                epoch = next.epoch, keyCommitment = next.keyCommitment(), acceptedHeads = setOf(f.first.entry.version.reference()),
                members = before.members + (f.admin.identity.fingerprint to before.members.getValue(f.admin.identity.fingerprint).copy(active = false)), signature = "unsigned")
            val draft = f.admin.service.resume(f.admin.code)
            val revoked = unsigned.copy(signature = Base64.getEncoder().encodeToString(draft.session.signRootPayload(unsigned.signingBytes())))
            draft.close(); var changed = false
            provider.dav.overrideRequest = { request ->
                if (request.method == "PUT" && !changed) { changed = true; custody.update(listOf(revoked)) }
                null
            }
            assertFails { owner.upload(provider.store("writer")) }
            assertTrue(changed); assertFalse(custody.ledger().current().members.getValue(f.admin.identity.fingerprint).active)
            assertFailsWith<IllegalArgumentException> { owner.weakReceipt() }; noWeakReceipt(directory)
            assertTrue(plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.VERSION }.all { item ->
                provider.dav.files.keys.none { "/commits/${item.key.id.value}/" in it }
            })
            f.assertUnchanged()
        }
    }

    @Test fun anIndependentAuthenticatedWaitingWitnessDoesNotBlockTheExactAlreadyAdmittedSelection() = fixture { f ->
        val proof = f.admin.prove()
        val member = f.admin.service.prepareMember(proof.second, proof.first)
        f.admin.service.addMember(f.admin.service.resume(f.admin.code), member.acknowledge(member.request.approvalBinding))
        val custody = f.admin.custody(); val current = custody.ledger().current(); val cipher = custody.key(current.epoch)
        val missing = SyncVersionRef(SyncStoreId.random(), "a".repeat(64))
        val originalChange = f.first.entry.version.changes.getValue("character_cards/card")
        val unsigned = SyncCausalVersion(f.access.trust.group, SyncStoreId.random(), current.reference(),
            f.admin.joiningIdentity.fingerprint, 1, null, setOf(f.first.entry.version.reference(), missing),
            mapOf("character_cards/card" to originalChange.copy(previous = setOf(f.first.entry.version.reference()))), "unsigned")
        val waiting = unsigned.copy(signature = Base64.getEncoder().encodeToString(f.admin.joiningIdentity.signSyncGroupPayload(unsigned.signingBytes())))
        val queue = SyncEncryptedOutbox(f.directory("waiting-witness-queue"), cipher)
        val codec = SyncGroupObjectCodec(f.directory("waiting-witness-codec"))
        val operation = SyncStoreId.random()
        val prepared = codec.enqueue(queue, operation, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), cipher,
            SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = waiting, blobs = f.first.metadata.envelope.blobs))
        queue.upload(operation, f.access.replica); f.scan()
        val view = f.access.node.reconcileForPrefetch()
        assertTrue(view.observations.any { it.key == prepared.context.key && it.stage == SyncInboxStage.WAITING_FOR_PARENTS })
        assertFalse(requireNotNull(view.history).contains(waiting.reference()))
        val owner = f.access.targetPublication(f.directory("admitted-only-route"), target())
        val plan = owner.prepare()
        assertEquals(setOf(f.first.entry.version.reference()), plan.versions)
        assertFalse(plan.objects.any { it.key == prepared.context.key })
        val destination = FileCiphertextStore(f.directory("admitted-only-destination"))
        assertEquals(plan.target, owner.upload(destination).target)
        assertNull(destination.metadata(prepared.context.key))
        f.assertUnchanged()
    }
}
