package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.VectorChunkStorageContract
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup

/** Deletes only persisted document chunks owned by the target Character. */
internal class DesktopCharacterDocumentRagCleanup(
    private val storage: JsonFileStorage,
) : CharacterDocumentRagCleanup {
    override suspend fun deleteDocumentChunks(characterId: String) {
        storage.deleteWhereUncached(
            VectorChunkStorageContract.ENTITY_TYPE,
            VectorChunk.serializer(),
        ) { chunk ->
            chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.sourceId == characterId
        }
    }
}
