package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakFixture
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCandidate
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
        suspend fun owner(directory: Path, target: SyncDeliveryTarget, source: CiphertextStore = access.reader): LanSyncGroupTargetPublication {
            val reconciled = access.node.reconcileForPrefetch()
            val custody = admin.custody()
            val proof = LanSyncGroupTargetExport(access.trust, admin.identity.fingerprint, custody.ledger(),
                reconciled.copy(history = reconciled.history?.snapshot()), access.vault, access.vaultDescriptor,
                source, custody::ledger, custody::key, custody, admin.workspace.resourceStore, { _, _, _ -> })
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
