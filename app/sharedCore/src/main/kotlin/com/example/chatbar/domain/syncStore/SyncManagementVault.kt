package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.lanSyncJson
import com.example.chatbar.domain.lanSync.lanSyncUtf8
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable data class SyncManagementVaultContext(val format: Int, val group: SyncStoreId, val objectId: SyncStoreId,
    val genesis: SyncVersionRef, val owner: String, val recipientKey: String) {
    init { require(format == 1 && owner.matches(Regex("[a-f0-9]{64}")) && recipientKey.matches(Regex("[a-f0-9]{64}"))) { "SYNC_MANAGEMENT_CONTEXT" } }
    val key get() = CiphertextObjectKey(CiphertextObjectKind.BLOB, objectId)
    fun bytes(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
        it.writeUTF("CCB-SYNC-MANAGEMENT-VAULT/1"); it.writeUTF(group.value); it.writeUTF(objectId.value)
        it.writeUTF(genesis.id.value); it.writeUTF(genesis.bodyHash); it.writeUTF(owner); it.writeUTF(recipientKey)
    } }.toByteArray()
}
class SyncManagementVaultBlob internal constructor(val context: SyncManagementVaultContext, private val value: ByteArray) {
    val key get() = context.key
    fun bytes() = value.copyOf()
    override fun toString() = "SyncManagementVaultBlob(encrypted)"
}

