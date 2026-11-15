package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.example.chatbar.domain.image.NovelAiPostProcessResultRecord
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncPostProcessTest {
    private fun record(result: NovelAiPostProcessResultRecord) = LanSyncRecord(LanSyncPostProcess.TYPE, result.id,
        lanSyncJson.encodeToJsonElement(NovelAiPostProcessResultRecord.serializer(), result).jsonObject)

    @Test fun metadataAndImageDigestRefuseBeforePublicationWhileLegacyOmittedImagesRemainOmitted() {
        val root = Files.createTempDirectory("lan-processed-invalid-").toRealPath()
        val state = Files.createTempDirectory("lan-processed-state-").toRealPath()
        val result = NovelAiPostProcessResultRecord("result", "a".repeat(64), "UPSCALE", "ORIGINAL", .5f, 0f,
            OMITTED_SAVE_SLOT_IMAGE_PREFIX + "b".repeat(64), "c".repeat(64), 1)
        // The legacy omission fingerprint is not the paid output digest; retain both unchanged.
        result.validateForImport()
        val ws = LanSyncWorkspace(root, state, root::resolve)
        val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = "pc", records = listOf(record(result))), emptyMap())
        ws.apply(plan, emptyMap(), "phone", emptyMap())
        ws.capture("phone").use { capture ->
            assertEquals(listOf(record(result)), capture.snapshot.records)
            assertTrue(capture.snapshot.resources.isEmpty())
            val file = root.resolve("entities/${LanSyncPostProcess.TYPE}/result.json")
            val bytes = Files.readAllBytes(file)
            val badPath = "ccb-lan-v1/images/${UUID.randomUUID()}/${"d".repeat(64)}/png"
            val invalid = listOf(result.copy(imagePath = badPath), result.copy(operation = "unsupported"),
                result.copy(strength = 1.1f), result.copy(imageSha256 = "invalid"))
            invalid.forEach { broken ->
                val tx = UUID.randomUUID().toString()
                assertFailsWith<IllegalArgumentException> {
                    ws.apply(plan.copy(transaction = tx, snapshot = plan.snapshot.copy(records = listOf(record(broken)))),
                        capture.snapshot.hashes(), "phone", emptyMap())
                }
                assertFalse(Files.exists(state.resolve("transaction-$tx/authorized")))
                assertContentEquals(bytes, Files.readAllBytes(file))
            }
        }
        val source = Files.createTempDirectory("lan-processed-source-").toRealPath()
        val image = source.resolve("images/result.png"); Files.createDirectories(image.parent)
        Files.write(image, LanSyncPreviewSample.image())
        val entity = source.resolve("entities/${LanSyncPostProcess.TYPE}/result.json"); Files.createDirectories(entity.parent)
        Files.writeString(entity, record(result.copy(imagePath = image.toString())).body.toString())
        val original = Files.readAllBytes(entity)
        assertEquals("SYNC_POSTPROCESS_IMAGE", assertFailsWith<IllegalArgumentException> {
            LanSyncWorkspace(source, Files.createTempDirectory("lan-processed-source-state-"), source::resolve).capture("pc")
        }.message)
        assertContentEquals(original, Files.readAllBytes(entity))
        assertContentEquals(LanSyncPreviewSample.image(), Files.readAllBytes(image))
    }
}
