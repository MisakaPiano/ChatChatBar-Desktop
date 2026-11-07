package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import com.example.chatbar.domain.image.NovelAiStudioDraft
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopStudioLargeLoadTest {
    @Test fun `actual typed draft and history load 160 MiB each within a 256 MiB child heap without rewriting originals`() {
        val root = Files.createTempDirectory("studio-large-synthetic-")
        var process: Process? = null
        try {
            Files.writeString(root.resolve("synthetic-owner"), "CCB_SYNTHETIC_STUDIO_LARGE_LOAD")
            val entities = Files.createDirectories(root.resolve("entities"))
            val history = Files.createDirectories(entities.resolve("novelai_generation_history"))
            val draft = entities.resolve("novelai_studio_draft.json")
            val entry = history.resolve("legacy.json")
            fun write(file: Path, prefix: String, suffix: String) {
                Files.newBufferedWriter(file).use { output ->
                    output.write(prefix)
                    val block = "A".repeat(8192)
                    repeat(160 * 128) { output.write(block) }
                    output.write(suffix)
                }
            }
            write(draft,
                """{"updatedAt":1,"stylePrompt":"same group","styleExpanded":true,"characters":[{"id":"role","prompt":"person","enabled":false}],"imageGuidance":{"vibes":[{"id":"vibe","encodedVibe":"""" ,
                """"}]}}""")
            write(entry,
                """{"id":"legacy","createdAt":1,"recipe":{"stylePrompt":"same group","imageGuidance":{"vibes":[{"id":"vibe","encodedVibe":"""" ,
                """"}]}}}""")
            val before = listOf(studioLargeSourceHash(draft), studioLargeSourceHash(entry))
            val log = root.resolve("child.log")
            val java = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
            process = ProcessBuilder(java.toString(), "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
                "-Dfile.encoding=UTF-8", "-cp", classpath, DesktopStudioLargeLoadChild::class.java.name, root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Synthetic Studio reader did not finish")
            assertEquals(0, process.exitValue(), Files.readString(log))
            assertEquals("STUDIO_LARGE_TYPED_PASS", Files.readString(root.resolve("result")))
            assertEquals(before, listOf(studioLargeSourceHash(draft), studioLargeSourceHash(entry)))
            assertTrue(Files.size(draft) > 160L * 1024 * 1024)
            assertTrue(Files.size(entry) > 160L * 1024 * 1024)
            val payload = Files.list(root.resolve("novelai-vibe-payloads")).use { it.toList() }
            assertEquals(1, payload.size)
            assertEquals(160L * 1024 * 1024, Files.size(payload.single()))
        } finally {
            process?.let { child ->
                if (child.isAlive) {
                    child.destroyForcibly()
                    check(child.waitFor(30, TimeUnit.SECONDS)) { "Owned synthetic child did not exit" }
                }
            }
            root.toFile().deleteRecursively()
        }
    }
}

private fun studioLargeSourceHash(path: Path): List<Byte> = Files.newInputStream(path).use { source ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val count = source.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toList()
}

/** Synthetic storage reader only; no application, UI, task runtime, network or user-root ingress. */
object DesktopStudioLargeLoadChild {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 1)
        val root = Path.of(args.single())
        check(Files.readString(root.resolve("synthetic-owner")) == "CCB_SYNTHETIC_STUDIO_LARGE_LOAD")
        check(Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
        val storage = JsonFileStorage(root)
        val draft = requireNotNull(storage.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()))
        check(draft.styleExpanded && !draft.characters.single().enabled)
        check(draft.characters.single().prompt == "person")
        val reference = requireNotNull(draft.imageGuidance.vibes.single().encodedVibe)
        check(reference.startsWith(NovelAiInlinePayloadStore.PREFIX) && reference.length < 100)
        val history = storage.loadAll("novelai_generation_history", NovelAiGenerationHistoryEntry.serializer()).single()
        check(history.id == "legacy" && history.recipe.stylePrompt == "same group")
        check(history.recipe.imageGuidance.vibes.single().encodedVibe == reference)
        val reopened = JsonFileStorage(root)
        check(reopened.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()) == draft)
        check(reopened.loadAll("novelai_generation_history", NovelAiGenerationHistoryEntry.serializer()) == listOf(history))
        Files.writeString(root.resolve("result"), "STUDIO_LARGE_TYPED_PASS")
        Unit
    }
}
