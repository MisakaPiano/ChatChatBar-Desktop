package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.BackupJsonStream
import com.example.chatbar.desktop.backup.strictUtf8Reader
import com.example.chatbar.desktop.security.*
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Activation boundary for an explicitly NEW import root and its NEW credential namespace.
 * Existing roots/global keys are never changed. Before completion the root is non-runnable.
 * The permanent v2 intent also makes the previous v1 journal reader refuse this root.
 */
internal class DesktopFreshRootAuthority(
    private val root: Path,
    private val credentialsBase: Path = WindowsSecretRootResolver.resolve(),
    private val protector: SecretProtector = WindowsDpapiSecretProtector(),
) {
    private val home = root.resolve(FENCE)
    private val intent = home.resolve("intent.json")
    init {
        require(root.isAbsolute && root == root.normalize())
        require(credentialsBase.isAbsolute && credentialsBase == credentialsBase.normalize())
        require(!root.startsWith(credentialsBase) && !credentialsBase.startsWith(root))
    }

    internal class Pending internal constructor(val namespace: String, val credentialRoot: Path,
        val store: DesktopSecretStore, internal val rootIdentity: String, internal val credentialIdentity: String,
        internal val verifyWrittenCredentials: () -> Map<String, String>)

    fun begin(): Pending {
        requireOwned()
        DesktopArchiveFiles.safe(root, true)
        check(Files.newDirectoryStream(root).use { children -> children.all {
            it.fileName.toString() == DesktopDataRootOwnership.LOCK_FILE_NAME
        } }) { "FRESH_IMPORT_REQUIRES_EMPTY_NEW_ROOT" }
        val namespace = UUID.randomUUID().toString()
        Files.createDirectories(home)
        DesktopArchiveFiles.safe(home, true)
        writeNew(intent, marker(namespace, "PREPARING", null))
        DesktopArchiveFiles.safe(credentialsBase.parent, true)
        Files.createDirectories(credentialsBase)
        DesktopArchiveFiles.safe(credentialsBase, true)
        val namespaces = credentialsBase.resolve("isolated-roots")
        Files.createDirectories(namespaces)
        DesktopArchiveFiles.safe(namespaces, true)
        val target = Files.createDirectory(namespaces.resolve(namespace))
        val rootIdentity = identity(root)
        val credentialIdentity = identity(target)
        // Bind completion to this invocation's writes, not whatever happens to be in the
        // directory later. Ciphertext hashes are local evidence; never plaintext digests.
        val written = linkedMapOf<String, String>()
        val writer = object : SecretBlobWriter {
            override fun write(destination: Path, envelope: ByteArray) = synchronized(written) {
                check(destination.parent == target && identity(target) == credentialIdentity) { "FRESH_IMPORT_CREDENTIAL_ROOT_REPLACED" }
                val name = destination.fileName.toString()
                check(name.matches(Regex("[a-f0-9]{64}\\.ccbsecret")) && name !in written && written.size < 10000) { "FRESH_IMPORT_CREDENTIAL_WRITE_CONFLICT" }
                writeNew(destination, envelope)
                written[name] = digest(envelope)
            }
        }
        val verify: () -> Map<String, String> = { synchronized(written) {
            check(identity(target) == credentialIdentity) { "FRESH_IMPORT_CREDENTIAL_ROOT_REPLACED" }
            val actual = Files.newDirectoryStream(target).use { entries ->
                val files = mutableListOf<Path>()
                for (file in entries) { check(files.size < 10000); files.add(file) }
                files
            }
            check(actual.map { it.fileName.toString() }.toSet() == written.keys) { "FRESH_IMPORT_UNDECLARED_CREDENTIAL_FILE" }
            actual.forEach { file ->
                check(digest(read(file, MAX_CONTEXT)) == written.getValue(file.fileName.toString())) { "FRESH_IMPORT_WRITTEN_CREDENTIAL_CHANGED" }
            }
            written.toMap()
        } }
        val delegate = WindowsSecretStore(target, protector, writer)
        fun requireBoundRoot() { check(identity(target) == credentialIdentity) { "FRESH_IMPORT_CREDENTIAL_ROOT_REPLACED" } }
        fun requireWrittenKey(key: DesktopCredentialKey) {
            val path = delegate.secretPath(key)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                check(path.fileName.toString() !in written) { "FRESH_IMPORT_WRITTEN_CREDENTIAL_MISSING" }
                return
            }
            check(digest(read(path, MAX_CONTEXT)) == written[path.fileName.toString()]) { "FRESH_IMPORT_WRITTEN_CREDENTIAL_CHANGED" }
        }
        val store = object : DesktopSecretStore {
            override fun load(key: DesktopCredentialKey): String? = synchronized(written) {
                requireBoundRoot(); requireWrittenKey(key); delegate.load(key)
            }
            override fun save(key: DesktopCredentialKey, secret: String) = synchronized(written) {
                requireBoundRoot(); delegate.save(key, secret)
            }
            override fun delete(key: DesktopCredentialKey) = synchronized(written) {
                requireBoundRoot(); requireWrittenKey(key)
                delegate.delete(key); written.remove(delegate.secretPath(key).fileName.toString()); Unit
            }
        }
        return Pending(namespace, target, store,
            rootIdentity, credentialIdentity, verify)
    }

    /** Caller has completed authentication, data/media validation and secret read-back first. */
    fun complete(pending: Pending, files: Map<String, String>) {
        requireOwned()
        val current = readMarker()
        check(current.phase == "PREPARING" && current.namespace == pending.namespace)
        check(identity(root) == pending.rootIdentity && identity(pending.credentialRoot) == pending.credentialIdentity)
        check(files.size <= 40000)
        val proof = buildJsonObject { files.toSortedMap().forEach { (path, hash) ->
            relative(path); check(hash.matches(Regex("[a-f0-9]{64}"))); put(path, hash)
        } }.toString().toByteArray()
        check(proof.size <= MAX_PROOF)
        verifyFiles(proof)
        val credentialFiles = pending.verifyWrittenCredentials()
        writeNew(home.resolve("files.json"), proof)
        val context = buildJsonObject {
            put("version", 1); put("namespace", pending.namespace)
            put("dataIdentity", pending.rootIdentity); put("credentialIdentity", pending.credentialIdentity)
            put("filesHash", digest(proof))
            put("credentialFiles", buildJsonObject {
                credentialFiles.toSortedMap().forEach { (name, hash) -> put(name, hash) }
            })
        }.toString().toByteArray()
        val protected = try { protector.protect(context) } finally { context.fill(0) }
        try {
            check(protected.size <= MAX_CONTEXT) { "FRESH_IMPORT_CONTEXT_BUDGET" }
            writeNew(home.resolve("context.protected"), protected)
            replaceIntent(marker(pending.namespace, "COMPLETE", digest(protected)))
        } finally { protected.fill(0); proof.fill(0) }
    }

    /** Called under root ownership before any repository opens. Missing/changed proof blocks. */
    fun verifyAndResolve(): Path {
        requireOwned()
        val marker = readMarker()
        check(marker.phase in setOf("COMPLETE", "ACTIVE")) { "FRESH_IMPORT_INCOMPLETE" }
        val target = credentialsBase.resolve("isolated-roots").resolve(marker.namespace)
        DesktopArchiveFiles.safe(target, true)
        val protected = read(home.resolve("context.protected"), MAX_CONTEXT)
        check(digest(protected) == marker.contextHash) { "FRESH_IMPORT_CONTEXT_CHANGED" }
        val plain = try { protector.unprotect(protected) } finally { protected.fill(0) }
        val context = try { strict(plain) } finally { plain.fill(0) }
        check(context.keys == setOf("version", "namespace", "dataIdentity", "credentialIdentity", "filesHash", "credentialFiles"))
        check(context.getValue("version").jsonPrimitive.int == 1)
        check(string(context, "namespace") == marker.namespace)
        check(string(context, "dataIdentity") == identity(root)) { "FRESH_IMPORT_DATA_ROOT_REPLACED" }
        check(string(context, "credentialIdentity") == identity(target)) { "FRESH_IMPORT_CREDENTIAL_ROOT_REPLACED" }
        // Genesis proof is consumed once. Ordinary later repository edits are not compared
        // against an obsolete import snapshot, but native namespace binding remains mandatory.
        if (marker.phase == "COMPLETE") {
            val credentialFiles = context.getValue("credentialFiles").jsonObject
            check(credentialFiles.size <= 10000)
            check(Files.newDirectoryStream(target).use { entries ->
                entries.map { it.fileName.toString() }.toSet() == credentialFiles.keys
            }) { "FRESH_IMPORT_CREDENTIAL_FILE_SET_CHANGED" }
            credentialFiles.forEach { (name, hash) ->
                check(name.matches(Regex("[a-f0-9]{64}\\.ccbsecret")))
                check(digest(read(target.resolve(name), MAX_CONTEXT)) == hash.jsonPrimitive.content) {
                    "FRESH_IMPORT_CREDENTIAL_FILE_CHANGED"
                }
            }
            val proof = read(home.resolve("files.json"), MAX_PROOF)
            try { check(digest(proof) == string(context, "filesHash")); verifyFiles(proof) }
            finally { proof.fill(0) }
            replaceIntent(marker(marker.namespace, "ACTIVE", marker.contextHash))
        }
        return target
    }

    private data class Marker(val namespace: String, val phase: String, val contextHash: String?)
    private fun readMarker(): Marker {
        val obj = strict(read(intent, 32768))
        check(obj.keys == setOf("version", "kind", "namespace", "phase", "contextHash"))
        check(obj.getValue("version").jsonPrimitive.int == 2 && string(obj, "kind") == KIND)
        val namespace = string(obj, "namespace")
        check(UUID.fromString(namespace).toString() == namespace)
        val hash = obj["contextHash"]?.takeUnless { it == JsonNull }?.let {
            it.jsonPrimitive.also { v -> check(v.isString) }.content.also { h -> check(h.matches(Regex("[a-f0-9]{64}"))) }
        }
        return Marker(namespace, string(obj, "phase"), hash)
    }

    private fun marker(namespace: String, phase: String, hash: String?) = buildJsonObject {
        put("version", 2); put("kind", KIND); put("namespace", namespace); put("phase", phase)
        put("contextHash", hash?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().toByteArray()

    private fun verifyFiles(bytes: ByteArray) = runBlocking {
        val obj = strict(bytes); check(obj.size <= 40000)
        val observed = hashSetOf<String>()
        Files.walk(root).use { paths -> paths.forEach { file ->
            // Only this authority's own fence and the held root lock are infrastructure.
            if (file.startsWith(home) || file == root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME)) return@forEach
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, true)
            else {
                DesktopArchiveFiles.safe(file, false)
                check(observed.size < 40000)
                observed.add(root.relativize(file).joinToString("/"))
            }
        } }
        check(observed == obj.keys) { "FRESH_IMPORT_FILE_SET_CHANGED" }
        obj.forEach { (name, hash) ->
            val file = relative(name)
            check(DesktopArchiveFiles.hash(file, MAX_FILE) == hash.jsonPrimitive.content) { "FRESH_IMPORT_FILE_CHANGED" }
        }
    }

    private fun relative(name: String): Path {
        check(name.isNotEmpty() && '\\' !in name && ':' !in name && !name.startsWith('/'))
        check(name.split('/').none { it.isBlank() || it == "." || it == ".." })
        val file = root.resolve(name).normalize()
        check(file.startsWith(root) && !file.startsWith(home))
        return file
    }

    private fun requireOwned() = check(DesktopDataRootOwnership.isHeld(root)) { "FRESH_IMPORT_ROOT_NOT_OWNED" }
    private fun identity(path: Path): String = runBlocking {
        DesktopArchiveFiles.safe(path, true)
        DesktopDocumentIndexReadEvidence({}).observe(path, true).let { "${it.volume}:${it.id}:${it.created}" }
    }
    private fun strict(bytes: ByteArray): JsonObject {
        val output = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), output) { value, _ -> value }.copy()
        return Json.parseToJsonElement(output.toString()).jsonObject
    }
    private fun string(value: JsonObject, field: String) = value.getValue(field).jsonPrimitive.also { check(it.isString) }.content
    private fun read(file: Path, maximum: Int): ByteArray {
        DesktopArchiveFiles.safe(file, false); check(Files.size(file) <= maximum)
        return Files.readAllBytes(file)
    }
    private fun replaceIntent(bytes: ByteArray) {
        val part = home.resolve("intent-${UUID.randomUUID()}.part")
        writeNew(part, bytes)
        Files.move(part, intent, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val FENCE = "backups/.restore-app.tmp/active-isolated-import"
        private const val KIND = "isolated-credential-root"
        private const val MAX_CONTEXT = 64 * 1024
        private const val MAX_PROOF = 12 * 1024 * 1024
        private const val MAX_FILE = 256L * 1024 * 1024
        fun exists(root: Path): Boolean = try {
            DesktopArchiveFiles.safe(root.resolve(FENCE), true)
            true
        } catch (_: NoSuchFileException) { false }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun writeNew(path: Path, bytes: ByteArray) {
            DesktopArchiveFiles.safe(path.parent, true)
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
    }
}
