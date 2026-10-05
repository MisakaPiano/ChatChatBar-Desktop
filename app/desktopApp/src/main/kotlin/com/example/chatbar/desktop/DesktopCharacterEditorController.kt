package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterPlaceholderPolicy
import com.example.chatbar.domain.card.CharacterSpeakerNamePolicy
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.CharacterWorldBookBindings
import com.example.chatbar.domain.card.CharacterSectionImportPolicy
import com.example.chatbar.domain.card.CharacterSectionSelection
import com.example.chatbar.domain.card.CharacterCardCharacterImportResult
import com.example.chatbar.domain.card.StructuredCharacterFreeformConverter
import com.example.chatbar.domain.draft.CharacterEditorDraftUiState
import com.example.chatbar.domain.draft.CharacterEditorDraftUiStateCodec
import com.example.chatbar.domain.draft.CharacterEditorPostCommitState
import com.example.chatbar.domain.draft.EditorPostCommitFingerprint
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal enum class CharacterEditorProblem {
    NAME_REQUIRED, GREETING_REQUIRED, CHARACTER_NAME_REQUIRED, DUPLICATE_CHARACTER_NAME,
    DUPLICATE_CARD_NAME, SOURCE_CHANGED, SOURCE_DELETED, COMMUNITY_READ_ONLY, SAVE_FAILED,
    DRAFT_FAILED, RESOURCE_FAILED, NEW_DRAFT_EXISTS, DOCUMENT_READ_FAILED, SAVE_COMMITTED_WARNING,
    CLEAN_DRAFT_WARNING,
}

internal data class DesktopCharacterEditorState(
    val characters: List<CharacterCard> = emptyList(),
    val query: String = "",
    val worldBooks: List<WorldBook> = emptyList(),
    val formatCards: List<FormatCard> = emptyList(),
    val card: CharacterCard? = null,
    val base: CharacterCard? = null,
    val draftBasis: EditorDraft? = null,
    val targetId: String? = null,
    val draftSessionId: String? = null,
    val recoveryTargetId: String? = null,
    val recoverySessionId: String? = null,
    val draftPersisted: Boolean = false,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val leavePrompt: Boolean = false,
    val problem: CharacterEditorProblem? = null,
    val detail: String? = null,
) {
    val visibleCharacters: List<CharacterCard> get() = characters.filter {
        it.name.contains(query, ignoreCase = true) ||
            it.characters.any { character -> character.name.contains(query, ignoreCase = true) }
    }
}

