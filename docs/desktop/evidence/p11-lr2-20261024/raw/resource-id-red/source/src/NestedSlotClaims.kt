package com.example.chatbar.desktop.backup

import java.io.InputStream
import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*

/** Checks inner v8 ZIP and typed claims without materialization. Not a restore/package reader replacement. */
internal object NestedSlotClaims {
    fun validate(input: InputStream, active: () -> Unit) {
        var slot: SaveSlot? = null; var messages = 0; var rag = 0; var voices = 0
        val names = mutableSetOf<String>()
        val imageReferences = mutableListOf<String>(); val metadataImages = mutableListOf<String>(); val audioReferences = mutableListOf<String>()
        val messageVersions = linkedMapOf<String, Set<String>>(); val voiceReferences = mutableListOf<Pair<String,String?>>()
        val voiceIds = mutableSetOf<String>()
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
                                "messages.jsonl" -> {
                                    val message=backupJson.decodeFromJsonElement<ChatMessage>(value)
                                    require(message.id.isNotBlank() && message.id !in messageVersions)
                                    messageVersions[message.id]=com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy.versions(message).map{it.id}.toSet()
                                    imageReferences += message.images;metadataImages += message.generatedImageMetadata.map{it.imagePath};messages++
                                }
                                "rag.jsonl" -> { backupJson.decodeFromJsonElement<VectorChunk>(value); rag++ }
                                else -> {
                                    val voice=backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(value)
                                    require(voice.id.isNotBlank() && voiceIds.add(voice.id));voices++
                                    audioReferences += voice.audioPath;voiceReferences += voice.messageId to voice.messageVersionId
                                }
                            }
                            require(messages <= 20000 && rag <= 20000 && voices <= 20000 && imageReferences.size <= 20000 && metadataImages.size <= 20000)
                        } finally { bytes.fill(0); row.reset() }
                    }
                    val buffered=entry.buffered(32768)
                    while (true) { active(); val b = buffered.read(); if (b < 0) break
                        if (b == 10) parse() else { require(row.size() < 1024 * 1024); row.write(b) }
                    }; parse()
                }
                else -> when {
                    name.startsWith("media/images/") -> Unit
                    name.startsWith("media/audio/") -> Unit
                    else -> error("NESTED_ENTRY_UNSUPPORTED")
                }
            }
        }
        val s = requireNotNull(slot); require(s.schemaVersion == 8)
        require(setOf("manifest.json", "messages.jsonl", "rag.jsonl").all { it in names })
        require(s.messages.isEmpty() && s.vectorChunks.isEmpty() && s.imageResources.isEmpty() && s.audioResources.isEmpty())
        s.chatBackground?.let{imageReferences += it}
        requireNotNull(s.packageRef).let { require(it.messageCount == messages && it.ragChunkCount == rag && it.imageCount == imageReferences.size && it.audioCount == voices) }
        (imageReferences+metadataImages).forEach { ref -> require(ref.startsWith("chatbar-save-slot-omitted-image:") ||
            ref.startsWith("chatbar-save-slot-image:") && "media/images/${ref.removePrefix("chatbar-save-slot-image:")}" in names) }
        audioReferences.forEach{ref->require(ref.startsWith("chatbar-save-slot-audio:") && "media/audio/${ref.removePrefix("chatbar-save-slot-audio:")}" in names)}
        voiceReferences.forEach{(message,version)->require(message in messageVersions && (version == null || version in messageVersions.getValue(message)))}
    }
}

