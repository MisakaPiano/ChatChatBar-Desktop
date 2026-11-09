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
import com.example.chatbar.domain.card.CharacterCardExportCompatibility
import com.example.chatbar.domain.card.forExportCompatibility
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterTransferPostCommitException
import com.example.chatbar.domain.card.CharacterTransferPostCommitOperation
import com.example.chatbar.domain.card.CharacterTransferObservation
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.OriginalCharacterCover
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext

internal enum class DesktopTransferKind { CHARACTER, FORMAT, WORLD_BOOK }
internal enum class DesktopTransferConflictAction { OVERWRITE, IMPORT_AS_NEW, CANCEL }

/** Only the Character transfer post-commit wrapper is allowed to carry an original cancellation. */
internal fun characterTransferCancellationCause(error: Throwable): CancellationException? = when (error) {
    // Coroutine stacktrace recovery may copy a CancellationException and chain the original as its cause.
    is CancellationException -> (error.cause as? CancellationException)
        ?.let(::characterTransferCancellationCause) ?: error
    is CharacterTransferPostCommitException -> error.cause?.let(::characterTransferCancellationCause)
    else -> null
}

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

/** In-memory evidence for this importer lifetime; never serialized or inferred from list caches. */
internal data class DesktopUnresolvedTransfer(
    val kind: DesktopTransferKind,
    val action: DesktopTransferConflictAction,
    val targetId: String,
    internal val expected: Any,
    internal val prior: Any?,
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

internal data class DesktopCoverExportDraft(val card: CharacterCard, val packageData: CharacterCardPackage,
    val backgroundBytes: ByteArray?, val originalCoverBytes: ByteArray? = null)
internal class DesktopCharacterJsonExportDraft(val card: CharacterCard, val packageData: CharacterCardPackage)

internal data class DesktopTypedTransferState(
    val externalImportReceipt: DesktopExternalImportReceipt? = null,
    val coverExport: DesktopCoverExportDraft? = null,
    val characterJsonExport: DesktopCharacterJsonExportDraft? = null,
    val characters: List<DesktopTransferItem> = emptyList(),
    val formats: List<DesktopTransferItem> = emptyList(),
    val worldBooks: List<DesktopTransferItem> = emptyList(),
    val pendingConflict: DesktopPendingTransferConflict? = null,
    val status: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val committedNotice: DesktopTransferCommittedNotice? = null,
    val typedNotice: DesktopTypedTransferNotice? = null,
    val unresolvedTransfer: DesktopUnresolvedTransfer? = null,
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
    private val readCharacterDurable: suspend (String) -> JsonFileStorage.EntityReadResult<CharacterCard> = characterRepository::readDurable,
    private val guardCharacterOutput: (CharacterCard, Path) -> Unit = { _, _ -> },
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
        if (rejectUnverifiedWrite()) return
        val path = filePicker.pickOpenFile(CHARACTER_FILES) ?: return
        importCharacter(path)
    }

    suspend fun chooseAndImportFormat() {
        if (rejectUnverifiedWrite()) return
        val path = filePicker.pickOpenFile(JSON_FILES) ?: return
        importFormat(path)
    }

    suspend fun chooseAndImportWorldBook() {
        if (rejectUnverifiedWrite()) return
        val path = filePicker.pickOpenFile(JSON_FILES) ?: return
        importWorldBook(path)
    }

    suspend fun importCharacter(path: Path) = runOperation(null, writing = true) {
        val request = withContext(kotlinx.coroutines.Dispatchers.IO) { decodeCharacter(path) }
        importCharacter(request)
    }

    internal suspend fun importCharacter(request: CharacterCardImportRequest) {
        if (rejectUnverifiedWrite()) return
        val existing = findCharacterConflict(request)
        if (existing == null) {
            executeCharacter(request, null, "角色导入成功")
        } else {
            mutableState.value = mutableState.value.copy(
                pendingConflict = DesktopPendingTransferConflict.Character(
                    existingId = existing.id,
                    existingName = existing.name,
                    incomingName = request.packageData.card.name,
                    request = request.copy(communityAuthority = com.example.chatbar.domain.card.CharacterCommunityOverwriteAuthority(existing.communityItemId)),
                    overwriteAllowed = !existing.isCommunityDownload ||
                        request.communitySource?.itemId == existing.communityItemId,
                ),
                status = null,
                error = if (existing.isCommunityDownload && request.communitySource?.itemId != existing.communityItemId)
                    "社区下载角色卡不能被本地导入覆盖；可选择作为新角色导入。" else null,
            )
        }
    }

    suspend fun recoverPreset(entry: PresetEntry) = runOperation(null, writing = true) {
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

    suspend fun importFormat(path: Path) = runOperation(null, writing = true) {
        val packageData = withContext(kotlinx.coroutines.Dispatchers.IO) { formatTransfers.decode(Files.readString(path, Charsets.UTF_8)) }
        importFormatPackage(packageData)
    }

    private suspend fun importFormatPackage(packageData: FormatCardPackage) {
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

    suspend fun importWorldBook(path: Path) = runOperation(null, writing = true) {
        val fallback = path.fileName.toString().substringBeforeLast('.').ifBlank { "导入世界书" }
        val packageData = withContext(kotlinx.coroutines.Dispatchers.IO) { worldBookTransfers.decode(Files.readString(path, Charsets.UTF_8), fallback) }
        importWorldPackage(packageData)
    }

    private suspend fun importWorldPackage(packageData: WorldBookPackage) {
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

    /** Network packages use the same conflict/evidence/commit owner; no temporary user file is created. */
    internal suspend fun importCommunity(item: com.example.chatbar.domain.community.CommunityItem,
        payload: com.example.chatbar.domain.community.CommunityImportPayload, updateCharacterId: String? = null
    ) = runOperation(null, writing = true) {
        check(mutableState.value.pendingConflict == null) { "请先处理当前导入冲突" }
        when (payload) {
            is com.example.chatbar.domain.community.CommunityImportPayload.Character -> {
                require(item.type == com.example.chatbar.domain.community.CommunityItemType.CHARACTER)
                val source = com.example.chatbar.domain.card.CharacterCardCommunitySource(item.id, item.updatedAt, item.sha256, item.title)
                val request = CharacterCardImportRequest(payload.data, communitySource = source)
                if (updateCharacterId == null) importCharacter(request)
                else {
                    val existing = requireNotNull(characterRepository.getById(updateCharacterId))
                    require(existing.communityItemId == source.itemId) { "更新来源与本地社区角色不匹配" }
                    mutableState.value = mutableState.value.copy(pendingConflict = DesktopPendingTransferConflict.Character(
                        existing.id, existing.name, payload.data.card.name,
                        request.copy(communityAuthority = com.example.chatbar.domain.card.CharacterCommunityOverwriteAuthority(existing.communityItemId)),
                        overwriteAllowed = true), status = null)
                }
            }
            is com.example.chatbar.domain.community.CommunityImportPayload.Format -> {
                require(item.type == com.example.chatbar.domain.community.CommunityItemType.FORMAT && updateCharacterId == null)
                importFormatPackage(payload.data)
            }
            is com.example.chatbar.domain.community.CommunityImportPayload.WorldBook -> {
                require(item.type == com.example.chatbar.domain.community.CommunityItemType.WORLD_BOOK && updateCharacterId == null)
                importWorldPackage(payload.data)
            }
        }
    }

    suspend fun resolveConflict(action: DesktopTransferConflictAction) {
        if (mutableState.value.pendingConflict == null) return
        runOperation(null, writing = true) {
            val conflict = mutableState.value.pendingConflict ?: return@runOperation
            when (action) {
                DesktopTransferConflictAction.CANCEL -> {
                    mutableState.value = mutableState.value.copy(pendingConflict = null, status = "已取消导入", error = null)
                    return@runOperation
                }
                DesktopTransferConflictAction.OVERWRITE -> when (conflict) {
                    is DesktopPendingTransferConflict.Character -> {
                        require(conflict.overwriteAllowed) { "社区下载角色卡不能被本地导入覆盖，请作为新角色导入" }
                        executeCharacter(conflict.request, conflict.existingId, "导入完成")
                    }
                    is DesktopPendingTransferConflict.Format -> executeTyped(DesktopTransferKind.FORMAT,
                        action, formatPackage = conflict.packageData, existingId = conflict.existingId)
                    is DesktopPendingTransferConflict.WorldBookConflict -> executeTyped(DesktopTransferKind.WORLD_BOOK,
                        action, worldPackage = conflict.packageData, existingId = conflict.existingId)
                }
                DesktopTransferConflictAction.IMPORT_AS_NEW -> when (conflict) {
                    is DesktopPendingTransferConflict.Character -> {
                        executeCharacter(conflict.request, null, "导入完成")
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
            recordExternalImport(requireNotNull(preparedFormat?.id ?: preparedWorld?.id))
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
                val evidence = preparedFormat?.let {
                    DesktopUnresolvedTransfer(kind, action, it.id, it, priorFormat)
                } ?: preparedWorld?.let {
                    DesktopUnresolvedTransfer(kind, action, it.id, it, priorWorld)
                }
                val outcome = withContext(NonCancellable) {
                    when {
                        committed -> TypedCommitOutcome.COMMITTED
                        evidence == null -> TypedCommitOutcome.PRECOMMIT
                        else -> runCatching { classifyEvidence(evidence) }.getOrDefault(TypedCommitOutcome.INDETERMINATE)
                    }
                }
                if (outcome != TypedCommitOutcome.PRECOMMIT) {
                    withContext(NonCancellable) {
                        // Even an uncertain durable result cannot leave a replayable decision.
                        mutableState.value = mutableState.value.copy(pendingConflict = null,
                            unresolvedTransfer = evidence.takeIf { outcome == TypedCommitOutcome.INDETERMINATE })
                        reconcileTyped(kind, action, outcome == TypedCommitOutcome.INDETERMINATE, evidence?.targetId)
                    }
                    if (error is CancellationException) throw error
                    return
                }
            }
            throw error
        }
    }

    private suspend fun classifyEvidence(evidence: DesktopUnresolvedTransfer): TypedCommitOutcome {
        val result = try {
            when (evidence.kind) {
                DesktopTransferKind.CHARACTER -> readCharacterDurable(evidence.targetId)
                DesktopTransferKind.FORMAT -> readFormatDurable(evidence.targetId)
                DesktopTransferKind.WORLD_BOOK -> readWorldDurable(evidence.targetId)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { return TypedCommitOutcome.INDETERMINATE }
        return when (result) {
            is JsonFileStorage.EntityReadResult.Valid<*> -> when {
                result.value == evidence.expected -> TypedCommitOutcome.COMMITTED
                evidence.prior != null && result.value == evidence.prior -> TypedCommitOutcome.PRECOMMIT
                else -> TypedCommitOutcome.INDETERMINATE
            }
            JsonFileStorage.EntityReadResult.Missing ->
                if (evidence.prior == null) TypedCommitOutcome.PRECOMMIT else TypedCommitOutcome.INDETERMINATE
            is JsonFileStorage.EntityReadResult.Corrupt,
            is JsonFileStorage.EntityReadResult.ReadError -> TypedCommitOutcome.INDETERMINATE
        }
    }

    private fun rejectUnverifiedWrite(): Boolean {
        if (mutableState.value.unresolvedTransfer == null) return false
        mutableState.value = mutableState.value.copy(error = DesktopUiText.TRANSFER_RECHECK_REQUIRED.zhCn)
        return true
    }

    suspend fun recheckUnresolvedTransfer() = runOperation(null) {
        val evidence = mutableState.value.unresolvedTransfer ?: return@runOperation
        when (classifyEvidence(evidence)) {
            TypedCommitOutcome.COMMITTED -> {
                mutableState.value = mutableState.value.copy(unresolvedTransfer = null, pendingConflict = null)
                withContext(NonCancellable) {
                    if (evidence.kind == DesktopTransferKind.CHARACTER) {
                        reconcileCharacter(evidence.characterOperation(), evidence.targetId)
                    } else reconcileTyped(evidence.kind, evidence.action, indeterminate = false, targetId = evidence.targetId)
                }
            }
            TypedCommitOutcome.PRECOMMIT -> {
                val refreshed = withContext(NonCancellable) {
                    runCatching { refreshRepository(evidence.kind); refreshLists() }.isSuccess
                }
                mutableState.value = mutableState.value.copy(unresolvedTransfer = null, pendingConflict = null,
                    typedNotice = null, committedNotice = null,
                    error = if (refreshed) null else DesktopUiText.TRANSFER_TYPED_REFRESH_FAILED.zhCn,
                    status = DesktopUiText.TRANSFER_NOT_COMMITTED.zhCn)
            }
            TypedCommitOutcome.INDETERMINATE -> {
                mutableState.value = mutableState.value.copy(error = DesktopUiText.TRANSFER_RECHECK_REQUIRED.zhCn)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun DesktopUnresolvedTransfer.characterOperation() =
        if (action == DesktopTransferConflictAction.OVERWRITE) CharacterTransferPostCommitOperation.OVERWRITE
        else CharacterTransferPostCommitOperation.IMPORT

    private suspend fun executeCharacter(request: CharacterCardImportRequest, existingId: String?, message: String) {
        val observation = CharacterTransferObservation()
        val operation = if (existingId == null) CharacterTransferPostCommitOperation.IMPORT
            else CharacterTransferPostCommitOperation.OVERWRITE
        try {
            val result = if (existingId == null) {
                characterTransfers.importNew(request.packageData, presetKey = request.presetKey,
                    presetVersion = request.presetVersion, observation = observation, communitySource = request.communitySource)
            } else characterTransfers.overwrite(existingId, request.packageData,
                request.presetKey, request.presetVersion, observation, communitySource = request.communitySource,
                communityAuthority = request.communityAuthority)
            recordExternalImport(result.id)
            mutableState.value = mutableState.value.copy(pendingConflict = null)
            finishCommittedCharacter(operation, result.id, message)
        } catch (error: Throwable) {
            val evidence = observation.expected?.let {
                DesktopUnresolvedTransfer(DesktopTransferKind.CHARACTER,
                    if (existingId == null) DesktopTransferConflictAction.IMPORT_AS_NEW else DesktopTransferConflictAction.OVERWRITE,
                    it.id, it, observation.prior)
            }
            val outcome = withContext(NonCancellable) {
                when {
                    observation.committed || error is CharacterTransferPostCommitException -> TypedCommitOutcome.COMMITTED
                    evidence == null -> TypedCommitOutcome.PRECOMMIT
                    else -> runCatching { classifyEvidence(evidence) }.getOrDefault(TypedCommitOutcome.INDETERMINATE)
                }
            }
            if (outcome == TypedCommitOutcome.PRECOMMIT) throw error
            withContext(NonCancellable) {
                mutableState.value = mutableState.value.copy(pendingConflict = null,
                    unresolvedTransfer = evidence.takeIf { outcome == TypedCommitOutcome.INDETERMINATE })
                if (outcome == TypedCommitOutcome.COMMITTED) {
                    reconcileCharacter(operation, evidence?.targetId
                        ?: (error as CharacterTransferPostCommitException).characterId)
                } else {
                    mutableState.value = mutableState.value.copy(status = null, error = null,
                        typedNotice = DesktopTypedTransferNotice(DesktopTransferKind.CHARACTER,
                            requireNotNull(evidence).action, indeterminate = true, reconciled = false))
                }
            }
            characterTransferCancellationCause(error)?.let { throw it }
        }
    }

    private suspend fun reconcileCharacter(operation: CharacterTransferPostCommitOperation, id: String) {
        recordExternalImport(id)
        mutableState.value = mutableState.value.copy(pendingConflict = null, status = null, error = null)
        val reconciled = runCatching { refreshCommittedCharacters(); refreshLists() }.isSuccess
        mutableState.value = mutableState.value.copy(pendingConflict = null, status = null, error = null,
            committedNotice = DesktopTransferCommittedNotice(operation, id, reconciled), typedNotice = null)
    }

    private suspend fun reconcileTyped(kind: DesktopTransferKind, action: DesktopTransferConflictAction,
        indeterminate: Boolean, targetId: String? = null) {
        if (!indeterminate && targetId != null) recordExternalImport(targetId)
        val reconciled = runCatching {
            refreshRepository(kind)
            refreshLists()
        }.isSuccess
        mutableState.value = mutableState.value.copy(pendingConflict = null, status = null, error = null,
            typedNotice = DesktopTypedTransferNotice(kind, action, indeterminate, reconciled))
    }

    private suspend fun recordExternalImport(targetId: String) {
        currentExternalImportReceipt(targetId)?.let { receipt ->
            mutableState.value = mutableState.value.copy(externalImportReceipt = receipt)
        }
    }

    private suspend fun refreshRepository(kind: DesktopTransferKind) = when (kind) {
        DesktopTransferKind.CHARACTER -> refreshCommittedCharacters()
        DesktopTransferKind.FORMAT -> formatRepository.refreshFromStorage()
        DesktopTransferKind.WORLD_BOOK -> worldBookRepository.refreshFromStorage()
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (committed: CharacterTransferPostCommitException) {
            throw committed
        } catch (error: Throwable) {
            throw CharacterTransferPostCommitException(operation, characterId,
                "角色操作已提交，但提交后的状态刷新失败", error)
        }
    }

    suspend fun exportCharacterJson(id: String, compatibility: CharacterCardExportCompatibility = CharacterCardExportCompatibility.OFFICIAL_V9) = runOperation(null) {
        val export = characterTransfers.prepareExport(id, compatibility)
        val destination = filePicker.pickSaveFile(JSON_FILES, safeFileName(export.card.name, "json")) ?: return@runOperation
        guardCharacterOutput(export.card, destination)
        writer.writeText(destination, json.encodeToString(CharacterCardPackage.serializer(), export.packageData))
        mutableState.value = state.value.copy(status = "文件已导出", error = null)
    }

    suspend fun beginCharacterJsonExport(id: String) = runOperation(null) {
        val export = characterTransfers.prepareExport(id)
        mutableState.value = state.value.copy(characterJsonExport = DesktopCharacterJsonExportDraft(export.card, export.packageData), coverExport = null)
    }

    fun cancelCharacterJsonExport() { if (!state.value.busy) mutableState.value = state.value.copy(characterJsonExport = null) }

    suspend fun finishCharacterJsonExport(draft: DesktopCharacterJsonExportDraft, compatibility: CharacterCardExportCompatibility) = runOperation(null) {
        check(state.value.characterJsonExport === draft) { "角色导出已关闭" }
        val packageData = if (compatibility == CharacterCardExportCompatibility.OFFICIAL_V9) draft.packageData else {
            val full = characterTransfers.prepareExport(draft.card.id, compatibility)
            check(full.card == draft.card && full.packageData.forExportCompatibility(CharacterCardExportCompatibility.OFFICIAL_V9)
                .copy(exportedAt = draft.packageData.exportedAt) == draft.packageData) { "角色卡或关联资源已变化，请重新打开导出" }
            full.packageData.copy(exportedAt = draft.packageData.exportedAt)
        }
        check(state.value.characterJsonExport === draft) { "角色导出已关闭" }
        val destination = filePicker.pickSaveFile(JSON_FILES, safeFileName(draft.card.name, "json")) ?: return@runOperation
        guardCharacterOutput(draft.card, destination)
        writer.writeText(destination, json.encodeToString(CharacterCardPackage.serializer(), packageData))
        mutableState.value = state.value.copy(characterJsonExport = null, status = "文件已导出", error = null)
    }

    suspend fun beginCoverExport(id: String) = runOperation(null) {
        val export = characterTransfers.prepareExport(id, CharacterCardExportCompatibility.DESKTOP_COVER_V10)
        val background = export.packageData.card.chatBackgroundResourceId
            ?.let(export.packageData.images::get)?.data?.let(::decodeCharacterPackagedImage)
            ?.let { it as? CharacterPackagedImageContent.Bytes }?.value
        val original = export.packageData.card.coverImageResourceId
            ?.let(export.packageData.images::getValue)?.data?.let(::decodeCharacterPackagedImage)
            ?.let { it as? CharacterPackagedImageContent.Bytes }?.value
        mutableState.value = mutableState.value.copy(coverExport = DesktopCoverExportDraft(export.card, export.packageData, background, original), characterJsonExport = null)
    }

    fun cancelCoverExport() { if (!state.value.busy) mutableState.value = state.value.copy(coverExport = null) }

    internal fun coverExportFilePicker(window: java.awt.Window?): DesktopFilePicker =
        if (window == null) filePicker else filePicker.withWindowOwner(window)

    fun chooseCoverReplacement(picker: DesktopFilePicker = filePicker): ByteArray? {
        val path = picker.pickOpenFile(DesktopFileType("导出封面图片", listOf("png", "jpg", "jpeg", "webp"))) ?: return null
        require(Files.size(path) <= DesktopImageEditing.MAX_BYTES) { "图片大小超过 32 MB" }
        val bytes = Files.readAllBytes(path)
        DesktopImageEditing.requireStatic(bytes)
        DesktopImageEditing.decode(bytes)
        return bytes
    }

    suspend fun finishCoverExport(draft: DesktopCoverExportDraft, options: CharacterCardPngExportOptions,
        replacement: ByteArray?, compatibility: CharacterCardExportCompatibility = CharacterCardExportCompatibility.OFFICIAL_V9,
        picker: DesktopFilePicker = filePicker) = runOperation(null) {
        check(state.value.coverExport === draft) { "封面导出已关闭" }
        val destination = picker.pickSaveFile(PNG_FILES, safeFileName(draft.card.name, "png")) ?: return@runOperation
        guardCharacterOutput(draft.card, destination)
        val rendered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            characterPngRenderer.renderExportImage(draft.card, options, draft.originalCoverBytes ?: draft.backgroundBytes, draft.backgroundBytes, replacement)
        }
        // The replacement changes visible pixels only. Package and original resource map are untouched.
        writer.writeBytes(destination, CharacterCardPngPackageCodec.attach(rendered, draft.packageData.forExportCompatibility(compatibility), json))
        mutableState.value = state.value.copy(coverExport = null, status = "角色 PNG 已导出", error = null)
    }

    suspend fun exportCharacterPng(id: String, options: CharacterCardPngExportOptions = CharacterCardPngExportOptions(),
        compatibility: CharacterCardExportCompatibility = CharacterCardExportCompatibility.OFFICIAL_V9) {
        runOperation(null) {
            val export = characterTransfers.prepareExport(id, CharacterCardExportCompatibility.DESKTOP_COVER_V10)
            val destination = filePicker.pickSaveFile(PNG_FILES, safeFileName(export.card.name, "png")) ?: return@runOperation
            guardCharacterOutput(export.card, destination)
            val backgroundBytes = export.packageData.card.chatBackgroundResourceId
                ?.let(export.packageData.images::get)
                ?.data
                ?.let(::decodeCharacterPackagedImage)
                ?.let { it as? CharacterPackagedImageContent.Bytes }
                ?.value
            val original = export.packageData.card.coverImageResourceId
                ?.let(export.packageData.images::getValue)?.data?.let(::decodeCharacterPackagedImage)
                ?.let { it as? CharacterPackagedImageContent.Bytes }?.value
            val rendered = characterPngRenderer.renderExportImage(export.card, options, original, backgroundBytes)
            writer.writeBytes(destination, CharacterCardPngPackageCodec.attach(rendered, export.packageData.forExportCompatibility(compatibility), json))
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
                    .toCharacterCardPackage(SillyTavernCardParser.parseJson(stJson, OriginalCharacterCover.imageOnlyPng(bytes)))
                    .let { OriginalCharacterCover.attachToImportedPackage(it, bytes) },
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

    private suspend fun runOperation(success: String?, writing: Boolean = false, operation: suspend () -> Unit) {
        if (mutableState.value.busy) return
        if (writing && rejectUnverifiedWrite()) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, committedNotice = null,
            typedNotice = mutableState.value.typedNotice.takeIf { mutableState.value.unresolvedTransfer != null })
        try {
            operation()
            if (success != null) mutableState.value = mutableState.value.copy(status = success)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (committed: CharacterTransferPostCommitException) {
            withContext(NonCancellable) {
                reconcileCharacter(committed.operation, committed.characterId)
            }
            characterTransferCancellationCause(committed)?.let { throw it }
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
