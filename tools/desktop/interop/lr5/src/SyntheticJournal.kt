package com.example.chatbar.desktop.lr5

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import com.example.chatbar.desktop.DesktopDocumentIndexReadEvidence
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.UUID

// EXPERIMENT ONLY. No application interfaces, real protector, discovery or runtime wiring.
internal class Refused(val code: String) : RuntimeException("LR5_$code")
internal fun need(condition: Boolean, code: String) { if (!condition) throw Refused(code) }
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
internal object ExperimentWire {
    private const val HEADER = "LR5-SYNTHETIC-1"
    fun encode(kind: String, values: Map<String, String>): ByteArray {
        need(values.size <= 64 && values.all { (k, v) -> k.matches(Regex("[a-zA-Z0-9.]+")) &&
            v.isNotEmpty() && v.length <= 1024 && v.all { it.code in 33..126 && it != '\\' } }, "WIRE_FIELDS")
        val body = (listOf(HEADER, kind, values.size.toString()) + values.toSortedMap().map { "${it.key}=${it.value}" })
            .joinToString("\n", postfix = "\n")
        return (body + digest(body.toByteArray()) + "\n").toByteArray()
    }
    fun decode(bytes: ByteArray, kind: String, keys: Set<String>): Map<String, String> {
        need(bytes.size <= 32768 && bytes.all { it.toInt() in 10..126 }, "WIRE_BOUNDS")
        val text = bytes.toString(Charsets.US_ASCII)
        val lines = text.split('\n')
        need(lines.size >= 5 && lines.last() == "" && lines[0] == HEADER && lines[1] == kind, "WIRE_VERSION_KIND")
        val count = lines[2].toIntOrNull() ?: throw Refused("WIRE_COUNT")
        need(count == keys.size && lines.size == count + 5, "WIRE_COUNT")
        val body = lines.take(count + 3).joinToString("\n", postfix = "\n")
        need(digest(body.toByteArray()) == lines[count + 3], "WIRE_CHECKSUM")
        val pairs = lines.drop(3).take(count).map {
            val index = it.indexOf('='); need(index > 0, "WIRE_FIELD")
            it.substring(0, index) to it.substring(index + 1)
        }
        need(pairs.map { it.first }.toSet() == keys && pairs.map { it.first }.distinct().size == count, "WIRE_FIELDS")
        val result = pairs.toMap()
        need(encode(kind, result).contentEquals(bytes), "WIRE_CANONICAL")
        return result
    }
}

internal object FakeProtector {
    fun protect(value: String): ByteArray = ("SYNTHETIC_ONLY|$value").toByteArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    fun verify(bytes: ByteArray, value: String): Boolean = bytes.contentEquals(protect(value))
}

internal data class Outcome(val state: String, val reason: String = "NONE") {
    val businessAuthorized: Boolean = false
}

