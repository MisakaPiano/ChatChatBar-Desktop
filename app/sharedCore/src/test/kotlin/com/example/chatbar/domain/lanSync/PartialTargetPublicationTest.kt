package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

/** Small actual HTTP+AEAD/native-codec fixtures; a partial receipt never becomes a full receipt. */
class PartialTargetPublicationTest {
    private class Fixture(val root: Path) : AutoCloseable {
        val admin = LanSyncGroupAdministrationTest.Fixture(root)
        lateinit var access: LanSyncGroupRuntimeAccess
        fun directory(name: String) = Files.createDirectory(root.resolve(name))
        suspend fun seed() { admin.seed(); access = requireNotNull(admin.service.runtimeAccess()); scan() }
        suspend fun scan() {
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor, 1).nextCursor } while (cursor != null)
        }
        suspend fun card(id: String, image: Boolean = false): LanSyncGroupSourceCommit {
            val avatar = if (image) admin.data.resolve("images/$id.png").also {
                Files.createDirectories(it.parent); Files.write(it, LanSyncPreviewSample.image())
            }.toString() else null
            val path = admin.data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, "synthetic $id", avatar = avatar, createdAt = 1, updatedAt = 1))
            val commit = access.node.commit(access.node.inspect().review(setOf("character_cards/$id")))
            access.node.upload(commit.entry.request.operation, access.replica, access.localTarget); scan(); return commit
        }
        suspend fun owner(directory: Path, target: SyncDeliveryTarget, backend: CiphertextStore = access.reader,
            frozen: SyncInboxReconciliation? = null, live: () -> Unit = {}, initialization: (String) -> Unit = {}): LanSyncGroupPartialTargetPublication {
            val work = directory.resolve("preparations"); if (!Files.exists(work)) Files.createDirectory(work)
            val custody = admin.custody()
            val proof = LanSyncGroupPartialTargetExport(access.trust, admin.identity.fingerprint, custody.ledger(),
                frozen ?: access.node.reconcileForPrefetch(), access.vault, access.vaultDescriptor, backend, { live(); custody.ledger() }, custody::key,
                custody, admin.workspace.resourceStore, { _, _, _ -> }, work)
            return LanSyncGroupPartialTargetPublication(directory, target, proof, initialization)
        }
        override fun close() = admin.close()
    }
    private class Parts(private val provider: WeakFixture, private val route: SyncStoreId = provider.route,
        private val name: String = "parts") : AutoCloseable {
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
        fun open(): WeakWebDavChunkAssembly {
            val path = provider.root.resolve(name); if (!Files.exists(path)) Files.createDirectory(path)
            val transport = WebDavHttpTransport(provider.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
                { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            return WeakWebDavChunkAssembly(transport, path, route, 64L * 1024, WebDavIoBudget(minimumFreeBytes = 0))
        }
        override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-partial-publication-").toRealPath()
        try { Fixture(root).use { it.seed(); work(it) } } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun target(id: SyncStoreId) = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id)
    private fun bytes(source: CiphertextStore, item: LanSyncGroupTargetObject) = ByteArrayOutputStream().use {
        assertEquals(CiphertextObjectMetadata(item.key, item.descriptor), source.readTo(item.key, it, item.descriptor)); it.toByteArray()
    }
    private fun noFullReceipt(root: Path) {
        for (pattern in listOf("receipt-*.json", "weak-receipt-*.json", "chunk-receipt-*.json", "cloud-receipt-*.json"))
            Files.newDirectoryStream(root, pattern).use { assertFalse(it.iterator().hasNext()) }
    }
    private fun observations(receipt: LanSyncGroupPartialCloudReceipt) = receipt.objects.associate { it.objectRequest.key to it.evidence.readback }
    private fun files(root: Path, suffix: String): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(suffix) }.toList()
    }

    @Test fun missingOldMediaStillPublishesTheIndependentPackageAndForeignPhysicalOperationsSurviveColdAndDifferentRouteReads() = fixture { f ->
        val first = f.card("a", true); val second = f.card("b")
        val imagePath = f.admin.data.resolve("images/a.png"); val imageHash = LanSyncResourceStore.hashFile(imagePath, Files.size(imagePath))
        val missing = first.metadata.envelope.blobs.getValue(imageHash).key
        val source = object : CiphertextStore by f.access.reader {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?) =
                if (key == missing) null else f.access.reader.readTo(key, destination, expected)
        }
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("partial-target"); val owner = f.owner(directory, target(provider.route), source); val plan = owner.prepare()
            assertEquals(setOf(first.entry.version.reference(), second.entry.version.reference()), plan.scope.wantedVersionRefs)
            assertEquals(setOf("character_cards/b"), plan.selection.completeUnitPackages.map { it.ref.unit }.toSet())
            val foreign = mutableMapOf<CiphertextObjectKey, WeakWebDavObservation>()
            val writer = provider.store("foreign")
            plan.objects.forEach { item -> foreign[item.key] = writer.appendAttempt(
                CiphertextPutRequest(item.key, SyncStoreId.random(), item.descriptor), bytes(f.access.reader, item).inputStream()) }
            val puts = provider.dav.puts.toList()
            val receipt = owner.uploadCloud(provider.store("relay"), parts.open())
            assertEquals(plan.scope, receipt.scope); assertFalse(receipt.observedSelection.originalScopeComplete)
            assertEquals(setOf("character_cards/b"), receipt.observedSelection.completeUnitPackages.map { it.ref.unit }.toSet())
            assertEquals(puts, provider.dav.puts.toList()); noFullReceipt(directory)
            receipt.objects.forEach { row ->
                val observed = assertIs<LanSyncGroupCloudTargetObservation.Whole>(assertIs<LanSyncGroupCloudTargetEvidence.Existing>(row.evidence).readback).observation
                assertEquals(foreign.getValue(row.objectRequest.key).candidate.operationId, observed.operation)
                assertNotEquals(row.objectRequest.operation, observed.operation)
            }
            assertEquals(1, owner.progress().pendingUnitPackages); assertEquals(0, owner.progress().pendingControlSlots)
            val requests = provider.server.requestCount
            val cold = f.owner(directory, target(provider.route), source); assertEquals(plan, cold.prepare())
            assertEquals(receipt, cold.uploadCloud(provider.store("relay"), parts.open())); assertEquals(requests, provider.server.requestCount)
            assertEquals(observations(receipt), observations(requireNotNull(cold.receipt())))
            val route2 = SyncStoreId.random()
            Parts(provider, route2, "parts-route2").use { otherParts ->
                val other = f.owner(f.directory("target-route2"), target(route2), source); other.prepare()
                val observed = other.uploadCloud(provider.store("relay-route2", route2), otherParts.open())
                assertTrue(provider.server.requestCount > requests); assertEquals(puts, provider.dav.puts.toList())
                observed.objects.forEach { assertEquals(route2, assertIs<LanSyncGroupCloudTargetObservation.Whole>(it.evidence.readback).observation.route) }
                assertEquals(plan.scope, observed.scope)
            }
        } }
    }

    @Test fun anActualProviderFailureKeepsEachForcedObjectAndColdRetrySkipsItsHttpAndRetainsItsOriginalTime() = fixture { f ->
        f.card("available")
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("target"); val owner = f.owner(directory, target(provider.route)); val plan = owner.prepare()
            assertTrue(plan.objects.size > 1)
            val deny = AtomicBoolean()
            provider.dav.overrideRequest = { request -> if (deny.get() && request.method == "PROPFIND") MockResponse().setResponseCode(503) else null }
            assertFailsWith<IOException> { owner.uploadCloud(provider.store("relay"), parts.open()) { state -> if (state.observedObjects == 1) deny.set(true) } }
            val saved = requireNotNull(owner.receipt()); assertEquals(1, saved.objects.size)
            assertEquals(1, owner.progress().observedObjects); assertEquals(0, saved.observedSelection.completeUnitPackages.size)
            assertFalse(saved.observedSelection.originalScopeComplete); noFullReceipt(directory)
            val first = saved.objects.single(); val firstKey = first.objectRequest.key; val sourceReads = AtomicInteger(); val oldHttp = AtomicInteger()
            val source = object : CiphertextStore by f.access.reader {
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                    if (key == firstKey) { sourceReads.incrementAndGet(); error("SYNTHETIC_RETAINED_SOURCE_REQUIRED") }
                    return f.access.reader.readTo(key, destination, expected)
                }
            }
            provider.dav.overrideRequest = { request -> if (request.requestUrl!!.encodedPath.contains(firstKey.id.value)) oldHttp.incrementAndGet(); null }
            val cold = f.owner(directory, target(provider.route), source); assertEquals(plan, cold.prepare())
            val complete = cold.uploadCloud(provider.store("relay"), parts.open())
            assertEquals(0, sourceReads.get()); assertEquals(0, oldHttp.get())
            assertEquals(first, complete.objects.single { it.objectRequest == first.objectRequest })
            assertEquals(plan.objects.size, complete.objects.size); assertTrue(complete.observedSelection.originalScopeComplete)
            assertEquals(1, complete.observedSelection.completeUnitPackages.size); noFullReceipt(directory)
            val before = provider.server.requestCount
            assertEquals(complete, cold.uploadCloud(provider.store("relay"), parts.open())); assertEquals(before, provider.server.requestCount)
        } }
    }

    @Test fun existingFullTypedHistoryIsAdoptedWithoutHttpOrRewritingItsBytesAndDamagedPartialEvidenceRefusesBeforeNetwork() = fixture { f ->
        f.card("available", true)
        for (mode in listOf("cloud", "weak", "chunk")) WeakFixture(f.directory("provider-$mode")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("target-$mode"); val route = target(provider.route)
            val full = f.access.targetPublication(directory, route); val fullPlan = full.prepare()
            val original = when (mode) {
                "cloud" -> full.uploadCloud(provider.store("full"), parts.open()).objects
                "weak" -> full.upload(provider.store("full")).objects.map { row -> LanSyncGroupCloudTargetObjectReceipt(row.objectRequest,
                    LanSyncGroupCloudTargetEvidence.Appended(LanSyncGroupCloudTargetObservation.Whole(row.appendObservation), LanSyncGroupCloudTargetObservation.Whole(row.readbackObservation))) }
                else -> full.upload(parts.open()).objects.map { row -> LanSyncGroupCloudTargetObjectReceipt(row.objectRequest,
                    LanSyncGroupCloudTargetEvidence.Appended(LanSyncGroupCloudTargetObservation.Chunks(row.appendObservation), LanSyncGroupCloudTargetObservation.Chunks(row.readbackObservation))) }
            }
            val originalFiles = Files.newDirectoryStream(directory).use { paths -> paths.filter {
                it.fileName.toString().startsWith("plan-") || it.fileName.toString().startsWith("$mode-receipt-")
            }.associateWith { Files.readAllBytes(it) } }
            val before = provider.server.requestCount; val partial = f.owner(directory, route); val plan = partial.prepare()
            assertEquals(fullPlan.objects.toSet(), plan.objects.toSet())
            val receipt = partial.uploadCloud(provider.store("partial"), parts.open())
            assertEquals(before, provider.server.requestCount)
            assertEquals(original.toSet(), receipt.objects.toSet())
            originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
            when (mode) {
                "cloud" -> assertEquals(original, requireNotNull(full.cloudReceipt()).objects)
                "weak" -> assertNotNull(full.weakReceipt())
                else -> assertNotNull(full.chunkReceipt())
            }
            val rawRecord = Files.walk(directory.resolve("partial-object-evidence")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".obj") }.findFirst().orElseThrow()
            }
            val damaged = Files.readAllBytes(rawRecord).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; Files.write(rawRecord, damaged)
            val requestCount = provider.server.requestCount
            assertFailsWith<CiphertextStoreException> { partial.uploadCloud(provider.store("partial"), parts.open()) }
            assertContentEquals(damaged, Files.readAllBytes(rawRecord)); assertEquals(requestCount, provider.server.requestCount)
        } }
    }

    @Test fun twoAuthenticatedVersionDescriptorsUnderOneLogicalKeyStayExactAcrossPrivateRetentionReadbackAndColdReuse() = fixture { f ->
        val first = f.card("a"); val second = f.card("b")
        val work = f.directory("variant-codec"); val key = f.admin.custody().key(second.metadata.context.epoch)
        val outbox = SyncEncryptedOutbox(f.directory("variant-outbox"), key)
        val prepared = SyncGroupObjectCodec(work).enqueue(outbox, SyncStoreId.random(), first.metadata.context.key, key, second.metadata.envelope)
        outbox.upload(prepared.request.operationId, f.access.reader)
        val variant = SyncGroupObjectCodec(work).read(f.access.reader, key, prepared.context, prepared.request.descriptor, second.entry.version.reference())
        val reconciled = f.access.node.reconcileForPrefetch()
        val frozen = reconciled.copy(authenticatedVersions = reconciled.authenticatedVersions.filter { it.envelope.version?.reference() != second.entry.version.reference() } + variant)
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("target"); val owner = f.owner(directory, target(provider.route), frozen = frozen); val plan = owner.prepare()
            val sameKey = plan.objects.filter { it.key == first.metadata.context.key }
            assertEquals(2, sameKey.size); assertEquals(2, sameKey.map { it.descriptor }.toSet().size)
            val receipt = owner.uploadCloud(provider.store("relay"), parts.open())
            assertEquals(2, receipt.observedSelection.completeUnitPackages.size)
            assertEquals(plan.scope.wantedVersionRefs, receipt.observedSelection.metadataRefs)
            assertEquals(2, receipt.objects.count { it.objectRequest.key == first.metadata.context.key })
            val requests = provider.server.requestCount
            val cold = f.owner(directory, target(provider.route), frozen = frozen); assertEquals(plan, cold.prepare())
            assertEquals(receipt, cold.uploadCloud(provider.store("relay"), parts.open())); assertEquals(requests, provider.server.requestCount)
            noFullReceipt(directory)
        } }
    }

    @Test fun aLocalGuardIntegrityFailureAfterTheFirstGetEscapesUnchangedWithoutTryingAnotherPhysicalCandidate() = fixture { f ->
        f.card("available")
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val blocked = AtomicBoolean(); val failure = CiphertextStoreException(CiphertextStoreFailure.INTEGRITY)
            val owner = f.owner(f.directory("target"), target(provider.route), live = { if (blocked.get()) throw failure })
            val plan = owner.prepare(); val first = plan.objects.first(); val writer = provider.store("foreign")
            repeat(2) { writer.appendAttempt(CiphertextPutRequest(first.key, SyncStoreId.random(), first.descriptor), bytes(f.access.reader, first).inputStream()) }
            val gets = AtomicInteger(); val puts = provider.dav.puts.toList()
            provider.dav.overrideRequest = { request ->
                if (request.method == "GET") { gets.incrementAndGet(); blocked.set(true) }; null
            }
            assertSame(failure, assertFailsWith<CiphertextStoreException> { owner.uploadCloud(provider.store("relay"), parts.open()) })
            assertEquals(1, gets.get()); assertEquals(puts, provider.dav.puts.toList())
            blocked.set(false); assertEquals(0, owner.progress().observedObjects)
            assertNull(owner.receipt())
        } }
    }

    @Test fun missingPartialOperationMarkersAndBothMissingBindingRecordsNeverGetRecreatedFromAnExistingPlan() = fixture { f ->
        f.card("available")
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("target"); val route = target(provider.route); val owner = f.owner(directory, route)
            owner.prepare(); owner.uploadCloud(provider.store("relay"), parts.open())
            for (name in listOf("partial-item-bindings", "partial-object-evidence")) {
                val store = directory.resolve(name); val operation = files(store.resolve("operations"), ".op").first()
                val original = Files.readAllBytes(operation); val bodies = files(store, ".obj").associateWith { Files.readAllBytes(it) }
                Files.delete(operation); val requests = provider.server.requestCount
                assertFailsWith<IllegalArgumentException> { owner.uploadCloud(provider.store("relay"), parts.open()) }
                assertFalse(Files.exists(operation)); assertEquals(requests, provider.server.requestCount)
                bodies.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file)) }
                Files.write(operation, original) // Restore only this synthetic fixture for the next independent deletion.
            }
            val preparedDirectory = f.directory("prepared-only"); val prepared = f.owner(preparedDirectory, route); prepared.prepare()
            val bindings = preparedDirectory.resolve("partial-item-bindings")
            (files(bindings, ".obj") + files(bindings.resolve("operations"), ".op")).forEach { Files.delete(it) }
            val requests = provider.server.requestCount
            assertFailsWith<IllegalArgumentException> { prepared.prepare() }
            assertTrue(files(bindings, ".obj").isEmpty()); assertTrue(files(bindings.resolve("operations"), ".op").isEmpty())
            // Losing the derived current pointer as well cannot turn a known planned item into a new reservation.
            Files.newDirectoryStream(preparedDirectory, "partial-current-*.json").use { paths -> paths.forEach { Files.delete(it) } }
            assertFailsWith<IllegalArgumentException> { prepared.prepare() }
            assertTrue(files(bindings, ".obj").isEmpty()); assertTrue(files(bindings.resolve("operations"), ".op").isEmpty())
            assertEquals(requests, provider.server.requestCount)
        } }
    }

    @Test fun missingLegacyOperationMarkersAreRefusedWithoutWritingTheOldFullModeOrStartingHttp() = fixture { f ->
        f.card("available")
        WeakFixture(f.directory("provider")).use { provider ->
            val directory = f.directory("target"); val route = target(provider.route)
            val full = f.access.targetPublication(directory, route); full.prepare()
            val store = directory.resolve("item-bindings")
            val bodies = files(store, ".obj").associateWith { Files.readAllBytes(it) }
            val operations = files(store.resolve("operations"), ".op"); assertTrue(operations.isNotEmpty())
            operations.forEach { Files.delete(it) }; val requests = provider.server.requestCount
            val partial = f.owner(directory, route)
            assertFailsWith<IllegalArgumentException> { partial.prepare() }
            operations.forEach { assertFalse(Files.exists(it)) }
            bodies.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file)) }
            assertTrue(files(directory.resolve("partial-item-bindings"), ".obj").isEmpty())
            assertEquals(requests, provider.server.requestCount)
        }
    }

    @Test fun aPresentDamagedAggregatePointerSurvivesReceiptAndAllCachedUploadWhileAnAbsentDerivedPointerCanRebuild() = fixture { f ->
        f.card("available")
        WeakFixture(f.directory("provider")).use { provider -> Parts(provider).use { parts ->
            val directory = f.directory("target"); val owner = f.owner(directory, target(provider.route)); owner.prepare()
            val receipt = owner.uploadCloud(provider.store("relay"), parts.open())
            val pointer = Files.newDirectoryStream(directory, "partial-receipt-current-*.json").use { it.single() }
            val original = Files.readAllBytes(pointer)
            val value = lanSyncJson.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
            val damaged = JsonObject(value + ("hash" to JsonPrimitive("0".repeat(64)))).toString().toByteArray(Charsets.UTF_8)
            Files.write(pointer, damaged); val requests = provider.server.requestCount
            assertFailsWith<IllegalArgumentException> { owner.receipt() }
            assertContentEquals(damaged, Files.readAllBytes(pointer))
            assertFailsWith<IllegalArgumentException> { owner.uploadCloud(provider.store("relay"), parts.open()) }
            assertContentEquals(damaged, Files.readAllBytes(pointer)); assertEquals(requests, provider.server.requestCount)
            Files.delete(pointer)
            assertEquals(receipt, owner.receipt()); assertContentEquals(original, Files.readAllBytes(pointer))
            assertEquals(requests, provider.server.requestCount)
        } }
    }

    @Test fun interruptedFirstInitializationResumesOnlyItsSameUnusedIntentAndAReadyUsedStoreNeverGetsRecreated() = fixture { f ->
        f.card("available")
        for ((index, point) in listOf("PARTIAL_INTENT_RETAINED", "PARTIAL_BINDINGS_STORE_RETAINED", "PARTIAL_EVIDENCE_STORE_RETAINED").withIndex()) {
            val directory = f.directory("initializing-$index"); val route = target(SyncStoreId.random()); val interrupted = IOException("SYNTHETIC_INITIALIZATION_STOP")
            assertSame(interrupted, assertFailsWith<IOException> {
                f.owner(directory, route, initialization = { if (it == point) throw interrupted })
            })
            assertFalse(Files.exists(directory.resolve("partial-ready.json")))
            val intent = Files.readAllBytes(directory.resolve("partial-target.json"))
            val storeId = directory.resolve("partial-item-bindings/store.id")
            val beforeId = if (Files.exists(storeId)) Files.readAllBytes(storeId) else null
            val cold = f.owner(directory, route)
            assertTrue(Files.isRegularFile(directory.resolve("partial-ready.json")))
            assertContentEquals(intent, Files.readAllBytes(directory.resolve("partial-target.json")))
            beforeId?.let { assertContentEquals(it, Files.readAllBytes(storeId)) }
            assertEquals(0, cold.progress().observedObjects)
        }
        val directory = f.directory("ready-used"); val route = target(SyncStoreId.random()); val owner = f.owner(directory, route)
        owner.prepare(); val ready = Files.readAllBytes(directory.resolve("partial-ready.json"))
        val missing = directory.resolve("partial-object-evidence"); check(missing.startsWith(f.root)); check(missing.toFile().deleteRecursively())
        assertFailsWith<IllegalArgumentException> { f.owner(directory, route) }
        assertFalse(Files.exists(missing)); assertContentEquals(ready, Files.readAllBytes(directory.resolve("partial-ready.json")))
        // A lost READY plus an entirely missing used store is still not an unused first initialization.
        Files.delete(directory.resolve("partial-ready.json"))
        assertFailsWith<IllegalArgumentException> { f.owner(directory, route) }
        assertFalse(Files.exists(missing)); assertTrue(Files.isRegularFile(directory.resolve("partial-used.json")))
    }
}
