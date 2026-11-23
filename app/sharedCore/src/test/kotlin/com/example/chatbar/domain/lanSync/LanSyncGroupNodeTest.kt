package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Three actual native roots with real signatures/HPKE/AEAD and synthetic protection/file transport. */
class LanSyncGroupNodeTest {
    internal class Group(val root: Path) {
        val identities = List(3) { LanSyncTlsIdentity.create() }
        val recipients = identities.map { SyncDeviceEncryptionKey.create(it.fingerprint) }
        val cipher = SyncStreamingCipher.create()
        val members = identities.indices.associate { index -> identities[index].fingerprint to SyncGroupMember(
            Base64.getEncoder().encodeToString(identities[index].certificate.encoded), recipients[index].publicKey.encoded) }
        val unsigned = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(),
            LanSyncScope.schemas.keys, members, emptySet(), "unsigned")
        val genesis = unsigned.copy(signature = Base64.getEncoder().encodeToString(identities[0].signSyncGroupPayload(unsigned.signingBytes())))
        val trust = SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(identities[0].fingerprint).certificate)
        val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        val backend = FileCiphertextStore(root.resolve("ciphertext-transport"))
        fun rotate(revoked: Int): SyncStreamingCipher {
            val next = SyncStreamingCipher.create(cipher.group)
            val current = ledger.current()
            val unsigned = current.copy(id = SyncStoreId.random(), parent = current.reference(), sequence = current.sequence + 1,
                epoch = next.epoch, keyCommitment = next.keyCommitment(),
                members = current.members + (identities[revoked].fingerprint to current.members.getValue(identities[revoked].fingerprint).copy(active = false)),
                signature = "unsigned")
            val signed = unsigned.copy(signature = Base64.getEncoder().encodeToString(identities[0].signSyncGroupPayload(unsigned.signingBytes())))
            assertEquals(SyncVersionState.ADMITTED, ledger.offer(signed))
            return next
        }
        fun publishRotation(next: SyncStreamingCipher, target: SyncDeliveryTarget): LanSyncGroupPublishPlan {
            val keys = mapOf(cipher.epoch to cipher, next.epoch to next)
            val queues = Files.createDirectory(root.resolve("rotation-queues"))
            val publisher = LanSyncGroupRootPublication(Files.createDirectory(root.resolve("rotation-publication")), identities[0], { ledger },
                { keys.getValue(it) }, { epoch -> SyncEncryptedOutbox(Files.createDirectories(queues.resolve(epoch.value)), keys.getValue(epoch)) })
            val plan = publisher.prepare()
            publisher.upload(backend, target)
            assertNotNull(publisher.receipt(target))
            return plan
        }
        inner class End(val index: Int) {
            val base = Files.createDirectory(root.resolve("node-$index")); val data = Files.createDirectory(base.resolve("data"))
            val runtime = Files.createDirectory(base.resolve("runtime")); val custodyRoot = Files.createDirectory(base.resolve("custody"))
            var journalHook: (String, Int) -> Unit = { _, _ -> }
            var resourceValidator: (String, Path, LanSyncRecord) -> Unit = { _, _, _ -> }
            val workspace = openWorkspace()
            fun openWorkspace() = LanSyncWorkspace(data, base.resolve("workspace"), { Path.of(it) },
                observeJournal = { stage, index -> journalHook(stage, index) }, validateResource = { kind, path, record ->
                    resourceValidator(kind, path, record)
                })
            var gated = false; var reloads = 0; var hook: (String) -> Unit = {}
            var reloadHook: suspend (Set<String>) -> Unit = {}
            fun custody() = SyncGroupCustody(custodyRoot, cipher.group, recipients[index], { it.reversedArray() }, { it.reversedArray() })
            fun author() = SyncAuthorJournal(runtime.resolve("author"), trust,
                Base64.getEncoder().encodeToString(identities[index].certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
            val platform = object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) {
                    check(!gated); gated = true
                    try { work() } finally { gated = false }
                }
                override suspend fun reload(sessionIds: Set<String>) { check(gated); reloads++; reloadHook(sessionIds) }
            }
            fun open(dependencies: CiphertextStore = backend, workspaceOwner: LanSyncWorkspace = workspace) =
                LanSyncGroupNode(runtime, workspaceOwner, platform, identities[index], trust, custody(), recipients[index], dependencies,
                { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, { kind, path, record ->
                    resourceValidator(kind, path, record)
                }, observe = { hook(it) })
            suspend fun enroll(): LanSyncGroupNode {
                val admitted = custody().enroll(trust, listOf(genesis))
                val capsule = SyncGroupKeyDelivery.create(ledger, cipher, identities[index].fingerprint, SyncStoreId.random(), identities[0]::signSyncGroupPayload)
                custody().rememberKey(capsule.openTentative(trust, recipients[index], CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(admitted))
                return open().also { it.initializeNewEnrollment() }
            }
            fun card(id: String, name: String) {
                val path = data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
                LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1))
            }
            fun name(id: String) = LanSyncJsonFiles.read(data.resolve("entities/character_cards/$id.json"), CharacterCard.serializer())?.name
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, SyncStoreId.random())
            suspend fun send(node: LanSyncGroupNode, commit: LanSyncGroupSourceCommit) {
                val store = object : CiphertextStore by backend {
                    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                        assertFalse(gated, "network delivery held native storage gate"); return backend.put(request, source)
                    }
                }
                node.upload(commit.entry.request.operation, store, target)
            }
            suspend fun scan(node: LanSyncGroupNode): LanSyncGroupNodeView {
                var cursor: CommitCursor? = null
                do { cursor = node.discover(backend, cursor, 1).nextCursor } while (cursor != null)
                return node.inspect()
            }
        }
    }
    private fun withGroup(work: suspend (Group) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-native-group-nodes-").toRealPath()
        try { work(Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    private fun retainedFiles(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) }
    }
    private fun assertRetainedFiles(before: Map<String, ByteArray>, root: Path) {
        val after = retainedFiles(root)
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertContentEquals(bytes, after.getValue(name), name) }
    }

    @Test fun unchangedSharedFormatLeavesIndependentConversationReceiptsAndSendsUnblocked() = withGroup { group ->
        val source = group.End(0); val target = group.End(1)
        val origin = source.enroll(); val receiver = target.enroll()
        val formatUnit = "format_cards/f"
        val a = "conversation/a"; val b = "conversation/b"
        val format = source.data.resolve("entities/format_cards/f.json")
        Files.createDirectories(format.parent)
        LanSyncJsonFiles.write(format, FormatCard.serializer(), FormatCard("f", "合成格式", "合成模板", createdAt = 1))
        fun session(end: Group.End, id: String, title: String) {
            val path = end.data.resolve("entities/chat_sessions/$id.json")
            Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatSession.serializer(), ChatSession(id, "card", title, formatCardId = "f", createdAt = 1, updatedAt = 1))
        }
        fun message(end: Group.End, id: String, text: String): Path {
            val path = end.data.resolve("entities/chat_messages/b_$id.json")
            Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatMessage.serializer(), ChatMessage(id, "b", MessageRole.USER, text, createdAt = 7, updatedAt = 7))
            return path
        }
        session(source, "a", "合成 A"); session(source, "b", "合成 B")
        source.send(origin, origin.commit(origin.inspect().review(setOf(formatUnit, a, b))))
        receiver.apply(requireNotNull(target.scan(receiver).plan))
        session(target, "a", "接收端保留 A"); session(source, "a", "来源端 A")
        message(source, "in", "B 的来信")
        source.send(origin, origin.commit(origin.inspect().review(setOf(a, b))))
        val incoming = requireNotNull(target.scan(receiver).plan)
        assertEquals(LanSyncGroupNativeStage.CONFLICT, incoming.units.getValue(a).stage)
        assertEquals(LanSyncGroupNativeStage.UNCHANGED, incoming.units.getValue(formatUnit).stage)
        assertEquals(setOf(b), incoming.advances.keys)
        receiver.apply(incoming)
        val localA = target.data.resolve("entities/chat_sessions/a.json")
        assertEquals("接收端保留 A", LanSyncJsonFiles.read(localA, ChatSession.serializer())?.title)
        assertEquals("B 的来信", LanSyncJsonFiles.read(target.data.resolve("entities/chat_messages/b_in.json"), ChatMessage.serializer())?.content)
        assertTrue(requireNotNull(target.scan(receiver).plan).advances.isEmpty())
        message(target, "out", "B 的回信")
        val isolated = receiver.inspect().review(setOf(b))
        assertTrue(isolated.requiredBindings.isEmpty())
        target.send(receiver, receiver.commit(isolated))
        origin.apply(requireNotNull(source.scan(origin).plan))
        assertEquals("B 的回信", LanSyncJsonFiles.read(source.data.resolve("entities/chat_messages/b_out.json"), ChatMessage.serializer())?.content)
        assertTrue(requireNotNull(source.scan(origin).plan).advances.isEmpty())
    }

    @Test fun realPinnedSocketTimeoutFetchesTheSameSignedMessageImageFromAnotherActiveMember() = withGroup { group ->
        val source = group.End(0); val receiver = group.End(1)
        val origin = source.enroll(); receiver.enroll()
        val image = Files.createDirectories(source.data.resolve("images")).resolve("test.png")
        val bytes = LanSyncPreviewSample.image()
        Files.write(image, bytes)
        val sessionPath = Files.createDirectories(source.data.resolve("entities/chat_sessions")).resolve("chat.json")
        val messagePath = Files.createDirectories(source.data.resolve("entities/chat_messages")).resolve("chat_m.json")
        LanSyncJsonFiles.write(sessionPath, ChatSession.serializer(), ChatSession("chat", "card", "合成", createdAt = 1, updatedAt = 1))
        LanSyncJsonFiles.write(messagePath, ChatMessage.serializer(), ChatMessage("m", "chat", MessageRole.USER, "带原图的消息",
            images = listOf(image.toString()), createdAt = 2, updatedAt = 2))
        val published = origin.commit(origin.inspect().review(setOf("conversation/chat")))
        source.send(origin, published)
        val blob = published.publication.blobs.getValue(lanSyncHash(bytes))
        val block = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val bReads = AtomicInteger()
        fun routeBackend(stalled: Boolean) = object : CiphertextStore by group.backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                if (key == blob.key) {
                    if (stalled) {
                        entered.countDown()
                        check(block.await(20, TimeUnit.SECONDS))
                    } else bReads.incrementAndGet()
                }
                return group.backend.readTo(key, destination, expected)
            }
        }
        val serverWorkers = Executors.newFixedThreadPool(2)
        val listeners = mutableListOf<SSLServerSocket>()
        fun openRoute(index: Int, stalled: Boolean): LanSyncGroupRemoteStore {
            val identity = group.identities[index]
            val joining = group.identities[1]
            val listener = (identity.context { it == joining.fingerprint }.serverSocketFactory
                .createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply { needClientAuth = true }
            listeners += listener
            val backend = routeBackend(stalled)
            val server = LanSyncGroupCiphertextServer(group.root.resolve("wire-$index"), { id, local, peer ->
                check(id == group.trust.group)
                LanSyncGroupPeerStore(group.trust, local, peer, { group.ledger }, backend) { null }
            })
            serverWorkers.submit {
                while (!listener.isClosed) {
                    try { server.serve(listener.accept() as SSLSocket) }
                    catch (_: IOException) { /* The timed-out client's exact socket closes before the source is released. */ }
                }
            }
            return LanSyncGroupRemoteStore(group.trust.group, group.root.resolve("client-$index"), {
                joining.context { it == identity.fingerprint }.socketFactory.createSocket(InetAddress.getLoopbackAddress(), listener.localPort) as SSLSocket
            }, readTimeoutMillis = if (stalled) 2_000 else 5_000)
        }
        val errors = mutableListOf<Pair<String, Exception>>()
        try {
            val reader = LanSyncGroupReplicaReader(FileCiphertextStore(group.root.resolve("received-route-objects")),
                group.root.resolve("route-work")) { route, reason -> errors += route to reason }
            reader.routes(listOf(LanSyncGroupReplicaReader.Route("A", openRoute(0, true)), LanSyncGroupReplicaReader.Route("B", openRoute(2, false))))
            val actual = receiver.open(reader)
            val incoming = requireNotNull(receiver.scan(actual).plan)
            assertEquals(0L, entered.count)
            assertTrue(errors.any { (route, cause) -> route == "A" && cause is SocketTimeoutException })
            assertEquals(1, bReads.get())
            assertEquals(setOf("conversation/chat"), incoming.advances.keys)
            actual.apply(incoming)
            val restored = requireNotNull(LanSyncJsonFiles.read(receiver.data.resolve("entities/chat_messages/chat_m.json"), ChatMessage.serializer()))
            assertEquals("带原图的消息", restored.content)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(restored.images.single())))
            assertTrue(requireNotNull(receiver.scan(actual).plan).advances.isEmpty())
            assertEquals(1, bReads.get())
        } finally {
            block.countDown()
            listeners.forEach { it.close() }
            serverWorkers.shutdown()
            check(serverWorkers.awaitTermination(25, TimeUnit.SECONDS))
        }
    }

    @Test fun aRealUnsupportedProgressivePhotoHoldsOnlyItsConversationAndRetainsThePublishedBase() = withGroup { group ->
        val publisher = group.End(0); val local = group.End(1)
        val origin = publisher.enroll(); val receiver = local.enroll()
        val a = "conversation/a"; val b = "conversation/b"
        fun session(end: Group.End, id: String) = Files.createDirectories(end.data.resolve("entities/chat_sessions")).resolve("$id.json")
        fun message(end: Group.End, id: String) = Files.createDirectories(end.data.resolve("entities/chat_messages")).resolve("b_$id.json")
        val aRecord = ChatSession("a", "card", "已有会话 A", createdAt = 1, updatedAt = 1)
        LanSyncJsonFiles.write(session(publisher, "a"), ChatSession.serializer(), aRecord)
        LanSyncJsonFiles.write(session(publisher, "b"), ChatSession.serializer(), ChatSession("b", "card", "独立会话 B", createdAt = 1, updatedAt = 1))
        publisher.send(origin, origin.commit(origin.inspect().review(setOf(a, b))))
        receiver.apply(requireNotNull(local.scan(receiver).plan))
        val previousA = receiver.inspect().nativeState.units.getValue(a)

        val photo = Files.createDirectories(local.data.resolve("images")).resolve("camera.jpg")
        val raster = BufferedImage(8000, 6000, BufferedImage.TYPE_BYTE_GRAY)
        val encoder = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(photo.toFile()).use { output ->
                encoder.output = output
                val params = encoder.defaultWriteParam.apply { progressiveMode = ImageWriteParam.MODE_DEFAULT }
                encoder.write(null, IIOImage(raster, null, null), params)
            }
        } finally { encoder.dispose(); raster.flush() }
        val originalFileHash = LanSyncResourceStore.hashFile(photo, Files.size(photo))
        val localA = session(local, "a")
        LanSyncJsonFiles.write(localA, ChatSession.serializer(), aRecord.copy(chatBackground = photo.toString()))
        val originalRecord = Files.readAllBytes(localA)
        local.resourceValidator = { kind, file, record ->
            if (kind == "images") ImageIO.createImageInputStream(file.toFile()).use { input ->
                val decoder = ImageIO.getImageReaders(input).next()
                try {
                    decoder.input = input
                    assertEquals("JPEG", decoder.formatName.uppercase())
                    LanSyncRasterPolicy.validateDimensions(file, record, decoder.getWidth(0), decoder.getHeight(0), jpeg = true)
                } finally { decoder.dispose() }
            }
        }
        assertFailsWith<LanSyncLocalImageUnsupported> { local.workspace.capture(group.identities[1].fingerprint).close() }
        LanSyncJsonFiles.write(session(publisher, "a"), ChatSession.serializer(), aRecord.copy(title = "不能覆盖 A"))
        LanSyncJsonFiles.write(message(publisher, "in"), ChatMessage.serializer(),
            ChatMessage("in", "b", MessageRole.USER, "B 仍然收件", createdAt = 2, updatedAt = 2))
        publisher.send(origin, origin.commit(origin.inspect().review(setOf(a, b))))
        val incoming = requireNotNull(local.scan(receiver).plan)
        assertEquals(LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED, incoming.units.getValue(a).stage)
        assertEquals(setOf(b), incoming.advances.keys)
        assertEquals(originalFileHash, incoming.before.resources.keys.single())
        receiver.apply(incoming)
        assertEquals("B 仍然收件", LanSyncJsonFiles.read(message(local, "in"), ChatMessage.serializer())?.content)
        assertEquals(previousA, receiver.inspect().nativeState.units.getValue(a))
        assertFailsWith<IllegalArgumentException> { receiver.inspect().review(setOf(a)) }
        local.workspace.captureGroup(group.identities[1].fingerprint).use { current ->
            val changed = current.snapshot.copy(records = current.snapshot.records.filterNot { it in current.snapshot.units().getValue(a) },
                resources = emptyMap())
            assertEquals("SYNC_GROUP_LOCAL_RESOURCE_BLOCKED", assertFailsWith<IllegalArgumentException> {
                local.workspace.apply(LanSyncPlan(java.util.UUID.randomUUID().toString(), changed, emptyMap()),
                    current.snapshot.hashes(), group.identities[1].fingerprint, emptyMap(), isolateLocalImages = true)
            }.message)
        }
        LanSyncJsonFiles.write(message(local, "out"), ChatMessage.serializer(),
            ChatMessage("out", "b", MessageRole.USER, "B 仍然发送", createdAt = 3, updatedAt = 3))
        val reply = receiver.commit(receiver.inspect().review(setOf(b)))
        assertEquals(setOf(b), reply.entry.version.changes.keys)
        local.send(receiver, reply)
        origin.apply(requireNotNull(publisher.scan(origin).plan))
        assertEquals("B 仍然发送", LanSyncJsonFiles.read(message(publisher, "out"), ChatMessage.serializer())?.content)
        assertEquals(previousA, local.open().inspect().nativeState.units.getValue(a))
        assertContentEquals(originalRecord, Files.readAllBytes(localA))
        assertEquals(originalFileHash, LanSyncResourceStore.hashFile(photo, Files.size(photo)))
        assertTrue(requireNotNull(publisher.scan(origin).plan).advances.isEmpty())
    }

    @Test fun aMissingOwnedMessagePhotoHoldsOnlyItsRealMediaComponentAcrossSignedUpdatesAndColdReopen() = withGroup { group ->
        val publisher = group.End(0); val local = group.End(1)
        val origin = publisher.enroll(); val receiver = local.enroll()
        val a = "conversation/a"; val b = "conversation/b"; val c = "conversation/c"
        fun path(end: Group.End, type: String, name: String): Path =
            Files.createDirectories(end.data.resolve("entities/$type")).resolve("$name.json")
        fun session(id: String, title: String = id) = LanSyncJsonFiles.write(path(publisher, "chat_sessions", id),
            ChatSession.serializer(), ChatSession(id, "card", title, createdAt = 1, updatedAt = 1))
        fun message(end: Group.End, session: String, id: String, body: String, photo: Path? = null) =
            LanSyncJsonFiles.write(path(end, "chat_messages", "${session}_$id"), ChatMessage.serializer(),
                ChatMessage(id, session, MessageRole.USER, body, images = listOfNotNull(photo?.toString()),
                    generatedImageMetadata = if (session == "a" && photo != null) listOf(
                        GeneratedImageMetadata(photo.toString(), "synthetic", negativePrompt = "", sizePreset = "small", width = 8, height = 8)
                    ) else emptyList(), createdAt = 2, updatedAt = 2))
        suspend fun send(units: Set<String>) {
            val review = origin.inspect().review(units)
            publisher.send(origin, origin.commit(review, confirmations = review.requiredBindings))
        }

        val image = Files.createDirectories(publisher.data.resolve("images")).resolve("published.png")
        val raster = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        try { assertTrue(ImageIO.write(raster, "png", image.toFile())) } finally { raster.flush() }
        val bytes = Files.readAllBytes(image)
        val independent = Files.write(image.resolveSibling("different-media-id.png"), bytes)
        session("a"); session("b"); message(publisher, "a", "old", "A 已发表的附件", image)
        send(setOf(a, b)); receiver.apply(requireNotNull(local.scan(receiver).plan))
        val oldReceipt = receiver.inspect().nativeState.units.getValue(a)
        val recordA = path(local, "chat_messages", "a_old")
        val rawA = Files.readAllBytes(recordA)
        val localMessage = requireNotNull(LanSyncJsonFiles.read(recordA, ChatMessage.serializer()))
        val localImage = Path.of(localMessage.images.single())
        assertEquals(localImage.toString(), localMessage.generatedImageMetadata.single().imagePath)
        assertContentEquals(bytes, Files.readAllBytes(localImage))
        Files.delete(localImage)

        session("a", "远端尝试更新 A")
        session("c")
        message(publisher, "b", "in", "B 收到独立附件", independent)
        message(publisher, "c", "linked", "复用 A 的真实媒体身份", image)
        send(setOf(a, b, c))
        val view = local.scan(receiver); val plan = requireNotNull(view.plan)
        assertEquals(LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED, plan.units.getValue(a).stage)
        assertEquals(LanSyncMissingImage.CODE, plan.units.getValue(a).code)
        assertEquals(setOf(a, c), plan.components.getValue(a))
        assertEquals(setOf(b), plan.components.getValue(b))
        assertEquals(LanSyncGroupNativeStage.DEPENDENCY_HELD, plan.units.getValue(c).stage)
        assertEquals(setOf(b), plan.advances.keys)
        assertFailsWith<IllegalArgumentException> { view.review(setOf(a)) }
        receiver.apply(plan)
        val received = requireNotNull(LanSyncJsonFiles.read(path(local, "chat_messages", "b_in"), ChatMessage.serializer()))
        assertEquals("B 收到独立附件", received.content)
        assertContentEquals(bytes, Files.readAllBytes(Path.of(received.images.single())))
        assertContentEquals(rawA, Files.readAllBytes(recordA))
        assertFalse(Files.exists(localImage))
        assertEquals(oldReceipt, receiver.inspect().nativeState.units.getValue(a))

        Files.delete(path(publisher, "chat_messages", "a_old")); Files.delete(path(publisher, "chat_sessions", "a"))
        send(setOf(a))
        val deletion = requireNotNull(local.scan(receiver).plan)
        assertNull(deletion.units.getValue(a).frontiers.single().change.valueHash)
        assertEquals(LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED, deletion.units.getValue(a).stage)
        assertFalse(a in deletion.advances)
        message(local, "b", "out", "B 仍可发送")
        val reply = receiver.commit(receiver.inspect().review(setOf(b)))
        assertEquals(setOf(b), reply.entry.version.changes.keys)
        local.send(receiver, reply)
        origin.apply(requireNotNull(publisher.scan(origin).plan))
        assertEquals("B 仍可发送", LanSyncJsonFiles.read(path(publisher, "chat_messages", "b_out"), ChatMessage.serializer())?.content)

        val reopenedWorkspace = local.openWorkspace()
        val cold = local.open(workspaceOwner = reopenedWorkspace)
        val retained = requireNotNull(reopenedWorkspace.recovery.read(reply.entry.request.sourceCheckpoint))
        reopenedWorkspace.recovery.verify(retained)
        assertEquals(setOf(b), cold.browseRecovery(retained.transaction).units.keys)
        assertEquals(LanSyncMissingImage.CODE, assertFailsWith<IllegalArgumentException> {
            cold.prepareRecovery(retained.transaction, a)
        }.message)
        val coldView = cold.inspect()
        assertEquals(oldReceipt, coldView.nativeState.units.getValue(a))
        assertEquals(LanSyncMissingImage.CODE, requireNotNull(coldView.plan).units.getValue(a).code)
        assertContentEquals(rawA, Files.readAllBytes(recordA))
        assertFalse(Files.exists(localImage))
        local.workspace.captureGroup(group.identities[1].fingerprint).use { capture ->
            assertEquals(setOf(a), capture.missingImageUnits)
            assertFailsWith<IllegalArgumentException> { LanSyncScope.validateGraph(capture.snapshot) }
            assertEquals(LanSyncMissingImage.CODE, assertFailsWith<IllegalArgumentException> {
                LanSyncGroupUnitCodec(local.base).prepare(capture, a).close()
            }.message)
            val dropped = capture.snapshot.copy(records = capture.snapshot.records.filter { it.unit != a })
            assertEquals(LanSyncMissingImage.CODE, assertFailsWith<IllegalArgumentException> {
                local.workspace.apply(LanSyncPlan(java.util.UUID.randomUUID().toString(), dropped, emptyMap()), capture.snapshot.hashes(),
                    group.identities[1].fingerprint, capture.blobs, isolateLocalImages = true)
            }.message)
        }
        Files.write(localImage, bytes)
        val restored = cold.inspect()
        assertFalse(requireNotNull(restored.plan).units.getValue(a).stage == LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED)
        assertEquals(oldReceipt, restored.nativeState.units.getValue(a))
        assertContentEquals(rawA, Files.readAllBytes(recordA))
        assertContentEquals(bytes, Files.readAllBytes(localImage))
    }

    @Test fun aTruncatedOwnedPngRetainsItsOriginalRecordWithoutBlockingAnIndependentPublish() = withGroup { group ->
        val local = group.End(0); val peer = group.End(1)
        local.resourceValidator = { kind, file, _ -> if (kind == "images") LanSyncRasterPolicy.checkPng(file, false) }
        val sender = local.enroll(); val receiver = peer.enroll()
        val damaged = "conversation/damaged"; val independent = "conversation/independent"
        val image = Files.createDirectories(local.data.resolve("images")).resolve("owned.png")
        val raster = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        try { assertTrue(ImageIO.write(raster, "png", image.toFile())) } finally { raster.flush() }
        val original = Files.readAllBytes(image)
        val sessions = Files.createDirectories(local.data.resolve("entities/chat_sessions"))
        val messages = Files.createDirectories(local.data.resolve("entities/chat_messages"))
        listOf("damaged", "independent").forEach { id ->
            LanSyncJsonFiles.write(sessions.resolve("$id.json"), ChatSession.serializer(), ChatSession(id, "card", id,
                createdAt = 1, updatedAt = 1))
        }
        val record = messages.resolve("damaged_original.json")
        LanSyncJsonFiles.write(record, ChatMessage.serializer(), ChatMessage("original", "damaged", MessageRole.USER, "保留这张图",
            images = listOf(image.toString()), createdAt = 2, updatedAt = 2))
        val initial = sender.commit(sender.inspect().review(setOf(damaged, independent)))
        local.send(sender, initial)
        receiver.apply(requireNotNull(peer.scan(receiver).plan))
        val previous = sender.inspect().nativeState.units.getValue(damaged)
        val recordBytes = Files.readAllBytes(record)
        val truncated = original.copyOf(8)
        Files.write(image, truncated)
        assertEquals("SYNC_IMAGE_INVALID", assertFailsWith<IllegalArgumentException> {
            local.workspace.capture(group.identities[0].fingerprint).close()
        }.message)

        LanSyncJsonFiles.write(messages.resolve("independent_new.json"), ChatMessage.serializer(),
            ChatMessage("new", "independent", MessageRole.USER, "独立会话继续发表", createdAt = 3, updatedAt = 3))
        val pending = sender.inspect()
        assertEquals(LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED, requireNotNull(pending.plan).units.getValue(damaged).stage)
        assertFailsWith<IllegalArgumentException> { pending.review(setOf(damaged)) }
        local.workspace.captureGroup(group.identities[0].fingerprint).use { capture ->
            assertEquals("SYNC_GROUP_LOCAL_RESOURCE_BLOCKED", assertFailsWith<IllegalArgumentException> {
                LanSyncGroupUnitCodec(local.base).prepare(capture, damaged).close()
            }.message)
        }
        val update = sender.commit(pending.review(setOf(independent)))
        assertEquals(setOf(independent), update.entry.version.changes.keys)
        local.send(sender, update)
        receiver.apply(requireNotNull(peer.scan(receiver).plan))
        val readBack = LanSyncJsonFiles.read(peer.data.resolve("entities/chat_messages/independent_new.json"), ChatMessage.serializer())
        assertEquals("独立会话继续发表", readBack?.content)
        assertEquals(previous, sender.inspect().nativeState.units.getValue(damaged))
        assertContentEquals(recordBytes, Files.readAllBytes(record))
        assertContentEquals(truncated, Files.readAllBytes(image))
        Files.write(image, original)
        val repaired = local.open(workspaceOwner = local.openWorkspace()).inspect()
        assertNotEquals(LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED, requireNotNull(repaired.plan).units.getValue(damaged).stage)
        assertEquals(previous, repaired.nativeState.units.getValue(damaged))
        assertContentEquals(recordBytes, Files.readAllBytes(record))
        assertContentEquals(original, Files.readAllBytes(image))
    }

    @Test fun signedConversationDeletionRetriesOnlyRepositoryReloadAcrossColdOpenAndSourceOnlyShutdown() = withGroup { g ->
        val source = g.End(0); val sender = source.enroll()
        val destination = g.End(1); val receiver = destination.enroll()
        fun session(end: Group.End, id: String) = end.data.resolve("entities/chat_sessions/$id.json")
        fun message(end: Group.End, id: String) = end.data.resolve("entities/chat_messages/$id.json")
        fun saveSession(end: Group.End, id: String, title: String) {
            val path = session(end, id)
            Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatSession.serializer(), ChatSession(id, "card", title, createdAt = 1, updatedAt = 1))
        }
        val deleted = "conversation/deleted"
        val kept = "conversation/kept"
        saveSession(source, "deleted", "signed conversation")
        saveSession(source, "kept", "existing conversation")
        val messagePath = message(source, "deleted_original")
        Files.createDirectories(messagePath.parent)
        LanSyncJsonFiles.write(messagePath, ChatMessage.serializer(),
            ChatMessage("original", "deleted", MessageRole.USER, "signed message", createdAt = 2, updatedAt = 2))
        source.send(sender, sender.commit(sender.inspect().review(setOf(deleted, kept))))
        assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, receiver.apply(requireNotNull(destination.scan(receiver).plan))?.status)
        assertTrue(Files.isRegularFile(session(destination, "deleted")))
        assertTrue(Files.isRegularFile(message(destination, "deleted_original")))
        val chats = ChatRepository(JsonFileStorage(destination.data))
        assertEquals(setOf("deleted", "kept"), chats.getAllSessions().map { it.id }.toSet())
        val beforeReloads = destination.reloads
        val refreshes = mutableListOf<Set<String>>()
        destination.reloadHook = { ids ->
            refreshes.add(ids)
            if (refreshes.size == 1) throw IOException("SYNTHETIC_PLATFORM_REPOSITORY_RELOAD_FAILED")
            chats.reloadAfterLanSync()
        }
        var writes = 0
        destination.hook = { if (it == "NATIVE_DATA_APPLIED") writes++ }

        Files.delete(messagePath)
        Files.delete(session(source, "deleted"))
        val review = sender.inspect().review(setOf(deleted))
        val tombstone = sender.commit(review, review.requiredBindings)
        assertNull(tombstone.entry.version.changes.getValue(deleted).content)
        source.send(sender, tombstone)
        val pending = requireNotNull(destination.scan(receiver).incomingReview)
        assertEquals(setOf(deleted), pending.reductions.keys)
        val approval = pending.acknowledge(pending.requiredBindings)
        val transaction = pending.confirmedPlan(approval).plan.transaction
        val failure = assertFailsWith<IllegalStateException> { receiver.applyIncoming(approval) }
        assertEquals("SYNC_GROUP_NATIVE_RELOAD_PENDING", failure.message)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }
            .any { it is IOException && it.message == "SYNTHETIC_PLATFORM_REPOSITORY_RELOAD_FAILED" })
        assertEquals(listOf(setOf("deleted", "kept")), refreshes)
        assertEquals(1, writes)
        assertTrue(destination.workspace.applied(transaction))
        assertFalse(Files.exists(session(destination, "deleted")))
        assertFalse(Files.exists(message(destination, "deleted_original")))
        saveSession(destination, "kept", "local edit between refresh attempts")
        assertEquals(setOf("deleted", "kept"), chats.getAllSessions().map { it.id }.toSet())
        assertEquals(0L, chats.lanSyncRevision.value)

        val nativeDir = destination.runtime.resolve("native")
        val beforeRetry = retainedFiles(nativeDir)
        val dataBeforeRetry = retainedFiles(destination.data)
        val afterShutdown = destination.open().inspectLocal(recoverNative = false)
        assertEquals(setOf(tombstone.entry.version.reference()), afterShutdown.nativeState.units.getValue(deleted).frontiers)
        assertNull(afterShutdown.nativeState.units.getValue(deleted).valueHash)
        assertEquals(beforeReloads + 1, destination.reloads)
        assertRetainedFiles(beforeRetry, nativeDir)
        assertRetainedFiles(dataBeforeRetry, destination.data)

        val opened = destination.open(workspaceOwner = destination.openWorkspace())
        val recovered = opened.inspectLocal()
        assertEquals(afterShutdown.nativeState, recovered.nativeState)
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, requireNotNull(recovered.plan).units.getValue(kept).stage)
        assertEquals(listOf(setOf("deleted", "kept"), setOf("deleted", "kept")), refreshes)
        assertEquals("kept", chats.getAllSessions().single().id)
        assertEquals("local edit between refresh attempts", chats.getAllSessions().single().title)
        assertEquals(1L, chats.lanSyncRevision.value)
        assertEquals(1, writes)
        assertRetainedFiles(dataBeforeRetry, destination.data)
        val afterRetry = retainedFiles(nativeDir)
        assertEquals(beforeRetry.keys, afterRetry.keys)
        assertFalse(beforeRetry.getValue("reload.protected").contentEquals(afterRetry.getValue("reload.protected")))
        beforeRetry.filterKeys { it != "reload.protected" }.forEach { (name, bytes) ->
            assertContentEquals(bytes, afterRetry.getValue(name), name)
        }
        assertEquals(0L, destination.author().state().committedSequence)
        assertEquals(recovered.nativeState, opened.inspectLocal().nativeState)
        assertEquals(beforeReloads + 2, destination.reloads)
        assertRetainedFiles(afterRetry, nativeDir)
    }

    @Test fun olderAppliedHistoryWithoutARefreshMarkerSurvivesCancelledReloadAndIgnoresSourceOnlyReceipts() = withGroup { g ->
        val source = g.End(0); val sender = source.enroll()
        val destination = g.End(1); val receiver = destination.enroll()
        val incoming = "character_cards/incoming"
        source.card("incoming", "received from signer")
        source.send(sender, sender.commit(sender.inspect().review(setOf(incoming))))
        assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, receiver.apply(requireNotNull(destination.scan(receiver).plan))?.status)
        val marker = destination.runtime.resolve("native/reload.protected")
        val originalMarker = Files.readAllBytes(marker)
        val firstReloads = destination.reloads
        destination.card("local", "signed only at destination")
        val authored = receiver.commit(receiver.inspect().review(setOf("character_cards/local")))
        destination.send(receiver, authored)
        assertEquals(firstReloads, destination.reloads)
        assertContentEquals(originalMarker, Files.readAllBytes(marker))

        Files.delete(marker)
        val older = retainedFiles(destination.runtime.resolve("native"))
        val onDisk = retainedFiles(destination.data)
        destination.reloadHook = { throw CancellationException("SYNTHETIC_CANCELLED_REFRESH") }
        assertEquals("SYNTHETIC_CANCELLED_REFRESH", assertFailsWith<CancellationException> {
            destination.open().inspectLocal()
        }.message)
        assertRetainedFiles(older, destination.runtime.resolve("native"))
        assertRetainedFiles(onDisk, destination.data)
        destination.reloadHook = {}
        val cold = destination.open()
        val state = cold.inspectLocal().nativeState
        assertEquals(2L, state.completedSequence)
        assertContentEquals(originalMarker, Files.readAllBytes(marker))
        assertEquals(firstReloads + 2, destination.reloads)
        assertEquals(state, cold.inspectLocal().nativeState)
        assertEquals(firstReloads + 2, destination.reloads)
        assertRetainedFiles(onDisk, destination.data)
    }

    @Test fun coldSourceOnlyRetriesWithOrWithoutMetadataPrefetchReuseTheOriginalLocalReceiptWithoutNativeOrLiveRouteWork() = withGroup { g ->
        val end = g.End(0); val initial = end.enroll(); val unit = "character_cards/cold"
        end.card("cold", "retained cold source original")
        val source = initial.commit(initial.inspect().review(setOf(unit)))
        val operation = source.entry.request.operation
        val target = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        val receipt = initial.upload(operation, g.backend, target, recoverNative = false)
        var cursor: CommitCursor? = null
        do { cursor = initial.discoverLocal(cursor, 1).nextCursor } while (cursor != null)
        val checkpoint = requireNotNull(end.workspace.recovery.read(source.entry.request.sourceCheckpoint))
        assertTrue(checkpoint.hasLocalOriginals)
        end.workspace.recovery.verify(checkpoint)
        val originals = linkedMapOf<String, Pair<Path, ByteArray>>()
        val originalHashes = linkedSetOf<String>()
        end.workspace.recovery.originals(checkpoint) { original, path ->
            originalHashes += original.hash
            originals[original.relative] = path to Files.readAllBytes(path)
        }
        assertTrue(originals.isNotEmpty())
        val change = source.entry.version.changes.getValue(unit)
        val content = requireNotNull(change.content)
        // This fixture shares its recovery object store with the cache. Delete only signed unit
        // bodies/manifests, after proving that none is an independently retained raw original.
        val cachedProofs = listOfNotNull(change.content, change.membership).distinctBy { it.sha256 }.associateWith { plain ->
            assertFalse(plain.sha256 in originalHashes)
            requireNotNull(end.workspace.resourceStore.completed(plain.sha256, plain.length))
        }
        val authorBefore = retainedFiles(end.runtime.resolve("author"))
        val nativeBefore = retainedFiles(end.runtime.resolve("native"))
        val sourceBefore = retainedFiles(end.runtime.resolve("source"))
        val queuesBefore = retainedFiles(end.runtime.resolve("queues"))
        val dailyBefore = retainedFiles(end.data)
        val recoveryBefore = retainedFiles(end.base.resolve("workspace/recovery"))
        val authorState = end.author().state()
        assertEquals(1L, authorState.committedSequence)
        assertEquals(source.entry, end.author().entry(operation))
        assertEquals(0, end.reloads)
        var liveCalls = 0
        val live = object : CiphertextStore by g.backend {
            private fun refused(): Nothing { liveCalls++; error("SYNTHETIC_COLD_RETRY_LIVE_ROUTE_FORBIDDEN") }
            override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = refused()
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = refused()
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = refused()
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = refused()
        }
        for ((index, prefetch) in listOf(true, false).withIndex()) {
            cachedProofs.forEach { (plain, path) ->
                Files.deleteIfExists(path)
                assertNull(end.workspace.resourceStore.completed(plain.sha256, plain.length))
            }
            val reader = LanSyncGroupReplicaReader(g.backend, end.base.resolve("cold-reader-$index")) { _, error -> throw error }
            reader.routes(listOf(LanSyncGroupReplicaReader.Route("SYNTHETIC_LIVE", live)))
            val cold = end.open(reader)
            if (prefetch) assertTrue(requireNotNull(cold.reconcileForPrefetch().history).contains(source.entry.version.reference()))
            // No inspect/inspectLocal may initialize this fresh Node before its upload.
            assertEquals(receipt, cold.upload(operation, g.backend, target, recoverNative = false))
            assertEquals(receipt, cold.receipt(operation, target))
            assertEquals(listOf(operation), cold.sourceOperations())
            assertNotNull(end.workspace.resourceStore.completed(content.sha256, content.length))
            assertEquals(authorState, end.author().state())
            assertEquals(source.entry.version, requireNotNull(end.author().entry(operation)).version)
            assertRetainedFiles(authorBefore, end.runtime.resolve("author"))
            assertRetainedFiles(nativeBefore, end.runtime.resolve("native"))
            assertRetainedFiles(sourceBefore, end.runtime.resolve("source"))
            assertRetainedFiles(queuesBefore, end.runtime.resolve("queues"))
            assertRetainedFiles(dailyBefore, end.data)
            assertRetainedFiles(recoveryBefore, end.base.resolve("workspace/recovery"))
            originals.forEach { (_, original) -> assertContentEquals(original.second, Files.readAllBytes(original.first)) }
            end.workspace.recovery.verify(checkpoint)
            assertEquals(0, end.reloads)
            assertEquals(0, liveCalls)
            assertFalse(end.gated)
        }
    }

    @Test fun twoOfflineOperationsWaitForRotatedKeyThenColdDrainInAuthorOrderWithoutReauthoring() = withGroup { g ->
        val receiver = g.End(0); val receiving = receiver.enroll()
        val daily = g.End(1); var node = daily.enroll()
        g.End(2).enroll()
        daily.card("a", "DL1 first offline original")
        val first = node.commit(node.inspect().review(setOf("character_cards/a")), operation = SyncStoreId.parse("f".repeat(32)))
        daily.card("b", "DL1 second offline original")
        val second = node.commit(node.inspect().review(setOf("character_cards/b")), operation = SyncStoreId.parse("0".repeat(31) + "1"))
        val commits = listOf(first, second)
        val operations = commits.map { it.entry.request.operation }
        val originalEntries = commits.map { it.entry }
        val origin = Files.readAllBytes(daily.runtime.resolve("author/origin.protected"))
        val originals = commits.map { requireNotNull(daily.workspace.recovery.read(it.entry.request.sourceCheckpoint)) }
        originals.forEach(daily.workspace.recovery::verify)
        assertEquals(listOf(1L, 2L), commits.map { it.entry.version.authorSequence })
        assertEquals(listOf(g.cipher.epoch, g.cipher.epoch), commits.map { it.publication.epoch })
        commits.forEach { assertNull(g.backend.metadata(it.publication.metadata.key)) }
        val peerTarget = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        operations.forEach { assertNull(node.receipt(it, peerTarget)) }

        val next = g.rotate(2)
        // Actual signed ledger can arrive before its recipient capsule. No source is reset/re-authored.
        daily.custody().update(listOf(g.ledger.current()))
        node = daily.open()
        assertEquals(LanSyncGroupNodeStage.WAITING_FOR_KEY, node.inspect().stage)
        assertEquals(operations, node.sourceOperations())
        assertEquals(2L, daily.author().state().committedSequence)
        val control = g.publishRotation(next, peerTarget)
        assertTrue(control.keyDeliveries.containsKey("${next.epoch.value}:${g.identities[1].fingerprint}"))
        assertFalse(control.keyDeliveries.keys.any { it.endsWith(":${g.identities[2].fingerprint}") })
        assertEquals(setOf(g.identities[2].fingerprint), control.revocationNotices.keys)

        val resumed = daily.scan(node)
        assertEquals(LanSyncGroupNodeStage.READY, resumed.stage)
        assertEquals(operations, node.sourceOperations(), "Both cold operations must survive random filename order")
        assertEquals(next.epoch, daily.custody().key(next.epoch).epoch)
        assertContentEquals(origin, Files.readAllBytes(daily.runtime.resolve("author/origin.protected")))
        assertEquals(originalEntries, operations.map { daily.author().entry(it) })
        assertEquals(2L, daily.author().state().committedSequence)
        for (operation in node.sourceOperations()) {
            assertNull(node.receipt(operation, peerTarget))
            val receipt = node.upload(operation, g.backend, peerTarget)
            assertEquals(peerTarget, receipt.target)
            assertEquals(receipt, node.receipt(operation, peerTarget))
        }
        val incoming = receiver.scan(receiving)
        assertEquals(LanSyncGroupNodeStage.READY, incoming.stage)
        val byReference = incoming.reconciliation.authenticatedVersions.filter { it.envelope.version != null }
            .groupBy { requireNotNull(it.envelope.version).reference() }
        originalEntries.forEach { entry ->
            val wrapped = requireNotNull(byReference[entry.version.reference()])
            assertTrue(wrapped.isNotEmpty())
            wrapped.forEach { authenticated ->
                assertEquals(next.epoch, authenticated.context.epoch)
                assertEquals(entry.version, authenticated.envelope.version)
                assertEquals(g.genesis.reference(), requireNotNull(authenticated.envelope.version).deviceLedger)
            }
        }
        receiving.apply(requireNotNull(incoming.plan))
        assertEquals("DL1 first offline original", receiver.name("a"))
        assertEquals("DL1 second offline original", receiver.name("b"))
        assertEquals(0L, receiver.author().state().committedSequence)
        val receipted = operations.map { node.receipt(it, peerTarget) }
        node = daily.open(); daily.scan(node)
        assertEquals(operations, node.sourceOperations())
        assertEquals(receipted, operations.map { node.upload(it, g.backend, peerTarget) })
        assertEquals(originalEntries, operations.map { daily.author().entry(it) })
        assertEquals(2L, daily.author().state().committedSequence)
        assertContentEquals(origin, Files.readAllBytes(daily.runtime.resolve("author/origin.protected")))
        originals.forEach(daily.workspace.recovery::verify)
    }

    @Test fun revokedOfflineDailyNodeReceivesItsNoticeAndRefusesBothRetainedOperationsWithoutLosingOriginals() = withGroup { g ->
        g.End(0).enroll()
        val daily = g.End(1); var node = daily.enroll()
        g.End(2).enroll()
        daily.card("a", "revoked DL1 first offline original")
        val first = node.commit(node.inspect().review(setOf("character_cards/a")), operation = SyncStoreId.parse("f".repeat(32)))
        daily.card("b", "revoked DL1 second offline original")
        val second = node.commit(node.inspect().review(setOf("character_cards/b")), operation = SyncStoreId.parse("0".repeat(31) + "1"))
        val operations = listOf(first, second).map { it.entry.request.operation }
        val originals = listOf(first, second).map { requireNotNull(daily.workspace.recovery.read(it.entry.request.sourceCheckpoint)) }
        val authorFiles = retainedFiles(daily.runtime.resolve("author"))
        val sourceFiles = retainedFiles(daily.runtime.resolve("source"))
        val queueFiles = retainedFiles(daily.runtime.resolve("queues"))
        val liveFiles = retainedFiles(daily.data)
        val sourceEntries = daily.workspace.recovery.list()
        val target = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        val next = g.rotate(1)
        val control = g.publishRotation(next, target)
        assertFalse(control.keyDeliveries.keys.any { it.endsWith(":${g.identities[1].fingerprint}") })
        assertEquals(setOf(g.identities[1].fingerprint), control.revocationNotices.keys)
        node = daily.open()
        val revoked = daily.scan(node)
        assertEquals(LanSyncGroupNodeStage.DEVICE_REVOKED, revoked.stage)
        assertTrue(revoked.reconciliation.observations.any { it.stage == SyncInboxStage.DEVICE_REVOKED })
        assertEquals(operations, node.sourceOperations())
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { daily.custody().key(next.epoch) }.message)
        val published = g.backend.listCommits().entries
        var puts = 0
        val remote = object : CiphertextStore by g.backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                puts++; return g.backend.put(request, source)
            }
        }
        for (operation in operations) {
            assertEquals("SYNC_GROUP_SOURCE_AUTHORITY_CHANGED", assertFailsWith<IllegalArgumentException> { node.upload(operation, remote, target) }.message)
            assertNull(node.receipt(operation, target))
        }
        assertEquals(0, puts)
        assertEquals(published, g.backend.listCommits().entries)
        assertRetainedFiles(authorFiles, daily.runtime.resolve("author"))
        assertRetainedFiles(sourceFiles, daily.runtime.resolve("source"))
        assertRetainedFiles(queueFiles, daily.runtime.resolve("queues"))
        assertRetainedFiles(liveFiles, daily.data)
        assertEquals(sourceEntries, daily.workspace.recovery.list())
        originals.forEach(daily.workspace.recovery::verify)
        assertEquals(2L, daily.author().state().committedSequence)
        assertEquals(listOf(first.entry, second.entry), operations.map { daily.author().entry(it) })
        assertEquals(LanSyncGroupNodeStage.DEVICE_REVOKED, daily.open().inspect().stage)
    }

    @Test fun threeIndependentNodesRetainOfflineBranchesPartialDeletionAndRestartedSourceOrder() = withGroup { g ->
        val a = g.End(0); var an = a.enroll(); val b = g.End(1); val bn = b.enroll(); val c = g.End(2); val cn = c.enroll()
        val ua = "character_cards/a"; val ub = "character_cards/b"; val uc = "character_cards/c"
        a.card("a", "A的独立角色"); b.card("b", "B离线新建角色")
        val first = an.commit(an.inspect().review(setOf(ua)), operation = SyncStoreId.parse("f".repeat(32)))
        val offline = bn.commit(bn.inspect().review(setOf(ub)))
        assertNull(an.receipt(first.entry.request.operation, a.target)); assertNull(g.backend.metadata(first.publication.metadata.key))
        a.send(an, first); b.send(bn, offline)
        val incoming = c.scan(cn); assertEquals(setOf(ua, ub), incoming.plan?.advances?.keys)
        cn.apply(requireNotNull(incoming.plan)); assertEquals("A的独立角色", c.name("a")); assertEquals("B离线新建角色", c.name("b"))
        an.apply(requireNotNull(a.scan(an).plan)); bn.apply(requireNotNull(b.scan(bn).plan))
        // Local deletion remains unsigned while a separate addition progresses.
        Files.delete(a.data.resolve("entities/character_cards/a.json")); a.card("c", "A随后独立新增")
        var view = an.inspect(); assertEquals(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, view.plan?.units?.get(ua)?.stage)
        assertFailsWith<IllegalArgumentException> { view.review(setOf(ua)).authorize() }
        val addition = an.commit(view.review(setOf(uc)), operation = SyncStoreId.parse("0".repeat(31) + "1"))
        assertEquals(2L, addition.entry.version.authorSequence); assertEquals(setOf(uc), addition.entry.version.changes.keys)
        a.send(an, addition); a.card("c", "重启前又编辑的原文")
        an = a.open(); view = an.inspect()
        assertEquals(2L, addition.entry.version.authorSequence)
        assertEquals(setOf(addition.entry.version.reference()), view.nativeState.units.getValue(uc).frontiers)
        assertEquals("重启前又编辑的原文", a.name("c")); assertNull(a.name("a"))
        val before = view.nativeState.completedSequence; assertEquals(before, an.inspect().nativeState.completedSequence)
        // B diverges locally; the signed deletion must not overwrite that branch.
        b.card("a", "B保留的离线修改")
        val deletionReview = an.inspect().review(setOf(ua))
        val deletion = an.commit(deletionReview, deletionReview.requiredBindings)
        assertEquals(3L, deletion.entry.version.authorSequence); assertNull(deletion.entry.version.changes.getValue(ua).content)
        a.send(an, deletion)
        val bv = b.scan(bn); assertEquals(LanSyncGroupNativeStage.CONFLICT, bv.plan?.units?.get(ua)?.stage)
        assertEquals(setOf(uc), bv.plan?.advances?.keys); bn.apply(requireNotNull(bv.plan))
        assertEquals("B保留的离线修改", b.name("a")); assertEquals("A随后独立新增", b.name("c"))
        val cv = c.scan(cn); cn.apply(requireNotNull(cv.plan))
        assertEquals("A的独立角色", c.name("a")); assertEquals("A随后独立新增", c.name("c"))
        val deletionPreview = requireNotNull(cn.inspect().incomingReview)
        assertEquals(setOf(ua), deletionPreview.reductions.keys)
        cn.applyIncoming(deletionPreview.acknowledge(deletionPreview.requiredBindings)); assertNull(c.name("a"))
        assertTrue(c.workspace.recovery.list().isNotEmpty()); assertTrue(c.reloads >= 2)
        val repeat = c.open().inspect(); assertTrue(requireNotNull(repeat.plan).advances.isEmpty())
        assertEquals(setOf(deletion.entry.version.reference()), repeat.nativeState.units.getValue(ua).frontiers)
    }

    @Test fun anUnapprovedPreparationSurvivesRestartWithoutAllocatingOrPublishingAnAuthorVersion() = withGroup { g ->
        val end = g.End(0); val node = end.enroll(); end.card("a", "尚未保留批准意图")
        val view = node.inspect(); val operation = SyncStoreId.random()
        end.hook = { if (it == "SOURCE_PREPARATION_RETAINED") throw IOException("SYNTHETIC_SOURCE_PREPARATION_INTERRUPTED") }
        assertFailsWith<IOException> { node.commit(view.review(setOf("character_cards/a")), operation = operation) }
        end.hook = {}; val recovered = end.open().inspect()
        assertEquals(setOf(operation), recovered.unsignedPreparations); assertEquals(0L, recovered.nativeState.completedSequence)
        assertTrue(requireNotNull(recovered.reconciliation.history).admittedVersions().isEmpty())
        assertTrue(g.backend.listCommits().entries.isEmpty()); assertEquals("尚未保留批准意图", end.name("a"))
    }

    @Test fun ordinaryStartupRefusesAMissingAuthorOriginInsteadOfReinitializingIt() = withGroup { g ->
        val end = g.End(0); end.enroll(); end.card("a", "保留原资料")
        val origin = end.runtime.resolve("author/origin.protected"); Files.delete(origin)
        assertEquals("SYNC_AUTHOR_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { end.open().inspect() }.message)
        assertFalse(Files.exists(origin)); assertEquals("保留原资料", end.name("a")); assertFalse(end.gated)
    }
    @Test fun firstEnrollmentCanObserveAnExactlySharedValueWithoutRewritingLocalAuditFieldsOrSigningADuplicate() = withGroup { g ->
        val source = g.End(0); val target = g.End(1); val sn = source.enroll(); val tn = target.enroll()
        val unit = "character_cards/a"; source.card("a", "same shared value"); target.card("a", "same shared value")
        val path = target.data.resolve("entities/$unit.json"); val card = requireNotNull(LanSyncJsonFiles.read(path, CharacterCard.serializer()))
        LanSyncJsonFiles.write(path, CharacterCard.serializer(), card.copy(updatedAt = 987654321))
        val raw = Files.readAllBytes(path)
        val sourceCommit = sn.commit(sn.inspect().review(setOf(unit))); source.send(sn, sourceCommit)
        val incoming = target.scan(tn)
        assertEquals(LanSyncGroupNativeStage.READY_OBSERVATION, incoming.plan?.units?.get(unit)?.stage)
        tn.apply(requireNotNull(incoming.plan))
        assertContentEquals(raw, Files.readAllBytes(path)); assertEquals(0L, target.author().state().committedSequence)
        assertEquals(setOf(sourceCommit.entry.version.reference()), tn.inspect().nativeState.units.getValue(unit).frontiers)
    }

    @Test fun restoringAnOwnAlreadySignedValueSurvivesRestartAsExactlyOneFreshLogicalRevision() = withGroup { g ->
        val end = g.End(0); var node = end.enroll(); val unit = "character_cards/a"
        end.card("a", "已签名的旧原文")
        val original = node.commit(node.inspect().review(setOf(unit)))
        end.card("a", "尚未签名的临时编辑")
        val preview = node.prepareRecovery(original.entry.request.sourceCheckpoint, unit)
        assertTrue(preview.hasChanges)
        val restored = node.applyRecovery(preview)
        assertTrue(restored.applied); assertEquals(setOf(unit), restored.restoredUnits)
        assertEquals("已签名的旧原文", end.name("a"))
        node = end.open(); val fresh = node.inspect()
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, fresh.plan?.units?.get(unit)?.stage)
        assertEquals(setOf(original.entry.version.reference()), fresh.nativeState.units.getValue(unit).frontiers)
        val revision = node.commit(fresh.review(setOf(unit)))
        assertEquals(2L, revision.entry.version.authorSequence)
        assertEquals(original.entry.version.changes.getValue(unit).valueHash, revision.entry.version.changes.getValue(unit).valueHash)
        assertNotEquals(original.entry.version.reference(), revision.entry.version.reference())
        assertEquals(mapOf(unit to setOf(restored.revision)), revision.restorations)
        val completed = end.open().inspect()
        assertEquals(LanSyncGroupNativeStage.UNCHANGED, completed.plan?.units?.get(unit)?.stage)
        // The signed acknowledgment of one exact restore cannot swallow a subsequent restore of that unit.
        end.card("a", "又一次临时编辑")
        val later = node.applyRecovery(node.prepareRecovery(original.entry.request.sourceCheckpoint, unit))
        assertNotEquals(restored.revision, later.revision)
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, end.open().inspect().plan?.units?.get(unit)?.stage)
    }

    @Test fun aCommittedLocalRestoreRetriesOnlyRepositoryRefreshAfterColdOpen() = withGroup { g ->
        val end = g.End(0); val node = end.enroll(); val unit = "conversation/manual"
        val sessions = Files.createDirectories(end.data.resolve("entities/chat_sessions"))
        val messages = Files.createDirectories(end.data.resolve("entities/chat_messages"))
        val photo = Files.createDirectories(end.data.resolve("images")).resolve("manual.png")
        val raster = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        try { assertTrue(ImageIO.write(raster, "png", photo.toFile())) } finally { raster.flush() }
        val photoBytes = Files.readAllBytes(photo)
        val localPhoto = photo.toString()
        val session = ChatSession("manual", "card", "旧标题", createdAt = 1, updatedAt = 1)
        val message = ChatMessage("m", "manual", MessageRole.USER, "原消息", images = listOf(localPhoto), createdAt = 2, updatedAt = 2)
        LanSyncJsonFiles.write(sessions.resolve("manual.json"), ChatSession.serializer(), session)
        LanSyncJsonFiles.write(messages.resolve("manual_m.json"), ChatMessage.serializer(), message)
        val old = node.commit(node.inspect().review(setOf(unit)))
        val nativeBefore = node.inspect().nativeState

        LanSyncJsonFiles.write(sessions.resolve("manual.json"), ChatSession.serializer(), session.copy(title = "同步前仍可编辑"))
        val chats = ChatRepository(JsonFileStorage(end.data))
        assertEquals("同步前仍可编辑", chats.getAllSessions().single().title)
        chats.updateComposerDraft(ChatDraft("manual", "原草稿", updatedAt = 3))
        val draft = end.data.resolve("entities/chat_drafts/manual.json")
        val draftBytes = Files.readAllBytes(draft)
        var attempted = 0
        end.reloadHook = { ids ->
            assertTrue("manual" in ids)
            if (++attempted == 1) throw IOException("SYNTHETIC_RESTORE_FIRST_RELOAD_FAILED")
            chats.reloadAfterLanSync()
        }
        val preview = node.prepareRecovery(old.entry.request.sourceCheckpoint, unit)
        val failure = assertFailsWith<IllegalStateException> { node.applyRecovery(preview) }
        assertEquals("SYNC_GROUP_NATIVE_RELOAD_PENDING", failure.message)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.take(8).any {
            it is IOException && it.message == "SYNTHETIC_RESTORE_FIRST_RELOAD_FAILED"
        })
        assertTrue(end.workspace.verifiedApplied(preview.id))
        val beforeRetry = retainedFiles(end.data)
        assertTrue(end.workspace.completedRecoveries(g.identities[0].fingerprint).single().reloadPending)
        assertEquals("旧标题", chats.getSession("manual")?.title)
        assertEquals("同步前仍可编辑", chats.observeSessions().first().single().title)

        val cold = end.open(workspaceOwner = end.openWorkspace())
        assertEquals(nativeBefore, cold.inspectLocal(recoverNative = false).nativeState)
        assertEquals(1, attempted)
        assertEquals(nativeBefore, cold.inspectLocal().nativeState)
        assertEquals(2, attempted)
        assertEquals("旧标题", chats.observeSessions().first().single().title)
        val updated = chats.getMessages("manual").single()
        assertEquals("原消息", updated.content)
        assertContentEquals(photoBytes, Files.readAllBytes(Path.of(updated.images.single())))
        assertContentEquals(draftBytes, Files.readAllBytes(draft))
        assertEquals("原草稿", chats.getComposerDraft("manual").content)
        assertRetainedFiles(beforeRetry, end.data)
        val merged = end.workspace.completedRecoveries(g.identities[0].fingerprint).single()
        assertEquals(preview.id, merged.revision); assertFalse(merged.reloadPending)
        assertEquals(nativeBefore, end.open().inspectLocal().nativeState)
        assertEquals(2, attempted)
        assertEquals(1L, end.author().state().committedSequence)
    }
}