/** Desktop presentation workflow; shared repositories remain the sole entity/draft authorities. */
internal class DesktopCharacterEditorController(
    private val characters: CharacterRepository,
    private val drafts: EditorDraftRepository,
    private val worlds: WorldBookRepository,
    private val formats: FormatCardRepository,
    private val chats: ChatRepository,
    private val resources: DesktopCharacterDraftResources,
    private val json: Json,
    private val filePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistCharacter: suspend (CharacterCard) -> Unit = characters::save,
    private val deleteDraft: suspend (EditorDraftType, String?) -> Unit = drafts::deleteForTarget,
    private val refreshRepository: suspend () -> Unit = characters::refreshFromStorage,
    private val rewriteTitles: suspend (String, String, String) -> Int = chats::rewriteSessionTitlesForCharacterCard,
    private val discardObsoleteResources: suspend (CharacterCard, List<CharacterCard>) -> Unit = resources::discardObsolete,
    private val discardDraftAssets: (String) -> Unit = resources::discardSession,
    private val persistDraftMarker: suspend (EditorDraft) -> EditorDraft = drafts::save,
) {
    private val _state = MutableStateFlow(DesktopCharacterEditorState())
    val state = _state.asStateFlow()
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var leaveAction: (() -> Unit)? = null
    private data class PendingDraft(val targetId: String?, val sessionId: String?)
    private data class PendingRename(val characterId: String, val oldName: String, val newName: String)
    private data class CharacterDraftCleanup(
        val draft: DesktopEditorDraftCleanup,
        val assetFailure: Throwable?,
    ) {
        val removed: Boolean get() = draft.removed
        val blocking: Boolean get() = draft.blocking
    }
    private var committedCard: CharacterCard? = null
    private var pendingDrafts: List<PendingDraft> = emptyList()
    private var pendingRename: PendingRename? = null
    private var pendingObsoleteSource: CharacterCard? = null

    suspend fun load() {
        _state.value = _state.value.copy(
            characters = characters.getAll(), worldBooks = worlds.getAll(), formatCards = formats.getAll(),
        )
    }

    fun search(query: String) { _state.value = _state.value.copy(query = query) }

    suspend fun openNew() {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave { scope.launch { openNew() } }) return
        flushDraft()
        val recovered = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, null)
        val uiState = try { CharacterEditorDraftUiStateCodec.decode(json, recovered?.openModalState) }
        catch (error: Exception) { report(CharacterEditorProblem.DRAFT_FAILED, error); return }
        if (recovered != null && restoreCommittedDraft(recovered, uiState?.postCommit)) return
        // Legacy Desktop committed-new drafts predate the fingerprint marker; keep their accepted recovery path.
        if (recovered != null && uiState?.postCommit == null) {
            val durable = recovered.characterPayload?.id?.let { characters.getById(it) }
            if (durable != null && restoreCommittedDraft(recovered, CharacterEditorPostCommitState(
                durable.id, EditorPostCommitFingerprint.character(json, durable)))) return
        }
        if (recovered?.characterPayload != null) {
            showDraft(recovered, null)
            val markedConflict = try { uiState?.postCommit?.characterId?.let { characters.getById(it) } != null }
            catch (error: Exception) { report(CharacterEditorProblem.DRAFT_FAILED, error); return }
            if (markedConflict)
                _state.value = _state.value.copy(problem = CharacterEditorProblem.SOURCE_CHANGED)
        } else {
            _state.value = _state.value.copy(card = displayCard(CharacterCard.create("")), base = null,
                draftBasis = null, targetId = null, draftSessionId = UUID.randomUUID().toString(),
                draftPersisted = false, dirty = false, problem = null, detail = null)
        }
    }

    suspend fun openExisting(id: String) {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave { scope.launch { openExisting(id) } }) return
        flushDraft()
        val source = characters.getById(id)
        val recovered = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, id)
        val uiState = try { CharacterEditorDraftUiStateCodec.decode(json, recovered?.openModalState) }
        catch (error: Exception) { report(CharacterEditorProblem.DRAFT_FAILED, error); return }
        if (recovered != null && restoreCommittedDraft(recovered, uiState?.postCommit, source)) return
        if (recovered?.characterPayload != null) {
            showDraft(recovered, source)
            return
        }
        if (source == null) {
            _state.value = _state.value.copy(problem = CharacterEditorProblem.SOURCE_DELETED)
            return
        }
        _state.value = _state.value.copy(card = displayCard(source), base = source, targetId = id,
            draftBasis = null, draftSessionId = UUID.randomUUID().toString(), draftPersisted = false,
            dirty = false, problem = if (source.isCommunityDownload) CharacterEditorProblem.COMMUNITY_READ_ONLY else null,
            detail = null)
    }

    private suspend fun restoreCommittedDraft(draft: EditorDraft,
        marker: CharacterEditorPostCommitState?, knownSource: CharacterCard? = null): Boolean {
        if (marker == null || draft.characterPayload?.id != marker.characterId ||
            (draft.targetId != null && draft.targetId != marker.characterId)) return false
        val durable = knownSource?.takeIf { it.id == marker.characterId }
            ?: characters.getById(marker.characterId) ?: return false
        if (EditorPostCommitFingerprint.character(json, durable) != marker.expectedFingerprint) return false
        committedCard = durable
        pendingRename = marker.oldCardName?.let { old ->
            marker.newCardName?.let { new -> PendingRename(durable.id, old, new) }
        }
        pendingDrafts = buildList {
            marker.recoveryTargetId?.let { add(PendingDraft(it, marker.recoverySessionId)) }
            add(PendingDraft(draft.targetId, draft.draftSessionId))
        }
        _state.value = _state.value.copy(card = displayCard(durable), base = durable,
            targetId = durable.id, draftBasis = draft, draftSessionId = draft.draftSessionId,
            recoveryTargetId = marker.recoveryTargetId, recoverySessionId = marker.recoverySessionId,
            dirty = false, draftPersisted = true, problem = CharacterEditorProblem.SAVE_COMMITTED_WARNING)
        retryCommittedCleanup()
        return true
    }

    private fun showDraft(draft: EditorDraft, source: CharacterCard?) {
        val problem = when {
            draft.targetId != null && source == null -> CharacterEditorProblem.SOURCE_DELETED
            source != null && drafts.isChanged(source, draft) -> CharacterEditorProblem.SOURCE_CHANGED
            source?.isCommunityDownload == true -> CharacterEditorProblem.COMMUNITY_READ_ONLY
            else -> null
        }
        val displayed = displayCard(draft.characterPayload!!)
        _state.value = _state.value.copy(card = displayed, base = source, draftBasis = draft,
            targetId = draft.targetId, draftSessionId = draft.draftSessionId,
            draftPersisted = true, dirty = source == null || displayed != displayCard(source) ||
                problem == CharacterEditorProblem.SOURCE_CHANGED, problem = problem, detail = null)
    }

    /** Only the mode changes; the inactive body remains in the payload and in storage. */
    fun switchMode(mode: CharacterEditMode) = edit { it.copy(editMode = mode) }

    fun edit(transform: (CharacterCard) -> CharacterCard) {
        val current = _state.value
        val card = current.card ?: return
        if (!current.dirty && current.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) {
            _state.value = current.copy(problem = CharacterEditorProblem.SAVE_COMMITTED_WARNING)
            return
        }
        if (current.base?.isCommunityDownload == true && current.targetId != null) {
            _state.value = current.copy(problem = CharacterEditorProblem.COMMUNITY_READ_ONLY)
            return
        }
        val transformed = transform(card)
        val next = if (!sameDocumentSources(transformed.customDocuments, card.customDocuments)) transformed.copy(
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0, ragIndexTotal = transformed.customDocuments.size,
            ragIndexMessage = null, ragIndexedAt = null,
        ) else transformed
        if (next == card) return
        _state.value = current.copy(card = next,
            dirty = current.base == null || next != displayCard(current.base), draftPersisted = false,
            problem = current.problem?.takeIf {
                it == CharacterEditorProblem.SOURCE_CHANGED || it == CharacterEditorProblem.SOURCE_DELETED
            }, detail = null)
        scheduleDraft()
    }

    fun addCharacter() = edit { it.copy(characters = it.characters + CharacterInfo.create("")) }

    fun updateCharacter(id: String, transform: (CharacterInfo) -> CharacterInfo) = edit { card ->
        card.copy(characters = card.characters.map { if (it.id == id) transform(it) else it })
    }

    fun removeCharacter(id: String) = edit { card ->
        card.copy(characters = card.characters.filterNot { it.id == id })
    }

    val availableImportCards: List<CharacterCard>
        get() {
            val current = _state.value
            return current.characters.filter { it.id != current.targetId &&
                it.editMode == CharacterEditMode.STRUCTURED && it.characters.any { person -> person.name.isNotBlank() } }
        }

    fun importCharacters(sourceCardId: String,
        selections: List<CharacterSectionSelection>): CharacterCardCharacterImportResult? {
        val before = _state.value
        val card = before.card ?: return null
        if (before.base?.isCommunityDownload == true && before.targetId != null) return null
        val source = availableImportCards.firstOrNull { it.id == sourceCardId } ?: return null
        val result = CharacterSectionImportPolicy.importIntoCharacterCard(card.characters, source.characters, selections)
        if (result.createdCount + result.updatedCount == 0) return null
        edit { it.copy(characters = result.characters) }
        return result.takeIf { _state.value.card?.characters == result.characters }
    }

    val canConvertStructuredToFreeform: Boolean
        get() = _state.value.card?.let { it.editMode == CharacterEditMode.STRUCTURED &&
            StructuredCharacterFreeformConverter.hasConvertibleContent(it.characters) } == true

    /** Called only after the separate Desktop replacement confirmation. */
    fun convertStructuredToFreeform(): Boolean {
        val before = _state.value
        val card = before.card ?: return false
        if (before.base?.isCommunityDownload == true && before.targetId != null) return false
        if (card.editMode != CharacterEditMode.STRUCTURED) return false
        val transition = StructuredCharacterFreeformConverter.createTransition(card.characters) ?: return false
        edit { it.copy(editMode = transition.targetMode,
            freeformCharacterText = transition.freeformCharacterText) }
        return _state.value.card?.editMode == CharacterEditMode.FREEFORM
    }

    fun addGreeting() = edit { it.copy(alternateGreetings = it.alternateGreetings + "") }

    fun setGreeting(index: Int, value: String) = edit { card ->
        card.copy(alternateGreetings = card.alternateGreetings.mapIndexed { i, old -> if (i == index) value else old })
    }

    fun removeGreeting(index: Int) = edit { card ->
        card.copy(alternateGreetings = card.alternateGreetings.filterIndexed { i, _ -> i != index })
    }

    fun toggleWorldBook(id: String) = edit { card ->
        if (id in card.worldBookIds) card.copy(
            worldBookIds = card.worldBookIds - id,
            boundWorldBookId = card.boundWorldBookId?.takeUnless { it == id },
            characterBook = card.characterBook?.takeUnless { it.id == id },
        ) else card.copy(
            worldBookIds = card.worldBookIds + id,
            characterBook = card.characterBook ?: _state.value.base?.characterBook?.takeIf { it.id == id },
        )
    }

    fun setDefaultFormatCard(id: String?) = edit { it.copy(defaultFormatCardId = id) }

    fun chooseAvatar(description: String = "Image") = chooseImage(description) { card, ref -> card.copy(avatar = ref) }
    fun chooseBackground(description: String = "Image") = chooseImage(description) { card, ref -> card.copy(chatBackground = ref) }
    fun chooseAppearance(id: String, description: String = "Image") = chooseImage(description) { card, ref ->
        card.copy(characters = card.characters.map { if (it.id == id) it.copy(appearanceImage = ref) else it })
    }

    private fun chooseImage(description: String, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val selected = filePicker.pickOpenFile(DesktopFileType(description, IMAGE_EXTENSIONS)) ?: return
        stageResource(selected, image = true) { card, ref -> assign(card, ref) }
    }

    fun cropAvatar(bytes: ByteArray) = cropImage(bytes) { card, ref -> card.copy(avatar = ref) }
    fun cropBackground(bytes: ByteArray) = cropImage(bytes) { card, ref -> card.copy(chatBackground = ref) }
    fun cropAppearance(id: String, bytes: ByteArray) = cropImage(bytes) { card, ref ->
        card.copy(characters = card.characters.map { if (it.id == id) it.copy(appearanceImage = ref) else it })
    }

    private fun cropImage(bytes: ByteArray, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        runCatching { resources.stageImage(session, bytes) }
            .onSuccess { ref -> edit { assign(it, ref) } }
            .onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun clearAvatar() = edit { it.copy(avatar = null) }
    fun clearBackground() = edit { it.copy(chatBackground = null) }
    fun clearAppearance(id: String) = updateCharacter(id) { it.copy(appearanceImage = null) }

    fun addDocumentFromPicker(description: String = "Text") {
        if (!canMutate()) return
        val selected = filePicker.pickOpenFile(DesktopFileType(description, TEXT_EXTENSIONS)) ?: return
        stageResource(selected, image = false) { card, ref ->
            card.copy(customDocuments = card.customDocuments + DocumentInfo.create(
                selected.fileName.toString(), ref, documentType(selected.fileName.toString())))
        }
    }

    fun addTextDocument(name: String, content: String) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        runCatching {
            val effectiveName = name.ifBlank { "document.txt" }
            val ref = resources.stageText(session, effectiveName, content)
            edit { card -> card.copy(customDocuments = card.customDocuments +
                DocumentInfo.create(effectiveName, ref, documentType(effectiveName))) }
        }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun editDocument(id: String, name: String, content: String) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        val previous = _state.value.card?.customDocuments?.firstOrNull { it.id == id } ?: return
        runCatching {
            val effectiveName = name.ifBlank { previous.fileName }
            val ref = resources.stageText(session, effectiveName, content)
            edit { card -> card.copy(customDocuments = card.customDocuments.map { doc ->
                if (doc.id == id) doc.copy(fileName = effectiveName, filePath = ref,
                    fileType = documentType(effectiveName, doc.fileType)) else doc
            }) }
        }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun renameDocument(id: String, name: String) = edit { card ->
        card.copy(customDocuments = card.customDocuments.map { doc ->
            if (doc.id == id) {
                val effectiveName = name.ifBlank { doc.fileName }
                doc.copy(fileName = effectiveName, fileType = documentType(effectiveName, doc.fileType))
            } else doc
        })
    }

    fun removeDocument(id: String) = edit { card ->
        card.copy(customDocuments = card.customDocuments.filterNot { it.id == id })
    }

    fun clearDocuments() = edit { it.copy(customDocuments = emptyList()) }

    fun documentText(id: String): Result<String>? = _state.value.card?.customDocuments?.firstOrNull { it.id == id }
        ?.let { runCatching { resources.readDocument(it.filePath) } }

    fun imageBytes(reference: String?): ByteArray? = reference?.let {
        runCatching { resources.readImage(it) }.getOrNull()
    }

    private fun stageResource(path: Path, image: Boolean, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        runCatching { resources.stage(session, path, image) }
            .onSuccess { ref -> edit { assign(it, ref) } }
            .onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    private fun canMutate(): Boolean {
        val current = _state.value
        if (current.base?.isCommunityDownload == true && current.targetId != null) {
            _state.value = current.copy(problem = CharacterEditorProblem.COMMUNITY_READ_ONLY)
            return false
        }
        return true
    }

    private fun displayCard(card: CharacterCard): CharacterCard = card.copy(
        worldBookIds = CharacterWorldBookBindings.effectiveIds(card),
        defaultImageNegativePrompt = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(
            card.defaultImageNegativePrompt),
    )

    private fun documentType(name: String, fallback: String = "txt"): String =
        name.substringAfterLast('.', fallback)

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = scope.launch { delay(700); persistDraft() }
    }

    suspend fun flushDraft() {
        draftJob?.cancel()
        draftJob?.join()
        draftJob = null
        if (_state.value.dirty && !_state.value.draftPersisted) persistDraft()
    }

    private suspend fun persistDraft() = draftMutex.withLock {
        val current = _state.value
        if (!current.dirty || current.draftPersisted) return@withLock
        val card = current.card ?: return@withLock
        val session = current.draftSessionId ?: return@withLock
        val draft = current.draftBasis?.copy(characterPayload = card, draftAssetPaths = draftAssets(card))
            ?: drafts.characterDraft(current.targetId, session, card, current.base,
                draftAssets(card), emptyList(), emptyList())
        try {
            drafts.save(draft)
            if (_state.value.card == card && _state.value.draftSessionId == session) {
                _state.value = _state.value.copy(draftPersisted = true, draftBasis = draft)
            }
        } catch (error: Throwable) { report(CharacterEditorProblem.DRAFT_FAILED, error) }
    }

    private fun draftAssets(card: CharacterCard): List<String> = buildList {
        card.avatar?.let(::add); card.chatBackground?.let(::add)
        card.characters.mapNotNullTo(this) { it.appearanceImage }
        card.customDocuments.mapTo(this) { it.filePath }
    }.filter(resources::isDraft)

    private fun sameDocumentSources(left: List<DocumentInfo>, right: List<DocumentInfo>): Boolean =
        left.map { listOf(it.id, it.fileName, it.filePath, it.fileType, it.addedAt.toString()) } ==
            right.map { listOf(it.id, it.fileName, it.filePath, it.fileType, it.addedAt.toString()) }

    suspend fun save(): Boolean {
        flushDraft()
        val before = _state.value
        val card = before.card ?: return false
        if (before.busy) return false
        if (!before.dirty) return if (before.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) false
            else cleanObsoleteDraft()
        if (before.dirty && !before.draftPersisted) {
            _state.value = before.copy(problem = CharacterEditorProblem.DRAFT_FAILED)
            return false
        }
        if (!validate(card, before.targetId)) return false
        val source = try { before.targetId?.let { characters.getById(it) } }
        catch (error: Throwable) { report(CharacterEditorProblem.SAVE_FAILED, error); return false }
        val newIdConflict = try { before.targetId == null && characters.getById(card.id) != null }
        catch (error: Throwable) { report(CharacterEditorProblem.SAVE_FAILED, error); return false }
        if (newIdConflict) {
            _state.value = before.copy(problem = CharacterEditorProblem.SOURCE_CHANGED)
            return false
        }
        if (before.targetId != null && source == null) {
            _state.value = before.copy(problem = CharacterEditorProblem.SOURCE_DELETED)
            return false
        }
        if (source?.isCommunityDownload == true) {
            _state.value = before.copy(problem = CharacterEditorProblem.COMMUNITY_READ_ONLY)
            return false
        }
        val persistedDraft = try { drafts.getForTarget(EditorDraftType.CHARACTER_CARD, before.targetId) }
        catch (error: Throwable) { report(CharacterEditorProblem.DRAFT_FAILED, error); return false }
        if (source != null && persistedDraft != null && drafts.isChanged(source, persistedDraft)) {
            _state.value = before.copy(problem = CharacterEditorProblem.SOURCE_CHANGED)
            return false
        }
        _state.value = before.copy(busy = true, problem = null, detail = null)
        var created = emptyList<String>()
        try {
            val documentsChanged = source == null || !sameDocumentSources(source.customDocuments, card.customDocuments)
            val mergedDocuments = if (!documentsChanged) source?.customDocuments ?: card.customDocuments else
                card.customDocuments.map { draftDoc ->
                    source?.customDocuments?.firstOrNull { currentDoc ->
                        currentDoc.id == draftDoc.id && sameDocumentSources(listOf(currentDoc), listOf(draftDoc))
                    } ?: draftDoc
                }
            val retained = (source ?: card).copy(
                name = NamePolicy.normalize(card.name), botName = card.botName,
                greeting = card.greeting, alternateGreetings = card.alternateGreetings,
                avatar = card.avatar, chatBackground = card.chatBackground,
                editMode = card.editMode, basicSetting = card.basicSetting,
                freeformCharacterText = card.freeformCharacterText,
                defaultImagePrompt = card.defaultImagePrompt,
                defaultImageNegativePrompt = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(card.defaultImageNegativePrompt),
                systemPrompt = card.systemPrompt, postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample, creatorNotes = card.creatorNotes,
                characters = card.characters.filterNot(CharacterPlaceholderPolicy::isEmpty)
                    .map { it.copy(name = NamePolicy.normalize(it.name)) },
                customDocuments = mergedDocuments, worldBookIds = card.worldBookIds,
                characterBook = null, boundWorldBookId = null,
                defaultFormatCardId = card.defaultFormatCardId,
                ragIndexStatus = if (documentsChanged) card.ragIndexStatus else source?.ragIndexStatus ?: card.ragIndexStatus,
                ragIndexDone = if (documentsChanged) card.ragIndexDone else source?.ragIndexDone ?: card.ragIndexDone,
                ragIndexTotal = if (documentsChanged) card.ragIndexTotal else source?.ragIndexTotal ?: card.ragIndexTotal,
                ragIndexMessage = if (documentsChanged) card.ragIndexMessage else source?.ragIndexMessage ?: card.ragIndexMessage,
                ragIndexedAt = if (documentsChanged) card.ragIndexedAt else source?.ragIndexedAt ?: card.ragIndexedAt,
                updatedAt = System.currentTimeMillis(),
            )
            card.characterBook?.takeIf { it.id in card.worldBookIds }?.let { embedded ->
                if (worlds.getById(embedded.id) == null) {
                    worlds.save(CharacterWorldBookBindings.independentEmbedded(card)!!)
                }
            }
            val (durable, newResources) = resources.materialize(retained)
            created = newResources
            val rename = source?.takeIf { it.name != durable.name }
            val markedDraft = try {
                val activeDraft = persistedDraft ?: error("Character draft missing before durable save")
                val previousUi = CharacterEditorDraftUiStateCodec.decode(json, activeDraft.openModalState)
                    ?: CharacterEditorDraftUiState()
                val marker = CharacterEditorPostCommitState(
                    characterId = durable.id,
                    expectedFingerprint = EditorPostCommitFingerprint.character(json, durable),
                    oldCardName = rename?.name,
                    newCardName = rename?.let { durable.name },
                    recoveryTargetId = before.recoveryTargetId,
                    recoverySessionId = before.recoverySessionId,
                )
                val markerRaw = CharacterEditorDraftUiStateCodec.encode(json, previousUi.copy(postCommit = marker))
                persistDraftMarker(activeDraft.copy(openModalState = markerRaw))
                val confirmed = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, before.targetId)
                require(confirmed != null && confirmed.draftSessionId == before.draftSessionId &&
                    confirmed.openModalState == markerRaw) {
                    "Character post-commit recovery marker was not durable"
                }
                confirmed
            } catch (error: Throwable) {
                resources.rollback(created, error)
                throw error
            }
            val cleanupErrors = mutableListOf<Throwable>()
            try { persistCharacter(durable) } catch (error: Throwable) {
                // Repository cache refresh may fail after the atomic entity write.
                val persisted = runCatching { characters.getById(durable.id) }
                    .onFailure(error::addSuppressed)
                if (persisted.getOrNull() == durable) cleanupErrors += error
                else {
                    if (persisted.isSuccess) resources.rollback(created, error)
                    throw error
                }
            }
            // The entity is durable. Preserve each pending operation until its own authority confirms it.
            committedCard = durable
            pendingDrafts = buildList {
                before.recoveryTargetId?.let { add(PendingDraft(it, before.recoverySessionId)) }
                add(PendingDraft(before.targetId, before.draftSessionId))
            }
            pendingRename = rename?.let { PendingRename(it.id, it.name, durable.name) }
            pendingObsoleteSource = source
            _state.value = _state.value.copy(card = durable, base = durable, targetId = durable.id,
                draftBasis = markedDraft, dirty = false, busy = false,
                problem = CharacterEditorProblem.SAVE_COMMITTED_WARNING,
                detail = cleanupErrors.firstOrNull()?.message)
            retryCommittedCleanup()
            return true
        } catch (error: Throwable) {
            _state.value = _state.value.copy(busy = false)
            report(CharacterEditorProblem.SAVE_FAILED, error)
            return false
        }
    }

    private suspend fun cleanupDraftAndAssets(targetId: String?, sessionId: String?): CharacterDraftCleanup {
        val draft = deleteEditorDraftConfirmed(drafts, EditorDraftType.CHARACTER_CARD, targetId, deleteDraft)
        val assetFailure = if (draft.removed && sessionId != null)
            runCatching { discardDraftAssets(sessionId) }.exceptionOrNull()
        else null
        return CharacterDraftCleanup(draft, assetFailure)
    }

    private fun needsCleanDraftCleanup(current: DesktopCharacterEditorState): Boolean =
        !current.dirty && current.card != null && current.base != null &&
            current.card == displayCard(current.base) &&
            ((current.draftBasis != null && current.draftBasis.targetId == current.targetId) ||
                current.problem == CharacterEditorProblem.CLEAN_DRAFT_WARNING) &&
            current.problem != CharacterEditorProblem.SAVE_COMMITTED_WARNING

    private suspend fun cleanObsoleteDraft(): Boolean = draftMutex.withLock {
        val before = _state.value
        if (!needsCleanDraftCleanup(before)) return@withLock true
        val cleanup = cleanupDraftAndAssets(before.targetId, before.draftSessionId)
        val current = _state.value
        if (current.card != before.card || current.draftSessionId != before.draftSessionId || current.dirty)
            return@withLock false
        if (cleanup.blocking) {
            _state.value = current.copy(draftBasis = if (cleanup.removed) null else current.draftBasis,
                draftPersisted = current.draftPersisted && !cleanup.removed,
                problem = CharacterEditorProblem.CLEAN_DRAFT_WARNING,
                detail = cleanup.draft.errors.firstOrNull()?.message)
            return@withLock false
        }
        _state.value = current.copy(draftBasis = null, draftPersisted = false,
            problem = cleanup.assetFailure?.let { CharacterEditorProblem.RESOURCE_FAILED },
            detail = cleanup.assetFailure?.message)
        true
    }

    suspend fun retryCleanup() {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) retryCommittedCleanup()
        else if (needsCleanDraftCleanup(_state.value)) cleanObsoleteDraft()
    }

    suspend fun retryCommittedCleanup() {
        val before = _state.value
        if (before.card == null || before.dirty) return
        val committed = committedCard ?: return
        val durableResult = runCatching { characters.getById(committed.id) }
        val durable = durableResult.getOrNull()
        val refreshed = runCatching { refreshRepository(); characters.getAll() }
        val cacheReady = durable == committed && refreshed.getOrNull()?.any { it == committed } == true
        val renameError = pendingRename?.takeIf { cacheReady }?.let { rename ->
            runCatching { rewriteTitles(rename.characterId,
                rename.oldName, rename.newName) }.exceptionOrNull().also { if (it == null) pendingRename = null }
        }
        // Keep the recovery marker physically durable until both cache and required rename agree.
        val semanticReady = cacheReady && pendingRename == null
        val cleanup = linkedMapOf<PendingDraft, CharacterDraftCleanup>()
        if (semanticReady) for (pending in pendingDrafts.distinct()) {
            val result = cleanupDraftAndAssets(pending.targetId, pending.sessionId)
            cleanup[pending] = result
            // Recovery drafts precede the active marker; never erase that marker if one remains.
            if (result.blocking) break
        }
        val draftsReady = semanticReady && cleanup.size == pendingDrafts.distinct().size &&
            cleanup.values.none(CharacterDraftCleanup::blocking)
        val orphanError = pendingObsoleteSource?.takeIf { draftsReady }?.let { source ->
            runCatching { discardObsoleteResources(source, refreshed.getOrThrow()) }
                .exceptionOrNull().also { if (it == null) pendingObsoleteSource = null }
        }
        val blocking = !draftsReady
        val activeRemoved = before.draftBasis == null ||
            cleanup[PendingDraft(before.draftBasis.targetId, before.draftSessionId)]?.removed == true
        val recoveryRemoved = before.recoveryTargetId?.let {
            cleanup[PendingDraft(it, before.recoverySessionId)]?.removed == true
        } ?: true
        val assetError = cleanup.values.firstNotNullOfOrNull(CharacterDraftCleanup::assetFailure)
        _state.value = before.copy(
            draftBasis = if (activeRemoved) null else before.draftBasis,
            draftPersisted = !activeRemoved,
            recoveryTargetId = if (recoveryRemoved) null else before.recoveryTargetId,
            recoverySessionId = if (recoveryRemoved) null else before.recoverySessionId,
            characters = refreshed.getOrNull() ?: before.characters,
            problem = when {
                blocking -> CharacterEditorProblem.SAVE_COMMITTED_WARNING
                orphanError != null || assetError != null -> CharacterEditorProblem.RESOURCE_FAILED
                else -> null
            },
            detail = (durableResult.exceptionOrNull() ?: refreshed.exceptionOrNull() ?: renameError
                ?: cleanup.values.flatMap { it.draft.errors }.firstOrNull()
                ?: orphanError ?: assetError)?.message
                ?: if (!cacheReady) "Committed Character could not be verified" else null,
        )
        if (!blocking) {
            committedCard = null
            pendingDrafts = emptyList()
            pendingRename = null
            pendingObsoleteSource = null
        }
    }

    private suspend fun validate(card: CharacterCard, targetId: String?): Boolean {
        val issue = when {
            NamePolicy.normalize(card.name).isBlank() -> CharacterEditorProblem.NAME_REQUIRED
            card.greeting.isBlank() -> CharacterEditorProblem.GREETING_REQUIRED
            card.characters.any { !CharacterPlaceholderPolicy.isEmpty(it) && it.name.isBlank() } ->
                CharacterEditorProblem.CHARACTER_NAME_REQUIRED
            CharacterSpeakerNamePolicy.duplicateNames(card.characters).isNotEmpty() ->
                CharacterEditorProblem.DUPLICATE_CHARACTER_NAME
            NamePolicy.conflict(card.name, characters.getAll().map { it.id to it.name }, targetId) != null ->
                CharacterEditorProblem.DUPLICATE_CARD_NAME
            else -> null
        }
        if (issue != null) { _state.value = _state.value.copy(problem = issue); return false }
        return true
    }

    /** Recovery and read-only copy retain editor payload but sever the protected source identity. */
    suspend fun saveAsNew() {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        flushDraft()
        val before = _state.value
        val card = before.card ?: return
        val existingNew = try { drafts.getForTarget(EditorDraftType.CHARACTER_CARD, null) }
        catch (error: Throwable) { report(CharacterEditorProblem.DRAFT_FAILED, error); return }
        if (existingNew != null && existingNew.draftSessionId != before.draftSessionId) {
            _state.value = before.copy(problem = CharacterEditorProblem.NEW_DRAFT_EXISTS)
            return
        }
        val newCard = card.copy(id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(card.name, characters.getAll().map { it.name }),
            communityItemId = null, communityItemUpdatedAt = null, communityItemSha256 = null,
            communityItemTitle = null, sourcePresetKey = null, sourcePresetVersion = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        val newSession = UUID.randomUUID().toString()
        _state.value = before.copy(card = newCard, base = null, draftBasis = null, targetId = null,
            draftSessionId = newSession, recoveryTargetId = before.targetId,
            recoverySessionId = before.draftSessionId,
            dirty = true, draftPersisted = false, problem = null)
        // Preserve the original conflict draft; user may recover it independently.
        persistDraft()
    }

    suspend fun discard() {
        draftJob?.cancelAndJoin()
        draftJob = null
        val before = _state.value
        if (!before.dirty && before.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        val draftTarget = if (before.draftBasis != null) before.draftBasis.targetId else before.targetId
        val cleanup = cleanupDraftAndAssets(draftTarget, before.draftSessionId)
        if (!cleanup.removed) {
            _state.value = before.copy(problem = CharacterEditorProblem.DRAFT_FAILED,
                detail = cleanup.draft.errors.firstOrNull()?.message)
            return
        }
        _state.value = before.copy(card = null, base = null, draftBasis = null, targetId = null, draftSessionId = null,
            recoveryTargetId = null, recoverySessionId = null,
            dirty = false, draftPersisted = false, leavePrompt = false,
            problem = (cleanup.assetFailure ?: cleanup.draft.errors.firstOrNull())
                ?.let { CharacterEditorProblem.RESOURCE_FAILED },
            detail = (cleanup.assetFailure ?: cleanup.draft.errors.firstOrNull())?.message)
        leaveAction?.also { leaveAction = null; it() }
    }

    fun requestLeave(action: () -> Unit) {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave(action)) return
        if (needsCleanDraftCleanup(_state.value)) {
            scope.launch { if (cleanObsoleteDraft() && !_state.value.dirty) action() }
        } else action()
    }

    private fun deferLeave(action: () -> Unit): Boolean {
        if (_state.value.card != null && _state.value.dirty) {
            leaveAction = action
            _state.value = _state.value.copy(leavePrompt = true)
            return true
        }
        return false
    }

    fun continueEditing() { leaveAction = null; _state.value = _state.value.copy(leavePrompt = false) }

    suspend fun saveAndLeave() {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (save()) {
            if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) {
                _state.value = _state.value.copy(leavePrompt = false)
                leaveAction = null
                return
            }
            _state.value = _state.value.copy(card = null, base = null, leavePrompt = false)
            leaveAction?.also { leaveAction = null; it() }
        }
    }

    fun closeClean() {
        if (_state.value.dirty) { requestLeave { closeClean() }; return }
        if (needsCleanDraftCleanup(_state.value)) { requestLeave { closeClean() }; return }
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        _state.value = _state.value.copy(card = null, base = null, leavePrompt = false, problem = null)
        leaveAction?.also { leaveAction = null; it() }
    }

    suspend fun closeAndDrain() {
        flushDraft()
        when {
            _state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING -> retryCommittedCleanup()
            needsCleanDraftCleanup(_state.value) -> cleanObsoleteDraft()
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun report(problem: CharacterEditorProblem, error: Throwable) {
        _state.value = _state.value.copy(problem = problem, detail = error.message)
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")
        val TEXT_EXTENSIONS = listOf("txt", "md", "json")
    }
}
