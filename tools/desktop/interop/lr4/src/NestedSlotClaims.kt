package com.example.chatbar.desktop.backup

import java.io.InputStream
import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*

/** Checks inner v8 ZIP and typed claims without materialization. Not a restore/package reader replacement. */
internal object NestedSlotClaims {
    class Refusal(val phase: String) : IllegalArgumentException("NESTED_CLAIMS_REFUSED")
    fun validate(input: InputStream, active: () -> Unit) { analyze(input,active,false) }
    fun analyze(input: InputStream, active: () -> Unit, preserveHistoric: Boolean): Map<String,Int> {
        var phase = "ZIP"
        try {
        var slot: SaveSlot? = null; var messages = 0; var rag = 0; var voices = 0
        val names = mutableSetOf<String>()
        val imageReferences = mutableListOf<String>(); val metadataImages = mutableListOf<String>(); val audioReferences = mutableListOf<String>()
        val messageVersions = linkedMapOf<String, Set<String>>(); val voiceReferences = mutableListOf<Pair<String,String?>>()
        val voiceIds = mutableSetOf<String>()
        val owners=linkedMapOf<String,VoiceReferenceDiagnosis.Owner>()
        val voiceEvidence=mutableListOf<VoiceReferenceDiagnosis.Voice>()
        StrictStreamingZip.walk(input, StrictStreamingZip.Limits(expanded = 256L shl 20, entry = 16L shl 20,
            compressed = 256L shl 20, entries = 4096), active) { name, entry ->
            names += name
            when (name) {
                "manifest.json" -> {
                    phase = "TYPED_MANIFEST"
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
                                    phase = "TYPED_MESSAGE"
                                    val message=backupJson.decodeFromJsonElement<ChatMessage>(value)
                                    if(message.id.isBlank() || message.id in messageVersions) throw StrictStreamingZip.ContentRefusal("DUPLICATE_OR_MALFORMED_IDENTITY")
                                    messageVersions[message.id]=com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy.versions(message).map{it.id}.toSet()
                                    owners[message.id]=VoiceReferenceDiagnosis.Owner(message.sessionId,messageVersions.getValue(message.id))
                                    imageReferences += message.images;metadataImages += message.generatedImageMetadata.map{it.imagePath};messages++
                                }
                                "rag.jsonl" -> { phase = "TYPED_RAG"; backupJson.decodeFromJsonElement<VectorChunk>(value); rag++ }
                                else -> {
                                    phase = "TYPED_VOICE"
                                    val voice=backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(value)
                                    if(voice.id.isBlank() || !voiceIds.add(voice.id)) throw StrictStreamingZip.ContentRefusal("DUPLICATE_OR_MALFORMED_IDENTITY");voices++
                                    voiceEvidence += VoiceReferenceDiagnosis.Voice(voice.messageId,voice.sessionId,voice.messageVersionId)
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
        phase = "MANIFEST_SHAPE"
        val s = requireNotNull(slot); require(s.schemaVersion == 8)
        require(setOf("manifest.json", "messages.jsonl", "rag.jsonl").all { it in names })
        require(s.messages.isEmpty() && s.vectorChunks.isEmpty() && s.imageResources.isEmpty() && s.audioResources.isEmpty())
        s.chatBackground?.let{imageReferences += it}
        phase = "COUNTS"
        requireNotNull(s.packageRef).let { require(it.messageCount == messages && it.ragChunkCount == rag && it.imageCount == imageReferences.size && it.audioCount == voices) }
        fun resources(prefix:String):Set<String> {
            val ids=names.filter{it.startsWith(prefix)}.map{it.substringAfterLast('/').substringBeforeLast('.')}
            require(ids.size==ids.distinct().size);return ids.toSet()
        }
        phase = "MEDIA_IDENTITIES"
        val imageIds=resources("media/images/");val audioIds=resources("media/audio/")
        phase = "IMAGE_REFERENCES"
        (imageReferences+metadataImages).forEach { ref -> require(ref.startsWith("chatbar-save-slot-omitted-image:") ||
            ref.startsWith("chatbar-save-slot-image:") && ref.removePrefix("chatbar-save-slot-image:") in imageIds) }
        phase = "AUDIO_REFERENCES"
        audioReferences.forEach{ref->require(ref.startsWith("chatbar-save-slot-audio:") && ref.removePrefix("chatbar-save-slot-audio:") in audioIds)}
        phase = "VOICE_VERSION_REFERENCES"
        val diagnosis=VoiceReferenceDiagnosis.classify(owners,voiceEvidence)
        val unsupported=diagnosis.keys-setOf("KNOWN_VERSION","LEGACY_VERSION_REPRESENTATION","NONNULL_VERSION_NOT_IN_OFFICIAL_VERSIONS","LEGACY_NONNULL_BLANK_REPRESENTATION")
        if(VoiceReferenceDiagnosis.blocked(diagnosis) && (!preserveHistoric || unsupported.isNotEmpty())) throw Refusal("VOICE_DIAGNOSIS_"+diagnosis.entries.sortedBy{it.key}.joinToString("_"){it.key+"_"+it.value})
        return diagnosis
        } catch(e: Refusal) { throw e
        } catch(e: StrictStreamingZip.Refusal) { throw e
        } catch(_: Exception) { throw Refusal(phase) }
    }
}

