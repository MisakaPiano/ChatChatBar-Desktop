package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A short-lived out-of-band capability. It grants only a key-possession exchange, never data access. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class LanSyncGroupJoinBootstrap(val storePort: Int, val vault: SyncManagementVaultContext,
    val vaultDescriptor: CiphertextDescriptor, val dataHeads: Set<SyncVersionRef> = emptySet(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requiresRecoveryProtocol: Boolean = false) {
    fun validate(trust: SyncGroupTrust, invitationPort: Int) {
        require(storePort in 1024..65535 && storePort != invitationPort && vault.group == trust.group &&
            vault.genesis == trust.genesis && vault.owner == SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint &&
            vaultDescriptor.length in 1..272L * 1024 && dataHeads.map { it.id }.toSet().size == dataHeads.size &&
            dataHeads.all { it.bodyHash.matches(Regex("[a-f0-9]{64}")) }) { "SYNC_GROUP_INVITE_BOOTSTRAP" }
    }
}
@Serializable data class LanSyncGroupInviteOffer(val trust: SyncGroupTrust, val host: String, val port: Int,
    val inviter: String, val token: String, val expiresAt: Long, val bootstrap: LanSyncGroupJoinBootstrap? = null) {
    fun validate(now: Long = System.currentTimeMillis()) {
        validateShape()
        validateTime(now)
    }

    private fun validateShape() {
        LanSyncTls.parseAddress(host)
        require(port in 1024..65535 && inviter.matches(HEX) && token.matches(HEX) && expiresAt > 0) {
            "SYNC_GROUP_INVITE_OFFER"
        }
        require(SyncDeviceSignatures.decode(trust.ownerCertificate).encoded == trust.ownerCertificate) { "SYNC_GROUP_INVITE_TRUST" }
        bootstrap?.validate(trust, port)
    }
    private fun validateTime(now: Long) {
        require(now >= 0) { "SYNC_GROUP_INVITE_CLOCK" }
        val remaining = expiresAt - now
        require(remaining >= -WALL_SKEW_MILLIS) { "SYNC_GROUP_INVITE_EXPIRED" }
        require(remaining <= LIFETIME_MILLIS + WALL_SKEW_MILLIS) { "SYNC_GROUP_INVITE_CLOCK" }
    }
    fun code(): String = (if (bootstrap?.requiresRecoveryProtocol == true) RECOVERY_PREFIX else PREFIX) +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            lanSyncJson.encodeToString(LanSyncGroupInviteOffer.serializer(), this).toByteArray(Charsets.UTF_8))
    internal fun binding() = lanSyncHash(code().toByteArray(Charsets.UTF_8))
    override fun toString() = "LanSyncGroupInviteOffer(REDACTED)"
    companion object {
        internal const val LIFETIME_MILLIS = 180_000L
        private const val WALL_SKEW_MILLIS = 30_000L
        internal const val MAX_CODE_CHARS = 16 * 1024
        internal const val MAX_FILE_CHARS = 64 * 1024
        private const val PREFIX = "CCB-GROUP-JOIN1:"
        private const val RECOVERY_PREFIX = "CCB-GROUP-JOIN2:"
        private val VERSION_PREFIX = Regex("^CCB-GROUP-JOIN[0-9]+:")
        private val HEX = Regex("[a-f0-9]{64}")
        fun parse(code: String, now: Long = System.currentTimeMillis()): LanSyncGroupInviteOffer = parseBounded(code, now, MAX_CODE_CHARS)

        internal fun readFile(input: InputStream, now: Long = System.currentTimeMillis()): LanSyncGroupInviteOffer {
            val bytes = ByteArray(MAX_FILE_CHARS + 1)
            try {
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    if (count == 0) throw IOException("SYNC_GROUP_INVITE_FILE_READ_UNAVAILABLE")
                    length += count
                }
                require(length <= MAX_FILE_CHARS) { "SYNC_GROUP_INVITE_FILE_TOO_LARGE" }
                require(length > 0) { "SYNC_GROUP_INVITE_FILE_EMPTY" }
                val document = bytes.copyOf(length)
                try { return parseBounded(lanSyncUtf8(document), now, MAX_FILE_CHARS) }
                finally { document.fill(0) }
            } finally {
                bytes.fill(0)
            }
        }

        private fun parseBounded(code: String, now: Long, maximum: Int): LanSyncGroupInviteOffer {
            val text = code.trim()
            require(text.length <= maximum) { "SYNC_GROUP_INVITE_CODE_TOO_LARGE" }
            val prefix = when {
                text.startsWith(PREFIX) -> PREFIX
                text.startsWith(RECOVERY_PREFIX) -> RECOVERY_PREFIX
                VERSION_PREFIX.containsMatchIn(text) -> error("SYNC_GROUP_INVITE_VERSION")
                else -> error("SYNC_GROUP_INVITE_OFFER")
            }
            val offer = try {
                InviteWire.json.decodeFromString(LanSyncGroupInviteOffer.serializer(),
                    lanSyncUtf8(Base64.getUrlDecoder().decode(text.removePrefix(prefix)))).also { it.validateShape() }
            } catch (_: Exception) { error("SYNC_GROUP_INVITE_OFFER") }
            require((prefix == RECOVERY_PREFIX) == (offer.bootstrap?.requiresRecoveryProtocol == true)) { "SYNC_GROUP_INVITE_VERSION" }
            offer.validateTime(now)
            return offer
        }
    }
}