internal class SyntheticJournal(private val root: Path, private val claim: String, private val fault: String = "NONE") {
    companion object {
        val ids = listOf("data", "key1", "key2", "voice", "moment")
        private val contextKeys = setOf("root", "oldRoot", "newRoot", "profileRoot", "profile", "protector", "account", "endpoint", "purpose", "generation", "consumers", "source")
        private val authorityKeys = setOf("generation", "manifest", "phase", "receipt")
        private val receiptKeys = setOf("generation", "manifest", "choice", "participants")
        private val manifestKeys = contextKeys + ids.flatMap { listOf("$it.old", "$it.new") }
        fun fresh(parent: Path): Pair<Path, String> {
            val root = Files.createTempDirectory(parent, "synthetic-lr5-")
            val claim = UUID.randomUUID().toString()
            Files.writeString(root.resolve("claim"), claim, StandardOpenOption.CREATE_NEW)
            for (name in listOf("profile", "old", "new", "wal")) Files.createDirectory(root.resolve(name))
            Files.writeString(root.resolve("source"), "SYNTHETIC_ONLY_SOURCE_UNCHANGED", StandardOpenOption.CREATE_NEW)
            for (id in ids) {
                Files.write(root.resolve("old/$id"), bytes(id, "OLD"), StandardOpenOption.CREATE_NEW)
                Files.write(root.resolve("new/$id"), bytes(id, "OLD"), StandardOpenOption.CREATE_NEW)
            }
            val ctx = mapOf("root" to native(root), "oldRoot" to native(root.resolve("old")), "newRoot" to native(root.resolve("new")),
                "profileRoot" to native(root.resolve("profile")), "profile" to "SYNTHETIC_PROFILE_A", "protector" to "FAKE_CONTEXT_A",
                "account" to "SYNTHETIC_ACCOUNT_A", "endpoint" to "https://synthetic.invalid/v1", "purpose" to "MODEL_AND_DORMANT",
                "generation" to "1", "consumers" to "DECLARED_SYNTHETIC_CLOSED", "source" to digest(Files.readAllBytes(root.resolve("source"))))
            Files.write(root.resolve("context"), ExperimentWire.encode("CONTEXT", ctx), StandardOpenOption.CREATE_NEW)
            Files.writeString(root.resolve("registry"), ids.joinToString("|"), StandardOpenOption.CREATE_NEW)
            for (name in listOf("profile/lock", "old/lock", "new/lock")) Files.createFile(root.resolve(name))
            return root to claim
        }
        fun bytes(id: String, generation: String): ByteArray = if (id.startsWith("key"))
            FakeProtector.protect("$id|$generation|SYNTHETIC_ACCOUNT_A|FAKE_CONTEXT_A")
        else "SYNTHETIC_ONLY|$id|$generation|RAW_EXACT_BYTES\n  ".toByteArray()
        private fun native(p: Path): String {
            val stamp = runBlocking { DesktopDocumentIndexReadEvidence({}).observe(p, directory = true) }
            return digest("${stamp.volume}:${stamp.id}:${stamp.created}".toByteArray())
        }
    }
    init {
        need(root.isAbsolute && claim.matches(Regex("[a-f0-9-]{36}")), "SANDBOX_CLAIM")
        need(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "SANDBOX_ROOT")
        need(read("claim", 128).toString(Charsets.US_ASCII) == claim, "SANDBOX_CLAIM")
    }
    private fun path(relative: String): Path {
        need(relative.matches(Regex("[a-zA-Z0-9./_-]+")) && !relative.split('/').any { it in setOf("", ".", "..") }, "PATH")
        val target = root.resolve(relative).normalize(); need(target.startsWith(root), "PATH")
        var p: Path? = target
        while (p != null && p.startsWith(root)) {
            need(!Files.isSymbolicLink(p), "UNSAFE_PATH"); p = p.parent
        }
        return target
    }
    private fun read(relative: String, max: Int = 32768): ByteArray {
        val p = path(relative)
        need(Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), "MISSING_PARTICIPANT")
        need(Files.size(p) <= max, "SIZE_LIMIT")
        return FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            val size = it.size(); need(size <= max, "SIZE_LIMIT")
            val b = ByteBuffer.allocate(size.toInt()); while (b.hasRemaining()) need(it.read(b) >= 0, "SHORT_READ")
            need(it.size() == size, "READ_CHANGED"); b.array()
        }
    }
    private fun exists(relative: String) = Files.exists(path(relative), LinkOption.NOFOLLOW_LINKS)
    private fun atomic(relative: String, bytes: ByteArray, replace: Boolean = false) {
        need(bytes.size <= 32768, "SIZE_LIMIT")
        val target = path(relative); val part = target.resolveSibling("part-${UUID.randomUUID()}")
        FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { f ->
            if (fault == "LOW_DISK_DURING_WRITE" && relative == "wal/key2.new") {
                f.write(ByteBuffer.wrap(bytes.copyOf(1))); f.force(true); throw IOException("SYNTHETIC_DISK_FAULT")
            }
            val b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) f.write(b); f.force(true)
        }
        if (replace) Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else { need(!exists(relative), "ALREADY_EXISTS"); Files.move(part, target, StandardCopyOption.ATOMIC_MOVE) }
        // File readback is observed; directory force support/failure is recorded, never treated as power-loss proof.
        need(read(relative).contentEquals(bytes), "READBACK")
        try { FileChannel.open(target.parent, StandardOpenOption.READ).use { it.force(true) }; event("DIRECTORY_FORCE_RETURNED") }
        catch (_: Exception) { event("DIRECTORY_FORCE_UNAVAILABLE") }
    }
    private fun event(code: String) {
        need(code.matches(Regex("[A-Z0-9_]+")), "EVENT_CODE")
        FileChannel.open(path("events"), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use { f ->
            f.write(ByteBuffer.wrap((code + "\n").toByteArray())); f.force(true)
        }
    }
    private fun stage(code: String) {
        event(code); println("STAGE=$code")
        if (fault == code) Runtime.getRuntime().halt(71)
        if (fault == "CANCEL_$code") throw Refused(if (exists("profile/authority")) "RESTART_REQUIRED" else "CANCELLED")
    }
    private fun context(): Map<String, String> {
        validateLayout()
        val c = ExperimentWire.decode(read("context"), "CONTEXT", contextKeys)
        need(c.getValue("root") == native(root) && c.getValue("oldRoot") == native(path("old")) &&
            c.getValue("newRoot") == native(path("new")) && c.getValue("profileRoot") == native(path("profile")), "ROOT_IDENTITY")
        need(c["profile"] == "SYNTHETIC_PROFILE_A" && c["protector"] == "FAKE_CONTEXT_A", "PROTECTION_CONTEXT")
        need(c["account"] == "SYNTHETIC_ACCOUNT_A" && c["endpoint"] == "https://synthetic.invalid/v1" && c["purpose"] == "MODEL_AND_DORMANT", "ACCOUNT_ENDPOINT")
        need(c["consumers"] == "DECLARED_SYNTHETIC_CLOSED" && read("registry").toString(Charsets.US_ASCII) == ids.joinToString("|"), "CONSUMER_REGISTRY")
        need(digest(read("source")) == c["source"], "SOURCE_CHANGED")
        ids.forEach { need(read("old/$it").contentEquals(bytes(it, "OLD")), "OLD_CHANGED") }
        need(directoryNames("old") == (ids + "lock").toSet() && directoryNames("new")
            .filterNot { it.startsWith("part-") }.toSet() == (ids + "lock").toSet(), "PARTICIPANT_REGISTRY")
        return c
    }
    private fun validateLayout() {
        need(directoryNames("profile").all { it in setOf("lock", "authority") }, "UNKNOWN_AUTHORITY_FILE")
        val walNames = setOf("manifest", "receipt") + ids.flatMap { listOf("$it.old", "$it.new") }
        need(directoryNames("wal").all { it in walNames }, "UNKNOWN_OR_PARTIAL_WAL")
        // Any incomplete per-target temporary also prevents admission; retain it, never sweep.
        need(directoryNames("new").all { it in (ids + "lock").toSet() }, "UNKNOWN_OR_PARTIAL_TARGET")
    }
    private fun directoryNames(dir: String): Set<String> = Files.newDirectoryStream(path(dir)).use { it.map { p -> p.fileName.toString() }.toSet() }
    private fun <T> locked(action: () -> T): T {
        val channels = mutableListOf<FileChannel>(); val locks = mutableListOf<FileLock>()
        try {
            for (relative in listOf("profile/lock", "old/lock", "new/lock")) {
                val f = FileChannel.open(path(relative), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); channels += f
                locks += (f.tryLock() ?: throw Refused("LOCKED"))
            }
            return action()
        } finally { locks.asReversed().forEach { it.release() }; channels.asReversed().forEach { it.close() } }
    }
    fun run(command: String, order: String = "KEY_FIRST"): Outcome = try {
        locked {
            when (command) {
                "APPLY" -> { prepare(); advance(order, false) }
                "RECOVER" -> recover(order, false)
                "ROLLBACK" -> recover(order, true)
                "VERIFY" -> verify()
                "HOLD" -> { context(); Files.createFile(path("lock-ready")); Thread.sleep(5000); Outcome("RESTART_REQUIRED", "LOCK_ONLY") }
                else -> throw Refused("COMMAND")
            }
        }
    } catch (e: Refused) { Outcome(if (e.code == "RESTART_REQUIRED") "RESTART_REQUIRED" else "RECOVERY_BLOCKED", e.code) }
      catch (_: Exception) { Outcome("RECOVERY_BLOCKED", "IO_OR_IDENTITY_FAILURE") }
    private fun prepare() {
        val c = context(); need(c["generation"] == "1", "STALE_GENERATION")
        need(!exists("wal/manifest") && !exists("profile/authority"), "ALREADY_PREPARED")
        val payloadBytes = ids.sumOf { read("old/$it").size.toLong() + bytes(it, "NEW").size.toLong() }
        // Experiment-only conservative peak, not an approved production reservation policy.
        val requiredBytes = Math.addExact(Math.multiplyExact(payloadBytes, 2L), 5L * 32768)
        val availableBytes = if (fault == "LOW_CAPACITY") 0L else Files.getFileStore(root).usableSpace
        need(availableBytes >= requiredBytes, "CAPACITY")
        event("CAPACITY_CHECKED")
        val values = c.toMutableMap()
        ids.forEach { id ->
            need(read("new/$id").contentEquals(bytes(id, "OLD")), "FOREIGN_TARGET")
            atomic("wal/$id.old", read("old/$id")); atomic("wal/$id.new", bytes(id, "NEW"))
            values["$id.old"] = digest(read("wal/$id.old")); values["$id.new"] = digest(read("wal/$id.new"))
        }
        atomic("wal/manifest", ExperimentWire.encode("MANIFEST", values))
        stage("BEFORE_INTENT")
        publish("INTENT", "NONE")
        stage("AFTER_INTENT")
    }
    private fun evidence(): Map<String, String> {
        val m = ExperimentWire.decode(read("wal/manifest"), "MANIFEST", manifestKeys)
        need(context() == m.filterKeys { it in contextKeys }, "GENERATION_CONTEXT_CHANGED")
        ids.forEach { id -> for (side in listOf("old", "new")) {
            val b = read("wal/$id.$side"); need(digest(b) == m["$id.$side"] && b.contentEquals(bytes(id, side.uppercase())), "SNAPSHOT_CHANGED")
        } }
        return m
    }
    private fun authority(): Map<String, String> {
        val a = ExperimentWire.decode(read("profile/authority"), "AUTHORITY", authorityKeys)
        need(a["generation"] == "2" && a["manifest"] == digest(read("wal/manifest")), "AUTHORITY_BINDING")
        need(a["phase"] in setOf("INTENT", "NEW", "OLD"), "AUTHORITY_PHASE")
        if (a["phase"] == "INTENT") need(a["receipt"] == "NONE", "AUTHORITY_BINDING")
        return a
    }
    private fun publish(phase: String, receipt: String) = atomic("profile/authority", ExperimentWire.encode("AUTHORITY", mapOf(
        "generation" to "2", "manifest" to digest(read("wal/manifest")), "phase" to phase, "receipt" to receipt)), replace = exists("profile/authority"))
    private fun recover(order: String, rollback: Boolean): Outcome {
        context()
        if (!exists("profile/authority")) {
            need(directoryNames("wal").isEmpty(), "AUTHORITY_MISSING")
            ids.forEach { need(read("new/$it").contentEquals(bytes(it, "OLD")), "FOREIGN_TARGET") }
            return Outcome("ALL_OLD", "NO_IMPORT")
        }
        evidence(); val a = authority()
        return if (a["phase"] == "INTENT") advance(order, rollback) else verify()
    }
    private fun advance(order: String, rollback: Boolean): Outcome {
        val m = evidence(); need(authority()["phase"] == "INTENT", "NOT_PENDING")
        val chosen = if (rollback) "OLD" else "NEW"
        if (exists("wal/receipt")) {
            val receipt = ExperimentWire.decode(read("wal/receipt"), "RECEIPT", receiptKeys)
            need(receipt == mapOf("generation" to "2", "manifest" to digest(read("wal/manifest")),
                "choice" to chosen, "participants" to ids.joinToString("|")), "RECEIPT_BINDING")
            // A receipt claims the whole set was already complete. Reverted OLD is not a legitimate partial apply here.
            verifyChosen(chosen, m)
        }
        val sequence = when (order) {
            "KEY_FIRST" -> listOf("key1", "key2", "data", "voice", "moment")
            "DATA_FIRST" -> ids
            else -> throw Refused("ORDER")
        }
        // Validate ALL targets before any recovery mutation; foreign data is not a rollback invitation.
        ids.forEach { need(digest(read("new/$it")) in setOf(m["$it.old"], m["$it.new"]), "FOREIGN_TARGET") }
        for (id in sequence) {
            if (fault == "SECOND_KEY_FAIL" && id == "key2") throw IOException("SYNTHETIC_SECOND_KEY_FAIL")
            need(digest(read("new/$id")) in setOf(m["$id.old"], m["$id.new"]), "FOREIGN_TARGET")
            atomic("new/$id", read("wal/$id.${chosen.lowercase()}"), replace = true)
            stage("AFTER_${id.uppercase()}")
        }
        verifyChosen(chosen, m)
        stage("BEFORE_RECEIPT")
        val receipt = ExperimentWire.encode("RECEIPT", mapOf("generation" to "2", "manifest" to digest(read("wal/manifest")),
            "choice" to chosen, "participants" to ids.joinToString("|")))
        atomic("wal/receipt", receipt, replace = exists("wal/receipt"))
        stage("AFTER_RECEIPT")
        // Recheck context/evidence and payload after receipt, before authority publication.
        evidence(); verifyChosen(chosen, m)
        stage("BEFORE_PUBLICATION")
        publish(chosen, digest(read("wal/receipt")))
        stage("AFTER_PUBLICATION")
        stage("BEFORE_REOPEN")
        return verify()
    }
    private fun verifyChosen(chosen: String, m: Map<String, String>) {
        ids.forEach { id ->
            val b = read("new/$id"); need(digest(b) == m["$id.${chosen.lowercase()}"], "NOT_UNIFORM")
            if (id.startsWith("key")) need(FakeProtector.verify(b, "$id|$chosen|SYNTHETIC_ACCOUNT_A|FAKE_CONTEXT_A"), "FAKE_PROTECTOR_BINDING")
        }
    }
    private fun verify(): Outcome {
        val m = evidence(); val a = authority(); val phase = a.getValue("phase")
        need(phase in setOf("OLD", "NEW"), "INCOMPLETE")
        val bytes = read("wal/receipt")
        need(a["receipt"] == digest(bytes), "RECEIPT_BINDING")
        val receipt = ExperimentWire.decode(bytes, "RECEIPT", receiptKeys)
        need(receipt == mapOf("generation" to "2", "manifest" to a.getValue("manifest"), "choice" to phase, "participants" to ids.joinToString("|")), "RECEIPT_BINDING")
        verifyChosen(phase, m)
        return Outcome("ALL_$phase", if (phase == "OLD") "ROLLBACK_ONLY" else "SYNTHETIC_SET_ONLY")
    }
}

object SyntheticJournalActor {
    @JvmStatic fun main(args: Array<String>) {
        val outcome = try {
            need(args.size == 5, "ARGUMENTS")
            SyntheticJournal(Path.of(args[0]), args[1], args[3]).run(args[2], args[4])
        } catch (_: Exception) { Outcome("RECOVERY_BLOCKED", "ACTOR_INPUT") }
        println("STATE=${outcome.state}"); println("REASON=${outcome.reason}"); println("BUSINESS_AUTHORIZED=false")
        if (outcome.state == "RECOVERY_BLOCKED") kotlin.system.exitProcess(2)
        if (outcome.state == "RESTART_REQUIRED") kotlin.system.exitProcess(3)
    }
}
