package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class CharacterCardExport(
    val card: CharacterCard,
    val packageData: CharacterCardPackage,
)

enum class CharacterTransferPostCommitOperation {
    IMPORT,
    DUPLICATE,
    OVERWRITE,
    DELETE,
}

class CharacterTransferPostCommitException(
    val operation: CharacterTransferPostCommitOperation,
    val characterId: String,
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

/**
 * Platform-neutral Character Package ↔ Entity materialization authority.
 *
 * 每个 public filesystem/repository operation 在最外层进入 [operationGate]。Desktop 注入与
 * JsonFileStorage 相同的 coordinator，因此整个多步骤 transfer 在 maintenance admission 中只登记
 * 一次；nested repository storage calls 复用既有 coroutine-context marker。Android 使用 no-op gate。
 */
class CharacterCardTransferCore internal constructor(
    private val characters: CharacterTransferCharacterStore,
    private val worldBooks: CharacterTransferWorldBookStore,
    private val formatCards: CharacterTransferFormatCardStore,
    private val resources: CharacterResourceStore,
    private val promptPolicy: CharacterTransferPromptPolicy,
    private val ragCleanup: CharacterDocumentRagCleanup,
    private val json: Json,
    private val operationGate: AppDataOperationGate,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMillis: () -> Long,
    private val newId: () -> String,
) {
    constructor(
        characterRepository: CharacterRepository,
        worldBookRepository: WorldBookRepository,
        formatCardRepository: FormatCardRepository,
        resources: CharacterResourceStore,
        promptPolicy: CharacterTransferPromptPolicy,
        ragCleanup: CharacterDocumentRagCleanup,
        json: Json,
        operationGate: AppDataOperationGate = NoOpAppDataOperationGate,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        characters = RepositoryCharacterTransferStore(characterRepository),
        worldBooks = RepositoryWorldBookTransferStore(worldBookRepository),
        formatCards = RepositoryFormatCardTransferStore(formatCardRepository, json),
        resources = resources,
        promptPolicy = promptPolicy,
        ragCleanup = ragCleanup,
        json = json,
        operationGate = operationGate,
        ioDispatcher = ioDispatcher,
        nowMillis = System::currentTimeMillis,
        newId = { UUID.randomUUID().toString() },
    )

    suspend fun prepareExport(id: String): CharacterCardExport = normalOperation {
        prepareExportUngated(id)
    }

    suspend fun exportJson(id: String): String = normalOperation {
        json.encodeToString(CharacterCardPackage.serializer(), prepareExportUngated(id).packageData)
    }

    fun decode(rawJson: String): CharacterCardPackage =
        json.decodeFromString(CharacterCardPackage.serializer(), rawJson)
            .withoutEmptyCharacterPlaceholders()
            .also(CharacterCardPackage::validateForImport)

    fun decodePng(pngBytes: ByteArray): CharacterCardPackage? {
        val payload = PngTextChunks.extractTextChunk(
            pngBytes,
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
        ) ?: return null
        val rawJson = if (payload.trimStart().startsWith("{")) {
            payload
        } else {
            String(Base64.getMimeDecoder().decode(payload), Charsets.UTF_8)
        }
        return decode(rawJson)
    }

    suspend fun duplicate(id: String): CharacterCard = normalOperation {
        val source = characters.getById(id) ?: error("角色卡不存在")
        val name = NamePolicy.nextCopyName(source.name, characters.getAll().map { it.name })
        createAndCommit(
            packageData = packageCard(source),
            id = newId(),
            name = name,
            operation = CharacterTransferPostCommitOperation.DUPLICATE,
        )
    }

    suspend fun importNew(
        packageData: CharacterCardPackage,
        requestedName: String = packageData.card.name,
        presetKey: String? = null,
        presetVersion: Int? = null,
    ): CharacterCard = normalOperation {
        val normalizedPackage = packageData.withoutEmptyCharacterPlaceholders()
        normalizedPackage.validateForImport()
        val allNames = characters.getAll().map { it.name }
        val uniqueName = if (allNames.any { NamePolicy.isSame(it, requestedName) }) {
            NamePolicy.nextCopyName(requestedName, allNames)
        } else {
            NamePolicy.normalize(requestedName)
        }
        createAndCommit(
            packageData = normalizedPackage,
            id = newId(),
            name = uniqueName,
            presetKey = presetKey,
            presetVersion = presetVersion,
            operation = CharacterTransferPostCommitOperation.IMPORT,
        )
    }

    /** Replacement entity persistence is the commit point; old-resource/RAG cleanup is post-commit. */
    suspend fun overwrite(
        existingId: String,
        packageData: CharacterCardPackage,
        presetKey: String? = null,
        presetVersion: Int? = null,
    ): CharacterCard = normalOperation {
        val normalizedPackage = packageData.withoutEmptyCharacterPlaceholders()
        normalizedPackage.validateForImport()
        val existing = characters.getById(existingId) ?: error("待覆盖角色卡不存在")
        val ledger = MaterializationLedger()
        var committed = false
        val replacement = try {
            materialize(
                packageData = normalizedPackage,
                id = existing.id,
                name = existing.name,
                presetKey = presetKey,
                presetVersion = presetVersion,
                createdAt = existing.createdAt,
                ledger = ledger,
            )
        } catch (error: Throwable) {
            rollback(ledger, error)
            throw error
        }

        var persistenceFailure: Throwable? = null
        try {
            characters.save(replacement) { committed = true }
        } catch (error: Throwable) {
            if (!committed) {
                rollback(ledger, error)
                throw error
            }
            persistenceFailure = error
        }

        val cleanupFailure = withContext(NonCancellable) {
            cleanupOwnedResources(
                card = existing,
                preserveReferences = replacement.ownedResourceReferences(),
            )
        }
        if (persistenceFailure != null) {
            cleanupFailure?.let(persistenceFailure::addSuppressed)
            throw CharacterTransferPostCommitException(
                operation = CharacterTransferPostCommitOperation.OVERWRITE,
                characterId = replacement.id,
                message = "角色卡覆盖已提交，但提交后的仓库状态刷新或资源清理失败",
                cause = persistenceFailure,
            )
        }
        if (cleanupFailure != null) {
            throw CharacterTransferPostCommitException(
                operation = CharacterTransferPostCommitOperation.OVERWRITE,
                characterId = replacement.id,
                message = "角色卡覆盖已提交，但旧资源清理失败",
                cause = cleanupFailure,
            )
        }
        replacement
    }

    /** Entity deletion is the commit point; owned-resource/RAG cleanup is post-commit and never resurrects it. */
    suspend fun deleteCard(id: String) = normalOperation {
        val previous = characters.getById(id)
        var committed = false
        var persistenceFailure: Throwable? = null
        try {
            characters.delete(id) { committed = true }
        } catch (error: Throwable) {
            if (!committed) throw error
            persistenceFailure = error
        }

        val cleanupFailure = withContext(NonCancellable) {
            previous?.let { cleanupOwnedResources(it) }
        }
        if (persistenceFailure != null) {
            cleanupFailure?.let(persistenceFailure::addSuppressed)
            throw CharacterTransferPostCommitException(
                operation = CharacterTransferPostCommitOperation.DELETE,
                characterId = id,
                message = "角色卡删除已提交，但提交后的仓库状态刷新或资源清理失败",
                cause = persistenceFailure,
            )
        }
        if (cleanupFailure != null) {
            throw CharacterTransferPostCommitException(
                operation = CharacterTransferPostCommitOperation.DELETE,
                characterId = id,
                message = "角色卡删除已提交，但 owned resource 或 RAG 清理失败",
                cause = cleanupFailure,
            )
        }
    }

    private suspend fun <T> normalOperation(operation: suspend () -> T): T =
        operationGate.withNormalOperation {
            withContext(ioDispatcher) { operation() }
        }

    private suspend fun prepareExportUngated(id: String): CharacterCardExport {
        val card = characters.getById(id) ?: error("角色卡不存在")
        return CharacterCardExport(card, packageCard(card))
    }

    private suspend fun createAndCommit(
        packageData: CharacterCardPackage,
        id: String,
        name: String,
        presetKey: String? = null,
        presetVersion: Int? = null,
        operation: CharacterTransferPostCommitOperation,
    ): CharacterCard {
        val ledger = MaterializationLedger()
        var committed = false
        val card = try {
            materialize(
                packageData = packageData,
                id = id,
                name = name,
                presetKey = presetKey,
                presetVersion = presetVersion,
                createdAt = nowMillis(),
                ledger = ledger,
            )
        } catch (error: Throwable) {
            rollback(ledger, error)
            throw error
        }
        try {
            characters.save(card) { committed = true }
        } catch (error: Throwable) {
            if (!committed) {
                rollback(ledger, error)
                throw error
            }
            throw CharacterTransferPostCommitException(
                operation = operation,
                characterId = card.id,
                message = "角色卡已提交，但提交后的仓库状态刷新失败",
                cause = error,
            )
        }
        return card
    }

    private suspend fun packageCard(card: CharacterCard): CharacterCardPackage {
        val exportableCharacters = card.characters.filterNot(CharacterPlaceholderPolicy::isEmpty)
        require(exportableCharacters.all { it.name.isNotBlank() }) { "人物名称不能为空" }
        val documents = card.customDocuments.map { document ->
            PackagedDocument(
                fileName = document.fileName,
                fileType = document.fileType,
                content = resources.readText(document.filePath),
            )
        }
        val images = linkedMapOf<String, PackagedImage>()
        val resourceIdsByReference = mutableMapOf<String, String>()
        fun packageImage(reference: String?, resourceId: String): String? {
            reference?.takeIf(String::isNotBlank) ?: return null
            resourceIdsByReference[reference]?.let { return it }
            images[resourceId] = PackagedImage(
                fileName = resources.fileName(reference).ifBlank { "$resourceId.jpg" },
                data = Base64.getEncoder().encodeToString(resources.readBytes(reference)),
            )
            resourceIdsByReference[reference] = resourceId
            return resourceId
        }
        val packagedCharacters = exportableCharacters.mapIndexed { index, character ->
            PackagedCharacter(
                name = character.name,
                profile = character.profile,
                appearance = character.appearance,
                appearanceImageResourceId = packageImage(
                    character.appearanceImage,
                    "character-$index-appearance",
                ),
                clothing = character.clothing,
                abilities = character.abilities,
                habits = character.habits,
                background = character.background,
                relationships = character.relationships,
                speakingStyle = character.speakingStyle,
                imagePrompt = character.imagePrompt,
                fishAudioVoice = character.fishAudioVoice,
            )
        }
        return CharacterCardPackage(
            card = PackagedCharacterCard(
                name = card.name,
                botName = card.botName,
                avatarResourceId = packageImage(card.avatar, "avatar"),
                characters = packagedCharacters,
                greeting = card.greeting,
                alternateGreetings = card.alternateGreetings,
                chatBackgroundResourceId = packageImage(card.chatBackground, "chat-background"),
                editMode = card.editMode,
                basicSetting = card.basicSetting,
                freeformCharacterText = card.freeformCharacterText,
                defaultImagePrompt = card.defaultImagePrompt,
                defaultImageNegativePrompt = promptPolicy.effectiveCharacterNaiNegativePrompt(
                    card.defaultImageNegativePrompt,
                ),
                defaultNovelAiImageModel = card.defaultNovelAiImageModel,
                systemPrompt = card.systemPrompt,
                postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample,
                creatorNotes = card.creatorNotes,
                tags = card.tags,
                creator = card.creator,
                characterVersion = card.characterVersion,
                extensions = card.extensions,
                characterBook = card.characterBook,
            ),
            documents = documents,
            images = images,
            defaultFormatCard = card.defaultFormatCardId?.takeIf(String::isNotBlank)?.let { formatId ->
                val format = formatCards.getById(formatId)
                    ?: error("绑定的默认格式卡不存在，请在角色卡编辑页重新选择或取消绑定")
                FormatCardPackage(
                    name = format.name,
                    content = format.content,
                    userTools = format.userTools,
                    sourcePresetKey = format.sourcePresetKey,
                    sourcePresetVersion = format.sourcePresetVersion,
                ).also { it.validateForImport() }
            },
            worldBooks = (
                card.worldBookIds.mapNotNull { worldBooks.getById(it) } +
                    listOfNotNull(card.characterBook)
                ).distinctBy { it.id },
        )
    }

    private suspend fun materialize(
        packageData: CharacterCardPackage,
        id: String,
        name: String,
        presetKey: String?,
        presetVersion: Int?,
        createdAt: Long,
        ledger: MaterializationLedger,
    ): CharacterCard {
        val now = nowMillis()
        val imageReferences = packageData.images.mapValues { (_, packaged) ->
            val reference = resources.materializeImage(packaged, now, newId())
            ledger.resourceReferences += reference
            reference
        }
        val documents = packageData.documents.map { packaged ->
            val documentId = newId()
            val reference = resources.materializeDocument(packaged, now, documentId)
            ledger.resourceReferences += reference
            DocumentInfo.create(packaged.fileName, reference, packaged.fileType).copy(
                id = documentId,
                addedAt = now,
            )
        }
        val packagedCard = packageData.card
        val availableWorldBooks = worldBooks.getAll().toMutableList()
        val importedWorldBooks = (packageData.worldBooks + listOfNotNull(packagedCard.characterBook))
            .distinctBy { it.id }
            .map { source ->
                WorldBookReusePolicy.findReusable(source, availableWorldBooks)
                    ?: createImportedWorldBook(
                        source = source,
                        cardName = name,
                        presetKey = presetKey,
                        presetVersion = presetVersion,
                        now = now,
                        existingNames = availableWorldBooks.map { it.name },
                    ).also { created ->
                        ledger.worldBookIds += created.id
                        worldBooks.save(created)
                        availableWorldBooks += created
                    }
            }
        val defaultFormatCardId = packageData.defaultFormatCard?.let { packaged ->
            formatCards.importCharacterDefault(packaged) { createdId ->
                ledger.formatCardIds += createdId
            }.id
        }
        return CharacterCard(
            id = id,
            name = NamePolicy.normalize(name),
            botName = packagedCard.botName,
            avatar = packagedCard.avatarResourceId?.let(imageReferences::getValue),
            characters = CharacterSpeakerNamePolicy.normalizeUnique(
                packagedCard.characters.map { character ->
                    CharacterInfo(
                        id = newId(),
                        name = character.name,
                        profile = character.profile,
                        appearance = character.appearance,
                        appearanceImage = character.appearanceImageResourceId?.let(imageReferences::getValue),
                        clothing = character.clothing,
                        abilities = character.abilities,
                        habits = character.habits,
                        background = character.background,
                        relationships = character.relationships,
                        speakingStyle = character.speakingStyle,
                        imagePrompt = character.imagePrompt,
                        fishAudioVoice = character.fishAudioVoice,
                    )
                },
            ),
            customDocuments = documents,
            greeting = packagedCard.greeting,
            alternateGreetings = packagedCard.alternateGreetings,
            chatBackground = packagedCard.chatBackgroundResourceId?.let(imageReferences::getValue),
            editMode = packagedCard.editMode,
            basicSetting = packagedCard.basicSetting,
            freeformCharacterText = packagedCard.freeformCharacterText,
            defaultImagePrompt = packagedCard.defaultImagePrompt,
            defaultImageNegativePrompt = promptPolicy.effectiveCharacterNaiNegativePrompt(
                packagedCard.defaultImageNegativePrompt,
            ),
            defaultNovelAiImageModel = packagedCard.defaultNovelAiImageModel,
            systemPrompt = packagedCard.systemPrompt,
            postHistoryInstructions = packagedCard.postHistoryInstructions,
            mesExample = packagedCard.mesExample,
            creatorNotes = packagedCard.creatorNotes,
            tags = packagedCard.tags,
            creator = packagedCard.creator,
            characterVersion = packagedCard.characterVersion,
            extensions = packagedCard.extensions,
            worldBookIds = importedWorldBooks.map { it.id },
            defaultFormatCardId = defaultFormatCardId,
            characterBook = null,
            boundWorldBookId = null,
            sourcePresetKey = presetKey,
            sourcePresetVersion = presetVersion,
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0,
            ragIndexTotal = documents.size,
            ragIndexMessage = if (documents.isEmpty()) "无参考文档" else "参考文档待建立索引",
            ragIndexedAt = null,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    private fun createImportedWorldBook(
        source: WorldBook,
        cardName: String,
        presetKey: String?,
        presetVersion: Int?,
        now: Long,
        existingNames: List<String>,
    ): WorldBook {
        val fallbackName = "$cardName 世界书"
        val requested = source.name.ifBlank { fallbackName }
        val worldBookName = if (existingNames.any { NamePolicy.isSame(it, requested) }) {
            NamePolicy.nextCopyName(requested, existingNames)
        } else {
            NamePolicy.normalize(requested)
        }
        val sourcePresetKey = source.sourcePresetKey?.takeIf(String::isNotBlank)
        val resolvedPresetKey = sourcePresetKey ?: presetKey?.takeIf(String::isNotBlank)
        val resolvedPresetVersion = if (sourcePresetKey != null) source.sourcePresetVersion else presetVersion
        return source.copy(
            id = newId(),
            name = worldBookName,
            entries = source.entries.map { it.copy(id = newId()) },
            sourcePresetKey = resolvedPresetKey,
            sourcePresetVersion = resolvedPresetVersion,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun rollback(ledger: MaterializationLedger, primary: Throwable) {
        withContext(NonCancellable) {
            ledger.formatCardIds.asReversed().forEach { id ->
                runCatching { formatCards.delete(id) }.exceptionOrNull()?.let(primary::addSuppressed)
            }
            ledger.worldBookIds.asReversed().forEach { id ->
                runCatching { worldBooks.delete(id) }.exceptionOrNull()?.let(primary::addSuppressed)
            }
            ledger.resourceReferences.asReversed().forEach { reference ->
                runCatching { resources.deleteOwned(reference) }.exceptionOrNull()?.let(primary::addSuppressed)
            }
        }
    }

    private suspend fun cleanupOwnedResources(
        card: CharacterCard,
        preserveReferences: Set<String> = emptySet(),
    ): Throwable? {
        var primary: Throwable? = null
        card.ownedResourceReferences()
            .filterNot(preserveReferences::contains)
            .forEach { reference ->
                try {
                    resources.deleteOwned(reference)
                } catch (error: Throwable) {
                    if (primary == null) primary = error else primary.addSuppressed(error)
                }
            }
        try {
            ragCleanup.deleteDocumentChunks(card.id)
        } catch (error: Throwable) {
            if (primary == null) primary = error else primary.addSuppressed(error)
        }
        return primary
    }

    private fun CharacterCard.ownedResourceReferences(): Set<String> = buildSet {
        avatar?.takeIf(String::isNotBlank)?.let(::add)
        chatBackground?.takeIf(String::isNotBlank)?.let(::add)
        characters.mapNotNullTo(this) { it.appearanceImage?.takeIf(String::isNotBlank) }
        customDocuments.mapNotNullTo(this) { it.filePath.takeIf(String::isNotBlank) }
    }

    private class MaterializationLedger {
        val resourceReferences = mutableListOf<String>()
        val worldBookIds = mutableListOf<String>()
        val formatCardIds = mutableListOf<String>()
    }
}

internal interface CharacterTransferCharacterStore {
    suspend fun getAll(): List<CharacterCard>
    suspend fun getById(id: String): CharacterCard?
    suspend fun save(card: CharacterCard, onCommitted: () -> Unit)
    suspend fun delete(id: String, onCommitted: () -> Unit)
}

internal interface CharacterTransferWorldBookStore {
    suspend fun getAll(): List<WorldBook>
    suspend fun getById(id: String): WorldBook?
    suspend fun save(book: WorldBook)
    suspend fun delete(id: String)
}

internal interface CharacterTransferFormatCardStore {
    suspend fun getById(id: String): com.example.chatbar.data.local.entity.FormatCard?
    suspend fun importCharacterDefault(
        packageData: FormatCardPackage,
        onCreating: (String) -> Unit,
    ): com.example.chatbar.data.local.entity.FormatCard
    suspend fun delete(id: String)
}

private class RepositoryCharacterTransferStore(
    private val repository: CharacterRepository,
) : CharacterTransferCharacterStore {
    override suspend fun getAll(): List<CharacterCard> = repository.getAll()
    override suspend fun getById(id: String): CharacterCard? = repository.getById(id)
    override suspend fun save(card: CharacterCard, onCommitted: () -> Unit) =
        repository.saveForTransfer(card, onCommitted)
    override suspend fun delete(id: String, onCommitted: () -> Unit) =
        repository.deleteForTransfer(id, onCommitted)
}

private class RepositoryWorldBookTransferStore(
    private val repository: WorldBookRepository,
) : CharacterTransferWorldBookStore {
    override suspend fun getAll(): List<WorldBook> = repository.getAll()
    override suspend fun getById(id: String): WorldBook? = repository.getById(id)
    override suspend fun save(book: WorldBook) = repository.save(book)
    override suspend fun delete(id: String) = repository.delete(id)
}

private class RepositoryFormatCardTransferStore(
    private val repository: FormatCardRepository,
    json: Json,
) : CharacterTransferFormatCardStore {
    private val transfer = FormatCardTransferService(repository, json)

    override suspend fun getById(id: String) = repository.getById(id)

    override suspend fun importCharacterDefault(
        packageData: FormatCardPackage,
        onCreating: (String) -> Unit,
    ) = transfer.importCharacterDefaultTracked(packageData, onCreating)

    override suspend fun delete(id: String) = repository.delete(id)
}