class LanSyncGroupInvitedPeer internal constructor(val trust: SyncGroupTrust, val device: String,
    val name: String, val member: SyncGroupMember, val binding: String)
data class LanSyncGroupInviteReceipt(val trust: SyncGroupTrust, val inviter: String, val member: SyncGroupMember)

@Serializable private data class InviteHello(val protocol: String, val binding: String, val token: String,
    val name: String, val recipient: String)
@Serializable private data class InviteChallenge(val protocol: String, val binding: String, val ciphertext: String)
@Serializable private data class InviteAnswer(val protocol: String, val response: String)
@Serializable private data class InviteDone(val protocol: String, val binding: String)
private object InviteWire {
    const val PROTOCOL = "CCB_GROUP_JOIN_PROOF_1"
    val json = Json(lanSyncJson) { ignoreUnknownKeys = false }
    fun <T> send(wire: LanSyncWire, serializer: KSerializer<T>, value: T) {
        val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "SYNC_GROUP_INVITE_FRAME" }; wire.send(bytes)
    }
    fun <T> read(wire: LanSyncWire, serializer: KSerializer<T>): T {
        val text = wire.text(64 * 1024)
        return try { json.decodeFromString(serializer, text) }
        catch (_: Exception) { error("SYNC_GROUP_INVITE_FRAME") }
    }
    // HPKE base mode alone authenticates no sender (RFC9180 sections9.1/9.7).
    // Both actual TLS certificates and the externally delivered offer bind this application challenge.
    fun context(offer: LanSyncGroupInviteOffer, device: String, recipient: SyncRecipientPublicKey, name: String): ByteArray {
        val hash = lanSyncHash(buildJsonObject {
            put("offer", offer.binding()); put("device", device); put("recipient", recipient.hash); put("name", name)
        })
        return ("CCB-SYNC-INVITE-KEY-POSSESSION/1\n" + hash).toByteArray(Charsets.US_ASCII)
    }
    fun answer(context: ByteArray, challenge: ByteArray) = lanSyncHash(context + challenge)
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}

