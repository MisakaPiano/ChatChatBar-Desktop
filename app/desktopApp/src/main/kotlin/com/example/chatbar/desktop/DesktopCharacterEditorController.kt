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

internal enum class CharacterEditorProblem {
    NAME_REQUIRED, GREETING_REQUIRED, CHARACTER_NAME_REQUIRED, DUPLICATE_CHARACTER_NAME,
    DUPLICATE_CARD_NAME, SOURCE_CHANGED, SOURCE_DELETED, COMMUNITY_READ_ONLY, SAVE_FAILED,
    DRAFT_FAILED, RESOURCE_FAILED,
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
    private val filePicker: DesktopFilePicker = SwingDesktopFilePicker(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(DesktopCharacterEditorState())
    val state = _state.asStateFlow()
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var leaveAction: (() -> Unit)? = null

    suspend fun load() {
        _state.value = _state.value.copy(
            characters = characters.getAll(), worldBooks = worlds.getAll(), formatCards = formats.getAll(),
        )
    }

    fun search(query: String) { _state.value = _state.value.copy(query = query) }

    suspend fun openNew() {
        if (deferLeave { scope.launch { openNew() } }) return
        flushDraft()
        val recovered = drafts.getLatestNew(EditorDraftType.CHARACTER_CARD)
        if (recovered?.characterPayload != null) {
            showDraft(recovered, null)
        } else {
            _state.value = _state.value.copy(card = CharacterCard.create(""), base = null,
                draftBasis = null, targetId = null, draftSessionId = UUID.randomUUID().toString(),
                draftPersisted = false, dirty = false, problem = null, detail = null)
        }
    }

    suspend fun openExisting(id: String) {
        if (deferLeave { scope.launch { openExisting(id) } }) return
        flushDraft()
        val source = characters.getById(id)
        val recovered = drafts.getForTarget(EditorDraftType.CHARACTER_CARD, id)
        if (recovered?.characterPayload != null) {
            showDraft(recovered, source)
            return
        }
        if (source == null) {
            _state.value = _state.value.copy(problem = CharacterEditorProblem.SOURCE_DELETED)
            return
        }
        _state.value = _state.value.copy(card = source, base = source, targetId = id,
            draftBasis = null, draftSessionId = UUID.randomUUID().toString(), draftPersisted = false,
            dirty = false, problem = if (source.isCommunityDownload) CharacterEditorProblem.COMMUNITY_READ_ONLY else null,
            detail = null)
    }

    private fun showDraft(draft: EditorDraft, source: CharacterCard?) {
        val problem = when {
            draft.targetId != null && source == null -> CharacterEditorProblem.SOURCE_DELETED
            source != null && drafts.isChanged(source, draft) -> CharacterEditorProblem.SOURCE_CHANGED
            source?.isCommunityDownload == true -> CharacterEditorProblem.COMMUNITY_READ_ONLY
            else -> null
        }
        _state.value = _state.value.copy(card = draft.characterPayload, base = source, draftBasis = draft,
            targetId = draft.targetId, draftSessionId = draft.draftSessionId,
            draftPersisted = true, dirty = true, problem = problem, detail = null)
    }

    /** Only the mode changes; the inactive body remains in the payload and in storage. */
    fun switchMode(mode: CharacterEditMode) = edit { it.copy(editMode = mode) }

    fun edit(transform: (CharacterCard) -> CharacterCard) {
        val current = _state.value
        val card = current.card ?: return
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
        _state.value = current.copy(card = next, dirty = true, draftPersisted = false,
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

    fun addGreeting() = edit { it.copy(alternateGreetings = it.alternateGreetings + "") }

    fun setGreeting(index: Int, value: String) = edit { card ->
        card.copy(alternateGreetings = card.alternateGreetings.mapIndexed { i, old -> if (i == index) value else old })
    }

    fun removeGreeting(index: Int) = edit { card ->
        card.copy(alternateGreetings = card.alternateGreetings.filterIndexed { i, _ -> i != index })
    }

    fun toggleWorldBook(id: String) = edit { card ->
        card.copy(worldBookIds = if (id in card.worldBookIds) card.worldBookIds - id else card.worldBookIds + id)
    }

    fun setDefaultFormatCard(id: String?) = edit { it.copy(defaultFormatCardId = id) }

    fun chooseAvatar(description: String = "Image") = chooseImage(description) { card, ref -> card.copy(avatar = ref) }
    fun chooseBackground(description: String = "Image") = chooseImage(description) { card, ref -> card.copy(chatBackground = ref) }
    fun chooseAppearance(id: String, description: String = "Image") = chooseImage(description) { card, ref ->
        card.copy(characters = card.characters.map { if (it.id == id) it.copy(appearanceImage = ref) else it })
    }

    private fun chooseImage(description: String, assign: (CharacterCard, String) -> CharacterCard) {
        val selected = filePicker.pickOpenFile(DesktopFileType(description, IMAGE_EXTENSIONS)) ?: return
        stageResource(selected, image = true) { card, ref -> assign(card, ref) }
    }

    fun clearAvatar() = edit { it.copy(avatar = null) }
    fun clearBackground() = edit { it.copy(chatBackground = null) }
    fun clearAppearance(id: String) = updateCharacter(id) { it.copy(appearanceImage = null) }

    fun addDocumentFromPicker(description: String = "Text") {
        val selected = filePicker.pickOpenFile(DesktopFileType(description, TEXT_EXTENSIONS)) ?: return
        stageResource(selected, image = false) { card, ref ->
            card.copy(customDocuments = card.customDocuments + DocumentInfo.create(
                selected.fileName.toString(), ref, "text/plain"))
        }
    }

    fun addTextDocument(name: String, content: String) {
        val session = _state.value.draftSessionId ?: return
        runCatching {
            val ref = resources.stageText(session, name.ifBlank { "document.txt" }, content)
            edit { card -> card.copy(customDocuments = card.customDocuments +
                DocumentInfo.create(name.ifBlank { "document.txt" }, ref, "text/plain")) }
        }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun editDocument(id: String, name: String, content: String) {
        val session = _state.value.draftSessionId ?: return
        runCatching {
            val ref = resources.stageText(session, name.ifBlank { "document.txt" }, content)
            edit { card -> card.copy(customDocuments = card.customDocuments.map { doc ->
                if (doc.id == id) doc.copy(fileName = name.ifBlank { "document.txt" }, filePath = ref) else doc
            }) }
        }.onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

    fun renameDocument(id: String, name: String) = edit { card ->
        card.copy(customDocuments = card.customDocuments.map { if (it.id == id) it.copy(fileName = name) else it })
    }

    fun removeDocument(id: String) = edit { card ->
        card.copy(customDocuments = card.customDocuments.filterNot { it.id == id })
    }

    fun clearDocuments() = edit { it.copy(customDocuments = emptyList()) }

    fun documentText(id: String): String? = _state.value.card?.customDocuments?.firstOrNull { it.id == id }
        ?.let { runCatching { resources.readDocument(it.filePath) }.getOrNull() }

    fun imageBytes(reference: String?): ByteArray? = reference?.let {
        runCatching { resources.readImage(it) }.getOrNull()
    }

    private fun stageResource(path: Path, image: Boolean, assign: (CharacterCard, String) -> CharacterCard) {
        val session = _state.value.draftSessionId ?: return
        runCatching { resources.stage(session, path, image) }
            .onSuccess { ref -> edit { assign(it, ref) } }
            .onFailure { report(CharacterEditorProblem.RESOURCE_FAILED, it) }
    }

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
        if (before.dirty && !before.draftPersisted) {
            _state.value = before.copy(problem = CharacterEditorProblem.DRAFT_FAILED)
            return false
        }
        if (!validate(card, before.targetId)) return false
        val source = try { before.targetId?.let { characters.getById(it) } }
        catch (error: Throwable) { report(CharacterEditorProblem.SAVE_FAILED, error); return false }
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
                defaultImageNegativePrompt = card.defaultImageNegativePrompt,
                systemPrompt = card.systemPrompt, postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample, creatorNotes = card.creatorNotes,
                characters = card.characters.filterNot(CharacterPlaceholderPolicy::isEmpty),
                customDocuments = mergedDocuments, worldBookIds = card.worldBookIds,
                defaultFormatCardId = card.defaultFormatCardId,
                ragIndexStatus = if (documentsChanged) card.ragIndexStatus else source?.ragIndexStatus ?: card.ragIndexStatus,
                ragIndexDone = if (documentsChanged) card.ragIndexDone else source?.ragIndexDone ?: card.ragIndexDone,
                ragIndexTotal = if (documentsChanged) card.ragIndexTotal else source?.ragIndexTotal ?: card.ragIndexTotal,
                ragIndexMessage = if (documentsChanged) card.ragIndexMessage else source?.ragIndexMessage ?: card.ragIndexMessage,
                ragIndexedAt = if (documentsChanged) card.ragIndexedAt else source?.ragIndexedAt ?: card.ragIndexedAt,
                updatedAt = System.currentTimeMillis(),
            )
            val (durable, newResources) = resources.materialize(retained)
            created = newResources
            try { characters.save(durable) } catch (error: Throwable) {
                // A cache refresh can fail after the atomic entity write. Never remove a
                // resource that the committed JSON may now reference.
                runCatching { characters.getById(durable.id) }
                    .onSuccess { persisted -> if (persisted != durable) resources.rollback(created, error) }
                    .onFailure(error::addSuppressed)
                throw error
            }
            // Entity is durable. Cleanup errors are explicit but never reclassify the save as failed.
            val cleanupErrors = mutableListOf<Throwable>()
            if (source != null) runCatching { resources.discardObsolete(source, characters.getAll()) }
                .onFailure(cleanupErrors::add)
            if (source != null && source.name != durable.name) {
                runCatching { chats.rewriteSessionTitlesForCharacterCard(source.id, source.name, durable.name) }
                    .onFailure(cleanupErrors::add)
            }
            runCatching { drafts.deleteForTarget(EditorDraftType.CHARACTER_CARD, before.targetId) }
                .onFailure(cleanupErrors::add)
            before.draftSessionId?.let { runCatching { resources.discardSession(it) }.onFailure(cleanupErrors::add) }
            before.recoveryTargetId?.let {
                runCatching { drafts.deleteForTarget(EditorDraftType.CHARACTER_CARD, it) }
                    .onFailure(cleanupErrors::add)
            }
            before.recoverySessionId?.let { runCatching { resources.discardSession(it) }.onFailure(cleanupErrors::add) }
            val refreshed = runCatching { characters.getAll() }.getOrElse {
                cleanupErrors += it
                before.characters.filterNot { old -> old.id == durable.id } + durable
            }
            _state.value = _state.value.copy(card = durable, base = durable, draftBasis = null, targetId = durable.id,
                dirty = false, draftPersisted = false, busy = false,
                recoveryTargetId = null, recoverySessionId = null,
                characters = refreshed, problem = cleanupErrors.firstOrNull()?.let { CharacterEditorProblem.RESOURCE_FAILED },
                detail = cleanupErrors.firstOrNull()?.message)
            return true
        } catch (error: Throwable) {
            _state.value = _state.value.copy(busy = false)
            report(CharacterEditorProblem.SAVE_FAILED, error)
            return false
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
        flushDraft()
        val before = _state.value
        val card = before.card ?: return
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
        before.targetId?.let { drafts.deleteForTarget(EditorDraftType.CHARACTER_CARD, it) }
            ?: drafts.deleteForTarget(EditorDraftType.CHARACTER_CARD, null)
        before.draftSessionId?.let(resources::discardSession)
        before.recoveryTargetId?.let { drafts.deleteForTarget(EditorDraftType.CHARACTER_CARD, it) }
        before.recoverySessionId?.let(resources::discardSession)
        _state.value = before.copy(card = null, base = null, draftBasis = null, targetId = null, draftSessionId = null,
            recoveryTargetId = null, recoverySessionId = null,
            dirty = false, draftPersisted = false, leavePrompt = false, problem = null, detail = null)
        leaveAction?.also { leaveAction = null; it() }
    }

    fun requestLeave(action: () -> Unit) {
        if (deferLeave(action)) return
        action()
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
        if (save()) {
            _state.value = _state.value.copy(card = null, base = null, leavePrompt = false)
            leaveAction?.also { leaveAction = null; it() }
        }
    }

    fun closeClean() {
        if (_state.value.dirty) { requestLeave { closeClean() }; return }
        _state.value = _state.value.copy(card = null, base = null, leavePrompt = false, problem = null)
        leaveAction?.also { leaveAction = null; it() }
    }

    suspend fun closeAndDrain() { flushDraft(); scope.coroutineContext[Job]?.cancel() }

    private fun report(problem: CharacterEditorProblem, error: Throwable) {
        _state.value = _state.value.copy(problem = problem, detail = error.message)
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")
        val TEXT_EXTENSIONS = listOf("txt", "md")
    }
}
