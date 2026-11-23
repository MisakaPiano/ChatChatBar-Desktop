package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Real UDP responder and mutual TLS, with loopback dials and synthetic protected profiles. */
class LanSyncGroupLedgerCatchUpTest {
    internal class LoopbackLan : LanSyncNetwork {
        private val ports = ConcurrentHashMap<String, Int>()
        val hints = AtomicInteger()
        val controlDials = AtomicInteger()
        @Volatile var probeMember: String? = null
        override val discoveryEnabled = false
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override fun listening(fingerprint: String, port: Int) { ports[fingerprint] = port }
        fun offline(fingerprint: String) { ports.remove(fingerprint) }
        fun peer(fingerprint: String, port: Int) = LanSyncPeer(fingerprint, "synthetic", "10.0.0.1", port)
        override fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String) =
            ports[fingerprint]?.let { peer(fingerprint, it) }
        override fun discoverGroupLedger(group: SyncStoreId, local: String, known: Set<String>): List<LanSyncPeer> {
            if (local != probeMember) return emptyList()
            val hint = query(group, local) ?: return emptyList()
            if (hint.device in known || ports[hint.device] != hint.port) return emptyList()
            hints.incrementAndGet()
            return listOf(peer(hint.device, hint.port))
        }
        fun query(group: SyncStoreId, requester: String): LanSyncGroupDiscoveryProtocol.Reply? {
            val nonce = "f".repeat(64)
            val frame = LanSyncGroupDiscoveryProtocol.ledgerRequest(group, requester, nonce)
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { socket ->
                socket.soTimeout = 1_000
                socket.send(DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), LanSyncGroupDiscovery.PORT))
                val response = DatagramPacket(ByteArray(LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1), LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1)
                try { socket.receive(response) } catch (_: SocketTimeoutException) { return null }
                assertTrue(response.address.isLoopbackAddress)
                assertNull(LanSyncGroupDiscoveryProtocol.reply(response.data, response.offset, response.length))
                return LanSyncGroupDiscoveryProtocol.ledgerReply(response.data, response.offset, response.length)
                    ?.also { assertEquals(group, it.group); assertEquals(nonce, it.nonce) }
            }
        }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 5_000) { _, port ->
                Socket(InetAddress.getLoopbackAddress(), port)
            }
        override fun connectLedgerOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket {
            controlDials.incrementAndGet()
            return LanSyncTls.connectOwnedWithDial(identity, peer.host, peer.port, peer.fingerprint, 5_000, own) { socket, _, port ->
                socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
            }
        }
    }

    internal class Member(val root: Path, val identity: LanSyncTlsIdentity, network: LoopbackLan) {
        val profile = Files.createDirectories(root.resolve("profile"))
        private val data = Files.createDirectories(root.resolve("data"))
        private val gate = LanSyncOperationGate()
        private val storage = JsonFileStorage(data, gate)
        val chats = ChatRepository(storage)
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.exclusive { work() }
            override suspend fun reload(sessionIds: Set<String>) = chats.reloadAfterLanSync()
        }
        private val workspace = LanSyncWorkspace(data, Files.createDirectories(root.resolve("workspace")), { Path.of(it) })
        val joining = LanSyncGroupJoining(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        private val enrollment = LanSyncGroupEnrollment(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val coordinator = LanSyncGroupCoordinator({ Files.isRegularFile(profile.resolve("joining/profile.protected")) },
            { enrollment }, { joining }, network, "synthetic member", sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))
        private var closed = false
        suspend fun access() = requireNotNull(joining.runtimeAccess())
        suspend fun addConversation(id: String, color: Int): ByteArray {
            val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).also { it.setRGB(1, 1, color) }
            val png = ByteArrayOutputStream().also { check(ImageIO.write(bitmap, "png", it)) }.toByteArray()
            val path = data.resolve("images/$id.png")
            Files.createDirectories(path.parent)
            Files.write(path, png)
            chats.createSession(ChatSession(id, "relay-card", "synthetic $id", createdAt = 10, updatedAt = 10))
            chats.addMessage(ChatMessage("$id-message", id, MessageRole.USER, "synthetic body $id",
                images = listOf(path.toString()), createdAt = 11, updatedAt = 11))
            return png
        }
        suspend fun verifyConversation(id: String, png: ByteArray) {
            assertNotNull(chats.getSession(id))
            val message = chats.getMessages(id).single()
            assertEquals("$id-message", message.id)
            assertEquals("synthetic body $id", message.content)
            assertContentEquals(png, Files.readAllBytes(Path.of(message.images.single())))
        }
        suspend fun close() {
            if (closed) return
            closed = true
            coordinator.closeAndDrain()
            enrollment.close()
        }
    }

    private suspend fun LanSyncGroupAdministrationTest.Fixture.join(member: Member, transport: LanSyncGroupAppTransport, network: LoopbackLan) {
        val invitation = service.invitation(transport.start()).also(invitations::add)
        val proof = CompletableDeferred<LanSyncGroupInvitedPeer>()
        val offer = transport.invitation(invitation) { proof.complete(it) }
        member.joining.prepare(offer.code()) {}
        member.joining.proveUsing("synthetic member", network)
        val preview = service.prepareMember(withTimeout(15_000) { proof.await() }, invitation)
        service.addMember(service.resume(code), preview.acknowledge(preview.request.approvalBinding))
        val joined = member.joining.activate(member.joining.bootstrapStore(network))
        assertEquals(LanSyncGroupJoinStage.READY, joined.profile.stage, joined.waitingCode)
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(60_000) {
        while (!condition()) delay(40)
    }

    @Test fun offlineFounderCatchesUpStaleMemberOverRealTlsAndContinuesBothDirections() = runBlocking {
        val root = Files.createTempDirectory("synthetic-membership-relay").toRealPath()
        val members = mutableListOf<Member>()
        val network = LoopbackLan()
        try {
            val founder = LanSyncGroupAdministrationTest.Fixture(Files.createDirectory(root.resolve("founder")))
            val b = Member(root.resolve("b"), LanSyncTlsIdentity.create(), network).also(members::add)
            val c = Member(root.resolve("c"), LanSyncTlsIdentity.create(), network).also(members::add)
            val (stale, admitted, group) = founder.use { a ->
                a.seed()
                val card = a.data.resolve("entities/character_cards/relay-card.json")
                Files.createDirectories(card.parent)
                LanSyncJsonFiles.write(card, CharacterCard.serializer(),
                    CharacterCard("relay-card", "synthetic relay card", createdAt = 1, updatedAt = 1))
                val source = a.node.inspect()
                val operation = a.node.commit(source.review(setOf("character_cards/relay-card"))).entry.request.operation
                a.node.upload(operation, FileCiphertextStore(a.profile.resolve("local-replica")), requireNotNull(a.service.profile()).localReplica)
                val transport = LanSyncGroupAppTransport(requireNotNull(a.service.runtimeAccess()), network) { _, _ -> }
                val port = transport.start()
                val checkpoints = try {
                    a.join(b, transport, network)
                    val bLedger = b.access().ledger().current()
                    a.join(c, transport, network)
                    val cLedger = c.access().ledger().current()
                    assertNotEquals(bLedger.reference(), cLedger.reference())
                    assertEquals(bLedger.reference(), b.access().ledger().current().reference())
                    assertFalse(c.identity.fingerprint in b.access().ledger().current().members)
                    assertEquals(cLedger.epoch, bLedger.epoch)
                    Triple(bLedger.reference(), cLedger.reference(), cLedger.group)
                } finally { transport.closeAndDrain() }
                assertFails { Socket(InetAddress.getLoopbackAddress(), port).close() }
                checkpoints
            }

            val staleTransport = LanSyncGroupAppTransport(b.access(), network) { _, _ -> }
            val newTransport = LanSyncGroupAppTransport(c.access(), network) { _, _ -> }
            try {
                val stalePeer = network.peer(b.identity.fingerprint, staleTransport.start())
                val admittedPeer = network.peer(c.identity.fingerprint, newTransport.start())
                assertFails { newTransport.remote(stalePeer).listCommits(null) }
                assertFailsWith<IllegalArgumentException> { staleTransport.remote(admittedPeer).listCommits(null) }
                val stranger = LanSyncTlsIdentity.create()
                assertFails {
                    network.connect(stranger, admittedPeer).use { receiveGroupLedger(it, group, stale) }
                }
                assertFails {
                    network.connect(b.identity, admittedPeer).use {
                        receiveGroupLedger(it, group, stale.copy(bodyHash = "0".repeat(64)))
                    }
                }
                assertNull(network.connect(b.identity, admittedPeer).use { receiveGroupLedger(it, group, admitted) })
            } finally { newTransport.closeAndDrain(); staleTransport.closeAndDrain() }
            assertEquals(stale, b.access().ledger().current().reference())

            c.coordinator.startIfConfigured()
            await { network.discoverGroup(group, b.identity.fingerprint, c.identity.fingerprint) != null }
            val cAddress = requireNotNull(network.discoverGroup(group, b.identity.fingerprint, c.identity.fingerprint))
            val responderNetwork = object : LanSyncNetwork by network { override val discoveryEnabled = true }
            val discovery = LanSyncGroupDiscovery(c.access(), responderNetwork) { _, failure -> throw failure }
            try {
                discovery.start(cAddress.port)
                assertNull(network.query(group, LanSyncTlsIdentity.create().fingerprint))
                network.probeMember = b.identity.fingerprint
                b.coordinator.startIfConfigured()
                await { b.coordinator.state.value.devices.any { it.id == c.identity.fingerprint } }
                assertEquals(admitted, b.access().ledger().current().reference())
                assertEquals(1, network.controlDials.get())
                assertTrue(network.hints.get() >= 1)

                val fromB = b.addConversation("from-b", 0xff0000)
                val fromC = c.addConversation("from-c", 0x00ff00)
                assertFalse(fromB.contentEquals(fromC))
                await { c.chats.getMessages("from-b").size == 1 && b.chats.getMessages("from-c").size == 1 }
                c.verifyConversation("from-b", fromB)
                b.verifyConversation("from-c", fromC)
                b.close()
                network.offline(b.identity.fingerprint)
                val reopened = Member(b.root, b.identity, network).also(members::add)
                assertEquals(admitted, reopened.access().ledger().current().reference())
                reopened.verifyConversation("from-c", fromC)
            } finally { discovery.close() }
        } finally {
            members.asReversed().forEach { it.close() }
            check(root.toFile().deleteRecursively())
        }
    }

    @Test fun provisionalLedgerIsAtomicAcrossTamperingWrongIdentityRevocationRollbackAndSignedForks() {
        val root = Files.createTempDirectory("synthetic-membership-evidence").toRealPath()
        try {
            val owner = LanSyncTlsIdentity.create()
            val before = LanSyncTlsIdentity.create()
            val incoming = LanSyncTlsIdentity.create()
            val outsider = LanSyncTlsIdentity.create()
            val recipient = SyncDeviceEncryptionKey.create(before.fingerprint)
            fun certificate(device: LanSyncTlsIdentity) = Base64.getEncoder().encodeToString(device.certificate.encoded)
            fun member(device: LanSyncTlsIdentity, key: SyncDeviceEncryptionKey = SyncDeviceEncryptionKey.create(device.fingerprint)) =
                SyncGroupMember(certificate(device), key.publicKey.encoded)
            fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature =
                Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
            val group = SyncStoreId.random()
            val genesis = sign(SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, 1, SyncStoreId.random(), "a".repeat(64),
                setOf("chat_sessions"), mapOf(owner.fingerprint to member(owner), before.fingerprint to member(before, recipient)),
                emptySet(), "unsigned"))
            val trust = SyncGroupTrust(group, genesis.reference(), certificate(owner))
            fun child(parent: SyncDeviceLedgerRecord, roster: Map<String, SyncGroupMember> = parent.members,
                epoch: SyncStoreId = parent.epoch) = sign(parent.copy(id = SyncStoreId.random(), parent = parent.reference(),
                sequence = parent.sequence + 1, members = roster, epoch = epoch,
                keyCommitment = if (epoch == parent.epoch) parent.keyCommitment else SyncStreamingCipher.create(group, epoch).keyCommitment(),
                signature = "unsigned"))
            val checkpoint = child(genesis)
            val roster = checkpoint.members + (incoming.fingerprint to member(incoming))
            val next = child(checkpoint, roster)
            val revoked = child(next, roster + (incoming.fingerprint to roster.getValue(incoming.fingerprint).copy(active = false)),
                SyncStoreId.random())
            fun fresh(path: Path): SyncGroupCustody = SyncGroupCustody(path, group, recipient, { it.reversedArray() }, { it.reversedArray() })
            fun envelope(tip: SyncDeviceLedgerRecord, vararg suffix: SyncDeviceLedgerRecord) =
                SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = tip, ledgerHistory = suffix.toList())
            val clean = fresh(root)
            clean.enroll(trust, listOf(genesis, checkpoint))
            val original = Files.readAllBytes(root.resolve("group.protected"))
            fun extend(value: SyncGroupEnvelope, source: LanSyncTlsIdentity = incoming): Boolean =
                clean.extendFromPeer(checkpoint.reference(), value, certificate(before), certificate(source))
            assertEquals("SYNC_GROUP_OWNER_SIGNATURE", assertFailsWith<IllegalArgumentException> {
                extend(envelope(next.copy(signature = checkpoint.signature)))
            }.message)
            assertEquals("SYNC_GROUP_LEDGER_RELAY_PEER", assertFailsWith<IllegalArgumentException> {
                extend(envelope(next), outsider)
            }.message)
            assertEquals("SYNC_GROUP_LEDGER_RELAY_PEER", assertFailsWith<IllegalArgumentException> {
                extend(envelope(revoked, next))
            }.message)
            assertEquals("SYNC_GROUP_LEDGER_RELAY_ANCESTRY", assertFailsWith<IllegalArgumentException> {
                extend(envelope(genesis))
            }.message)
            assertEquals("SYNC_GROUP_LEDGER_RELAY_CONTEXT", assertFailsWith<IllegalArgumentException> {
                extend(envelope(next.copy(group = SyncStoreId.random())))
            }.message)
            val fork = child(genesis, roster)
            assertEquals(SyncVersionState.ADMITTED, SyncDeviceLedger(trust).also { it.offer(genesis) }.offer(fork))
            assertEquals("SYNC_GROUP_LEDGER_RELAY_ANCESTRY", assertFailsWith<IllegalArgumentException> {
                extend(envelope(fork))
            }.message)
            assertContentEquals(original, Files.readAllBytes(root.resolve("group.protected")))
            assertEquals(checkpoint.reference(), fresh(root).ledger().current().reference())
            assertTrue(extend(envelope(next)))
            assertFalse(extend(envelope(next)))
            assertEquals(next.reference(), fresh(root).ledger().current().reference())

            val pendingRoot = Files.createDirectory(root.resolve("pending-fork"))
            val pending = fresh(pendingRoot)
            pending.enroll(trust, listOf(genesis, checkpoint))
            pending.update(listOf(child(next), child(next))) // Two owner-signed heads waiting for the same missing parent.
            val retained = Files.readAllBytes(pendingRoot.resolve("group.protected"))
            assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> {
                pending.extendFromPeer(checkpoint.reference(), envelope(next), certificate(before), certificate(incoming))
            }.message)
            assertContentEquals(retained, Files.readAllBytes(pendingRoot.resolve("group.protected")))
            assertEquals(checkpoint.reference(), fresh(pendingRoot).ledger().current().reference())
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