/** Dedicated invitation-only TLS role. App owns bounded listener/socket lifetime; ordinary PeerStore stays closed. */
class LanSyncGroupInvitation(private val identity: LanSyncTlsIdentity, private val trust: SyncGroupTrust,
    private val authority: () -> SyncDeviceLedger, private val wallTime: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = System::nanoTime, private val bootstrap: LanSyncGroupJoinBootstrap? = null,
    private val publishedCheckpoint: SyncVersionRef? = null) : AutoCloseable {
    private class Issued(val offer: LanSyncGroupInviteOffer, val started: Long) {
        var claimed = false; var accepted: LanSyncGroupInvitedPeer? = null
    }
    private val lock = Any()
    private var issued: Issued? = null
    private var closed = false
    fun issue(host: String, port: Int, forFile: Boolean = false): LanSyncGroupInviteOffer = synchronized(lock) {
        check(!closed) { "SYNC_GROUP_INVITE_CLOSED" }
        val ledger = ledger()
        require(publishedCheckpoint == null || ledger.current().reference() == publishedCheckpoint) { "SYNC_GROUP_INVITE_EXPIRED_OR_CHANGED" }
        val maximum = if (forFile) LanSyncGroupInviteOffer.MAX_FILE_CHARS else LanSyncGroupInviteOffer.MAX_CODE_CHARS
        val sizeError = if (forFile) "SYNC_GROUP_INVITE_FILE_TOO_LARGE" else "SYNC_GROUP_INVITE_CODE_TOO_LARGE"
        require((bootstrap?.dataHeads?.size ?: 0) <= maximum / 64) { sizeError }
        val recovered = ledger.ancestry().any { it.recovery != null }
        require(!recovered || bootstrap != null) { "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" }
        val frozenBootstrap = bootstrap?.let {
            it.copy(dataHeads = Collections.unmodifiableSet(it.dataHeads.toSet()), requiresRecoveryProtocol = recovered)
        }
        val offer = LanSyncGroupInviteOffer(trust.copy(checkpoint = ledger.current().reference()), host, port, identity.fingerprint,
            ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }, wallTime() + LanSyncGroupInviteOffer.LIFETIME_MILLIS, frozenBootstrap)
        offer.validate(wallTime())
        require(offer.code().length <= maximum) { sizeError }
        issued = Issued(offer, monotonic()); offer
    }
    internal fun fileBytes(expected: LanSyncGroupInviteOffer): ByteArray = synchronized(lock) {
        requireCurrentFileOffer(expected)
        expected.code().toByteArray(Charsets.US_ASCII)
    }

    internal fun requireCurrentFileOffer(expected: LanSyncGroupInviteOffer) = synchronized(lock) {
        live(checkFileOffer(expected))
    }

    /** The state transition is ordered with closing, claiming and timing out this offer. */
    internal fun whileFileOfferOpen(expected: LanSyncGroupInviteOffer, action: () -> Unit) = synchronized(lock) {
        checkFileOffer(expected)
        action()
    }

    private fun checkFileOffer(expected: LanSyncGroupInviteOffer): Issued {
        val current = requireNotNull(issued) { "SYNC_GROUP_INVITE_MISSING" }
        require(!closed && current.offer === expected && !current.claimed && withinLifetime(current)) {
            "SYNC_GROUP_INVITE_EXPIRED_OR_CHANGED"
        }
        return current
    }

    /** Never reuse this context for ordinary group-store or legacy sync commands. */
    fun invitationContext(): SSLContext = identity.context { synchronized(lock) {
        val current = issued
        current != null && !current.claimed && runCatching { live(current) }.isSuccess
    } }
    private fun ledger(): SyncDeviceLedger = authority().also { ledger ->
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate && ledger.contains(trust.checkpoint) &&
            ledger.current().members[identity.fingerprint]?.let { it.active && it.certificate == Base64.getEncoder().encodeToString(identity.certificate.encoded) } == true) {
            "SYNC_GROUP_INVITE_AUTHORITY"
        }
    }
    private fun withinLifetime(current: Issued): Boolean =
        monotonic() - current.started in 0L until LanSyncGroupInviteOffer.LIFETIME_MILLIS * 1_000_000

    private fun live(current: Issued) {
        require(!closed && issued === current && withinLifetime(current) &&
            ledger().current().reference() == current.offer.trust.checkpoint) { "SYNC_GROUP_INVITE_EXPIRED_OR_CHANGED" }
    }
    /** The TLS session supplies the signing certificate; the request supplies no claimed device ID/certificate. */
    fun serve(socket: SSLSocket): LanSyncGroupInvitedPeer {
        var verified: LanSyncGroupInvitedPeer? = null
        return try { exchange(socket) { verified = it } }
        catch (error: IOException) { verified ?: throw error }
    }
    private fun exchange(socket: SSLSocket, onVerified: (LanSyncGroupInvitedPeer) -> Unit): LanSyncGroupInvitedPeer = socket.use {
        LanSyncTls.configure(socket); socket.soTimeout = 15_000; socket.startHandshake()
        require(socket.session.localCertificates?.single()?.encoded?.let { lanSyncHash(it) } == identity.fingerprint) { "SYNC_GROUP_INVITE_LOCAL_TLS" }
        val certificate = Base64.getEncoder().encodeToString(socket.session.peerCertificates.single().encoded)
        val actor = SyncDeviceSignatures.decode(certificate)
        LanSyncWire(socket).use { wire ->
            val hello = InviteWire.read(wire, InviteHello.serializer())
            require(hello.protocol == InviteWire.PROTOCOL && hello.name.isNotBlank() && hello.name.length <= 160 &&
                hello.token.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_INVITE_REQUEST" }
            val recipient = try { SyncRecipientPublicKey.parse(hello.recipient) } catch (_: Exception) { error("SYNC_GROUP_INVITE_RECIPIENT") }
            require(recipient.encoded == hello.recipient) { "SYNC_GROUP_INVITE_RECIPIENT" }
            val current = synchronized(lock) {
                val current = requireNotNull(issued) { "SYNC_GROUP_INVITE_MISSING" }; live(current)
                require(!current.claimed && InviteWire.same(hello.token, current.offer.token) && hello.binding == current.offer.binding()) { "SYNC_GROUP_INVITE_CAPABILITY" }
                require(actor.fingerprint !in ledger().current().members) { "SYNC_GROUP_INVITE_DEVICE_ALREADY_KNOWN" }
                require(ledger().current().members.values.none { it.recipientKey == recipient.encoded }) { "SYNC_GROUP_INVITE_RECIPIENT_ALREADY_KNOWN" }
                current.claimed = true; current
            }
            try {
                val context = InviteWire.context(current.offer, actor.fingerprint, recipient, hello.name)
                val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
                try {
                    InviteWire.send(wire, InviteChallenge.serializer(), InviteChallenge(InviteWire.PROTOCOL, current.offer.binding(),
                        Base64.getEncoder().encodeToString(recipient.encrypt(challenge, context))))
                    val answer = InviteWire.read(wire, InviteAnswer.serializer())
                    require(answer.protocol == InviteWire.PROTOCOL && answer.response.matches(Regex("[a-f0-9]{64}")) &&
                        InviteWire.same(answer.response, InviteWire.answer(context, challenge))) { "SYNC_GROUP_INVITE_KEY_PROOF" }
                    val peer = LanSyncGroupInvitedPeer(current.offer.trust, actor.fingerprint, hello.name,
                        SyncGroupMember(certificate, recipient.encoded), lanSyncHash(context))
                    synchronized(lock) { live(current); current.accepted = peer }
                    // Confirmation or socket cleanup may fail after the single verified proof was retained.
                    onVerified(peer)
                    InviteWire.send(wire, InviteDone.serializer(), InviteDone(InviteWire.PROTOCOL, current.offer.binding()))
                    peer
                } finally { challenge.fill(0) }
            } finally {
                synchronized(lock) {
                    if (issued === current && current.accepted == null) current.claimed = false
                }
            }
        }
    }
    /** Call inside the explicit root-ledger admission; possession alone does not add a member. */
    fun requireCurrent(peer: LanSyncGroupInvitedPeer) = synchronized(lock) {
        val current = requireNotNull(issued) { "SYNC_GROUP_INVITE_MISSING" }; live(current)
        require(current.accepted === peer && peer.trust == current.offer.trust) { "SYNC_GROUP_INVITE_PROOF_CHANGED" }
    }

    /** The verified proof is available before the acknowledgement can be received by the joiner. */
    internal fun verifiedPendingDevice(): String? = synchronized(lock) {
        val current = issued ?: return@synchronized null
        val peer = current.accepted ?: return@synchronized null
        if (runCatching { live(current) }.isSuccess) peer.device else null
    }

    override fun close() = synchronized(lock) { closed = true; issued = null }
}

