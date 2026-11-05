package com.example.chatbar.desktop.backup

import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopPublished1238BackupFixtureTest {
    @Test fun `frozen published writer emits original code86 archive containing Medium and transparency`() = runBlocking<Unit> {
        val parent = Files.createDirectories(Path.of("build/preview-synthetic/frozen1238").toAbsolutePath())
        val root = Files.createTempDirectory(parent, "fixture-")
        try {
            val draft = NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
                basePrompt = "synthetic scene", negativePrompt = "synthetic negative",
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
                    effort = NovelAiEffort.MEDIUM, transparentBackground = true))
            val bytes = backupJson.encodeToString(NovelAiStudioDraft.serializer(), draft).toByteArray()
            val input = Files.write(root.resolve("draft.json"), bytes)
            val output = root.resolve("published1238-studio-v5-original.cbbackup")
            DesktopFullBackupWriter.export(mapOf("entities/novelai_studio_draft.json" to input), output, null)
            var observed: JsonObject? = null
            val proof = DesktopFullBackupSource.verify(output, null, object : DesktopFullBackupSource.TentativeSink {
                override fun entity(path: String, original: JsonObject, raw: ByteArray) { observed = original }
                override fun preferences(original: JsonObject) {}
                override fun media(path: String, input: InputStream) { fail("No media expected") }
            })
            assertEquals(86, proof.manifest.appVersionCode)
            assertEquals("CCB Desktop / official 1.4.7 transport compatibility", proof.manifest.appVersionName)
            val saved = backupJson.decodeFromJsonElement<NovelAiStudioDraft>(assertNotNull(observed))
            assertEquals(NovelAiEffort.MEDIUM, saved.v5Settings.effort)
            assertTrue(saved.v5Settings.transparentBackground)
            assertContentEquals(bytes, Files.readAllBytes(input))
            val review = Path.of(requireNotNull(System.getenv("CCB_FORMAT_AI_REVIEW_DIR")))
            Files.createDirectories(review)
            Files.copy(output, review.resolve(output.fileName))
        } finally { check(root.startsWith(parent)); root.toFile().deleteRecursively() }
    }
}
