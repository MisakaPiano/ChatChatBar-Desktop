package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopPostProcessResultRecord
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopPostProcessRetainedMediaTest {
    @Test fun `take incoming metadata only preserves matching computer processed image through actual destination mapping`() {
        val path = "entities/desktop_image_post_process_results/result.json"
        val record = DesktopPostProcessResultRecord("result", "a".repeat(64), "UPSCALE", "ORIGINAL", .5f, 0f,
            DesktopBackupPolicy.PREFIX + "images/processed.png", "b".repeat(64), 1)
        fun json(r: DesktopPostProcessResultRecord) = backupJson.encodeToJsonElement(DesktopPostProcessResultRecord.serializer(), r).jsonObject
        val pc = mapOf(path to json(record))
        val incoming = mapOf(path to json(record.copy(imagePath = OMITTED_SAVE_SLOT_IMAGE_PREFIX + "c".repeat(64))))
        val scope = InspectionScope(emptySet(), 0, 0, 0, 0, 0, false, true, false, false)
        val media = mapOf("images/processed.png" to "d".repeat(64))
        val preview = DesktopMergePreview.create(pc, incoming, media, emptyMap(), scope)
        val selection = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE })
        DesktopMergeDestinationPlan.create(selection, media, emptyMap()).use { plan ->
            assertEquals(1, plan.resources.size)
            assertEquals(DesktopMergeDestinationPlan.Side.COMPUTER, plan.resources.values.single().side)
            val restored = plan.entities.getValue(path)
            assertEquals(DesktopBackupPolicy.PREFIX + plan.resources.keys.single(), restored.getValue("imagePath").jsonPrimitive.content)
            assertEquals(record.imageSha256, restored.getValue("imageSha256").jsonPrimitive.content)
        }
        val computer = DesktopMergePreview.Unit("key", "desktop_image_post_process_results", "result", pc, "signature")
        val retention = DesktopMergeRetainedMedia(scope, computer) {}
        assertNull(retention.fields(DesktopMergeRecord(path, json(record.copy(sourceSha256 = "e".repeat(64), imagePath = OMITTED_SAVE_SLOT_IMAGE_PREFIX + "c".repeat(64))))))
        assertNull(retention.fields(DesktopMergeRecord(path, json(record.copy(imageSha256 = "e".repeat(64), imagePath = OMITTED_SAVE_SLOT_IMAGE_PREFIX + "c".repeat(64))))))
    }
}