object LanSyncGroupInviteClient {
    /** Production connect always uses the numeric LAN address and exact externally delivered TLS pin. */
    fun exchange(offer: LanSyncGroupInviteOffer, identity: LanSyncTlsIdentity, recipient: SyncDeviceEncryptionKey,
        name: String): LanSyncGroupInviteReceipt = exchange(offer, identity, recipient, name,
            { LanSyncTls.connect(identity, offer.host, offer.port, offer.inviter, handshakeTimeoutMillis = 15_000) })
    /** The dial returns an authenticated socket from the same LanSyncTls.connect path as production. */
    internal fun exchange(offer: LanSyncGroupInviteOffer, identity: LanSyncTlsIdentity, recipient: SyncDeviceEncryptionKey,
        name: String, connect: () -> SSLSocket, now: Long = System.currentTimeMillis(),
        beforeAnswer: () -> Unit = {}): LanSyncGroupInviteReceipt {
        offer.validate(now)
        require(recipient.device == identity.fingerprint && name.isNotBlank() && name.length <= 160) { "SYNC_GROUP_INVITE_CLIENT" }
        return connect().use { socket ->
            socket.soTimeout = 15_000
            require(LanSyncTls.peerFingerprint(socket) == offer.inviter && socket.session.localCertificates?.single()?.encoded?.let { lanSyncHash(it) } == identity.fingerprint) {
                "SYNC_GROUP_INVITE_TLS_PIN"
            }
            LanSyncWire(socket).use { wire ->
                InviteWire.send(wire, InviteHello.serializer(), InviteHello(InviteWire.PROTOCOL, offer.binding(), offer.token, name, recipient.publicKey.encoded))
                val challenge = InviteWire.read(wire, InviteChallenge.serializer())
                require(challenge.protocol == InviteWire.PROTOCOL && challenge.binding == offer.binding() && challenge.ciphertext.length <= 64 * 1024) { "SYNC_GROUP_INVITE_CHALLENGE" }
                val context = InviteWire.context(offer, identity.fingerprint, recipient.publicKey, name)
                val plain = try { recipient.decrypt(Base64.getDecoder().decode(challenge.ciphertext), context) } catch (_: Exception) { error("SYNC_GROUP_INVITE_KEY_PROOF") }
                try {
                    require(plain.size == 32) { "SYNC_GROUP_INVITE_KEY_PROOF" }
                    beforeAnswer()
                    InviteWire.send(wire, InviteAnswer.serializer(), InviteAnswer(InviteWire.PROTOCOL, InviteWire.answer(context, plain)))
                } finally { plain.fill(0) }
                val done = InviteWire.read(wire, InviteDone.serializer())
                require(done.protocol == InviteWire.PROTOCOL && done.binding == offer.binding()) { "SYNC_GROUP_INVITE_REPLY" }
                LanSyncGroupInviteReceipt(offer.trust, offer.inviter,
                    SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded))
            }
        }
    }
}
