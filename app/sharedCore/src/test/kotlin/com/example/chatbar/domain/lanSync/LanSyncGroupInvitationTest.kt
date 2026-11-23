package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual mutual TLS and HPKE on isolated loopback sockets; no membership/profile/data activation. */
class LanSyncGroupInvitationTest {
    private class Fixture(val root: Path) : AutoCloseable {
        val f = LanSyncGroupPublicationQueueTest.Fixture(root)
        val joiner = LanSyncTlsIdentity.create(); val recipient = SyncDeviceEncryptionKey.create(joiner.fingerprint)
        var wall = System.currentTimeMillis(); var nanos = 100L
        val owner = LanSyncGroupInvitation(f.owner, f.ledger.trustAnchor, { f.ledger }, { wall }, { nanos })
        val listener = (owner.invitationContext().serverSocketFactory.createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .also { it.needClientAuth = true }
        val executor = Executors.newSingleThreadExecutor()
        fun offer() = owner.issue("10.0.0.1", listener.localPort)
        fun connect(identity: LanSyncTlsIdentity = joiner) = LanSyncTls.connect(identity, "10.0.0.1", listener.localPort, f.owner.fingerprint, 15_000) { _, _ ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        }
        fun attempt(offer: LanSyncGroupInviteOffer, identity: LanSyncTlsIdentity = joiner,
            key: SyncDeviceEncryptionKey = recipient): Pair<Result<LanSyncGroupInvitedPeer>, Result<LanSyncGroupInviteReceipt>> {
            val server = executor.submit<LanSyncGroupInvitedPeer> { owner.serve(listener.accept() as SSLSocket) }
            val client = runCatching { LanSyncGroupInviteClient.exchange(offer, identity, key, "合成手机", { connect(identity) }, wall) }
            return runCatching { server.get(20, TimeUnit.SECONDS) } to client
        }
        fun malformed(offer: LanSyncGroupInviteOffer, recipient: String, response: String? = null,
            extra: Pair<String, JsonElement>? = null): Result<LanSyncGroupInvitedPeer> {
            val server = executor.submit<LanSyncGroupInvitedPeer> { owner.serve(listener.accept() as SSLSocket) }
            runCatching { connect().use { socket ->
                LanSyncWire(socket).use { wire ->
                    val body = buildJsonObject {
                        put("protocol", "CCB_GROUP_JOIN_PROOF_1"); put("binding", offer.binding()); put("token", offer.token)
                        put("name", "合成手机"); put("recipient", recipient); extra?.let { put(it.first, it.second) }
                    }
                    wire.send(body.toString().toByteArray())
                    if (response != null) {
                        wire.text(64 * 1024)
                        wire.send(buildJsonObject { put("protocol", "CCB_GROUP_JOIN_PROOF_1"); put("response", response) }.toString().toByteArray())
                        runCatching { wire.text(64 * 1024) }
                    }
                }
            } }
            return runCatching { server.get(20, TimeUnit.SECONDS) }
        }
        override fun close() { owner.close(); listener.close(); executor.shutdownNow(); check(executor.awaitTermination(25, TimeUnit.SECONDS)) }
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-tls-group-invite-").toRealPath()
        try { Fixture(root).use(work) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun realTlsCertificateAndRecipientPossessionBindAnUnadmittedPeerWithoutGrantingAnyDataAccess() = withFixture { t ->
        val checkpoint = t.f.ledger.current().reference(); val offer = t.offer()
        val (server, client) = t.attempt(offer); val peer = server.getOrThrow(); val receipt = client.getOrThrow()
        t.owner.requireCurrent(peer)
        assertEquals(t.joiner.fingerprint, peer.device); assertEquals(t.recipient.publicKey.encoded, peer.member.recipientKey)
        assertEquals(Base64.getEncoder().encodeToString(t.joiner.certificate.encoded), peer.member.certificate)
        assertEquals(offer.trust, receipt.trust); assertEquals(peer.member, receipt.member)
        assertEquals(checkpoint, t.f.ledger.current().reference()); assertFalse(peer.device in t.f.ledger.current().members)
        val store = LanSyncGroupPeerStore(t.f.ledger.trustAnchor, Base64.getEncoder().encodeToString(t.f.owner.certificate.encoded),
            peer.member.certificate, { t.f.ledger }, t.f.backend, { null })
        assertFails { store.listCommits(null, 1) }
        val forged = LanSyncGroupInvitedPeer(peer.trust, peer.device, peer.name, peer.member, peer.binding)
        assertFailsWith<IllegalArgumentException> { t.owner.requireCurrent(forged) }
    }

    @Test fun onlyRecoveryBootstrapUsesV2AndItsCapabilityCannotBeRelabeledAsV1() = withFixture { t ->
        val basic = t.offer()
        val trust = basic.trust
        val vault = SyncManagementVaultContext(1, trust.group, SyncStoreId.random(), trust.genesis, t.f.owner.fingerprint, "1".repeat(64))
        val descriptor = CiphertextDescriptor(1, CiphertextSha256.parse("2".repeat(64)))
        val bootstrap = LanSyncGroupJoinBootstrap(if (basic.port == 16555) 16556 else 16555, vault, descriptor)
        val v1 = basic.copy(bootstrap = bootstrap).code()
        val v2 = basic.copy(bootstrap = bootstrap.copy(requiresRecoveryProtocol = true)).code()
        assertTrue(v1.startsWith("CCB-GROUP-JOIN1:"))
        assertTrue(v2.startsWith("CCB-GROUP-JOIN2:"))
        assertFalse("requiresRecoveryProtocol" in Base64.getUrlDecoder().decode(v1.substringAfter(':')).toString(Charsets.UTF_8))
        assertEquals(bootstrap, LanSyncGroupInviteOffer.parse(v1, t.wall).bootstrap)
        assertTrue(requireNotNull(LanSyncGroupInviteOffer.parse(v2, t.wall).bootstrap).requiresRecoveryProtocol)
        assertEquals(v2, LanSyncGroupInviteOffer.readFile(v2.toByteArray().inputStream(), t.wall).code())
        for (relabeled in listOf(v1.replace("JOIN1:", "JOIN2:"), v2.replace("JOIN2:", "JOIN1:"))) {
            assertEquals("SYNC_GROUP_INVITE_VERSION", assertFailsWith<IllegalArgumentException> {
                LanSyncGroupInviteOffer.parse(relabeled, t.wall)
            }.message)
        }
    }

    @Test fun aWrongCapabilityCannotConsumeTheCorrectOfferAndSuccessfulCapabilityCannotReplay() = withFixture { t ->
        val offer = t.offer(); val wrong = offer.copy(token = if (offer.token.startsWith('0')) "1".repeat(64) else "0".repeat(64))
        val failed = t.attempt(wrong); assertTrue(failed.first.isFailure && failed.second.isFailure)
        val good = t.attempt(offer); good.first.getOrThrow(); good.second.getOrThrow()
        val replay = t.attempt(offer); assertTrue(replay.first.isFailure && replay.second.isFailure)
    }

    @Test fun validHelloWithoutAnAnswerDoesNotConsumeTheSameUnexpiredOffer() = withFixture { t ->
        val offer = t.offer()
        assertTrue(t.malformed(offer, t.recipient.publicKey.encoded).isFailure)
        val accepted = t.attempt(offer)
        assertEquals(t.joiner.fingerprint, accepted.first.getOrThrow().device)
        accepted.second.getOrThrow()
        assertTrue(t.attempt(offer).second.isFailure)
    }

    @Test fun receiverClockToleranceNeverExtendsMonotonicOfferLifetimeAndWhitespaceIsAllowed() = withFixture { t ->
        val offer = t.offer()
        assertEquals(offer, LanSyncGroupInviteOffer.parse(" \r\n${offer.code()}\t ", t.wall - 20_000))
        assertEquals(offer, LanSyncGroupInviteOffer.parse(offer.code(), offer.expiresAt + 20_000))
        assertEquals("SYNC_GROUP_INVITE_CLOCK", assertFailsWith<IllegalArgumentException> {
            LanSyncGroupInviteOffer.parse(offer.code(), t.wall - 31_000)
        }.message)
        assertEquals("SYNC_GROUP_INVITE_EXPIRED", assertFailsWith<IllegalArgumentException> {
            LanSyncGroupInviteOffer.parse(offer.code(), offer.expiresAt + 31_000)
        }.message)
        t.wall += 181_000
        t.nanos += 181_000_000_000L
        val result = t.attempt(offer)
        assertTrue(result.first.isFailure && result.second.isFailure)
    }

    @Test fun senderAndReceiverShareTheV1TextLimitWithoutDiscardingAnyInitialHeads() = withFixture { t ->
        val original = t.offer()
        val trust = original.trust
        val vault = SyncManagementVaultContext(1, trust.group, SyncStoreId.random(), trust.genesis, t.f.owner.fingerprint, "1".repeat(64))
        val base = LanSyncGroupJoinBootstrap(if (original.port == 33000) 33001 else 33000, vault,
            CiphertextDescriptor(100, CiphertextSha256.parse("2".repeat(64))))
        val heads = List(230) { SyncVersionRef(SyncStoreId.random(), "3".repeat(64)) }
        fun bootstrap(size: Int) = base.copy(dataHeads = heads.take(size).toSet())
        val firstOversized = (1..heads.size).first { size ->
            original.copy(bootstrap = bootstrap(size)).code().length > LanSyncGroupInviteOffer.MAX_CODE_CHARS
        }
        fun issuer(size: Int) = LanSyncGroupInvitation(t.f.owner, trust, { t.f.ledger }, { t.wall }, { t.nanos }, bootstrap(size))
        val fitted = issuer(firstOversized - 1).use { it.issue(original.host, original.port) }
        assertEquals(bootstrap(firstOversized - 1).dataHeads, LanSyncGroupInviteOffer.parse(fitted.code(), t.wall).bootstrap?.dataHeads)
        val oversized = original.copy(bootstrap = bootstrap(firstOversized))
        assertEquals("SYNC_GROUP_INVITE_CODE_TOO_LARGE", assertFailsWith<IllegalArgumentException> {
            LanSyncGroupInviteOffer.parse(oversized.code(), t.wall)
        }.message)
        issuer(firstOversized).use { owner ->
            assertEquals("SYNC_GROUP_INVITE_CODE_TOO_LARGE", assertFailsWith<IllegalArgumentException> {
                owner.issue(original.host, original.port)
            }.message)
        }
        assertEquals(firstOversized, oversized.bootstrap!!.dataHeads.size)
    }

    @Test fun anotherRecipientPublicKeyWithoutItsPrivateKeyCannotBecomeAProvedMember() = withFixture { t ->
        val victim = SyncDeviceEncryptionKey.create(LanSyncTlsIdentity.create().fingerprint)
        val result = t.malformed(t.offer(), victim.publicKey.encoded, response = "0".repeat(64))
        assertTrue(result.isFailure); assertFalse(t.joiner.fingerprint in t.f.ledger.current().members)
    }

    @Test fun alreadyKnownDeviceOrRecipientCannotBeReenrolledThroughTheInvitationException() {
        withFixture { t ->
            val result = t.attempt(t.offer(), t.f.sender, t.f.recipients.getValue(t.f.sender.fingerprint))
            assertTrue(result.first.isFailure && result.second.isFailure)
        }
        withFixture { t ->
            assertTrue(t.malformed(t.offer(), t.f.recipients.getValue(t.f.sender.fingerprint).publicKey.encoded).isFailure)
            val valid = t.attempt(t.offer()); valid.first.getOrThrow(); valid.second.getOrThrow()
        }
    }

    @Test fun monotonicExpiryReplacementAndCloseInvalidatePreviouslyVerifiedPeerEvidence() {
        for (action in listOf("expiry", "replace", "close")) withFixture { t ->
            val peer = t.attempt(t.offer()).first.getOrThrow(); t.owner.requireCurrent(peer)
            when (action) { "expiry" -> t.nanos += 181_000_000_000L; "replace" -> t.offer(); else -> t.owner.close() }
            assertFailsWith<IllegalArgumentException> { t.owner.requireCurrent(peer) }
        }
    }

    @Test fun newSignedLedgerCheckpointInvalidatesTheOldProofBeforeRootAdmission() = withFixture { t ->
        val peer = t.attempt(t.offer()).first.getOrThrow()
        val next = t.f.sign(t.f.genesis.copy(id = SyncStoreId.random(), parent = t.f.genesis.reference(), sequence = 2))
        assertEquals(SyncVersionState.ADMITTED, t.f.ledger.offer(next))
        assertFailsWith<IllegalArgumentException> { t.owner.requireCurrent(peer) }
    }

    @Test fun requestCannotSupplyAClaimedCertificateAndMalformedPrivateInputNeverAppearsInErrors() = withFixture { t ->
        val marker = "SYNTHETIC_TOKEN_SHOULD_NOT_APPEAR_IN_ERROR"
        val failure = t.malformed(t.offer(), t.recipient.publicKey.encoded, extra = "certificate" to JsonPrimitive(marker)).exceptionOrNull()
        assertNotNull(failure)
        generateSequence(failure) { it.cause }.forEach { assertFalse(it.message.orEmpty().contains(marker)) }
        val bad = "CCB-GROUP-JOIN1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"token\":\"" + marker).toByteArray())
        val decode = assertFailsWith<IllegalStateException> { LanSyncGroupInviteOffer.parse(bad, t.wall) }
        assertEquals("SYNC_GROUP_INVITE_VERSION", assertFailsWith<IllegalArgumentException> {
            LanSyncGroupInviteOffer.parse("CCB-GROUP-JOIN3:QUJD", t.wall)
        }.message)
        assertEquals("SYNC_GROUP_INVITE_OFFER", decode.message); assertNull(decode.cause)
    }

    @Test fun publicOfferUsesOnlyNumericLanTargetsAndClientMustOwnTheMatchingRecipientIdentity() = withFixture { t ->
        val offer = t.offer(); assertEquals(offer, LanSyncGroupInviteOffer.parse(offer.code(), t.wall))
        assertFalse(offer.toString().contains(offer.token))
        for (host in listOf("127.0.0.1", "example.com", "https://10.0.0.1", "8.8.8.8"))
            assertFailsWith<IllegalArgumentException> { offer.copy(host = host).validate(t.wall) }
        val other = SyncDeviceEncryptionKey.create(LanSyncTlsIdentity.create().fingerprint)
        assertFailsWith<IllegalArgumentException> { LanSyncGroupInviteClient.exchange(offer, t.joiner, other, "合成手机", { error("CONNECT_MUST_NOT_RUN") }, t.wall) }
    }
}
