package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.domain.card.CharacterCardImportRequest
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterTransferPostCommitException
import com.example.chatbar.domain.card.CharacterTransferPostCommitOperation
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.card.SillyTavernCardMapper
import com.example.chatbar.domain.card.SillyTavernCardParser
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.card.CharacterPackagedImageContent
import com.example.chatbar.domain.card.decodeCharacterPackagedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext

internal enum class DesktopTransferKind { CHARACTER, FORMAT, WORLD_BOOK }
internal enum class DesktopTransferConflictAction { OVERWRITE, IMPORT_AS_NEW, CANCEL }

internal data class DesktopTransferItem(val id: String, val name: String)
internal data class DesktopTransferCommittedNotice(
    val operation: CharacterTransferPostCommitOperation,
    val characterId: String,
    val reconciled: Boolean,
)
internal data class DesktopTypedTransferNotice(
    val kind: DesktopTransferKind,
    val action: DesktopTransferConflictAction,
    val indeterminate: Boolean,
    val reconciled: Boolean,
)

internal sealed interface DesktopPendingTransferConflict {
    val existingId: String
    val existingName: String
    val incomingName: String

    data class Character(
        override val existingId: String,
        override val existingName: String,
        override val incomingName: String,
        val request: CharacterCardImportRequest,
        val overwriteAllowed: Boolean,
    ) : DesktopPendingTransferConflict

    data class Format(
        override val existingId: String,
        override val existingName: String,
        override val incomingName: String,
        val packageData: FormatCardPackage,
    ) : DesktopPendingTransferConflict

    data class WorldBookConflict(
        override val existingId: String,
        override val existingName: String,
        override val incomingName: String,
        val packageData: WorldBookPackage,
    ) : DesktopPendingTransferConflict
}

internal data class DesktopTypedTransferState(
    val characters: List<DesktopTransferItem> = emptyList(),
    val formats: List<DesktopTransferItem> = emptyList(),
    val worldBooks: List<DesktopTransferItem> = emptyList(),
    val pendingConflict: DesktopPendingTransferConflict? = null,
    val status: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val committedNotice: DesktopTransferCommittedNotice? = null,
    val typedNotice: DesktopTypedTransferNotice? = null,
)

