package com.example.chatbar.desktop.backup

import java.io.InputStream
import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*

/** Checks inner v8 ZIP and typed claims without materialization. Not a restore/package reader replacement. */
internal object NestedSlotClaims {
    fun validate(input: InputStream, active: () -> Unit) {
        var slot: SaveSlot? = null; var messages = 0; var rag = 0; var audio = 0; var images = 0
        val names = mutableSetOf<String>()
        StrictStreamingZip.walk(input, StrictStreamingZip.Limits(expanded = 256L shl 20, entry = 16L shl 20,
            compressed = 256L shl 20, entries = 4096), active) { name, entry ->
            names += name
            when (name) {
                "manifest.json" -> {
                    val bytes = LargeArchiveReadOnly.bounded(entry, 256 * 1024, active)
                    try { slot = backupJson.decodeFromJsonElement<SaveSlot>(LargeArchiveReadOnly.strict(bytes, active)) }
                    finally { bytes.fill(0) }
                }
                "messages.jsonl", "rag.jsonl", "voices.jsonl" -> {
                    // Bounded JSONL; never readLine() before checking the maximum record size.
                    val row = java.io.ByteArrayOutputStream()
                    fun parse() {
                        if (row.size() == 0) return
                        val bytes = row.toByteArray()
                        try {
                            val value = LargeArchiveReadOnly.strict(bytes, active)
                            when (name) {
                                "messages.jsonl" -> { backupJson.decodeFromJsonElement<ChatMessage>(value); messages++ }
                                "rag.jsonl" -> { backupJson.decodeFromJsonElement<VectorChunk>(value); rag++ }
                                else -> backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(value)
                            }
                            require(messages <= 20000 && rag <= 20000)
                        } finally { bytes.fill(0); row.reset() }
                    }
                    while (true) { active(); val b = entry.read(); if (b < 0) break
                        if (b == 10) parse() else { require(row.size() < 1024 * 1024); row.write(b) }
                    }; parse()
                }
                else -> when {
                    name.startsWith("media/images/") -> images++
                    name.startsWith("media/audio/") -> audio++
                    else -> error("NESTED_ENTRY_UNSUPPORTED")
                }
            }
        }
        val s = requireNotNull(slot); require(s.schemaVersion == 8)
        require(setOf("manifest.json", "messages.jsonl", "rag.jsonl").all { it in names })
        require(s.messages.isEmpty() && s.vectorChunks.isEmpty() && s.imageResources.isEmpty() && s.audioResources.isEmpty())
        requireNotNull(s.packageRef).let { require(it.messageCount == messages && it.ragChunkCount == rag && it.imageCount == images && it.audioCount == audio) }
    }
}
