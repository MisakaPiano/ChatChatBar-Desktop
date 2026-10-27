package com.example.chatbar.desktop.backup.preparation

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import kotlinx.serialization.json.*
import java.io.StringWriter

internal enum class DormantKind { HISTORIC_VOICE, MOMENT_POST, MOMENT_TASK, UNKNOWN }
internal class DormantInput(val kind: DormantKind, bytes: ByteArray) {
    init { require(bytes.size <= 1024*1024) }
    private val raw = bytes.copyOf()
    val byteLength get() = raw.size
    fun bytes() = raw.copyOf()
    override fun toString() = "DormantInput($kind,REDACTED)"
}
internal class DormantContext(
    cards: Set<String>, sessions: Set<String>, senders: Set<String>,
    messageVersions: Map<Pair<String,String>,Set<String>>,
    val sourceCode: Int = 84, val sourceEvidence: PreparationEvidence = PreparationEvidence.UNKNOWN,
) {
    val cards=cards.toSet();val sessions=sessions.toSet();val senders=senders.toSet()
    val messageVersions=messageVersions.mapValues { it.value.toSet() }
    override fun toString() = "DormantContext(REDACTED)"
}
internal class DormantPrepared internal constructor(raw: List<ByteArray>, media: Map<String,ByteArray>, val requiredBytes: Long, kinds: List<DormantKind>) {
    val recordKinds = kinds.toList()
    private val records = raw.map { it.copyOf() }
    private val resources = media.mapValues { it.value.copyOf() }
    fun rawCopies() = records.map { it.copyOf() }
    fun mediaCopies() = resources.mapValues { it.value.copyOf() }
    val runnable = false
    val published = false
    val productionParticipationVerified = false
    override fun toString() = "DormantPrepared(records=${records.size},resources=${resources.size},INERT)"
}

/** Unwired memory-only prototype. No persisted representation, filesystem, scheduling or network. */
internal object DormantPreservationPreparation {
    class Refused : IllegalArgumentException("DORMANT_PREPARATION_REFUSED")
    fun requiredCapacity(raw: Long, media: Long, reserve: Long): Long {
        if (raw < 0 || media < 0 || reserve < 0) throw Refused()
        return try { Math.addExact(Math.multiplyExact(Math.addExact(raw,media),2),reserve) } catch(_: ArithmeticException) { throw Refused() }
    }
    fun prepare(inputs: List<DormantInput>, media: Map<String,ByteArray>, context: DormantContext,
        available: Long, reserve: Long, active: () -> Unit = {}): DormantPrepared {
        try {
            require(inputs.size in 1..1000 && media.size <= 3000)
            require(context.sourceCode in setOf(84,86) && context.sourceEvidence == PreparationEvidence.SYNTHETIC_VERIFIED)
            require(media.values.all { it.size <= 8*1024*1024 })
            val declaredRaw=inputs.sumOf { it.byteLength.toLong() };val declaredMedia=media.values.sumOf { it.size.toLong() }
            require(declaredRaw+declaredMedia <= 32L*1024*1024)
            val required=requiredCapacity(declaredRaw,declaredMedia,reserve);require(available >= required)
            val raw = inputs.map { active(); it.bytes().also { b -> require(b.size <= 1024*1024) } }
            val copies = media.mapValues { active(); require(it.value.size <= 8*1024*1024); it.value.copyOf() }
            val rawBytes=raw.sumOf { it.size.toLong() };val mediaBytes=copies.values.sumOf { it.size.toLong() }
            require(rawBytes+mediaBytes <= 32L*1024*1024)
            require(rawBytes==declaredRaw && mediaBytes==declaredMedia)
            val ids=mutableSetOf<Pair<DormantKind,String>>();val posts=mutableMapOf<String,MomentPostSnapshot>()
            val tasks=mutableListOf<MomentTaskSnapshot>();val references=mutableSetOf<String>()
            fun resource(ref:String?) { if(ref!=null) {
                require(ref.startsWith("chatbar-backup-file:images/") || ref.startsWith("chatbar-save-slot-audio:"))
                require(ref.none { it.isISOControl() } && '\\' !in ref && ref.split('/').none { it==".." || it=="." })
                require(copies.containsKey(ref));references += ref
            } }
            raw.forEachIndexed { i,bytes ->
                active()
                val out=StringWriter();BackupJsonStream(bytes.inputStream().strictUtf8Reader(),out,checkActive=active){s,_->s}.copy()
                val value=backupJson.parseToJsonElement(out.toString())
                when(inputs[i].kind) {
                    DormantKind.HISTORIC_VOICE -> {
                        val v=backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(value)
                        require(v.id.isNotBlank() && ids.add(inputs[i].kind to v.id))
                        require(v.sessionId in context.sessions && context.messageVersions.containsKey(v.sessionId to v.messageId))
                        require(!v.messageVersionId.isNullOrBlank() && v.messageVersionId !in context.messageVersions.getValue(v.sessionId to v.messageId))
                        resource(v.audioPath)
                    }
                    DormantKind.MOMENT_POST -> {
                        val p=backupJson.decodeFromJsonElement<MomentPostSnapshot>(value)
                        require(p.id.isNotBlank() && ids.add(inputs[i].kind to p.id))
                        require(p.characterCardId in context.cards && p.sessionId in context.sessions)
                        require(p.senderCharacterId == null || p.senderCharacterId in context.senders)
                        posts[p.id]=p;resource(p.senderAvatar);resource(p.imagePath);resource(p.generatedImageMetadata?.imagePath)
                    }
                    DormantKind.MOMENT_TASK -> {
                        val t=backupJson.decodeFromJsonElement<MomentTaskSnapshot>(value)
                        require(t.id.isNotBlank() && ids.add(inputs[i].kind to t.id))
                        require(t.characterCardId in context.cards && t.sessionId in context.sessions);tasks += t
                    }
                    DormantKind.UNKNOWN -> throw Refused()
                }
            }
            tasks.forEach { t -> t.postId?.let { id -> val p=requireNotNull(posts[id]);require(p.sessionId==t.sessionId && p.characterCardId==t.characterCardId) } }
            require(references==copies.keys) // Unknown/unowned supplied media is not silently discarded.
            active();return DormantPrepared(raw,copies,required,inputs.map { it.kind })
        } catch(_: Exception) { throw Refused() } // No raw JSON/ID/path in failures.
    }
}
