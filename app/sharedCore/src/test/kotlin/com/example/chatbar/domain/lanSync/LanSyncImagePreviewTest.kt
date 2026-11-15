package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncImagePreviewTest {
    @Test fun encodedLengthDoesNotBypassNativeValidationOrMakeOpaqueTextAReference() {
        val root = Files.createTempDirectory("lan-image-policy-data-").toRealPath()
        val state = Files.createTempDirectory("lan-image-policy-state-").toRealPath()
        try {
            var nativeCalls = 0
            val workspace = LanSyncWorkspace(root, state, { Path.of(it) }, validateResource = { _, _, _ ->
                nativeCalls++; require(false) { "SYNC_IMAGE_INVALID" }
            })
            val bytes = ByteArray(32 * 1024 * 1024 + 1) { (it % 251).toByte() }
            val hash = lanSyncHash(bytes); val source = state.resolve("full-document.bin"); Files.write(source, bytes)
            workspace.resourceStore.retain(source, hash, bytes.size.toLong())
            val reference = "ccb-lan-v1/images/11111111-1111-1111-1111-111111111111/$hash/bin"
            val row = LanSyncRecord("character_cards", "card", lanSyncJson.encodeToJsonElement(CharacterCard.serializer(),
                CharacterCard("card", "合成大资源", avatar = reference, extensions = "{\"avatar\":\"$reference\"}", createdAt = 1, updatedAt = 1)).jsonObject)
            assertEquals("SYNC_IMAGE_INVALID", assertFailsWith<IllegalArgumentException> { workspace.previewImage(row, reference) }.message)
            assertEquals(1, nativeCalls)
            val opaque = row.copy(body = JsonObject(row.body + ("avatar" to JsonNull)))
            assertTrue(LanSyncReviewImages.references(opaque).isEmpty())
            assertEquals("SYNC_IMAGE_REFERENCE", assertFailsWith<IllegalArgumentException> { workspace.previewImage(opaque, reference) }.message)
            assertEquals(hash, LanSyncResourceStore.hashFile(source, bytes.size.toLong()))
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }
}
