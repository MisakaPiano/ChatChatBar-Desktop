package com.example.chatbar.domain.chat

import java.io.InputStream

data class SaveSlotRestoredAudio(val path: String, val durationMs: Long, val byteLength: Long)
interface SaveSlotAudioRestorePort {
    suspend fun restoreArchivedAudioStream(sessionId: String, resourceId: String, input: InputStream): SaveSlotRestoredAudio
    fun deleteIfOwned(path: String): Boolean
}
