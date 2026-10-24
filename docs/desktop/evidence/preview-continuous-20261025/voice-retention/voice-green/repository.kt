package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.voice.CurrentVoiceSegment
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VoiceSegmentAnchor(
    val segment: CurrentVoiceSegment,
    val anchor: VoiceAnchor
)

data class VoiceMessagePlacement(
    val voice: GeneratedVoiceMessage,
    val segmentIndex: Int?
)

private data class VoiceAnchorStateKey(
    val messageId: String,
    val messageVersionId: String?
)

class VoiceMessageRepository(
    private val storage: JsonFileStorage,
    private val strictReads: Boolean = false,
) {
    private val mutex = Mutex()
    private val _voices = MutableStateFlow<List<GeneratedVoiceMessage>>(emptyList())
    val voices = _voices.asStateFlow()
    private var anchorsByVersion = emptyMap<VoiceAnchorStateKey, VoiceAnchorState>()
    private var initialized = false

    // Android retains its existing loading policy. Desktop opts in to complete, uncached
    // inventories: corrupt entries can never become an empty reference set for deletion.
    private suspend fun loadVoices(): List<GeneratedVoiceMessage> = if (!strictReads) {
        storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
    } else buildList {
        storage.visitEntitiesStrict(VOICE_TYPE, GeneratedVoiceMessage.serializer()) { id, voice ->
            check(id == voice.id && voice.sessionId.isNotBlank() && voice.messageId.isNotBlank())
            add(voice)
        }
    }
    private suspend fun loadAnchors(): List<VoiceAnchorState> = if (!strictReads) {
        storage.loadAll(ANCHOR_TYPE, VoiceAnchorState.serializer())
    } else buildList {
        storage.visitEntitiesStrict(ANCHOR_TYPE, VoiceAnchorState.serializer()) { id, state ->
            check(id == anchorStorageId(VoiceAnchorStateKey(state.messageId, state.messageVersionId)))
            add(state)
        }
    }

    suspend fun initialize() {
        mutex.withLock {
            if (initialized) return@withLock
            _voices.value = loadVoices()
            anchorsByVersion = loadAnchors()
                .associateBy { VoiceAnchorStateKey(it.messageId, it.messageVersionId) }
            initialized = true
        }
    }

    fun observeSession(sessionId: String): Flow<List<GeneratedVoiceMessage>> =
        voices.map { all ->
            all.filter { it.sessionId == sessionId }
                .sortedWith(compareBy(GeneratedVoiceMessage::messageId, GeneratedVoiceMessage::sourceOrder, GeneratedVoiceMessage::createdAt))
        }

    suspend fun get(id: String): GeneratedVoiceMessage? {
        initialize()
        return _voices.value.firstOrNull { it.id == id }
    }

    /** Deletion proof for one explicitly owned artifact, never a permissive cached inventory.
     * Any unknown/corrupt entity invalidates proof and leaves audio preserved.
     */
    suspend fun isAudioReferenced(path: String): Boolean {
        val target = java.nio.file.Path.of(path)
        check(target.isAbsolute && target.normalize() == target)
        var referenced = false
        storage.visitEntitiesStrict(VOICE_TYPE, GeneratedVoiceMessage.serializer()) { id, voice ->
            check(id == voice.id)
            val reference = java.nio.file.Path.of(voice.audioPath)
            check(reference.isAbsolute && reference.normalize() == reference) { "语音音频引用身份未知；文件已保留" }
            if (reference == target) referenced = true
        }
        return referenced
    }

    suspend fun readDurable(id: String) = storage.readEntityStrict(VOICE_TYPE, id, GeneratedVoiceMessage.serializer())

    /** Explicit refresh after a proven durable voice commit; never resets damaged data. */
    suspend fun reload() = mutex.withLock {
        val loaded = loadVoices()
        val anchors = loadAnchors()
        anchorsByVersion = anchors.associateBy { VoiceAnchorStateKey(it.messageId, it.messageVersionId) }
        _voices.value = loaded
        initialized = true
    }

    suspend fun listForSession(sessionId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.sessionId == sessionId }
    }

    suspend fun snapshotAnchorsForMessages(messageIds: Set<String>): List<VoiceAnchorState> = mutex.withLock {
        ensureInitializedLocked()
        anchorsByVersion.values.filter { it.messageId in messageIds }
    }

    /** Caller supplies fresh message identities; preserve exact version/segment placement. */
    suspend fun restoreCopiedAnchors(states: List<VoiceAnchorState>) = mutex.withLock {
        ensureInitializedLocked()
        states.forEach { state ->
            val key = VoiceAnchorStateKey(state.messageId, state.messageVersionId)
            check(key !in anchorsByVersion) { "复制语音锚点身份冲突" }
            storage.saveEntity(ANCHOR_TYPE, anchorStorageId(key), state, VoiceAnchorState.serializer())
            anchorsByVersion = anchorsByVersion + (key to state)
        }
    }

    suspend fun listForMessage(messageId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.messageId == messageId }
    }

    suspend fun listForCurrentVersion(message: ChatMessage): List<GeneratedVoiceMessage> {
        ensureAnchors(message)
        return VoiceMessageVersionPolicy.visibleVoices(message, _voices.value)
    }

    suspend fun ensureAnchors(message: ChatMessage): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureInitializedLocked()
        migrateLegacyVoiceVersionsLocked(message)
        val version = MessageAlternativeVersionPolicy.activeVersion(message)
        ensureVersionAnchorsLocked(message, version.id, version.content)
    }

    suspend fun ensureAnchorsForVersion(
        message: ChatMessage,
        messageVersionId: String,
        content: String
    ): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureInitializedLocked()
        migrateLegacyVoiceVersionsLocked(message)
        ensureVersionAnchorsLocked(message, messageVersionId, content)
    }

    private suspend fun ensureVersionAnchorsLocked(
        message: ChatMessage,
        messageVersionId: String,
        content: String
    ): List<VoiceSegmentAnchor> {
        val key = VoiceAnchorStateKey(message.id, messageVersionId)
        val current = anchorsByVersion[key]
        val reconciliation = if (current == null) {
            val state = VoiceAnchorPolicy.initialState(
                messageId = message.id,
                content = content,
                messageVersionId = messageVersionId,
                sessionId = message.sessionId,
                includeNarration = true
            )
            com.example.chatbar.domain.voice.VoiceAnchorReconciliation(state, emptyMap())
        } else {
            VoiceAnchorPolicy.reconcile(
                old = current,
                newContent = content,
                includeNarration = true
            )
        }
        val state = reconciliation.state.copy(
            messageVersionId = messageVersionId,
            sessionId = message.sessionId
        )
        if (current != state) {
            storage.saveEntity(
                ANCHOR_TYPE,
                anchorStorageId(key),
                state,
                VoiceAnchorState.serializer()
            )
            anchorsByVersion = anchorsByVersion + (key to state)
        }
        if (reconciliation.anchorReplacement.isNotEmpty()) {
            val changed = VoiceMessageVersionPolicy.applyAnchorReplacements(
                voices = _voices.value,
                messageId = message.id,
                messageVersionId = messageVersionId,
                replacements = reconciliation.anchorReplacement
            )
            persistChangedVoicesLocked(_voices.value, changed)
        }
        val segments = VoiceAnchorPolicy.eligibleSegments(
            content,
            includeNarration = true
        )
        return state.anchors.mapIndexedNotNull { index, anchor ->
            segments.getOrNull(index)?.let { VoiceSegmentAnchor(it, anchor) }
        }
    }

    suspend fun placementsForMessage(message: ChatMessage): List<VoiceMessagePlacement> {
        val anchors = ensureAnchors(message)
        val indexByAnchor = anchors.associate { it.anchor.id to it.segment.segmentIndex }
        return VoiceMessageVersionPolicy.visibleVoices(message, _voices.value)
            .map { voice -> VoiceMessagePlacement(voice, voice.anchorId?.let(indexByAnchor::get)) }
            .sortedWith(
                compareBy<VoiceMessagePlacement> { it.segmentIndex ?: -1 }
                    .thenBy { it.voice.sourceOrder }
                    .thenBy { it.voice.createdAt }
            )
    }

    suspend fun save(voice: GeneratedVoiceMessage) = mutex.withLock {
        ensureInitializedLocked()
        storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        _voices.value = _voices.value.filterNot { it.id == voice.id } + voice
    }

    suspend fun replace(voice: GeneratedVoiceMessage) = save(voice)

    suspend fun delete(id: String): GeneratedVoiceMessage? = mutex.withLock {
        ensureInitializedLocked()
        val existing = _voices.value.firstOrNull { it.id == id } ?: return@withLock null
        storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, id)
        _voices.value = _voices.value.filterNot { it.id == id }
        existing
    }

    suspend fun deleteForMessage(messageId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.messageId == messageId }
        removed.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        val anchorKeys = anchorsByVersion.keys.filter { it.messageId == messageId }
        anchorKeys.map(::anchorStorageId).plus(messageId).distinct().forEach { storageId ->
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, storageId)
        }
        anchorsByVersion = anchorsByVersion.filterKeys { it.messageId != messageId }
        _voices.value = _voices.value.filterNot { it.messageId == messageId }
        removed
    }

    suspend fun deleteForSession(sessionId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.sessionId == sessionId }
        val messageIds = buildSet {
            removed.mapTo(this, GeneratedVoiceMessage::messageId)
            anchorsByVersion.values
                .filter { it.sessionId == sessionId }
                .mapTo(this, VoiceAnchorState::messageId)
        }
        removed.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        val anchorKeys = anchorsByVersion.keys.filter { it.messageId in messageIds }
        val anchorStorageIds = anchorKeys.map(::anchorStorageId) + messageIds
        anchorStorageIds.distinct().forEach { storageId ->
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, storageId)
        }
        anchorsByVersion = anchorsByVersion.filterKeys { it.messageId !in messageIds }
        _voices.value = _voices.value.filterNot { it.sessionId == sessionId }
        removed
    }

    suspend fun restoreForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>
    ) = mutex.withLock {
        ensureInitializedLocked()
        val old = _voices.value.filter { it.sessionId == sessionId }
        old.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        voices.forEach { voice ->
            storage.saveEntity(
                VOICE_TYPE,
                voice.id,
                voice.copy(sessionId = sessionId),
                GeneratedVoiceMessage.serializer()
            )
        }
        _voices.value = _voices.value.filterNot { it.sessionId == sessionId } +
            voices.map { it.copy(sessionId = sessionId) }
    }

    suspend fun restoreForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>,
        messages: List<ChatMessage>
    ) {
        restoreForSessionStreaming(sessionId, voices) { emit -> messages.forEach { emit(it) } }
    }

    /** Archive callers can restore anchors without retaining the whole conversation body. */
    suspend fun restoreForSessionStreaming(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>,
        messages: suspend (suspend (ChatMessage) -> Unit) -> Unit
    ) {
        val byMessage = voices.groupBy { it.messageId }
        val rebound = mutableMapOf<String, GeneratedVoiceMessage>()
        messages { message ->
            val states = mutableMapOf<String, VoiceAnchorState>()
            MessageAlternativeVersionPolicy.versions(message).forEach { version ->
                val anchors = ensureAnchorsForVersion(message, version.id, version.content)
                states[version.id] = VoiceAnchorState(message.id, version.id, message.sessionId,
                    version.content, anchors.map(VoiceSegmentAnchor::anchor))
            }
            byMessage[message.id].orEmpty().forEach { voice ->
                // Desktop strict restore keeps an explicit historical identity even when its
                // text version was pruned. It stays invisible to the current-version filter.
                // Android's default legacy inference remains unchanged; null identities still
                // use the existing migration policy on both platforms.
                val versionId = voice.messageVersionId?.takeIf {
                    it in states || (strictReads && it.isNotBlank())
                } ?: VoiceMessageVersionPolicy.inferLegacyVersionId(message, voice, null)
                val state = states[versionId]
                rebound[voice.id] = voice.copy(sessionId = sessionId, messageVersionId = versionId,
                    anchorId = state?.let { VoiceMessageVersionPolicy.anchorIdForVoice(voice, it) } ?: voice.anchorId,
                    updatedAt = System.currentTimeMillis())
            }
        }
        val remapped = voices.map { rebound[it.id] ?: it.copy(sessionId = sessionId, updatedAt = System.currentTimeMillis()) }
        restoreForSession(sessionId, remapped)
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        _voices.value = loadVoices()
        anchorsByVersion = loadAnchors()
            .associateBy { VoiceAnchorStateKey(it.messageId, it.messageVersionId) }
        initialized = true
    }

    private suspend fun migrateLegacyVoiceVersionsLocked(message: ChatMessage) {
        val legacyKey = VoiceAnchorStateKey(message.id, null)
        val legacyState = anchorsByVersion[legacyKey]
        val legacyVoices = _voices.value.filter {
            it.messageId == message.id && it.messageVersionId.isNullOrBlank()
        }
        if (legacyState == null && legacyVoices.isEmpty()) return

        val versions = MessageAlternativeVersionPolicy.versions(message)
        val activeVersionId = MessageAlternativeVersionPolicy.activeVersionId(message)
        val legacyStateVersionId = legacyState?.let { state ->
            versions.firstOrNull { it.content == state.displayContentSnapshot }?.id
                ?: activeVersionId
        }
        if (legacyState != null && legacyStateVersionId != null) {
            val migratedKey = VoiceAnchorStateKey(message.id, legacyStateVersionId)
            if (anchorsByVersion[migratedKey] == null) {
                val migratedState = legacyState.copy(
                    messageVersionId = legacyStateVersionId,
                    sessionId = message.sessionId
                )
                storage.saveEntity(
                    ANCHOR_TYPE,
                    anchorStorageId(migratedKey),
                    migratedState,
                    VoiceAnchorState.serializer()
                )
                anchorsByVersion = anchorsByVersion + (migratedKey to migratedState)
            }
        }

        val changed = _voices.value.map { voice ->
            if (voice.messageId != message.id || !voice.messageVersionId.isNullOrBlank()) {
                voice
            } else {
                val versionId = VoiceMessageVersionPolicy.inferLegacyVersionId(
                    message = message,
                    voice = voice,
                    legacyState = legacyState
                )
                val version = versions.firstOrNull { it.id == versionId }
                val key = VoiceAnchorStateKey(message.id, versionId)
                var state = anchorsByVersion[key]
                if (state == null && version != null) {
                    state = VoiceAnchorPolicy.initialState(
                        messageId = message.id,
                        content = version.content,
                        messageVersionId = versionId,
                        sessionId = message.sessionId,
                        includeNarration = true
                    )
                    storage.saveEntity(
                        ANCHOR_TYPE,
                        anchorStorageId(key),
                        state,
                        VoiceAnchorState.serializer()
                    )
                    anchorsByVersion = anchorsByVersion + (key to state)
                }
                voice.copy(
                    messageVersionId = versionId,
                    anchorId = state?.let {
                        VoiceMessageVersionPolicy.anchorIdForVoice(voice, it)
                    },
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        persistChangedVoicesLocked(_voices.value, changed)
        if (legacyState != null) {
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, anchorStorageId(legacyKey))
            anchorsByVersion = anchorsByVersion - legacyKey
        }
    }

    private suspend fun persistChangedVoicesLocked(
        old: List<GeneratedVoiceMessage>,
        next: List<GeneratedVoiceMessage>
    ) {
        val oldById = old.associateBy(GeneratedVoiceMessage::id)
        next.filter { oldById[it.id] != it }.forEach { voice ->
            storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        }
        _voices.value = next
    }

    private fun anchorStorageId(key: VoiceAnchorStateKey): String {
        val versionId = key.messageVersionId ?: return key.messageId
        return UUID.nameUUIDFromBytes(
            "${key.messageId}\u0000$versionId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    private companion object {
        const val VOICE_TYPE = "generated_voice_messages"
        const val ANCHOR_TYPE = "voice_anchor_states"
    }
}
