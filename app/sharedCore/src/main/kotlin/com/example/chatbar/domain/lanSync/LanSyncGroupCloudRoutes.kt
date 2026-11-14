package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncDeviceSignatures
import com.example.chatbar.domain.syncStore.SyncGroupTrust
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.domain.syncStore.SyncVersionRef
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LanSyncGroupCloudDraft(val name: String, val endpoint: String, val username: String,
    val password: ByteArray, val enabled: Boolean = true) {
    override fun toString() = "LanSyncGroupCloudDraft(REDACTED)"
}

/** Display projection only. It carries no password or backend completion claim. */
data class LanSyncGroupCloudRoute(val id: SyncStoreId, val name: String, val endpoint: String,
    val username: String, val enabled: Boolean) {
    override fun toString() = "LanSyncGroupCloudRoute(REDACTED)"
}

class LanSyncGroupCloudRoutesException internal constructor(code: String) : IllegalStateException(code)

/** Adapter-only short-lived secret. A borrowed password copy is erased when its callback returns. */
internal class LanSyncGroupCloudCredentials internal constructor(val id: SyncStoreId, val endpoint: String,
    val username: String, password: ByteArray) : Closeable {
    private var secret: ByteArray? = password.copyOf()
    fun <T> withPassword(work: (ByteArray) -> T): T {
        val borrowed = synchronized(this) { secret?.copyOf() ?: throw LanSyncGroupCloudRoutesException("SYNC_GROUP_CLOUD_ROUTES_CREDENTIALS_CLOSED") }
        try { return work(borrowed) } finally { borrowed.fill(0) }
    }
    override fun close() = synchronized(this) { secret?.fill(0); secret = null }
    override fun toString() = "LanSyncGroupCloudCredentials(REDACTED)"
}

/**
 * Explicitly constructed, daily-protected local route custody. It performs no network operation,
 * group enrollment, provider capability assertion or backup credential export.
 */
