package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.BackupJsonStream
import com.example.chatbar.desktop.security.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** EXPERIMENT ONLY: file protocol for cooperating synthetic processes. Never a production schema. */
internal class T0RecoveryLab(val base: Path) {
    enum class Topology { SHARED, PER_ROOT }
    enum class Admission { ALL_OLD, ALL_NEW, BLOCKED }
    class Blocked : IllegalStateException("T0_BLOCKED")
    class FakeProtector(private val context: String = "fake-context") : SecretProtector {
        override fun protect(plaintext: ByteArray): ByteArray = context.toByteArray() + byteArrayOf(0) + plaintext.map { (it.toInt() xor 90).toByte() }.toByteArray()
        override fun unprotect(ciphertext: ByteArray): ByteArray {
            val prefix = context.toByteArray() + byteArrayOf(0)
            if (!ciphertext.take(prefix.size).toByteArray().contentEquals(prefix)) throw Blocked()
            return ciphertext.drop(prefix.size).map { (it.toInt() xor 90).toByte() }.toByteArray()
        }
    }
    val config get() = read(base.resolve("config.json"))
    val topology get() = Topology.valueOf(config.getValue("topology").jsonPrimitive.content)
    fun root(name: String) = base.resolve("data-$name")
    fun partition(name: String) = if (topology == Topology.SHARED) "shared" else name
    fun home(name: String = "A") = base.resolve("txn-${partition(name)}")
    fun secretRoot(name: String) = if (topology == Topology.SHARED)
        WindowsSecretRootResolver.resolve(mapOf("LOCALAPPDATA" to base.resolve("profile").toString()), root(name))
        else base.resolve("test-namespaces/$name")
    fun store(name: String, context: String = "fake-context") = WindowsSecretStore(secretRoot(name), FakeProtector(context))
    fun targets(name: String): List<Path> = (if (topology == Topology.SHARED) listOf(root("A"), root("B")) else listOf(root(name)))
        .map { it.resolve("data") } + KEYS.map { store(name).secretPath(it) }
    private fun roots(name: String) = if (topology == Topology.SHARED) listOf("A", "B") else listOf(name)
    private fun relative(p: Path) = p.toAbsolutePath().normalize().also { if (!it.startsWith(base)) throw Blocked() }.let { base.relativize(it).toString().replace('\\', '/') }
    private fun path(s: String): Path {
        if (s.split('/').any { it.isBlank() || it == "." || it == ".." } || ':' in s || '\\' in s) throw Blocked()
        return base.resolve(s).normalize().also { if (!it.startsWith(base)) throw Blocked() }
    }
    private fun identity(name: String): String {
        val stamp = kotlinx.coroutines.runBlocking { DesktopDocumentIndexReadEvidence({}, extendedPaths = true).observe(root(name), directory = true) }
        return Files.readString(root(name).resolve("root.id")) + ":${stamp.volume}:${stamp.id}:" + hash(root(name).resolve("binding"))
    }
    private fun registry(name: String) {
        val registered = read(base.resolve("registry.json"))
        roots(name).forEach { if (identity(it) != registered.getValue(it).jsonPrimitive.content) throw Blocked() }
    }
    fun <T> locked(name: String = "A", body: () -> T): T {
        FileChannel.open(base.resolve("coord-${partition(name)}.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: Exception) { null } ?: throw Blocked()
            lock.use {
                val owners = mutableListOf<DesktopDataRootOwnership>()
                try {
                    registry(name)
                    roots(name).sorted().forEach { n ->
                        val result = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root(n), DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("unused-bootstrap")))
                        if (result !is DesktopDataRootOwnershipResult.Acquired) throw Blocked()
                        owners += result.ownership
                    }
                    return body()
                } finally { owners.reversed().forEach { it.close() } }
            }
        }
    }
    fun prepare(name: String = "A", consent: Boolean = true, community: Boolean = false) = locked(name) {
        if (!consent || community || gateUnlocked(name) != Admission.ALL_OLD) throw Blocked()
        if (topology == Topology.SHARED && Files.readString(root("A").resolve("binding")) != Files.readString(root("B").resolve("binding"))) throw Blocked()
        val work = home(name); Files.createDirectories(work.resolve("old")); Files.createDirectories(work.resolve("new"))
        val prepared = WindowsSecretStore(work.resolve("prepared-secrets"), FakeProtector())
        KEYS.forEachIndexed { i, key -> prepared.save(key, "FAKE_T0_NEW_$i") }
        val rows = targets(name).mapIndexed { i, target ->
            val old = Files.readAllBytes(target)
            val next = if (i < roots(name).size) "SYNTHETIC_DATA_GEN_2".toByteArray() else Files.readAllBytes(prepared.secretPath(KEYS[i - roots(name).size]))
            write(work.resolve("old/$i"), old); write(work.resolve("new/$i"), next)
            if (i >= roots(name).size) write(work.resolve("old-secrets").resolve(target.fileName), old)
            buildJsonObject { put("target", relative(target)); put("old", digest(old)); put("new", digest(next)); put("key", i >= roots(name).size) }
        }
        val m = buildJsonObject {
            put("version", 1); put("op", UUID.randomUUID().toString()); put("context", "fake-context")
            put("roots", buildJsonObject { roots(name).forEach { put(it, identity(it)) } })
            put("rows", JsonArray(rows))
        }
        write(work.resolve("manifest.json"), m.toString().toByteArray())
        write(work.resolve("manifest.sha"), hash(work.resolve("manifest.json")).toByteArray())
    }
    fun publish(name: String = "A") = locked(name) {
        val work = home(name)
        write(work.resolve("reservation"), Files.readAllBytes(work.resolve("manifest.sha")))
        write(work.resolve("intent.part"), Files.readAllBytes(work.resolve("manifest.sha")))
        Files.move(work.resolve("intent.part"), work.resolve("intent"), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun manifest(name: String, context: String): JsonObject {
        val work = home(name)
        if (Files.readString(work.resolve("reservation")) != hash(work.resolve("manifest.json")) ||
            Files.readString(work.resolve("intent")) != hash(work.resolve("manifest.json")) ||
            Files.readString(work.resolve("manifest.sha")) != hash(work.resolve("manifest.json"))) throw Blocked()
        val m = read(work.resolve("manifest.json"))
        if (m.keys != setOf("version", "op", "context", "roots", "rows") || m.getValue("version").jsonPrimitive.int != 1 ||
            m.getValue("context").jsonPrimitive.content != context) throw Blocked()
        if (m.getValue("roots").jsonObject != buildJsonObject { roots(name).forEach { put(it, identity(it)) } }) throw Blocked()
        val rows = m.getValue("rows").jsonArray
        if (rows.map { path(it.jsonObject.getValue("target").jsonPrimitive.content) } != targets(name)) throw Blocked()
        rows.forEachIndexed { i, value ->
            val row = value.jsonObject
            if (row.keys != setOf("target", "old", "new", "key")) throw Blocked()
            if (hash(work.resolve("old/$i")) != row.getValue("old").jsonPrimitive.content ||
                hash(work.resolve("new/$i")) != row.getValue("new").jsonPrimitive.content ||
                hash(path(row.getValue("target").jsonPrimitive.content)) !in setOf(row.getValue("old").jsonPrimitive.content, row.getValue("new").jsonPrimitive.content)) throw Blocked()
            // Exercise the actual envelope decoder plus the explicitly NON-CRYPTOGRAPHIC test protector.
            if (row.getValue("key").jsonPrimitive.boolean) for ((suffix, directory) in listOf("old" to "old-secrets", "new" to "prepared-secrets")) {
                val key = KEYS[i - roots(name).size]
                val evidenceStore = WindowsSecretStore(work.resolve(directory), FakeProtector(context))
                if (hash(evidenceStore.secretPath(key)) != hash(work.resolve("$suffix/$i")) || evidenceStore.load(key) == null) throw Blocked()
            }
        }
        return m
    }
    fun apply(order: String, cut: String = "NONE", name: String = "A") = locked(name) {
        val m = manifest(name, "fake-context"); val rows = m.getValue("rows").jsonArray
        val indices = rows.indices.sortedBy { if (rows[it].jsonObject.getValue("key").jsonPrimitive.boolean) (if (order == "KEY_FIRST") 0 else 1) else (if (order == "KEY_FIRST") 1 else 0) }
        var keys = 0
        indices.forEachIndexed { ordinal, i ->
            val row = rows[i].jsonObject; val key = row.getValue("key").jsonPrimitive.boolean
            if (key && ++keys == 2 && cut == "SECOND_KEY_FAIL") throw java.io.IOException("T0_SECOND_KEY_FAIL")
            val target = path(row.getValue("target").jsonPrimitive.content)
            val bytes = Files.readAllBytes(home(name).resolve("new/$i"))
            if (key) AtomicSecretBlobWriter.write(target, bytes) else install(target, bytes)
            if (ordinal == 0 && cut == "FIRST_WRITE") Runtime.getRuntime().halt(71)
        }
        if (cut == "ALL_WRITES") Runtime.getRuntime().halt(71)
        finish(name, Admission.ALL_NEW, cut)
    }
    fun recover(name: String = "A", context: String = "fake-context", hardExit: Boolean = false): Admission = locked(name) {
        val work = home(name)
        if (!Files.exists(work.resolve("reservation"))) return@locked gateUnlocked(name)
        val m = manifest(name, context)
        if (Files.exists(work.resolve("decision"))) return@locked gateUnlocked(name)
        // A prior completion receipt without its decision is lost authority, not unfinished APPLYING.
        if (Files.exists(work.resolve("receipt")) || Files.exists(work.resolve("receipt.sha"))) throw Blocked()
        m.getValue("rows").jsonArray.forEachIndexed { i, value ->
            install(path(value.jsonObject.getValue("target").jsonPrimitive.content), Files.readAllBytes(work.resolve("old/$i")))
            if (i == 0 && hardExit) Runtime.getRuntime().halt(72)
        }
        finish(name, Admission.ALL_OLD)
        gateUnlocked(name)
    }
    private fun finish(name: String, outcome: Admission, cut: String = "NONE") {
        // Test receipt protocol only; no claim of authenticated authority or power-loss durability.
        val work = home(name); write(work.resolve("decision"), outcome.name.toByteArray())
        if (cut == "DECISION_ONLY") Runtime.getRuntime().halt(71)
        val receipt = "${hash(work.resolve("manifest.json"))}:${outcome.name}".toByteArray()
        write(work.resolve("receipt"), receipt); write(work.resolve("receipt.sha"), digest(receipt).toByteArray())
    }
    fun gate(name: String = "A"): Admission = try { locked(name) { gateUnlocked(name) } } catch (_: Exception) { Admission.BLOCKED }
    private fun gateUnlocked(name: String): Admission {
        registry(name)
        val work = home(name)
        val expected = if (!Files.exists(work.resolve("reservation"))) read(base.resolve("baseline-${partition(name)}.json")) else {
            val m = manifest(name, "fake-context")
            val receipt = Files.readString(work.resolve("receipt"))
            val decision = Admission.valueOf(Files.readString(work.resolve("decision")))
            if (decision == Admission.BLOCKED || receipt != "${hash(work.resolve("manifest.json"))}:${decision.name}" || hash(work.resolve("receipt")) != Files.readString(work.resolve("receipt.sha"))) throw Blocked()
            val key = if (decision == Admission.ALL_NEW) "new" else "old"
            val hashes = buildJsonObject { m.getValue("rows").jsonArray.forEach { value -> val row = value.jsonObject; put(row.getValue("target").jsonPrimitive.content, row.getValue(key)) } }
            if (!matches(hashes)) throw Blocked()
            return decision
        }
        if (!matches(expected)) throw Blocked()
        return Admission.ALL_OLD
    }
    private fun matches(hashes: JsonObject) = hashes.all { (p, value) -> hash(path(p)) == value.jsonPrimitive.content }
    fun consume(name: String = "A"): Admission {
        // This callback fence is prototype-only. No production Repository/Provider is constructed.
        return try { locked(name) {
            val admitted = gateUnlocked(name)
            KEYS.forEach { if (store(name).load(it) == null) throw Blocked() }
            Files.readAllBytes(root(name).resolve("data"))
            Files.writeString(base.resolve("business-$name"), admitted.name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            admitted
        } } catch (_: Exception) { Admission.BLOCKED }
    }
    fun seed(topology: Topology) {
        write(base.resolve("config.json"), buildJsonObject { put("topology", topology.name) }.toString().toByteArray())
        for (name in listOf("A", "B")) {
            Files.createDirectories(root(name)); write(root(name).resolve("root.id"), UUID.randomUUID().toString().toByteArray())
            write(root(name).resolve("data"), "SYNTHETIC_DATA_GEN_1".toByteArray()); write(root(name).resolve("binding"), "same-model|provider|https://alpha.invalid".toByteArray())
            KEYS.forEachIndexed { i, key -> store(name).save(key, "FAKE_T0_OLD_$i") }
        }
        write(base.resolve("registry.json"), buildJsonObject { listOf("A", "B").forEach { put(it, identity(it)) } }.toString().toByteArray())
        for (name in if (topology == Topology.SHARED) listOf("A") else listOf("A", "B")) {
            write(base.resolve("baseline-${partition(name)}.json"), buildJsonObject { targets(name).forEach { put(relative(it), hash(it)) } }.toString().toByteArray())
        }
        write(base.resolve("source.synthetic"), "SYNTHETIC_SOURCE_NOT_A_CBBackup".toByteArray())
    }
    companion object {
        val KEYS = listOf(DesktopCredentialKey.ModelApiKey("same-model"), DesktopCredentialKey.FishAudioApiKey)
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun hash(path: Path) = digest(Files.readAllBytes(path))
        fun write(path: Path, bytes: ByteArray) {
            Files.createDirectories(path.parent)
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { c ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) c.write(buffer); c.force(true)
            }
        }
        fun install(path: Path, bytes: ByteArray) {
            val temp = path.resolveSibling(path.fileName.toString() + ".t0-part")
            write(temp, bytes); Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        fun read(path: Path): JsonObject {
            if (Files.size(path) > 32768) throw Blocked()
            val out = java.io.StringWriter()
            Files.newBufferedReader(path).use { BackupJsonStream(it, out) { value, _ -> value }.copy() }
            return Json.parseToJsonElement(out.toString()).jsonObject
        }
    }
}

/** Child never reads profile env, DPAPI or user roots; only a parent-created build fixture. */
object T0SyntheticChild {
    @JvmStatic fun main(args: Array<String>) {
        val base = Path.of(args[0]).toAbsolutePath().normalize()
        check(base.startsWith(Path.of("build/g1b-t0").toAbsolutePath().normalize()))
        val lab = T0RecoveryLab(base)
        when (args[1]) {
            "before-intent" -> { lab.prepare(); Runtime.getRuntime().halt(71) }
            "after-intent" -> { lab.prepare(); lab.publish(); Runtime.getRuntime().halt(71) }
            "apply" -> { lab.prepare(); lab.publish(); lab.apply(args[2], args[3]) }
            "recover-crash" -> lab.recover(hardExit = true)
            "recover" -> println("T0_RECOVERY=" + lab.recover())
            "gate-B" -> println("T0_GATE=" + lab.consume("B"))
            else -> error("T0_UNKNOWN_CHILD_MODE")
        }
    }
}
