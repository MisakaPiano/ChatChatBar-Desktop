package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LanSyncGroupFrozenImageContextTest {
    @Test fun frozenInspectionOwnsOnlyItsExactRecordAndReadsHistoricalImageAfterLiveSourceChanges() = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-frozen-image-").toRealPath()
        try {
            val group = LanSyncGroupNodeTest.Group(root)
            val daily = group.End(0); val node = daily.enroll()
            val image = daily.data.resolve("images/avatar.png"); Files.createDirectories(image.parent)
            val path = daily.data.resolve("entities/character_cards/a.json"); Files.createDirectories(path.parent)
            val oldBytes = LanSyncPreviewSample.image(false)
            Files.write(image, oldBytes)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("a", "old frozen record", avatar = image.toString(), createdAt = 1, updatedAt = 1))
            val frozen = node.inspect()
            val oldRecord = requireNotNull(frozen.plan).before.records.single { it.type == "character_cards" }
            val oldReference = LanSyncReviewImages.references(oldRecord).single().reference
            val context = LanSyncGroupFrozenImageContext.Inspection(frozen)
            assertTrue(context.owns(oldRecord, oldReference))
            val preview = node.prepareFrozenImage(oldRecord, oldReference)
            assertNotEquals(image, preview.path)

            val newBytes = LanSyncPreviewSample.image(true)
            assertFalse(oldBytes.contentEquals(newBytes))
            Files.write(image, newBytes)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("a", "new live record", avatar = image.toString(), createdAt = 1, updatedAt = 2))
            val fresh = node.inspect()
            val newRecord = requireNotNull(fresh.plan).before.records.single { it.type == "character_cards" }
            val newReference = LanSyncReviewImages.references(newRecord).single().reference
            assertTrue(context.owns(oldRecord, oldReference))
            assertFalse(context.owns(newRecord, newReference))
            assertFalse(context.owns(oldRecord.copy(body = newRecord.body), oldReference))
            assertFalse(LanSyncGroupFrozenImageContext.Inspection(fresh).owns(oldRecord, oldReference))
            assertContentEquals(oldBytes, node.readFrozenImagePreview(preview))
            assertEquals(0L, daily.author().state().committedSequence)
            assertEquals(0, daily.reloads)

            Files.write(preview.path, newBytes)
            assertEquals("SYNC_CACHED_RESOURCE_DAMAGED", assertFailsWith<IllegalArgumentException> {
                node.readFrozenImagePreview(preview)
            }.message)
            assertContentEquals(newBytes, Files.readAllBytes(image))
            assertEquals("new live record", daily.name("a"))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
