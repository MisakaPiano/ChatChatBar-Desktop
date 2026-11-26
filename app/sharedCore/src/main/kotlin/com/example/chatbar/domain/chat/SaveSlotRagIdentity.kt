package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.VectorChunk
import java.util.UUID

/** Portable restore identity only; never changes content or grants source provenance. */
object SaveSlotRagIdentity {
    fun restore(chunk: VectorChunk, targetSessionId: String): VectorChunk {
        require(targetSessionId.isNotBlank() && chunk.id.isNotBlank())
        val restoredId = UUID.nameUUIDFromBytes(
            "$targetSessionId\u0000${chunk.id}".toByteArray(Charsets.UTF_8)
        ).toString()
        return chunk.copy(id = restoredId, sourceId = targetSessionId)
    }
}
