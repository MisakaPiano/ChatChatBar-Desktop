package com.example.chatbar.domain.lanSync

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.*
import java.math.BigInteger
import java.net.*
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.*

/** Private PKCS12 bytes must be wrapped by DPAPI / Android Keystore before persisting. */
class LanSyncTlsIdentity private constructor(val keyStore: KeyStore, val certificate: X509Certificate) {
    val fingerprint: String get() = lanSyncHash(certificate.encoded)
    fun encode(): ByteArray = ByteArrayOutputStream().also { keyStore.store(it, PASSWORD) }.toByteArray()

    /** The same protected device owner signs only registered domain-separated group digest protocols. */
    fun signSyncGroupPayload(payload: ByteArray): ByteArray {
        require(com.example.chatbar.domain.syncStore.SyncDeviceSignatures.isPayload(payload)) { "SYNC_GROUP_SIGNATURE_DOMAIN" }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyStore.getKey("device", PASSWORD) as PrivateKey); update(payload); sign()
        }
    }

    fun context(acceptPeer: (String) -> Boolean): SSLContext {
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain)
            private fun verify(chain: Array<X509Certificate>) {
                require(chain.size == 1) { "SYNC_CERTIFICATE_CHAIN" }
                val certificate = chain.single()
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
                require(certificate.publicKey.algorithm == "EC" && acceptPeer(lanSyncHash(certificate.encoded))) { "SYNC_UNTRUSTED_DEVICE" }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(keyManager.keyManagers, arrayOf(trust), SecureRandom()) }
    }

    companion object {
        // The entire envelope is OS-protected. This password only satisfies the PKCS12 API.
        private val PASSWORD = "ccb-lan-local-envelope-v1".toCharArray()
        fun create(): LanSyncTlsIdentity {
            val provider = BouncyCastleProvider()
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=CCB LAN Device")
            val now = System.currentTimeMillis()
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger(160, SecureRandom()).abs(), Date(now - 60_000),
                    Date(now + 10L * 365 * 24 * 60 * 60 * 1000), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(pair.private)))
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, PASSWORD); setKeyEntry("device", pair.private, PASSWORD, arrayOf(certificate))
            }
            return LanSyncTlsIdentity(store, certificate)
        }
        fun decode(bytes: ByteArray): LanSyncTlsIdentity {
            require(bytes.size <= 16_384) { "SYNC_IDENTITY_SIZE" }
            val store = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), PASSWORD) }
            require(store.size() == 1 && store.isKeyEntry("device")) { "SYNC_IDENTITY" }
            return LanSyncTlsIdentity(store, store.getCertificate("device") as X509Certificate)
        }
    }
}

/** TLS supplies authentication, fresh handshake keys, integrity and record replay protection. */
object LanSyncTls {
    fun configure(socket: SSLSocket) {
        socket.enabledProtocols = socket.supportedProtocols.filter { it in setOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
        socket.enabledCipherSuites = socket.enabledCipherSuites.filter {
            (it.contains("GCM") || it.contains("CHACHA20")) && !it.contains("_NULL_")
        }.toTypedArray()
        require(socket.enabledCipherSuites.isNotEmpty()) { "SYNC_TLS_UNAVAILABLE" }
        socket.soTimeout = 120_000
    }
    fun connect(identity: LanSyncTlsIdentity, host: String, port: Int, fingerprint: String,
        handshakeTimeoutMillis: Int = 120_000): SSLSocket = connect(identity, host, port, fingerprint, handshakeTimeoutMillis) { address, targetPort ->
        val plain = Socket()
        try { plain.connect(InetSocketAddress(address, targetPort), 5_000); plain }
        catch (failure: Throwable) { plain.close(); throw failure }
    }
    /** Same authentication path; only the numeric-address TCP dial can be replaced in isolated tests. */
    internal fun connect(identity: LanSyncTlsIdentity, host: String, port: Int, fingerprint: String,
        handshakeTimeoutMillis: Int, dial: (InetAddress, Int) -> Socket): SSLSocket {
        val address = parseAddress(host)
        require(port in 1024..65535 && fingerprint.matches(Regex("[0-9a-f]{64}")) && handshakeTimeoutMillis in 1..120_000) { "SYNC_ADDRESS" }
        val plain = dial(address, port)
        try {
            val socket = identity.context { MessageDigest.isEqual(it.toByteArray(), fingerprint.toByteArray()) }
                .socketFactory.createSocket(plain, host, port, true) as SSLSocket
            configure(socket); socket.soTimeout = handshakeTimeoutMillis; socket.startHandshake()
            require(peerFingerprint(socket) == fingerprint) { "SYNC_DEVICE_CHANGED" }
            return socket
        } catch (failure: Throwable) { plain.close(); throw failure }
    }
    fun peerFingerprint(socket: SSLSocket): String = lanSyncHash(socket.session.peerCertificates.single().encoded)
    /** Numeric RFC1918 IPv4 only: no URL, DNS lookup, redirects or arbitrary Internet targets. */
    fun parseAddress(host: String): InetAddress {
        val parts = host.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "SYNC_LOCAL_ADDRESS" }
        val p = parts.map(String::toInt)
        require(p[0] == 10 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31) { "SYNC_LOCAL_ADDRESS" }
        return InetAddress.getByAddress(p.map(Int::toByte).toByteArray())
    }
    fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().map { it.hostAddress!! }
        .filter { runCatching { parseAddress(it) }.isSuccess }.distinct()
}

/** Every message and blob is bounded before allocation. Binary resources are streamed in chunks. */
class LanSyncWire(private val socket: SSLSocket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
    @Synchronized fun send(bytes: ByteArray) {
        require(bytes.size <= MAX_MESSAGE) { "SYNC_MESSAGE_LIMIT" }
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    fun receive(maximumBytes: Int = MAX_MESSAGE): ByteArray {
        require(maximumBytes in 0..MAX_MESSAGE)
        val size = input.readInt(); require(size in 0..maximumBytes) { "SYNC_MESSAGE_LIMIT" }
        return ByteArray(size).also(input::readFully)
    }
    fun sendText(value: String) = send(value.toByteArray(Charsets.UTF_8))
    fun text(maximumBytes: Int = MAX_MESSAGE): String = lanSyncUtf8(receive(maximumBytes))
    fun sendBlob(source: InputStream, length: Long) {
        require(length in 1..BINARY_CHUNK_BYTES)
        output.writeLong(length)
        var count = 0L; val buffer = ByteArray(64 * 1024)
        while (count < length) {
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), length - count).toInt())
            require(n > 0) { "SYNC_SOURCE_TRUNCATED" }; output.write(buffer, 0, n); count = lanSyncAddLength(count, n.toLong())
        }
        require(source.read() == -1) { "SYNC_SOURCE_CHANGED" }; output.flush()
    }
    fun receiveBlob(destination: OutputStream, length: Long, expectedHash: String) {
        require(length in 1..BINARY_CHUNK_BYTES && input.readLong() == length) { "SYNC_BLOB_LENGTH" }
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; val buffer = ByteArray(64 * 1024)
        while (count < length) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - count).toInt())
            require(n > 0) { "SYNC_BLOB_TRUNCATED" }; destination.write(buffer, 0, n); digest.update(buffer, 0, n); count = lanSyncAddLength(count, n.toLong())
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "SYNC_BLOB_DAMAGED" }
    }
    override fun close() = socket.close()
    companion object {
        const val MAX_MESSAGE = 64 * 1024 * 1024
        const val BINARY_CHUNK_BYTES = 1024L * 1024
    }
}