/** Random-code-only AES256-GCM vault; no daily identity/account protector or plaintext export branch. */
class SyncManagementSession private constructor(private var identity: LanSyncTlsIdentity?, private var recipient: SyncDeviceEncryptionKey?,
    private var keyset: ByteArray?, private var aead: Aead?, private var context: SyncManagementVaultContext?,
    private val started: Long, private val lifetimeNanos: Long, private val now: () -> Long) : AutoCloseable {
    private var sealed: SyncManagementVaultBlob? = null
    @Serializable private data class Code(val format: Int, val context: SyncManagementVaultContext, val keyset: String)
    @Serializable private data class Material(val format: Int, val identity: String, val recipient: String)
    val ownerCertificate: String get() = synchronized(this) { checkOpen(); Base64.getEncoder().encodeToString(requireNotNull(identity).certificate.encoded) }
    val recipientPublicKey: SyncRecipientPublicKey get() = synchronized(this) { checkOpen(); requireNotNull(recipient).publicKey }
    val isUnlocked: Boolean get() = synchronized(this) {
        if (identity != null && now() - started >= lifetimeNanos) closeOwned()
        identity != null
    }
    val trust: SyncGroupTrust get() = synchronized(this) {
        checkOpen(); val bound = requireNotNull(context) { "SYNC_MANAGEMENT_GROUP_NOT_BOUND" }
        SyncGroupTrust(bound.group, bound.genesis, Base64.getEncoder().encodeToString(requireNotNull(identity).certificate.encoded))
    }
    fun signRootPayload(payload: ByteArray): ByteArray = synchronized(this) {
        checkOpen()
        require(payload.size <= 128) { "SYNC_MANAGEMENT_SIGNATURE_DOMAIN" }
        val frozen = payload.copyOf()
        require(String(frozen, Charsets.US_ASCII).matches(
            Regex("CCB-SYNC-(DEVICE-LEDGER|KEY-DELIVERY|LEDGER-NOTICE)-SIGNATURE/1\n[a-f0-9]{64}"))) { "SYNC_MANAGEMENT_SIGNATURE_DOMAIN" }
        requireNotNull(identity).signSyncGroupPayload(frozen)
    }

    /** Bind only a new matching fixed-owner group; no existing owner pin is rewritten. */
    fun seal(trust: SyncGroupTrust): SyncManagementVaultBlob = synchronized(this) {
        checkOpen(); val owner = requireNotNull(identity); val recipient = requireNotNull(recipient)
        require(trust.ownerCertificate == Base64.getEncoder().encodeToString(owner.certificate.encoded)) { "SYNC_MANAGEMENT_OWNER_CHANGED" }
        val bound = context ?: SyncManagementVaultContext(1, trust.group, SyncStoreId.random(), trust.genesis, owner.fingerprint, recipient.publicKey.hash)
        require(bound.group == trust.group && bound.genesis == trust.genesis) { "SYNC_MANAGEMENT_GROUP_CHANGED" }
        sealed?.let { return@synchronized it }
        val identityBytes = owner.encode(); val recipientBytes = recipient.protectedKeyset { it.copyOf() }
        try {
            val material = Material(1, Base64.getEncoder().encodeToString(identityBytes), Base64.getEncoder().encodeToString(recipientBytes))
            val plain = lanSyncJson.encodeToString(Material.serializer(), material).toByteArray(Charsets.UTF_8)
            val encrypted = try { require(plain.size in 1..MAX_PLAIN) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
                requireNotNull(aead).encrypt(plain, bound.bytes()) } finally { plain.fill(0) }
            context = bound
            SyncManagementVaultBlob(bound, encode(bound, encrypted)).also { sealed = it }
        } finally { identityBytes.fill(0); recipientBytes.fill(0) }
    }
    /** Explicit one-session UI export. This secret must not be persisted with daily profiles/credentials. */
    fun recoveryCode(): String = synchronized(this) {
        checkOpen(); val bound = requireNotNull(context) { "SYNC_MANAGEMENT_GROUP_NOT_BOUND" }
        val code = Code(1, bound, Base64.getUrlEncoder().withoutPadding().encodeToString(requireNotNull(keyset)))
        val bytes = lanSyncJson.encodeToString(Code.serializer(), code).toByteArray(Charsets.UTF_8)
        try { PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) } finally { bytes.fill(0) }
    }
    fun protectJournal(bytes: ByteArray): ByteArray = synchronized(this) {
        checkOpen(); require(bytes.size in 1..MAX_JOURNAL) { "SYNC_MANAGEMENT_JOURNAL_BUDGET" }
        requireNotNull(aead).encrypt(bytes, journalContext())
    }
    fun unprotectJournal(bytes: ByteArray): ByteArray = synchronized(this) {
        checkOpen(); require(bytes.size in 1..MAX_JOURNAL + 1024) { "SYNC_MANAGEMENT_JOURNAL_BUDGET" }
        requireNotNull(aead).decrypt(bytes, journalContext()).also {
            if (it.size !in 1..MAX_JOURNAL) { it.fill(0); error("SYNC_MANAGEMENT_JOURNAL_BUDGET") }
        }
    }
    // Available before genesis exists, so first-root intent/key custody uses this same code-only key.
    // The individual protected owners additionally bind their group/genesis/record domain internally.
    private fun journalContext() = ("CCB-SYNC-MANAGEMENT-JOURNAL/1\n" + requireNotNull(identity).fingerprint + "\n" +
        requireNotNull(recipient).publicKey.hash).toByteArray(Charsets.US_ASCII)
    private fun checkOpen() {
        if (identity != null && now() - started >= lifetimeNanos) closeOwned()
        check(identity != null && recipient != null && aead != null && keyset != null) { "SYNC_MANAGEMENT_SESSION_LOCKED" }
    }
    private fun closeOwned() { keyset?.fill(0); keyset = null; aead = null; identity = null; recipient = null }
    override fun close() = synchronized(this) { closeOwned() }
    override fun toString() = "SyncManagementSession(REDACTED)"

    companion object {
        private const val PREFIX = "CCB-MGMT1."
        private val MAGIC = "CCB-SYNC-MANAGEMENT-VAULT/1\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_PLAIN = 256 * 1024
        private const val MAX_JOURNAL = 17 * 1024 * 1024
        private fun lifetime(seconds: Long): Long {
            require(seconds in 1..900) { "SYNC_MANAGEMENT_SESSION_LIFETIME" }; return seconds * 1_000_000_000
        }
        private fun primitive(handle: KeysetHandle): Aead {
            require(handle.size() == 1 && handle.primary.key.parameters == PredefinedAeadParameters.AES256_GCM) { "SYNC_MANAGEMENT_CODE_PARAMETERS" }
            return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
        fun create(lifetimeSeconds: Long = 300, now: () -> Long = System::nanoTime): SyncManagementSession {
            AeadConfig.register(); val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            val bytes = ByteArrayOutputStream().also { CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
            val identity = LanSyncTlsIdentity.create(); val recipient = SyncDeviceEncryptionKey.create(identity.fingerprint)
            return SyncManagementSession(identity, recipient, bytes, primitive(handle), null, now(), lifetime(lifetimeSeconds), now)
        }
        fun open(recoveryCode: String, vaultBytes: ByteArray, lifetimeSeconds: Long = 300,
            now: () -> Long = System::nanoTime): SyncManagementSession {
            require(vaultBytes.size in MAGIC.size + 9..MAX_PLAIN + 8192) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
            val retainedVault = vaultBytes.copyOf()
            val codeBytes = decodeCode(recoveryCode)
            var secret: ByteArray? = null
            try {
                val code = readCode(codeBytes)
                secret = Base64.getUrlDecoder().decode(code.keyset)
                require(secret.size in 1..4096) { "SYNC_MANAGEMENT_CODE_FORMAT" }
                AeadConfig.register(); val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(secret))
                val aead = primitive(handle); val (context, encrypted) = decode(retainedVault)
                require(context == code.context) { "SYNC_MANAGEMENT_VAULT_CONTEXT" }
                val plain = try { aead.decrypt(encrypted, context.bytes()) } catch (failure: Exception) {
                    throw IllegalArgumentException("SYNC_MANAGEMENT_VAULT_AUTHENTICATION")
                }
                try {
                    val material = try { lanSyncJson.decodeFromString(Material.serializer(), boundedJson(plain, MAX_PLAIN)) }
                        catch (_: Exception) { throw IllegalArgumentException("SYNC_MANAGEMENT_VAULT_FORMAT") }
                    require(material.format == 1 && material.identity.length in 1..32 * 1024 && material.recipient.length in 1..128 * 1024) { "SYNC_MANAGEMENT_VAULT_FORMAT" }
                    val identityBytes = Base64.getDecoder().decode(material.identity); val recipientBytes = Base64.getDecoder().decode(material.recipient)
                    try {
                        val identity = LanSyncTlsIdentity.decode(identityBytes)
                        val certificate = SyncDeviceSignatures.decode(Base64.getEncoder().encodeToString(identity.certificate.encoded))
                        val recipient = SyncDeviceEncryptionKey.open(certificate.fingerprint, recipientBytes) { it.copyOf() }
                        require(certificate.fingerprint == context.owner && recipient.publicKey.hash == context.recipientKey) { "SYNC_MANAGEMENT_VAULT_IDENTITY" }
                        // A domain-bound digest probe checks the recovered private/public pair; it is never a ledger/container.
                        val digest = MessageDigest.getInstance("SHA-256").digest("CCB-MANAGEMENT-KEY-CHECK/1\n".toByteArray() + context.bytes())
                        val probe = ("CCB-SYNC-KEY-DELIVERY-SIGNATURE/1\n" + digest.joinToString("") { "%02x".format(it) }).toByteArray(Charsets.US_ASCII)
                        require(certificate.verifies(probe, Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(probe)))) { "SYNC_MANAGEMENT_VAULT_IDENTITY" }
                        return SyncManagementSession(identity, recipient, secret.copyOf(), aead, context, now(), lifetime(lifetimeSeconds), now).also {
                            it.sealed = SyncManagementVaultBlob(context, retainedVault)
                        }
                    } finally { identityBytes.fill(0); recipientBytes.fill(0) }
                } finally { plain.fill(0) }
            } catch (failure: Exception) {
                // Serialization/crypto parsers may echo private JSON/keyset fields in their causes.
                val publicCodes = setOf("SYNC_MANAGEMENT_CODE_FORMAT", "SYNC_MANAGEMENT_CODE_PARAMETERS", "SYNC_MANAGEMENT_CONTEXT",
                    "SYNC_MANAGEMENT_VAULT_BUDGET", "SYNC_MANAGEMENT_VAULT_FORMAT", "SYNC_MANAGEMENT_VAULT_CONTEXT",
                    "SYNC_MANAGEMENT_VAULT_AUTHENTICATION", "SYNC_MANAGEMENT_VAULT_IDENTITY", "SYNC_MANAGEMENT_SESSION_LIFETIME")
                throw IllegalArgumentException(failure.message?.takeIf { it in publicCodes } ?: "SYNC_MANAGEMENT_VAULT_FORMAT")
            } finally { codeBytes.fill(0); secret?.fill(0) }
        }
        /** Untrusted lookup hint only; opening the complete vault still authenticates all pins. */
        fun locate(recoveryCode: String): SyncManagementVaultContext {
            val bytes = decodeCode(recoveryCode)
            return try { readCode(bytes).context } finally { bytes.fill(0) }
        }
        private fun readCode(bytes: ByteArray): Code = try {
            lanSyncJson.decodeFromString(Code.serializer(), boundedJson(bytes, 8192)).also {
                require(it.format == 1 && it.keyset.length in 1..8192) { "SYNC_MANAGEMENT_CODE_FORMAT" }
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("SYNC_MANAGEMENT_CODE_FORMAT")
        }
        private fun decodeCode(recoveryCode: String): ByteArray {
            require(recoveryCode.length in PREFIX.length + 1..16 * 1024 && recoveryCode.startsWith(PREFIX)) { "SYNC_MANAGEMENT_CODE_FORMAT" }
            return try { Base64.getUrlDecoder().decode(recoveryCode.removePrefix(PREFIX)) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("SYNC_MANAGEMENT_CODE_FORMAT") }
        }
        private fun boundedJson(bytes: ByteArray, maximum: Int): String {
            require(bytes.size in 1..maximum) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
            val text = lanSyncUtf8(bytes); var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 8) { "SYNC_MANAGEMENT_VAULT_BUDGET" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_MANAGEMENT_VAULT_FORMAT" }
            }
            require(!quoted && depth == 0) { "SYNC_MANAGEMENT_VAULT_FORMAT" }; return text
        }
        private fun encode(context: SyncManagementVaultContext, encrypted: ByteArray): ByteArray {
            val header = lanSyncJson.encodeToString(SyncManagementVaultContext.serializer(), context).toByteArray(Charsets.UTF_8)
            require(header.size in 1..4096 && encrypted.size in 1..MAX_PLAIN + 1024) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
            return ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
                it.write(MAGIC); it.writeInt(header.size); it.write(header); it.writeInt(encrypted.size); it.write(encrypted)
            } }.toByteArray()
        }
        private fun decode(bytes: ByteArray): Pair<SyncManagementVaultContext, ByteArray> {
            require(bytes.size in MAGIC.size + 9..MAX_PLAIN + 8192) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
            DataInputStream(bytes.inputStream()).use { input ->
                require(ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC)) { "SYNC_MANAGEMENT_VAULT_FORMAT" }
                val length = input.readInt(); require(length in 1..4096 && length <= input.available() - 4) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
                val header = ByteArray(length).also(input::readFully)
                val context = lanSyncJson.decodeFromString(SyncManagementVaultContext.serializer(), boundedJson(header, 4096))
                val size = input.readInt(); require(size in 1..MAX_PLAIN + 1024 && input.available() == size) { "SYNC_MANAGEMENT_VAULT_BUDGET" }
                return context to ByteArray(size).also(input::readFully)
            }
        }
    }
}
