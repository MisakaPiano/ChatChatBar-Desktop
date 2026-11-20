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
import com.example.chatbar.domain.draft.CharacterEditorSemanticProjection
import com.example.chatbar.domain.chat.SpeakerTagHistoryService
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import com.example.chatbar.domain.image.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal enum class CharacterEditorProblem {
    NAME_REQUIRED, GREETING_REQUIRED, CHARACTER_NAME_REQUIRED, DUPLICATE_CHARACTER_NAME,
    DUPLICATE_CARD_NAME, SOURCE_CHANGED, SOURCE_DELETED, COMMUNITY_READ_ONLY, SAVE_FAILED,
    DRAFT_FAILED, RESOURCE_FAILED, NEW_DRAFT_EXISTS, DOCUMENT_READ_FAILED, SAVE_COMMITTED_WARNING,
    CLEAN_DRAFT_WARNING, PERSON_UNAVAILABLE,
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
    val cleanDraftCleanupInProgress: Boolean = false,
    val leavePrompt: Boolean = false,
    val problem: CharacterEditorProblem? = null,
    val detail: String? = null,
    val resourceCleanupPending: Boolean = false,
    val saveAsNewFailed: Boolean = false,
    val personFocusId: String? = null,
    val personFocusRevision: Long = 0,
    val momentsFocusRevision: Long = 0,
    val stylePromptUndo: NovelAiStylePromptUndo? = null,
    val documentFocusId: String? = null,
    val documentFocusRevision: Long = 0,
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
    val fishLibrary: (() -> DesktopFishAudioLibrary)? = null,
    val fishCredentials: DesktopFishCredentialController? = null,
    val fishPreview: (() -> DesktopFishPreviewRuntime)? = null,
    private val dataGate: com.example.chatbar.data.operation.AppDataOperationGate = com.example.chatbar.data.operation.NoOpAppDataOperationGate,
    val documentIndex: DesktopRagController? = null,
    val backgroundLibrary: DesktopCharacterBackgrounds? = null,
    private val speakerHistory: SpeakerTagHistoryService = SpeakerTagHistoryService(characters, chats),
    val imageModelSettings: kotlinx.coroutines.flow.Flow<com.example.chatbar.domain.image.NovelAiImageModel?> = kotlinx.coroutines.flow.flowOf(null),
    private val manualResourceAccess: ((() -> Unit) -> Unit) = { it() },
) {
    internal var cardAi: DesktopCharacterCardAiController? = null
    internal var promptBudget: DesktopEditorBudgetController? = null
    internal var appearanceAi: DesktopCharacterAppearanceController? = null
    internal var cardImages: DesktopCharacterImageController? = null
    internal var imagePromptTools: (() -> DesktopCharacterPromptTools)? = null
    internal val canUseCardAi: Boolean get() = _state.value.let { s ->
        s.card != null && !s.busy && s.draftSessionId != null &&
            !desktopCharacterEditorPresentation(s).readOnly && s.problem !in setOf(
                CharacterEditorProblem.SOURCE_CHANGED, CharacterEditorProblem.SOURCE_DELETED)
    }

    internal fun applyCardAi(session: String, basis: CharacterCard, result: CharacterCard): Boolean = synchronized(resourceMutationLock) {
        val current = _state.value
        if (!canUseCardAi || current.draftSessionId != session || current.card != basis ||
            result.id != basis.id || result.createdAt != basis.createdAt) return@synchronized false
        edit { result }
        _state.value.card == result
    }

    internal fun stageGeneratedCardImage(session: String, basis: CharacterCard, destination: CharacterCard,
        personId: String?, image: ByteArray, crop: ByteArray? = null,
        assign: (CharacterCard) -> Boolean = { applyCardAi(session, basis, it) }): Boolean = synchronized(resourceMutationLock) {
        if (!canUseCardAi || _state.value.draftSessionId != session || _state.value.card != basis) return@synchronized false
        require(destination.id == basis.id && destination.createdAt == basis.createdAt)
        val staged = mutableListOf<String>()
        var assignmentEntered = false
        try {
            val full = resources.stageImage(session, image).also(staged::add)
            val next = if (personId == null) {
                val avatar = crop?.let { resources.stageImage(session, it).also(staged::add) } ?: full
                val cover = resources.copyAsCover(session, full).also(staged::add)
                destination.copy(avatar = avatar, chatBackground = full, coverImage = cover)
            } else {
                require(destination.characters.count { it.id == personId } == 1)
                destination.copy(characters = destination.characters.map { if (it.id == personId) it.copy(appearanceImage = full) else it })
            }
            assignmentEntered = true
            if (assign(next)) return@synchronized true
            staged.asReversed().forEach(resources::discardNew)
            false
        } catch (error: Throwable) {
            if (!assignmentEntered) staged.asReversed().forEach { runCatching { resources.discardNew(it) }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    private val resourceMutationLock = Any()
    private val _state = MutableStateFlow(DesktopCharacterEditorState())
    val state = _state.asStateFlow()
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var leaveAction: (() -> Unit)? = null
    private val pendingCleanLeave = AtomicBoolean()
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
    private val committedCleanupMutex = Mutex()

    private fun withManualResourceSession(session: String, operation: () -> Unit) = manualResourceAccess {
        synchronized(resourceMutationLock) {
            val current = _state.value
            check(current.card != null && current.draftSessionId == session && canMutate()) { "草稿已切换，请重新添加资料" }
            operation()
        }
    }
    private val resourceCleanupCards = linkedSetOf<CharacterCard>()
    private val resourceCleanupSessions = linkedSetOf<String>()

    suspend fun load() {
        val cards = characters.getAll()
        val worldBooks = worlds.getAll()
        val formatCards = formats.getAll()
        // Catalog reads suspend. Apply them to the latest state so a concurrent open/edit survives.
        _state.update { it.copy(characters = cards, worldBooks = worldBooks, formatCards = formatCards) }
    }

    fun search(query: String) { _state.update { it.copy(query = query) } }

    suspend fun openNew() {
        if (leaveBlocked()) return
        if (deferLeave { scope.launch { openNew() } }) return
        flushDraft()
        val recovered = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, null)
        if (leaveBlocked()) return
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
                draftPersisted = false, dirty = false, problem = null, detail = null, saveAsNewFailed = false)
        }
    }

    suspend fun openPerson(cardId: String, personId: String) {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (_state.value.targetId != cardId || _state.value.card == null) {
            if (deferLeave { scope.launch { openPerson(cardId, personId) } }) return
            openExisting(cardId)
        }
        val source = characters.getById(cardId)
        _state.update { current ->
            if (current.targetId != cardId || current.card == null || current.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) current
            else if (current.card.characters.count { it.id == personId } != 1 || source?.characters?.count { it.id == personId } != 1)
                current.copy(problem = CharacterEditorProblem.PERSON_UNAVAILABLE)
            else current.copy(personFocusId = personId, personFocusRevision = current.personFocusRevision + 1)
        }
    }

    suspend fun openMoments(cardId: String) {
        if (characters.getById(cardId) == null) {
            _state.value = _state.value.copy(problem = CharacterEditorProblem.SOURCE_DELETED)
            return
        }
        if (_state.value.targetId != cardId || _state.value.card == null) {
            if (deferLeave { scope.launch { openMoments(cardId) } }) return
            openExisting(cardId)
        }
        _state.update { current -> if (current.targetId == cardId && current.card != null)
            current.copy(momentsFocusRevision = current.momentsFocusRevision + 1) else current }
    }

    suspend fun openExisting(id: String) {
        if (leaveBlocked()) return
        if (deferLeave { scope.launch { openExisting(id) } }) return
        flushDraft()
        val source = characters.getById(id)
        val recovered = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, id)
        if (leaveBlocked()) return
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

    suspend fun openDocument(cardId: String, documentId: String, unavailable: () -> Unit) {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
        if (_state.value.targetId != cardId || _state.value.card == null) {
            if (deferLeave { scope.launch { openDocument(cardId, documentId, unavailable) } }) return
            openExisting(cardId)
        }
        val source = characters.getById(cardId)
        while (true) {
            val current = _state.value
            if (current.targetId != cardId || current.card?.customDocuments?.count { it.id == documentId } != 1 ||
                source?.customDocuments?.count { it.id == documentId } != 1) {
                unavailable(); return
            }
            if (_state.compareAndSet(current, current.copy(documentFocusId = documentId,
                    documentFocusRevision = current.documentFocusRevision + 1))) return
        }
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
            draftPersisted = true, dirty = source == null || !sameEditableCard(displayed, source) ||
                problem == CharacterEditorProblem.SOURCE_CHANGED, problem = problem, detail = null, saveAsNewFailed = false)
    }

    /** Only the mode changes; the inactive body remains in the payload and in storage. */
    fun switchMode(mode: CharacterEditMode) = edit { it.copy(editMode = mode) }

    fun edit(transform: (CharacterCard) -> CharacterCard) {
        synchronized(resourceMutationLock) {
            val current = _state.value
            val card = current.card ?: return
            if (current.busy) return
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
                stylePromptUndo = current.stylePromptUndo?.takeIf {
                    next.defaultImagePrompt == it.appliedPrompt && next.defaultImageNegativePrompt == it.appliedNegativePrompt
                },
                dirty = current.base == null || !sameEditableCard(next, current.base), draftPersisted = false,
                problem = current.problem?.takeIf {
                    it == CharacterEditorProblem.SOURCE_CHANGED || it == CharacterEditorProblem.SOURCE_DELETED ||
                        it == CharacterEditorProblem.RESOURCE_FAILED && current.resourceCleanupPending
                }, detail = null)
            scheduleDraft()
        }
    }


    /** A picker belongs to one draft lifetime and one existing person, never a list position. */
    fun bindFishVoice(draftSessionId: String, personId: String,
        binding: com.example.chatbar.data.local.entity.FishAudioVoiceBinding?): Boolean {
        if (_state.value.draftSessionId != draftSessionId ||
            _state.value.card?.characters?.none { it.id == personId } != false) return false
        updateCharacter(personId) { it.copy(fishAudioVoice = binding) }
        return _state.value.card?.characters?.firstOrNull { it.id == personId }?.fishAudioVoice == binding
    }

    fun addCharacter() = edit { it.copy(characters = it.characters + CharacterInfo.create("")) }

    fun setCharacterEntryEnabled(id: String, enabled: Boolean) = edit { card ->
        if (card.editMode != CharacterEditMode.STRUCTURED || card.characters.count { it.id == id } != 1) card
        else card.copy(characters = card.characters.map { if (it.id == id) it.copy(desktopEnabled = enabled) else it })
    }

    fun updateCharacter(id: String, transform: (CharacterInfo) -> CharacterInfo) = edit { card ->
        card.copy(characters = card.characters.map { if (it.id == id) transform(it) else it })
    }

    /** Tag/expanded-editor callbacks belong to the captured card draft and person, never current selection. */
    internal fun updateCharacterImagePrompt(sessionId: String, cardId: String, personId: String, text: String): Boolean {
        val current = _state.value
        val card = current.card ?: return false
        if (current.draftSessionId != sessionId || card.id != cardId || card.editMode != CharacterEditMode.STRUCTURED ||
            card.characters.count { it.id == personId } != 1) return false
        updateCharacter(personId) { it.copy(imagePrompt = text) }
        return _state.value.card?.characters?.singleOrNull { it.id == personId }?.imagePrompt == text
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

    fun moveGreeting(index: Int, offset: Int) = edit { card ->
        val destination = index + offset
        if (offset !in listOf(-1, 1) || index !in card.alternateGreetings.indices ||
            destination !in card.alternateGreetings.indices) card
        else card.copy(alternateGreetings = card.alternateGreetings.toMutableList().apply {
            add(destination, removeAt(index))
        })
    }

    fun setCardTag(index: Int, text: String) = edit { card ->
        card.copy(tags = card.tags.mapIndexed { current, old -> if (current == index) text else old })
    }

    fun removeCardTag(index: Int) = edit { card ->
        card.copy(tags = card.tags.filterIndexed { current, _ -> current != index })
    }

    fun addCardTag(text: String) = edit { card ->
        val tag = text.trim()
        if (tag.isEmpty() || card.tags.any { it.trim().equals(tag, ignoreCase = true) }) card
        else card.copy(tags = card.tags + tag)
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
    fun chooseCover(description: String = "Image") {
        if (!canMutate()) return
        val selected = filePicker.pickOpenFile(DesktopFileType(description, IMAGE_EXTENSIONS)) ?: return
        stageResource(selected, image = true, originalCover = true) { card, ref -> card.copy(coverImage = ref) }
    }

    fun copyAvatarAsCover() {
        if (!canMutate()) return
        val avatar = _state.value.card?.avatar ?: return
        val session = _state.value.draftSessionId ?: return
        runCatching { withManualResourceSession(session) {
            val reference = resources.copyAsCover(session, avatar)
            edit { it.copy(coverImage = reference) }
        } }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }
    fun chooseBackground(description: String = "Image") = chooseImage(description) { card, ref -> card.copy(chatBackground = ref, chatBackgroundImages = (card.chatBackgroundImages + listOfNotNull(card.chatBackground) + ref).distinct()) }
    fun chooseAppearance(id: String, description: String = "Image") = chooseImage(description) { card, ref ->
        card.copy(characters = card.characters.map { if (it.id == id) it.copy(appearanceImage = ref) else it })
    }

    private fun chooseImage(description: String, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val selected = filePicker.pickOpenFile(DesktopFileType(description, IMAGE_EXTENSIONS)) ?: return
        stageResource(selected, image = true) { card, ref -> assign(card, ref) }
    }

    /** No draft/resource mutation until the crop workspace's explicit Apply action. */
    fun selectImageForEditing(description: String): ByteArray? {
        if (!canMutate()) return null
        val selected = filePicker.pickOpenFile(DesktopFileType(description, IMAGE_EXTENSIONS)) ?: return null
        return runCatching {
            require(Files.size(selected) <= 20 * 1024 * 1024) { "Selected file exceeds 20 MB" }
            Files.readAllBytes(selected).also { DesktopImageEditing.requireStatic(it); DesktopImageEditing.decode(it) }
        }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }.getOrNull()
    }

    fun cropAvatar(bytes: ByteArray) = cropImage(bytes) { card, ref -> card.copy(avatar = ref) }
    fun cropBackground(bytes: ByteArray) = cropImage(bytes) { card, ref -> card.copy(chatBackground = ref, chatBackgroundImages = (card.chatBackgroundImages + listOfNotNull(card.chatBackground) + ref).distinct()) }
    fun cropAppearance(id: String, bytes: ByteArray) = cropImage(bytes) { card, ref ->
        card.copy(characters = card.characters.map { if (it.id == id) it.copy(appearanceImage = ref) else it })
    }

    private fun cropImage(bytes: ByteArray, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        runCatching { withManualResourceSession(session) {
            val ref = resources.stageImage(session, bytes)
            edit { assign(it, ref) }
        } }
            .onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun clearAvatar() = edit { it.copy(avatar = null) }
    fun clearCover() = edit { it.copy(coverImage = null) }
    fun clearBackground() = edit { it.copy(chatBackground = null,
        chatBackgroundImages = (it.chatBackgroundImages + listOfNotNull(it.chatBackground)).distinct()) }
    fun addBackgroundAsset() = chooseImage("添加角色卡背景") { card, ref ->
        card.copy(chatBackgroundImages = (card.chatBackgroundImages + ref).distinct())
    }
    fun cropBackgroundAsset(bytes: ByteArray) = cropImage(bytes) { card, ref ->
        card.copy(chatBackgroundImages = (card.chatBackgroundImages + ref).distinct())
    }
    fun removeBackgroundAsset(reference: String) = edit { it.copy(chatBackgroundImages = it.chatBackgroundImages - reference) }
    fun selectCardBackground(reference: String) = edit {
        require(reference in it.chatBackgroundImages)
        it.copy(chatBackground = reference)
    }

    fun clearAppearance(id: String) = updateCharacter(id) { it.copy(appearanceImage = null) }

    fun addDocumentFromPicker(description: String = "Text") {
        if (!canMutate()) return
        val selected = filePicker.pickOpenFile(DesktopFileType(description, TEXT_EXTENSIONS)) ?: return
        stageResource(selected, image = false) { card, ref ->
            card.copy(customDocuments = card.customDocuments + DocumentInfo.create(
                selected.fileName.toString(), ref, documentType(selected.fileName.toString())))
        }
    }

    fun addTextDocument(name: String, content: String): Boolean {
        if (!canMutate()) return false
        val session = _state.value.draftSessionId ?: return false
        return runCatching { withManualResourceSession(session) {
            val effectiveName = name.ifBlank { "document.txt" }
            val ref = resources.stageText(session, effectiveName, content)
            edit { card -> card.copy(customDocuments = card.customDocuments +
                DocumentInfo.create(effectiveName, ref, documentType(effectiveName))) }
        } }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }.isSuccess
    }

    fun editDocument(id: String, name: String, content: String) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        val previous = _state.value.card?.customDocuments?.firstOrNull { it.id == id } ?: return
        runCatching { withManualResourceSession(session) {
            val effectiveName = name.ifBlank { previous.fileName }
            val ref = resources.stageText(session, effectiveName, content)
            edit { card -> card.copy(customDocuments = card.customDocuments.map { doc ->
                if (doc.id == id) doc.copy(fileName = effectiveName, filePath = ref,
                    fileType = documentType(effectiveName, doc.fileType)) else doc
            }) }
        } }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
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

    suspend fun loadStyleCatalog(): NovelAiStyleCatalogLoadResult = withContext(Dispatchers.IO) {
        val raw = try { resources.readDocument("asset:$NOVEL_AI_STYLE_CATALOG_ASSET_PATH") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext NovelAiStyleCatalogLoadResult(fatalError = "内置画风目录无法读取，请检查或修复程序资源") }
        NovelAiStyleCatalogParser(Json { ignoreUnknownKeys = true }).parse(raw) {
            resources.readImage("asset:$it"); true
        }
    }

    fun applyStylePreset(preset: NovelAiStylePreset): Boolean {
        if (!canMutate()) return false
        val current = _state.value
        val card = current.card ?: return false
        val fill = NovelAiStylePromptFillState(card.defaultImagePrompt, card.defaultImageNegativePrompt, current.stylePromptUndo)
            .apply(preset, CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt())
        edit { it.copy(defaultImagePrompt = fill.value, defaultImageNegativePrompt = fill.negativeValue) }
        val latest = _state.value
        val savedCard = latest.card ?: return false
        if (latest.draftSessionId != current.draftSessionId || savedCard.defaultImagePrompt != fill.value ||
            savedCard.defaultImageNegativePrompt != fill.negativeValue) return false
        _state.value = latest.copy(stylePromptUndo = fill.undo)
        return true
    }

    fun undoStylePreset() {
        if (!canMutate()) return
        val current = _state.value
        val card = current.card ?: return
        val fill = NovelAiStylePromptFillState(card.defaultImagePrompt, card.defaultImageNegativePrompt, current.stylePromptUndo).undoLastFill()
        edit { it.copy(defaultImagePrompt = fill.value, defaultImageNegativePrompt = fill.negativeValue) }
        _state.value = _state.value.copy(stylePromptUndo = null)
    }

    private fun stageResource(path: Path, image: Boolean, originalCover: Boolean = false, assign: (CharacterCard, String) -> CharacterCard) {
        if (!canMutate()) return
        val session = _state.value.draftSessionId ?: return
        runCatching { withManualResourceSession(session) {
            val ref = resources.stage(session, path, image, originalCover)
            edit { assign(it, ref) }
        } }
            .onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    private fun canMutate(): Boolean {
        val current = _state.value
        if (current.busy || current.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return false
        if (current.base?.isCommunityDownload == true && current.targetId != null) {
            _state.compareAndSet(current, current.copy(problem = CharacterEditorProblem.COMMUNITY_READ_ONLY))
            return false
        }
        return true
    }

    private fun displayCard(card: CharacterCard): CharacterCard = card.copy(
        worldBookIds = CharacterWorldBookBindings.effectiveIds(card),
        defaultImageNegativePrompt = editableNegativePrompt(card.defaultImageNegativePrompt),
    )

    private fun sameEditableCard(left: CharacterCard, right: CharacterCard): Boolean =
        CharacterEditorSemanticProjection.normalize(displayCard(left)) ==
            CharacterEditorSemanticProjection.normalize(displayCard(right))

    private fun editableNegativePrompt(value: String): String =
        value.takeUnless(String::isBlank) ?: CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt()

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

    private suspend fun persistDraft() = dataGate.withNormalOperation { draftMutex.withLock {
        val current = _state.value
        if (current.busy || !current.dirty || current.draftPersisted) return@withLock
        val card = current.card ?: return@withLock
        val session = current.draftSessionId ?: return@withLock
        val draft = current.draftBasis?.copy(characterPayload = card, draftAssetPaths = draftAssets(card))
            ?: drafts.characterDraft(current.targetId, session, card, current.base,
                draftAssets(card), emptyList(), emptyList())
        try {
            drafts.save(draft)
            synchronized(resourceMutationLock) {
                _state.update { latest ->
                    if (!latest.busy && latest.card == card && latest.draftSessionId == session) {
                        latest.copy(draftPersisted = true, draftBasis = draft)
                    } else latest
                }
            }
        } catch (error: Throwable) { report(CharacterEditorProblem.DRAFT_FAILED, error) }
    } }

    private fun draftAssets(card: CharacterCard): List<String> = buildList {
        card.avatar?.let(::add); card.chatBackground?.let(::add); card.coverImage?.let(::add)
        addAll(card.chatBackgroundImages)
        card.characters.mapNotNullTo(this) { it.appearanceImage }
        card.customDocuments.mapTo(this) { it.filePath }
    }.filter(resources::isDraft)

    private fun sameDocumentSources(left: List<DocumentInfo>, right: List<DocumentInfo>): Boolean =
        left.map { listOf(it.id, it.fileName, it.filePath, it.fileType, it.addedAt.toString()) } ==
            right.map { listOf(it.id, it.fileName, it.filePath, it.fileType, it.addedAt.toString()) }

    suspend fun save(): Boolean {
        val saved = dataGate.withNormalOperation { saveWithinDataOperation() }
        // The durable entity/marker commit owns normal admission; the full-reference image
        // cleaner needs its own exclusive admission, as on the existing recovery/retry path.
        if (saved) retryCommittedCleanup()
        return saved
    }

    /** Preflight may suspend while the user continues editing. Never publish its older form. */
    private fun reserveSaveSnapshot(before: DesktopCharacterEditorState): Boolean {
        // Generated images and manual resources can stage and edit off the window thread.
        // Reserve under their lock so an accepted edit cannot overwrite the busy snapshot.
        synchronized(resourceMutationLock) {
            while (true) {
                val current = _state.value
                if (current.busy || current.targetId != before.targetId || current.draftSessionId != before.draftSessionId ||
                    current.base != before.base) return false
                if (current.card != before.card || !current.dirty || !current.draftPersisted) {
                    if (current.dirty) reportSaveProblem(before, CharacterEditorProblem.SAVE_FAILED,
                        "保存检查期间内容已变化，最新编辑已保留，请再次保存。", allowChangedCard = true)
                    return false
                }
                if (_state.compareAndSet(current, current.copy(busy = true, problem = null, detail = null))) return true
            }
        }
    }

    private fun reportSaveProblem(before: DesktopCharacterEditorState, problem: CharacterEditorProblem,
        detail: String? = null, allowChangedCard: Boolean = false) {
        while (true) {
            val current = _state.value
            if (current.busy || current.targetId != before.targetId || current.draftSessionId != before.draftSessionId ||
                current.base != before.base || (!allowChangedCard && current.card != before.card)) return
            if (_state.compareAndSet(current, current.copy(problem = problem, detail = detail))) return
        }
    }

    private suspend fun saveWithinDataOperation(): Boolean {
        flushDraft()
        val before = _state.value
        val card = before.card ?: return false
        if (before.busy) return false
        if (!before.dirty) return if (before.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) false
            else cleanObsoleteDraft()
        if (before.dirty && !before.draftPersisted) {
            reportSaveProblem(before, CharacterEditorProblem.DRAFT_FAILED)
            return false
        }
        val valid = try { validate(card, before) }
        catch (error: Throwable) { reportSaveProblem(before, CharacterEditorProblem.SAVE_FAILED, error.message); return false }
        if (!valid) return false
        val source = try { before.targetId?.let { characters.getById(it) } }
        catch (error: Throwable) { reportSaveProblem(before, CharacterEditorProblem.SAVE_FAILED, error.message); return false }
        val newIdConflict = try { before.targetId == null && characters.getById(card.id) != null }
        catch (error: Throwable) { reportSaveProblem(before, CharacterEditorProblem.SAVE_FAILED, error.message); return false }
        if (newIdConflict) {
            reportSaveProblem(before, CharacterEditorProblem.SOURCE_CHANGED)
            return false
        }
        if (before.targetId != null && source == null) {
            reportSaveProblem(before, CharacterEditorProblem.SOURCE_DELETED)
            return false
        }
        if (source?.isCommunityDownload == true) {
            reportSaveProblem(before, CharacterEditorProblem.COMMUNITY_READ_ONLY)
            return false
        }
        val persistedDraft = try { drafts.getForTarget(EditorDraftType.CHARACTER_CARD, before.targetId) }
        catch (error: Throwable) { reportSaveProblem(before, CharacterEditorProblem.DRAFT_FAILED, error.message); return false }
        if (source != null && persistedDraft != null && drafts.isChanged(source, persistedDraft)) {
            reportSaveProblem(before, CharacterEditorProblem.SOURCE_CHANGED)
            return false
        }
        if (!reserveSaveSnapshot(before)) return false
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
                avatar = card.avatar, chatBackground = card.chatBackground, coverImage = card.coverImage,
                chatBackgroundImages = card.chatBackgroundImages,
                editMode = card.editMode, basicSetting = card.basicSetting,
                freeformCharacterText = card.freeformCharacterText,
                defaultImagePrompt = card.defaultImagePrompt,
                defaultNovelAiImageModel = card.defaultNovelAiImageModel,
                defaultImageGenerationSettings = card.defaultImageGenerationSettings,
                defaultImageNegativePrompt = editableNegativePrompt(card.defaultImageNegativePrompt),
                systemPrompt = card.systemPrompt, postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample, creatorNotes = card.creatorNotes,
                tags = card.tags, creator = card.creator, characterVersion = card.characterVersion,
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
            val (materialized, newResources) = resources.materialize(retained)
            created = newResources
            val oldPeople = source?.characters.orEmpty().associateBy(CharacterInfo::id)
            val speakerRenames = materialized.characters.mapNotNull { person ->
                val previous = oldPeople[person.id] ?: return@mapNotNull null
                if (previous.name.trim().isEmpty() || person.name.trim().isEmpty() || previous.name.trim() == person.name.trim()) null
                else SpeakerTagRename(person.id, previous.name.trim(), person.name.trim())
            }
            val speakerTask = speakerHistory.createTask(materialized.id, materialized.updatedAt, speakerRenames)
            val durable = materialized.copy(pendingSpeakerRenameTasks = materialized.pendingSpeakerRenameTasks + listOfNotNull(speakerTask))
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
                draftBasis = markedDraft, dirty = false, busy = true,
                problem = CharacterEditorProblem.SAVE_COMMITTED_WARNING,
                detail = cleanupErrors.firstOrNull()?.message)
            return true
        } catch (error: Throwable) {
            _state.value = _state.value.copy(busy = false)
            report(CharacterEditorProblem.SAVE_FAILED, error)
            return false
        }
    }

    private suspend fun cleanupDraftAndAssets(targetId: String?, sessionId: String?): CharacterDraftCleanup = dataGate.withNormalOperation {
        val retired = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, targetId)
        val draft = deleteEditorDraftConfirmed(drafts, EditorDraftType.CHARACTER_CARD, targetId, deleteDraft)
        val assetFailure = if (draft.removed && sessionId != null)
            runCatching { discardUnreferencedCharacterDraftAssets(drafts, sessionId, discardDraftAssets, resources::sessionPath, retired) }.exceptionOrNull()
        else null
        CharacterDraftCleanup(draft, assetFailure)
    }

    private fun needsCleanDraftCleanup(current: DesktopCharacterEditorState): Boolean =
        !current.dirty && current.card != null && current.base != null &&
            sameEditableCard(current.card, current.base) &&
            ((current.draftBasis != null && current.draftBasis.targetId == current.targetId) ||
                current.problem == CharacterEditorProblem.CLEAN_DRAFT_WARNING) &&
            current.problem != CharacterEditorProblem.SAVE_COMMITTED_WARNING

    private suspend fun cleanObsoleteDraft(): Boolean = dataGate.withNormalOperation { draftMutex.withLock {
        val nextSession = UUID.randomUUID().toString()
        val before = synchronized(resourceMutationLock) {
            val captured = _state.value
            if (!needsCleanDraftCleanup(captured)) return@withLock !captured.dirty
            // New edits/resources belong to a fresh directory while the old draft deletion suspends.
            if (!_state.compareAndSet(captured, captured.copy(draftSessionId = nextSession,
                    draftBasis = null, draftPersisted = false, cleanDraftCleanupInProgress = true))) return@withLock false
            captured
        }
        try {
            val cleanup = cleanupDraftAndAssets(before.targetId, before.draftSessionId)
            val current = _state.value
            if (current.card != before.card || current.draftSessionId != nextSession || current.dirty)
                return@withLock false
            if (cleanup.blocking) {
                _state.compareAndSet(current, current.copy(draftSessionId = if (cleanup.removed) nextSession else before.draftSessionId,
                    draftBasis = if (cleanup.removed) null else before.draftBasis,
                    draftPersisted = before.draftPersisted && !cleanup.removed,
                    problem = CharacterEditorProblem.CLEAN_DRAFT_WARNING,
                    detail = cleanup.draft.errors.firstOrNull()?.message))
                return@withLock false
            }
            _state.compareAndSet(current, current.copy(draftBasis = null, draftPersisted = false,
                problem = cleanup.assetFailure?.let { CharacterEditorProblem.RESOURCE_FAILED },
                detail = cleanup.assetFailure?.message))
        } finally {
            synchronized(resourceMutationLock) {
                _state.update { it.copy(cleanDraftCleanupInProgress = false) }
            }
        }
    } }

    suspend fun retryCleanup() {
        if (_state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) retryCommittedCleanup()
        else if (_state.value.resourceCleanupPending) committedCleanupMutex.withLock { retryResourceCleanupLocked() }
        else if (needsCleanDraftCleanup(_state.value)) cleanObsoleteDraft()
    }

    /** Optional resource maintenance never rewrites a card or borrows an old committed draft. */
    private suspend fun retryResourceCleanupLocked() {
        if (resourceCleanupCards.isEmpty() && resourceCleanupSessions.isEmpty()) return
        var failure: Throwable? = null
        val currentCards = runCatching { refreshRepository(); characters.getAll() }
        for (previous in resourceCleanupCards.toList()) {
            val error = runCatching { discardObsoleteResources(previous, currentCards.getOrThrow()) }.exceptionOrNull()
            if (error == null) resourceCleanupCards.remove(previous) else if (failure == null) failure = error
        }
        for (session in resourceCleanupSessions.toList()) {
            val error = runCatching { dataGate.withNormalOperation {
                check(session != _state.value.draftSessionId || !_state.value.dirty && _state.value.draftBasis == null) {
                    "Active draft resource cleanup is deferred"
                }
                discardUnreferencedCharacterDraftAssets(drafts, session, discardDraftAssets, resources::sessionPath)
            } }.exceptionOrNull()
            if (error == null) resourceCleanupSessions.remove(session) else if (failure == null) failure = error
        }
        val pending = resourceCleanupCards.isNotEmpty() || resourceCleanupSessions.isNotEmpty()
        _state.update { it.copy(resourceCleanupPending = pending,
            problem = if (pending) it.problem ?: CharacterEditorProblem.RESOURCE_FAILED
                else it.problem?.takeUnless { issue -> issue == CharacterEditorProblem.RESOURCE_FAILED },
            detail = if (pending) failure?.message ?: it.detail else if (it.problem == CharacterEditorProblem.RESOURCE_FAILED) null else it.detail) }
    }

    suspend fun retryCommittedCleanup() = committedCleanupMutex.withLock { retryCommittedCleanupLocked() }

    private suspend fun retryCommittedCleanupLocked() {
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
        var speakerCard = committed
        val speakerError = if (cacheReady) runCatching {
            val sourceCard = requireNotNull(durable)
            for (task in sourceCard.pendingSpeakerRenameTasks.sortedBy { it.createdAt }) speakerHistory.execute(sourceCard.id, task.id)
        }.exceptionOrNull() else null
        if (cacheReady) {
            val latest = runCatching { characters.getById(committed.id) }.getOrNull()
            if (latest != null && EditorPostCommitFingerprint.character(json, latest) == EditorPostCommitFingerprint.character(json, committed)) {
                speakerCard = latest
                committedCard = latest
            }
        }
        // The existing semantic fingerprint excludes derived/task metadata; retain the marker until all required work agrees.
        val semanticReady = cacheReady && pendingRename == null && speakerError == null && speakerCard.pendingSpeakerRenameTasks.isEmpty()
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
        if (orphanError != null) pendingObsoleteSource?.let(resourceCleanupCards::add)
        cleanup.filterValues { it.assetFailure != null }.keys.mapNotNull { it.sessionId }.forEach(resourceCleanupSessions::add)
        val resourcesPending = resourceCleanupCards.isNotEmpty() || resourceCleanupSessions.isNotEmpty()
        _state.value = before.copy(
            card = speakerCard, base = speakerCard,
            busy = false, resourceCleanupPending = resourcesPending,
            // A failed retired directory must never be reused by the next editable draft.
            draftSessionId = if (!blocking && activeRemoved && before.draftSessionId in resourceCleanupSessions)
                UUID.randomUUID().toString() else before.draftSessionId,
            draftBasis = if (activeRemoved) null else before.draftBasis,
            draftPersisted = !activeRemoved,
            recoveryTargetId = if (recoveryRemoved) null else before.recoveryTargetId,
            recoverySessionId = if (recoveryRemoved) null else before.recoverySessionId,
            characters = refreshed.getOrNull() ?: before.characters,
            problem = when {
                blocking -> CharacterEditorProblem.SAVE_COMMITTED_WARNING
                orphanError != null || assetError != null || resourcesPending -> CharacterEditorProblem.RESOURCE_FAILED
                else -> null
            },
            detail = (durableResult.exceptionOrNull() ?: refreshed.exceptionOrNull() ?: renameError ?: speakerError
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

    private suspend fun validate(card: CharacterCard, before: DesktopCharacterEditorState): Boolean {
        val issue = when {
            NamePolicy.normalize(card.name).isBlank() -> CharacterEditorProblem.NAME_REQUIRED
            card.greeting.isBlank() -> CharacterEditorProblem.GREETING_REQUIRED
            card.characters.any { !CharacterPlaceholderPolicy.isEmpty(it) && it.name.isBlank() } ->
                CharacterEditorProblem.CHARACTER_NAME_REQUIRED
            CharacterSpeakerNamePolicy.duplicateNames(card.characters).isNotEmpty() ->
                CharacterEditorProblem.DUPLICATE_CHARACTER_NAME
            NamePolicy.conflict(card.name, characters.getAll().map { it.id to it.name }, before.targetId) != null ->
                CharacterEditorProblem.DUPLICATE_CARD_NAME
            else -> null
        }
        if (issue != null) { reportSaveProblem(before, issue); return false }
        val imageIssue = card.defaultImageGenerationSettings?.applyTo(
            com.example.chatbar.domain.image.NovelAiGenerationSettings(model = card.defaultNovelAiImageModel
                ?: com.example.chatbar.domain.image.NovelAiImageModel.V4_5_FULL))?.validationError(0)
        if (imageIssue != null) {
            reportSaveProblem(before, CharacterEditorProblem.SAVE_FAILED, imageIssue)
            return false
        }
        return true
    }

    /** Recovery and read-only copy retain editor payload but sever the protected source identity. */
    internal fun draftAssetSessionPath(reference: String): Path? = resources.sessionPath(reference)

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
        val existingNames = characters.getAll().map { it.name }
        try {
            dataGate.withNormalOperation {
                synchronized(resourceMutationLock) {
                    check(_state.value == before) { "Draft changed during copy" }
                    val newSession = UUID.randomUUID().toString()
                    val newCard = resources.fork(card, newSession).copy(id = UUID.randomUUID().toString(),
                        name = NamePolicy.nextCopyName(card.name, existingNames),
                        communityItemId = null, communityItemUpdatedAt = null, communityItemSha256 = null,
                        communityItemTitle = null, sourcePresetKey = null, sourcePresetVersion = null,
                        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
                    _state.value = before.copy(card = newCard, base = null, draftBasis = null, targetId = null,
                        draftSessionId = newSession, recoveryTargetId = before.targetId,
                        recoverySessionId = before.draftSessionId,
                        dirty = true, draftPersisted = false, problem = null, saveAsNewFailed = false)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update { current ->
                if (current == before) current.copy(saveAsNewFailed = true, detail = failure.message) else current
            }
            return
        }
        // Preserve the original conflict draft; user may recover it independently.
        persistDraft()
    }

    suspend fun discard() {
        val before = synchronized(resourceMutationLock) {
            val current = _state.value
            if (current.card == null || current.busy || current.cleanDraftCleanupInProgress ||
                pendingCleanLeave.get() || current.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING) return
            _state.update { it.copy(busy = true) }
            current
        }
        try {
            draftJob?.cancelAndJoin()
            draftJob = null
            val draftTarget = if (before.draftBasis != null) before.draftBasis.targetId else before.targetId
            val cleanup = dataGate.withNormalOperation { draftMutex.withLock {
                cleanupDraftAndAssets(draftTarget, before.draftSessionId)
            } }
            if (!cleanup.removed) {
                _state.update { it.copy(problem = CharacterEditorProblem.DRAFT_FAILED,
                    detail = cleanup.draft.errors.firstOrNull()?.message) }
                return
            }
            val error = cleanup.assetFailure ?: cleanup.draft.errors.firstOrNull()
            _state.update { it.copy(card = null, base = null, draftBasis = null, targetId = null, draftSessionId = null,
                recoveryTargetId = null, recoverySessionId = null,
                dirty = false, draftPersisted = false, leavePrompt = false,
                problem = error?.let { CharacterEditorProblem.RESOURCE_FAILED }, detail = error?.message) }
        } finally {
            synchronized(resourceMutationLock) { _state.update { it.copy(busy = false) } }
        }
        leaveAction?.also { leaveAction = null; it() }
    }

    fun requestLeave(action: () -> Unit) {
        val cleanFirst = synchronized(resourceMutationLock) {
            if (leaveBlocked() || deferLeave(action)) return
            needsCleanDraftCleanup(_state.value).also { if (it) pendingCleanLeave.set(true) }
        }
        if (cleanFirst) {
            scope.launch {
                val mayLeave = try { cleanObsoleteDraft() }
                finally { pendingCleanLeave.set(false) }
                if (mayLeave && !_state.value.dirty) action()
            }
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
        requestLeave {
            synchronized(resourceMutationLock) {
                if (leaveBlocked() || _state.value.dirty) return@synchronized
                _state.update { it.copy(card = null, base = null, leavePrompt = false,
                    problem = if (it.resourceCleanupPending) CharacterEditorProblem.RESOURCE_FAILED else null) }
                leaveAction?.also { leaveAction = null; it() }
            }
        }
    }

    private fun leaveBlocked(): Boolean = _state.value.let {
        it.busy || it.cleanDraftCleanupInProgress || pendingCleanLeave.get() ||
            it.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING
    }

    suspend fun closeAndDrain() {
        cardAi?.closeAndDrain()
        appearanceAi?.closeAndDrain()
        cardImages?.closeAndDrain()
        flushDraft()
        when {
            _state.value.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING -> retryCommittedCleanup()
            needsCleanDraftCleanup(_state.value) -> cleanObsoleteDraft()
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun report(problem: CharacterEditorProblem, error: Throwable) {
        _state.update { it.copy(problem = problem, detail = error.message) }
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")
        val TEXT_EXTENSIONS = listOf("txt", "md", "json")
    }
}
