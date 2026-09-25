package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.VectorChunkStorageContract
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCharacterDocumentRagCleanupTest {
    @Test
    fun `cleanup deletes only target Character document chunks`() = runTest {
        val root = Files.createTempDirectory("desktop-rag-cleanup-")
        try {
            val storage = JsonFileStorage(root)
            val chunks = listOf(
                chunk("target-doc", ChunkSourceType.DOCUMENT, "target"),
                chunk("other-doc", ChunkSourceType.DOCUMENT, "other"),
                chunk("memory", ChunkSourceType.CHAT_MEMORY, "target"),
                chunk("setting", ChunkSourceType.CHARACTER_SETTING, "target"),
            )
            storage.saveAllUncached(
                VectorChunkStorageContract.ENTITY_TYPE,
                chunks.associateBy(VectorChunk::id),
                VectorChunk.serializer(),
            )

            DesktopCharacterDocumentRagCleanup(storage).deleteDocumentChunks("target")
            val remaining = storage.queryUncached(
                VectorChunkStorageContract.ENTITY_TYPE,
                VectorChunk.serializer(),
            ) { true }

            assertEquals(setOf("other-doc", "memory", "setting"), remaining.map(VectorChunk::id).toSet())
            DesktopCharacterDocumentRagCleanup(storage).deleteDocumentChunks("missing")
            assertEquals(3, storage.queryUncached(VectorChunkStorageContract.ENTITY_TYPE, VectorChunk.serializer()) { true }.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun chunk(id: String, type: ChunkSourceType, sourceId: String) = VectorChunk(
        id = id,
        sourceType = type,
        sourceId = sourceId,
        content = id,
        embedding = emptyList(),
        createdAt = 1L,
    )
}
