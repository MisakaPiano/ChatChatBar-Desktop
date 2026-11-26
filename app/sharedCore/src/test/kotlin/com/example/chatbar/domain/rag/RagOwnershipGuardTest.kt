package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RagOwnershipGuardTest {
    private fun chunk(id: String, owner: String = "s", type: ChunkSourceType = ChunkSourceType.CHAT_MEMORY,
        doc: String? = null, text: String = "old-$id") = VectorChunk(id, type, owner, content = text,
        embedding = listOf(1f, 0f), metadata = doc?.let { mapOf("originalDocId" to it) }.orEmpty(), createdAt = 1)
    private suspend fun fixture(block: suspend (Path, JsonFileStorage, RagRepository) -> Unit) {
        val root = Files.createTempDirectory("synthetic-rag-owner-")
        try { val storage = JsonFileStorage(root); block(root, storage, RagRepository(storage)) }
        finally { root.toFile().deleteRecursively() }
    }
    private fun file(root: Path, id: String) = root.resolve("entities/vector_chunks/$id.json")

    @Test fun `late foreign ordinary upsert refuses whole preflight before earlier write`() = runBlocking {
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("first"), chunk("last", "other")))
            val first = Files.readAllBytes(file(root, "first")); val last = Files.readAllBytes(file(root, "last"))
            assertFails { repo.saveChunks(listOf(chunk("first", text = "replacement"), chunk("last", text = "foreign overwrite"))) }
            assertContentEquals(first, Files.readAllBytes(file(root, "first")))
            assertContentEquals(last, Files.readAllBytes(file(root, "last")))
        }
    }
    @Test fun `same owner update retains ordinary key but kind owner and document witnesses cannot change`() = runBlocking {
        for (replacement in listOf(chunk("x", "other"), chunk("x", type = ChunkSourceType.DOCUMENT, doc = "one"))) fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("x"))); val original = Files.readAllBytes(file(root, "x"))
            assertFails { repo.saveChunks(listOf(replacement)) }; assertContentEquals(original, Files.readAllBytes(file(root, "x")))
        }
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("x", type = ChunkSourceType.DOCUMENT, doc = "one")))
            val original = Files.readAllBytes(file(root, "x"))
            assertFails { repo.saveChunks(listOf(chunk("x", type = ChunkSourceType.DOCUMENT, doc = "two"))) }
            assertContentEquals(original, Files.readAllBytes(file(root, "x")))
            repo.saveChunks(listOf(chunk("x", type = ChunkSourceType.DOCUMENT, doc = "one", text = "new")))
            assertEquals("new", RagRepository(JsonFileStorage(root)).getChunkById("x")!!.content)
        }
    }
    @Test fun `corrupt misfiled unknown and duplicate ordinary targets preserve their original bytes`() = runBlocking {
        fixture { root, storage, repo ->
            val value = chunk("x")
            val raws = listOf("broken", storage.json.encodeToString(VectorChunk.serializer(), value.copy(id = "wrong")),
                storage.json.encodeToString(VectorChunk.serializer(), value).dropLast(1) + ",\"unknownFuture\":true}")
            Files.createDirectories(file(root, "x").parent)
            for (raw in raws) {
                Files.writeString(file(root, "x"), raw)
                assertFails { repo.saveChunks(listOf(value.copy(content = "new"))) }
                assertEquals(raw, Files.readString(file(root, "x")))
            }
            Files.delete(file(root, "x")); repo.saveChunks(listOf(value))
            val before = Files.readAllBytes(file(root, "x"))
            assertFails { repo.saveChunks(listOf(value, value.copy(content = "last wins is forbidden"))) }
            assertContentEquals(before, Files.readAllBytes(file(root, "x")))
        }
    }
    @Test fun `physical upsert keys cannot escape their directory and empty save creates no authority`() = runBlocking {
        fixture { root, _, repo ->
            repo.saveChunks(emptyList())
            assertFalse(Files.exists(root.resolve("entities/vector_chunks")))
            for (id in listOf("../escape", "nested/escape", "./escape")) assertFails { repo.saveChunks(listOf(chunk(id))) }
            assertFalse(Files.exists(root.resolve("entities/escape.json")))
            assertFalse(Files.exists(root.resolve("entities/vector_chunks/nested")))
        }
    }
    @Test fun `batch physical aliases cannot overwrite a different logical owner`() = runBlocking {
        val aliases = mutableListOf("./x")
        if (System.getProperty("os.name").startsWith("Windows")) aliases += "X"
        for (alias in aliases) fixture { root, _, repo ->
            assertFails { repo.saveChunks(listOf(chunk("x", "a"), chunk(alias, "b"))) }
            assertFalse(Files.exists(file(root, "x")))
        }
    }
    @Test fun `ordinary late serialization failure keeps old or new per file without moving originals away`() = runBlocking {
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("first"), chunk("second")))
            val second = Files.readAllBytes(file(root, "second"))
            assertFails { repo.saveChunks(listOf(chunk("first", text = "completed first"), chunk("second").copy(embedding = listOf(Float.NaN)))) }
            val cold = RagRepository(JsonFileStorage(root))
            assertEquals("completed first", cold.getChunkById("first")!!.content)
            assertContentEquals(second, Files.readAllBytes(file(root, "second")))
            assertEquals("old-second", cold.getChunkById("second")!!.content)
        }
    }
    @Test fun `missing staged record refuses strict replacement before moving originals`() = runBlocking {
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("old"), chunk("foreign", "other")))
            val original = Files.readAllBytes(file(root, "old")); val foreign = Files.readAllBytes(file(root, "foreign"))
            assertFails { repo.replaceChunksForSessionStreaming("s") { emit ->
                emit(chunk("new"))
                val staging = Files.list(file(root, "new").parent).use { paths -> paths.filter { it.fileName.toString().startsWith(".replace-") }.toList().single() }
                Files.delete(staging.resolve("new.json"))
            } }
            assertContentEquals(original, Files.readAllBytes(file(root, "old")))
            assertContentEquals(foreign, Files.readAllBytes(file(root, "foreign")))
            assertFalse(Files.exists(file(root, "new")))
        }
    }
    @Test fun `nonfile staged row and vanished stage refuse without losing original`() = runBlocking {
        for (vanish in listOf(false, true)) fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("old"))); val original = Files.readAllBytes(file(root, "old"))
            assertFails { repo.replaceChunksForSessionStreaming("s") { emit ->
                emit(chunk("new"))
                val staging = Files.list(file(root, "new").parent).use { paths -> paths.filter { it.fileName.toString().startsWith(".replace-") }.toList().single() }
                Files.delete(staging.resolve("new.json"))
                if (vanish) Files.delete(staging) else Files.createDirectory(staging.resolve("new.json"))
            } }
            assertContentEquals(original, Files.readAllBytes(file(root, "old")))
        }
    }
    @Test fun `corrupt selected row refuses replacement without treating it absent`() = runBlocking {
        fixture { root, _, repo ->
            Files.createDirectories(file(root, "bad").parent); Files.writeString(file(root, "bad"), "bad selected JSON")
            assertFails { repo.replaceChunksForSessionStreaming("s") { it(chunk("new")) } }
            assertEquals("bad selected JSON", Files.readString(file(root, "bad"))); assertFalse(Files.exists(file(root, "new")))
        }
    }
    @Test fun `healthy strict replacement and foreign collision retain original source ownership`() = runBlocking {
        for (collision in listOf(false, true)) fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("old"), chunk("foreign", "other")))
            val foreign = Files.readAllBytes(file(root, "foreign"))
            val result = runCatching { repo.replaceChunksForSessionStreaming("s") { it(chunk(if (collision) "foreign" else "new")) } }
            assertEquals(collision, result.isFailure)
            assertContentEquals(foreign, Files.readAllBytes(file(root, "foreign")))
            assertEquals(listOf(if (collision) "old" else "new"), RagRepository(JsonFileStorage(root)).getAllChunksForSession("s").map { it.id })
        }
    }
    @Test fun `intentional empty replacement clears only the selected owner`() = runBlocking {
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("old"), chunk("foreign", "other")))
            val foreign = Files.readAllBytes(file(root, "foreign"))
            repo.replaceChunksForSessionStreaming("s") { }
            assertFalse(Files.exists(file(root, "old")))
            assertContentEquals(foreign, Files.readAllBytes(file(root, "foreign")))
        }
    }
    @Test fun `late install IO failure rolls back healthy original on Windows`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        fixture { root, _, repo ->
            repo.saveChunks(listOf(chunk("old"))); val original = Files.readAllBytes(file(root, "old"))
            var held: java.nio.channels.FileChannel? = null
            var stagedFile: Path? = null
            var producerCompleted = false
            try {
                val failure = assertFails { repo.replaceChunksForSessionStreaming("s") { emit ->
                    emit(chunk("new"))
                    val staging = Files.list(file(root, "new").parent).use { paths -> paths.filter { it.fileName.toString().startsWith(".replace-") }.toList().single() }
                    stagedFile = staging.resolve("new.json")
                    held = java.nio.channels.FileChannel.open(requireNotNull(stagedFile), setOf(java.nio.file.StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE))
                    producerCompleted = true
                } }
                assertTrue(producerCompleted && held?.isOpen == true, "Fault setup must reach a held, completed stage")
                val move = assertIs<java.nio.file.FileSystemException>(failure)
                assertEquals(stagedFile!!.toAbsolutePath().normalize(), Path.of(move.file).toAbsolutePath().normalize())
                assertEquals(file(root, "new").toAbsolutePath().normalize(), Path.of(move.otherFile).toAbsolutePath().normalize())
                assertContentEquals(original, Files.readAllBytes(file(root, "old")))
                assertFalse(Files.exists(file(root, "new")))
            } finally { held?.close() }
        }
    }
}