class LanSyncGroupCloudRoutes(private val root: Path, private val trust: SyncGroupTrust,
    private val device: String, private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray,
    private val endpointPolicy: WebDavEndpointPolicy = WebDavEndpointPolicy.HTTPS_ONLY) {
    @Serializable private data class Entry(val id: SyncStoreId, val name: String, val endpoint: String,
        val username: String, val password: ByteArray, val enabled: Boolean) {
        fun row() = LanSyncGroupCloudRoute(id, name, endpoint, username, enabled)
        override fun toString() = "CloudRouteEntry(REDACTED)"
    }
    @Serializable private data class Catalog(val format: Int, val group: SyncStoreId, val genesis: SyncVersionRef,
        val ownerCertificate: String, val device: String, val entries: List<Entry>, val retired: Set<SyncStoreId>) {
        fun clear() = entries.forEach { it.password.fill(0) }
        override fun toString() = "CloudRouteCatalog(REDACTED)"
    }
    private val file = root.resolve("cloud-routes.protected")
    private val lock: ReentrantLock
    private val mutableRevision = MutableStateFlow(0L)
    /** Process-local dirty token; no IDs, paths, URLs or credentials enter this flow. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    init {
        try {
            lanSyncSafe(root, true)
            require(root.isAbsolute && root.normalize() == root && device.matches(Regex("[a-f0-9]{64}")) &&
                SyncDeviceSignatures.decode(trust.ownerCertificate).encoded == trust.ownerCertificate)
        } catch (_: Exception) { fail("BINDING") }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    fun rows(): List<LanSyncGroupCloudRoute> = guarded { withCatalog { catalog ->
        Collections.unmodifiableList(catalog.entries.map(Entry::row))
    } }

    /** Endpoint/auth changes are represented by adding a fresh route, never reusing old receipts. */
    fun add(draft: LanSyncGroupCloudDraft): SyncStoreId = guarded { withCatalog { catalog ->
        requireBudget(catalog.entries.size < MAX_ROUTES)
        val name = draft.name.trim()
        validateText(name, 160, blank = false)
        validateText(draft.username, 4096, blank = true)
        val endpoint = endpoint(draft.endpoint)
        val password = draft.password.copyOf()
        try {
            requireBudget(password.size <= MAX_PASSWORD_BYTES)
            validatePassword(password)
            var id: SyncStoreId
            do { id = SyncStoreId.random() } while (id in catalog.retired || catalog.entries.any { it.id == id })
            val entry = Entry(id, name, endpoint, draft.username, password, draft.enabled)
            write(catalog.copy(entries = catalog.entries + entry))
            changed()
            id
        } finally { password.fill(0) }
    } }

    fun setEnabled(id: SyncStoreId, enabled: Boolean) = guarded { withCatalog { catalog ->
        val old = catalog.entries.singleOrNull { it.id == id } ?: fail("NOT_FOUND")
        if (old.enabled != enabled) {
            write(catalog.copy(entries = catalog.entries.map { if (it.id == id) it.copy(enabled = enabled) else it }))
            changed()
        }
    } }

    /** Removes only local configuration. Remote objects, queued ciphertext and target receipts remain. */
    fun remove(id: SyncStoreId) = guarded { withCatalog { catalog ->
        if (catalog.entries.none { it.id == id }) {
            if (id !in catalog.retired) fail("NOT_FOUND")
        } else {
            requireBudget(catalog.retired.size < MAX_RETIRED)
            write(catalog.copy(entries = catalog.entries.filterNot { it.id == id }, retired = catalog.retired + id))
            changed()
        }
    } }

    internal fun credentials(id: SyncStoreId): LanSyncGroupCloudCredentials = guarded { withCatalog { catalog ->
        val entry = catalog.entries.singleOrNull { it.id == id } ?: fail("NOT_FOUND")
        if (!entry.enabled) fail("DISABLED")
        LanSyncGroupCloudCredentials(entry.id, entry.endpoint, entry.username, entry.password)
    } }

    private fun empty() = Catalog(1, trust.group, trust.genesis, trust.ownerCertificate, device, emptyList(), emptySet())
    private inline fun <T> withCatalog(work: (Catalog) -> T): T {
        val catalog = read()
        try { return work(catalog) } finally { catalog.clear() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun read(): Catalog {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return empty()
        val encrypted = try { lanSyncRead(file, MAX_CATALOG_BYTES + PROTECTION_OVERHEAD) } catch (_: Exception) { fail("IO") }
        var plain: ByteArray? = null
        var catalog: Catalog? = null
        try {
            val decoded = try { unprotect(encrypted) } catch (_: Exception) { fail("PROTECTION_FAILED") }
            plain = decoded
            requireBudget(decoded.size in 1..MAX_CATALOG_BYTES)
            preflight(decoded)
            val retained = try { ByteArrayInputStream(decoded).use { lanSyncJson.decodeFromStream(Catalog.serializer(), it) } }
                catch (_: Exception) { fail("INVALID") }
            catalog = retained
            validate(retained)
            return retained
        } catch (failure: Exception) { catalog?.clear(); throw failure }
        finally { encrypted.fill(0); plain?.fill(0) }
    }

    private fun validate(catalog: Catalog) {
        if (catalog.format != 1 || catalog.group != trust.group || catalog.genesis != trust.genesis ||
            catalog.ownerCertificate != trust.ownerCertificate || catalog.device != device) fail("BINDING")
        requireBudget(catalog.entries.size <= MAX_ROUTES && catalog.retired.size <= MAX_RETIRED)
        if (catalog.entries.map { it.id }.toSet().size != catalog.entries.size || catalog.entries.any { it.id in catalog.retired }) fail("INVALID")
        catalog.entries.forEach {
            validateText(it.name, 160, blank = false)
            validateText(it.username, 4096, blank = true)
            requireBudget(it.password.size <= MAX_PASSWORD_BYTES)
            if (endpoint(it.endpoint) != it.endpoint) fail("INVALID")
            validatePassword(it.password)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun write(catalog: Catalog) {
        if (mutableRevision.value == Long.MAX_VALUE) fail("BUDGET")
        validate(catalog)
        val buffer = ClearingBuffer()
        var plain: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            val bounded = object : OutputStream() {
                override fun write(value: Int) { requireBudget(buffer.size() < MAX_CATALOG_BYTES); buffer.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    requireBudget(length >= 0 && buffer.size() <= MAX_CATALOG_BYTES - length); buffer.write(bytes, offset, length)
                }
            }
            lanSyncJson.encodeToStream(Catalog.serializer(), catalog, bounded)
            val decoded = buffer.toByteArray()
            plain = decoded
            val protected = try { protect(decoded) } catch (_: Exception) { fail("PROTECTION_FAILED") }
            val stored = try { protected.copyOf() } finally { if (protected !== decoded) protected.fill(0) }
            encrypted = stored
            requireBudget(stored.size in 1..MAX_CATALOG_BYTES + PROTECTION_OVERHEAD)
            lanSyncAtomicWrite(file, stored)
        } finally { plain?.fill(0); encrypted?.fill(0); buffer.clear() }
    }

    private fun preflight(bytes: ByteArray) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = CharArray(8192)
        var depth = 0; var quoted = false; var escaped = false
        try {
            InputStreamReader(ByteArrayInputStream(bytes), decoder).use { input ->
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (quoted) {
                            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                        } else when (char) {
                            '"' -> quoted = true
                            '{', '[' -> { depth++; requireBudget(depth <= 16) }
                            '}', ']' -> { depth--; if (depth < 0) fail("INVALID") }
                        }
                    }
                }
            }
            if (quoted || depth != 0) fail("INVALID")
        } catch (failure: LanSyncGroupCloudRoutesException) { throw failure }
        catch (_: Exception) { fail("INVALID") }
        finally { buffer.fill('\u0000') }
    }

    private fun validatePassword(bytes: ByteArray) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = CharArray(4096)
        try { InputStreamReader(ByteArrayInputStream(bytes), decoder).use { input -> while (input.read(buffer) >= 0) { } } }
        catch (_: Exception) { fail("INVALID") }
        finally { buffer.fill('\u0000') }
    }

    private fun endpoint(value: String): String {
        requireBudget(value.length <= 8192)
        val url = value.toHttpUrlOrNull() ?: fail("ENDPOINT")
        val synthetic = endpointPolicy == WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP && url.scheme == "http" &&
            url.host in setOf("127.0.0.1", "::1", "[::1]")
        // Same endpoint contract as WebDavHttpTransport; parsing creates no client or network call.
        if (!(url.isHttps || synthetic) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || !url.encodedPath.endsWith('/')) fail("ENDPOINT")
        return url.toString()
    }
    private fun validateText(value: String, maximum: Int, blank: Boolean) {
        requireBudget(value.length <= maximum)
        if ((!blank && value.isBlank()) || value.any { it.code < 32 || it.code == 127 }) fail("INVALID")
    }
    private fun changed() { mutableRevision.value += 1 }
    private fun requireBudget(ok: Boolean) { if (!ok) fail("BUDGET") }
    private fun fail(suffix: String): Nothing = throw LanSyncGroupCloudRoutesException("SYNC_GROUP_CLOUD_ROUTES_$suffix")

    private fun <T> guarded(work: () -> T): T {
        if (lock.isHeldByCurrentThread) fail("REENTRY")
        lock.lockInterruptibly()
        try {
            try {
                lanSyncSafe(root, true)
                FileChannel.open(root.resolve("cloud-routes.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use { return work() }
                }
            } catch (failure: LanSyncGroupCloudRoutesException) { throw failure }
            catch (_: Exception) { fail("IO") }
        } finally { lock.unlock() }
    }
    private class ClearingBuffer : ByteArrayOutputStream() { fun clear() { buf.fill(0); reset() } }
    companion object {
        // Catalog working budgets only; these do not limit attachment sizes or total synchronized data.
        private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
        private const val PROTECTION_OVERHEAD = 1024 * 1024
        private const val MAX_ROUTES = 1024
        private const val MAX_RETIRED = 16384
        private const val MAX_PASSWORD_BYTES = 64 * 1024
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
