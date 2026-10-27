package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Test-classpath only. Explicit source + newly created root; never an installed data root. */
internal object SlotIsolatedRestoreProbe {
    suspend fun run(source: Path, ownedParent: Path): Map<String, String> {
        DesktopArchiveFiles.safe(source, false); DesktopArchiveFiles.safe(ownedParent, true)
        val root = Files.createTempDirectory(ownedParent, "isolated-slot-")
        var stage = "SOURCE"
        val result = linkedMapOf<String, String>()
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
            ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
            val before = DesktopArchiveFiles.hash(source, DesktopSaveSlotArchive.MAX_ARCHIVE)
            val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("synthetic-bootstrap.json"))
            val owner = (DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired).ownership
            val gate = DesktopDataOperationCoordinator(); val focus = DesktopAudioFocus()
            try {
                val storage = JsonFileStorage(root, gate); val chats = ChatRepository(storage)
                val voices = VoiceMessageRepository(storage, true)
                val session = ChatSession.create("synthetic-unbound-card", "Isolated offline target")
                    .copy(id = "isolated-target", longTermMemoryEnabled = false)
                chats.createSession(session)
                val service = DesktopSaveSlotService(root, gate, DesktopAudioMaintenance({ focus }) {},
                    object : DesktopSessionReservation {
                        override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T = operation()
                    }, chats, voices, { null }, { reference -> root.resolve(reference) })
                stage = "IMPORT"
                val imported = service.import(source, session.id)
                stage = "RESTORE"
                service.restore(imported.id, session.id)
                stage = "VERIFY"
                val restored = chats.getMessages(session.id)
                val json = Json { ignoreUnknownKeys = false }
                ZipFile(source.toFile()).use { zip ->
                    val sourceSession = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader(Charsets.UTF_8).use {
                        json.decodeFromString(SaveSlot.serializer(), it.readText()).sessionId
                    }
                    val sourceIds = zip.getInputStream(zip.getEntry("messages.jsonl")).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter(String::isNotBlank).map { json.decodeFromString(ChatMessage.serializer(), it).id }.toList()
                    }
                    check(sourceIds.distinct().size == sourceIds.size)
                    // Independently check the shared copy contract: a foreign conversation gets
                    // an ordered, disjoint target namespace; version/source-turn evidence does not.
                    val rebound = sourceSession != session.id
                    val ids = sourceIds.sorted().mapIndexed { index, id ->
                        id to if (rebound) "${session.id}-${index.toString().padStart(10, '0')}" else id
                    }.toMap()
                    fun expectedId(id: String) = ids[id] ?: if (rebound)
                        java.util.UUID.nameUUIDFromBytes("${session.id}\u0000$id".toByteArray(Charsets.UTF_8)).toString() else id
                    check(restored.map { it.id }.distinct().size == restored.size)
                    if (rebound) check(restored.none { it.id in sourceIds })
                    var index = 0
                    zip.getInputStream(zip.getEntry("messages.jsonl")).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter(String::isNotBlank).forEach { line ->
                            val expected = MessageAlternativeVersionPolicy.normalize(json.decodeFromString(ChatMessage.serializer(), line))
                            val actual = restored[index++]
                            check(expectedId(expected.id) == actual.id && actual.sessionId == session.id &&
                                expected.generatedFromMessageId?.let(::expectedId) == actual.generatedFromMessageId &&
                                expected.role == actual.role && expected.content == actual.content && expected.alternatives == actual.alternatives &&
                                expected.alternativeVersionIds == actual.alternativeVersionIds && expected.currentAlternativeVersionId == actual.currentAlternativeVersionId &&
                                expected.sourceTurnId == actual.sourceTurnId && expected.sourceTurnOrder == actual.sourceTurnOrder)
                        }
                    }
                    check(index == restored.size)
                    result["MESSAGE_IDENTITY"] = if (rebound) "REBOUND_TO_TARGET" else "SOURCE_IDS_RETAINED"
                    fun hash(input: java.io.InputStream): String = input.use {
                        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                        while (true) { val n = it.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
                        md.digest().joinToString("") { b -> "%02x".format(b) }
                    }
                    val sourceImages = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("media/images/") }
                        .map { hash(zip.getInputStream(it)) }.toList().sorted()
                    val sourceAudio = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("media/audio/") }
                        .map { hash(zip.getInputStream(it)) }.toList().sorted()
                    val imageRefs = (restored.flatMap { it.images } + listOfNotNull(chats.getSession(session.id)?.chatBackground))
                        .filterNot { it.startsWith("chatbar-save-slot-omitted-image:") }.distinct()
                    val outputImages = imageRefs.map { hash(Files.newInputStream(root.resolve(it))) }.sorted()
                    val outputAudio = voices.listForSession(session.id).map { hash(Files.newInputStream(Path.of(it.audioPath))) }.sorted()
                    check(sourceImages == outputImages && sourceAudio == outputAudio)
                    result["MEDIA_BYTES"] = "PRESERVED"
                    result["MESSAGE_TEXT_VERSIONS_TURNS"] = "PRESERVED"
                    result["MESSAGES"] = restored.size.toString()
                    result["IMAGES"] = sourceImages.size.toString(); result["AUDIO"] = sourceAudio.size.toString()
                }
                check(!DesktopSessionCommitJournal(root).hasPending())
                result["ISOLATED_RESTORE"] = "COMMITTED"
            } catch (_: Exception) {
                result["ISOLATED_RESTORE"] = if (stage == "VERIFY") "COMMITTED_VERIFICATION_FAILED" else "REFUSED_$stage"
            } finally {
                focus.closeAndDrain(); gate.closeAndDrain(); owner.close()
            }
            if (result["ISOLATED_RESTORE"] == "COMMITTED") {
                val reopened = (DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired).ownership
                try {
                    DesktopSessionCommitJournal(root).recover()
                    val storage = JsonFileStorage(root)
                    check(ChatRepository(storage).getMessages("isolated-target").size.toString() == result["MESSAGES"])
                    check(VoiceMessageRepository(storage, true).listForSession("isolated-target").size.toString() == result["AUDIO"])
                    result["REOPEN_REPOSITORIES"] = "COUNTS_VERIFIED"
                } finally { reopened.close() }
            }
            check(DesktopArchiveFiles.hash(source, DesktopSaveSlotArchive.MAX_ARCHIVE) == before)
            result["SOURCE_BYTES"] = "UNCHANGED"
            result["PRIVATE_TEST_ROOT"] = "RETAINED"
            result["PROVIDER_SECRETSTORE_GUI"] = "NOT_USED"
        }
        return result
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        try {
            check(args.size == 1)
            val parent = Path.of("desktopApp/build/prepreview-private").toAbsolutePath().normalize()
            DesktopArchiveFiles.safe(parent.parent, true)
            Files.createDirectories(parent)
            val result = run(Path.of(args[0]), parent)
            result.forEach { (key, value) -> println("PROBE_$key=$value") }
            if (result["ISOLATED_RESTORE"] != "COMMITTED") kotlin.system.exitProcess(2)
        } catch (_: Throwable) { println("PROBE=REFUSED_INTERNAL_OR_IO"); kotlin.system.exitProcess(2) }
    }
}
