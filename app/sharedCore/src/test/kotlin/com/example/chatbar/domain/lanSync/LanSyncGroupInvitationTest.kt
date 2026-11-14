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

    @Test fun aWrongCapabilityCannotConsumeTheCorrectOfferAndSuccessfulCapabilityCannotReplay() = withFixture { t ->
        val offer = t.offer(); val wrong = offer.copy(token = if (offer.token.startsWith('0')) "1".repeat(64) else "0".repeat(64))
        val failed = t.attempt(wrong); assertTrue(failed.first.isFailure && failed.second.isFailure)
        val good = t.attempt(offer); good.first.getOrThrow(); good.second.getOrThrow()
        val replay = t.attempt(offer); assertTrue(replay.first.isFailure && replay.second.isFailure)
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