/** Thin Desktop ingress/egress controller over authoritative shared transfer services. */
internal class DesktopTypedTransferController(
    private val characterRepository: CharacterRepository,
    private val formatRepository: FormatCardRepository,
    private val worldBookRepository: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val formatTransfers: FormatCardTransferService,
    private val worldBookTransfers: WorldBookTransferService,
    private val characterPngRenderer: DesktopCharacterCardPngRenderer,
    private val json: Json,
    private val filePicker: DesktopFilePicker,
    private val writer: DesktopExternalFileWriterFacade = DesktopExternalFileWriterFacade.Default,
    private val afterCharacterCommit: (CharacterTransferPostCommitOperation, String) -> Unit = { _, _ -> },
    private val refreshCommittedCharacters: suspend () -> Unit = characterRepository::refreshFromStorage,
    private val presetSource: DesktopPresetSource? = null,
    private val afterTypedPrepared: (DesktopTransferKind, String) -> Unit = { _, _ -> },
    private val afterTypedCommit: suspend (DesktopTransferKind, DesktopTransferConflictAction) -> Unit = { _, _ -> },
    private val readFormatDurable: suspend (String) -> JsonFileStorage.EntityReadResult<FormatCard> = formatRepository::readDurable,
    private val readWorldDurable: suspend (String) -> JsonFileStorage.EntityReadResult<WorldBook> = worldBookRepository::readDurable,
) {
    private val mutableState = MutableStateFlow(DesktopTypedTransferState())
    val state: StateFlow<DesktopTypedTransferState> = mutableState.asStateFlow()

    internal fun markCommittedRefreshFailed() {
        val notice = mutableState.value.committedNotice ?: return
        mutableState.value = mutableState.value.copy(committedNotice = notice.copy(reconciled = false))
    }

    internal fun markTypedRefreshFailed() {
        val notice = mutableState.value.typedNotice ?: return
        mutableState.value = mutableState.value.copy(typedNotice = notice.copy(reconciled = false))
    }

    suspend fun refresh() = runOperation("列表已刷新") {
        refreshLists()
    }

    suspend fun chooseAndImportCharacter() {
        val path = filePicker.pickOpenFile(CHARACTER_FILES) ?: return
        importCharacter(path)
    }

    suspend fun chooseAndImportFormat() {
        val path = filePicker.pickOpenFile(JSON_FILES) ?: return
        importFormat(path)
    }

    suspend fun chooseAndImportWorldBook() {
        val path = filePicker.pickOpenFile(JSON_FILES) ?: return
        importWorldBook(path)
    }

    suspend fun importCharacter(path: Path) = runOperation(null) {
        val request = decodeCharacter(path)
        importCharacter(request)
    }

    internal suspend fun importCharacter(request: CharacterCardImportRequest) {
        val existing = findCharacterConflict(request)
        if (existing == null) {
            val imported = characterTransfers.importNew(request.packageData, presetKey = request.presetKey, presetVersion = request.presetVersion)
            finishCommittedCharacter(CharacterTransferPostCommitOperation.IMPORT, imported.id, "角色导入成功")
        } else {
            mutableState.value = mutableState.value.copy(
                pendingConflict = DesktopPendingTransferConflict.Character(
                    existingId = existing.id,
                    existingName = existing.name,
                    incomingName = request.packageData.card.name,
                    request = request,
                    overwriteAllowed = !existing.isCommunityDownload,
                ),
                status = null,
                error = if (existing.isCommunityDownload) "社区下载角色卡不能被本地导入覆盖；可选择作为新角色导入。" else null,
            )
        }
    }

    suspend fun recoverPreset(entry: PresetEntry) = runOperation(null) {
        val source = presetSource ?: error("Bundled presets unavailable")
        when (entry.type) {
            PresetType.CHARACTER -> importCharacter(
                CharacterCardImportRequest(source.characterPackage(entry), entry.presetKey, entry.version))
            PresetType.FORMAT -> {
                val data = source.formatPackage(entry).copy(sourcePresetKey = entry.presetKey,
                    sourcePresetVersion = entry.version)
                val existing = formatRepository.getAll().firstOrNull { NamePolicy.isSame(it.name, data.name) }
                if (existing == null) {
                    executeTyped(DesktopTransferKind.FORMAT, DesktopTransferConflictAction.IMPORT_AS_NEW,
                        formatPackage = data)
                } else mutableState.value = mutableState.value.copy(
                    pendingConflict = DesktopPendingTransferConflict.Format(existing.id, existing.name, data.name, data),
                    status = null,
                )
            }
            PresetType.WORLD_BOOK -> {
                val packageData = source.worldBookPackage(entry)
                val data = packageData.copy(book = packageData.book.copy(
                    sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version))
                val existing = worldBookRepository.getAll().firstOrNull { NamePolicy.isSame(it.name, data.book.name) }
                if (existing == null) {
                    executeTyped(DesktopTransferKind.WORLD_BOOK, DesktopTransferConflictAction.IMPORT_AS_NEW,
                        worldPackage = data)
                } else mutableState.value = mutableState.value.copy(
                    pendingConflict = DesktopPendingTransferConflict.WorldBookConflict(
                        existing.id, existing.name, data.book.name, data), status = null,
                )
            }
            PresetType.MODEL_CATALOG -> error("Model catalog recovery has a separate authority")
        }
    }

    suspend fun importFormat(path: Path) = runOperation(null) {
        val packageData = formatTransfers.decode(Files.readString(path, Charsets.UTF_8))
        val conflict = formatRepository.getAll().firstOrNull { NamePolicy.isSame(it.name, packageData.name) }
        if (conflict == null) {
            executeTyped(DesktopTransferKind.FORMAT, DesktopTransferConflictAction.IMPORT_AS_NEW,
                formatPackage = packageData)
        } else {
            mutableState.value = mutableState.value.copy(
                pendingConflict = DesktopPendingTransferConflict.Format(conflict.id, conflict.name, packageData.name, packageData),
                status = null,
            )
        }
    }

    suspend fun importWorldBook(path: Path) = runOperation(null) {
        val fallback = path.fileName.toString().substringBeforeLast('.').ifBlank { "导入世界书" }
        val packageData = worldBookTransfers.decode(Files.readString(path, Charsets.UTF_8), fallback)
        val conflict = worldBookRepository.getAll().firstOrNull { NamePolicy.isSame(it.name, packageData.book.name) }
        if (conflict == null) {
            executeTyped(DesktopTransferKind.WORLD_BOOK, DesktopTransferConflictAction.IMPORT_AS_NEW,
                worldPackage = packageData)
        } else {
            mutableState.value = mutableState.value.copy(
                pendingConflict = DesktopPendingTransferConflict.WorldBookConflict(
                    conflict.id,
                    conflict.name,
                    packageData.book.name,
                    packageData,
                ),
                status = null,
            )
        }
    }

    suspend fun resolveConflict(action: DesktopTransferConflictAction) {
        if (mutableState.value.pendingConflict == null) return
        runOperation(null) {
            val conflict = mutableState.value.pendingConflict ?: return@runOperation
            when (action) {
                DesktopTransferConflictAction.CANCEL -> {
                    mutableState.value = mutableState.value.copy(pendingConflict = null, status = "已取消导入", error = null)
                    return@runOperation
                }
                DesktopTransferConflictAction.OVERWRITE -> when (conflict) {
                    is DesktopPendingTransferConflict.Character -> {
                        require(conflict.overwriteAllowed) { "社区下载角色卡不能被本地导入覆盖，请作为新角色导入" }
                        val replaced = characterTransfers.overwrite(
                            conflict.existingId,
                            conflict.request.packageData,
                            conflict.request.presetKey,
                            conflict.request.presetVersion,
                        )
                        finishCommittedCharacter(CharacterTransferPostCommitOperation.OVERWRITE, replaced.id, "导入完成")
                    }
                    is DesktopPendingTransferConflict.Format -> executeTyped(DesktopTransferKind.FORMAT,
                        action, formatPackage = conflict.packageData, existingId = conflict.existingId)
                    is DesktopPendingTransferConflict.WorldBookConflict -> executeTyped(DesktopTransferKind.WORLD_BOOK,
                        action, worldPackage = conflict.packageData, existingId = conflict.existingId)
                }
                DesktopTransferConflictAction.IMPORT_AS_NEW -> when (conflict) {
                    is DesktopPendingTransferConflict.Character -> {
                        val imported = characterTransfers.importNew(
                            conflict.request.packageData,
                            presetKey = conflict.request.presetKey,
                            presetVersion = conflict.request.presetVersion,
                        )
                        finishCommittedCharacter(CharacterTransferPostCommitOperation.IMPORT, imported.id, "导入完成")
                    }
                    is DesktopPendingTransferConflict.Format -> executeTyped(DesktopTransferKind.FORMAT,
                        action, formatPackage = conflict.packageData)
                    is DesktopPendingTransferConflict.WorldBookConflict -> executeTyped(DesktopTransferKind.WORLD_BOOK,
                        action, worldPackage = conflict.packageData)
                }
            }
        }
    }

    private enum class TypedCommitOutcome { PRECOMMIT, COMMITTED, INDETERMINATE }

    /** Exact prepared ID and durable observation prevent a stale conflict from replaying an import. */
    private suspend fun executeTyped(
        kind: DesktopTransferKind,
        action: DesktopTransferConflictAction,
        formatPackage: FormatCardPackage? = null,
        worldPackage: WorldBookPackage? = null,
        existingId: String? = null,
    ) {
        require(kind != DesktopTransferKind.CHARACTER)
        var preparedFormat: FormatCard? = null
        var preparedWorld: WorldBook? = null
        var committed = false
        val priorFormat = if (kind == DesktopTransferKind.FORMAT && existingId != null) {
            (readFormatDurable(existingId) as? JsonFileStorage.EntityReadResult.Valid)?.value
                ?: error("待覆盖格式卡持久化状态不可确认")
        } else null
        val priorWorld = if (kind == DesktopTransferKind.WORLD_BOOK && existingId != null) {
            (readWorldDurable(existingId) as? JsonFileStorage.EntityReadResult.Valid)?.value
                ?: error("待覆盖世界书持久化状态不可确认")
        } else null
        try {
            when (kind) {
                DesktopTransferKind.FORMAT -> if (existingId == null) {
                    formatTransfers.importNewObserved(requireNotNull(formatPackage),
                        onPrepared = { preparedFormat = it; afterTypedPrepared(kind, it.id) }, onCommitted = { committed = true })
                } else {
                    formatTransfers.overwriteObserved(existingId, requireNotNull(formatPackage),
                        onPrepared = { preparedFormat = it; afterTypedPrepared(kind, it.id) }, onCommitted = { committed = true })
                }
                DesktopTransferKind.WORLD_BOOK -> if (existingId == null) {
                    worldBookTransfers.importNewObserved(requireNotNull(worldPackage),
                        onPrepared = { preparedWorld = it; afterTypedPrepared(kind, it.id) }, onCommitted = { committed = true })
                } else {
                    worldBookTransfers.overwriteObserved(existingId, requireNotNull(worldPackage),
                        onPrepared = { preparedWorld = it; afterTypedPrepared(kind, it.id) }, onCommitted = { committed = true })
                }
                DesktopTransferKind.CHARACTER -> error("Character has its own transfer contract")
            }
            // Durable write returned. Consume the choice before any list read that can fail.
            mutableState.value = mutableState.value.copy(pendingConflict = null)
            afterTypedCommit(kind, action)
            try {
                refreshLists(if (kind == DesktopTransferKind.FORMAT) "格式卡导入完成" else "世界书导入完成")
                mutableState.value = mutableState.value.copy(status = null,
                    typedNotice = DesktopTypedTransferNotice(kind, action, indeterminate = false, reconciled = true))
            } catch (error: Throwable) {
                withContext(NonCancellable) { reconcileTyped(kind, action, indeterminate = false) }
                if (error is CancellationException) throw error
            }
        } catch (error: Throwable) {
            if (mutableState.value.typedNotice != null) {
                if (error is CancellationException) throw error
                return
            }
            if (mutableState.value.typedNotice == null) {
                val outcome = withContext(NonCancellable) {
                    classifyTyped(kind, existingId, preparedFormat, preparedWorld,
                        priorFormat, priorWorld, committed)
                }
                if (outcome != TypedCommitOutcome.PRECOMMIT) {
                    withContext(NonCancellable) {
                        // Even an uncertain durable result cannot leave a replayable decision.
                        mutableState.value = mutableState.value.copy(pendingConflict = null)
                        reconcileTyped(kind, action, outcome == TypedCommitOutcome.INDETERMINATE)
                    }
                    if (error is CancellationException) throw error
                    return
                }
            }
            throw error
        }
    }

    private suspend fun classifyTyped(kind: DesktopTransferKind, existingId: String?,
        preparedFormat: FormatCard?, preparedWorld: WorldBook?, priorFormat: FormatCard?,
        priorWorld: WorldBook?, committed: Boolean): TypedCommitOutcome {
        if (committed) return TypedCommitOutcome.COMMITTED
        val id = preparedFormat?.id ?: preparedWorld?.id ?: return TypedCommitOutcome.PRECOMMIT
        val result = try {
            if (kind == DesktopTransferKind.FORMAT) readFormatDurable(id)
            else readWorldDurable(id)
        } catch (_: Exception) { return TypedCommitOutcome.INDETERMINATE }
        return when (result) {
            is JsonFileStorage.EntityReadResult.Valid<*> -> when {
                result.value == preparedFormat || result.value == preparedWorld -> TypedCommitOutcome.COMMITTED
                existingId != null && (result.value == priorFormat || result.value == priorWorld) -> TypedCommitOutcome.PRECOMMIT
                else -> TypedCommitOutcome.INDETERMINATE
            }
            JsonFileStorage.EntityReadResult.Missing ->
                if (existingId == null) TypedCommitOutcome.PRECOMMIT else TypedCommitOutcome.INDETERMINATE
            is JsonFileStorage.EntityReadResult.Corrupt,
            is JsonFileStorage.EntityReadResult.ReadError -> TypedCommitOutcome.INDETERMINATE
        }
    }

    private suspend fun reconcileTyped(kind: DesktopTransferKind, action: DesktopTransferConflictAction,
        indeterminate: Boolean) {
        val reconciled = runCatching {
            if (kind == DesktopTransferKind.FORMAT) formatRepository.refreshFromStorage()
            else worldBookRepository.refreshFromStorage()
            refreshLists()
        }.isSuccess
        mutableState.value = mutableState.value.copy(pendingConflict = null, status = null, error = null,
            typedNotice = DesktopTypedTransferNotice(kind, action, indeterminate, reconciled))
    }

    /** The shared transfer has returned, so any later status/list failure is post-commit. */
    private suspend fun finishCommittedCharacter(
        operation: CharacterTransferPostCommitOperation,
        characterId: String,
        message: String,
    ) {
        try {
            afterCharacterCommit(operation, characterId)
            refreshLists(message)
        } catch (committed: CharacterTransferPostCommitException) {
            throw committed
        } catch (error: Throwable) {
            throw CharacterTransferPostCommitException(operation, characterId,
                "角色操作已提交，但提交后的状态刷新失败", error)
        }
    }

    suspend fun exportCharacterJson(id: String) = exportText(
        suggestedName(id, characterRepository.getById(id)?.name, "json"),
        characterTransfers.exportJson(id),
    )

    suspend fun exportCharacterPng(id: String, options: CharacterCardPngExportOptions = CharacterCardPngExportOptions()) {
        runOperation(null) {
            val export = characterTransfers.prepareExport(id)
            val destination = filePicker.pickSaveFile(PNG_FILES, safeFileName(export.card.name, "png")) ?: return@runOperation
            val backgroundBytes = export.packageData.card.chatBackgroundResourceId
                ?.let(export.packageData.images::get)
                ?.data
                ?.let(::decodeCharacterPackagedImage)
                ?.let { it as? CharacterPackagedImageContent.Bytes }
                ?.value
            val rendered = characterPngRenderer.render(export.card, options, backgroundBytes)
            writer.writeBytes(destination, CharacterCardPngPackageCodec.attach(rendered, export.packageData, json))
            mutableState.value = mutableState.value.copy(status = "角色 PNG 已导出", error = null)
        }
    }

    suspend fun exportFormatJson(id: String) = exportText(
        suggestedName(id, formatRepository.getById(id)?.name, "json"),
        formatTransfers.exportJson(id),
    )

    suspend fun exportWorldBookJson(id: String) = exportText(
        suggestedName(id, worldBookRepository.getById(id)?.name, "json"),
        worldBookTransfers.exportJson(id),
    )

    suspend fun exportWorldBookSillyTavern(id: String) = exportText(
        safeFileName("${worldBookRepository.getById(id)?.name ?: id}-sillytavern", "json"),
        worldBookTransfers.exportSillyTavernJson(id),
    )

    private suspend fun exportText(suggestedName: String, content: String) = runOperation(null) {
        val destination = filePicker.pickSaveFile(JSON_FILES, suggestedName) ?: return@runOperation
        writer.writeText(destination, content)
        mutableState.value = mutableState.value.copy(status = "文件已导出", error = null)
    }

    private fun decodeCharacter(path: Path): CharacterCardImportRequest {
        val bytes = Files.readAllBytes(path)
        if (PngTextChunks.isPng(bytes)) {
            characterTransfers.decodePng(bytes)?.let { return CharacterCardImportRequest(it) }
            val stJson = SillyTavernCardParser.extractCharaChunk(bytes)
                ?: error("PNG 不包含 ChatBar 或 SillyTavern Character metadata")
            return CharacterCardImportRequest(
                SillyTavernCardMapper(AuthoritativeCharacterTransferPromptPolicy)
                    .toCharacterCardPackage(SillyTavernCardParser.parseJson(stJson, bytes)),
            )
        }
        val raw = String(bytes, Charsets.UTF_8)
        val ccb = runCatching { characterTransfers.decode(raw) }.getOrNull()
        return CharacterCardImportRequest(
            ccb ?: SillyTavernCardMapper(AuthoritativeCharacterTransferPromptPolicy)
                .toCharacterCardPackage(SillyTavernCardParser.parseJson(raw)),
        )
    }

    private suspend fun findCharacterConflict(request: CharacterCardImportRequest): CharacterCard? {
        val all = characterRepository.getAll()
        request.presetKey?.takeIf(String::isNotBlank)?.let { key ->
            all.firstOrNull { it.sourcePresetKey == key }?.let { return it }
        }
        return all.firstOrNull { NamePolicy.isSame(it.name, request.packageData.card.name) }
    }

    private suspend fun refreshLists(message: String? = null) {
        mutableState.value = mutableState.value.copy(
            characters = characterRepository.getAll().map { DesktopTransferItem(it.id, it.name) },
            formats = formatRepository.getAll().map { DesktopTransferItem(it.id, it.name) },
            worldBooks = worldBookRepository.getAll().map { DesktopTransferItem(it.id, it.name) },
            pendingConflict = null,
            status = message ?: mutableState.value.status,
            error = null,
        )
    }

    private suspend fun runOperation(success: String?, operation: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, committedNotice = null, typedNotice = null)
        try {
            operation()
            if (success != null) mutableState.value = mutableState.value.copy(status = success)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (committed: CharacterTransferPostCommitException) {
            withContext(NonCancellable) {
                // Consume the decision first: a failed cache refresh must never expose a replay button.
                mutableState.value = mutableState.value.copy(pendingConflict = null, status = null, error = null)
                val reconciled = runCatching {
                    refreshCommittedCharacters()
                    refreshLists()
                }.isSuccess
                mutableState.value = mutableState.value.copy(
                    pendingConflict = null, status = null, error = null,
                    committedNotice = DesktopTransferCommittedNotice(
                        committed.operation, committed.characterId, reconciled,
                    ),
                )
            }
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(error = error.message ?: error::class.simpleName, status = null)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

    private fun suggestedName(id: String, name: String?, extension: String): String =
        safeFileName(name ?: id, extension)

    private fun safeFileName(name: String, extension: String): String =
        "${name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "export" }}.$extension"

    companion object {
        val JSON_FILES = DesktopFileType("JSON", listOf("json"))
        val PNG_FILES = DesktopFileType("PNG", listOf("png"))
        val CHARACTER_FILES = DesktopFileType("Character JSON or PNG", listOf("json", "png"), "json")
    }
}

internal fun interface DesktopExternalFileWriterFacade {
    fun writeBytes(destination: Path, value: ByteArray)

    fun writeText(destination: Path, value: String) = writeBytes(destination, value.toByteArray(Charsets.UTF_8))

    object Default : DesktopExternalFileWriterFacade {
        override fun writeBytes(destination: Path, value: ByteArray) = DesktopExternalFileWriter.writeBytes(destination, value)
        override fun writeText(destination: Path, value: String) = DesktopExternalFileWriter.writeText(destination, value)
    }
}
