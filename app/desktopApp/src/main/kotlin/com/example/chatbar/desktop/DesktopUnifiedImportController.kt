package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.domain.card.ModelTemplateImportObservation
import com.example.chatbar.domain.card.ModelTemplatePackage
import com.example.chatbar.domain.card.ModelTemplateTransferService
import com.example.chatbar.domain.card.SharedImportClassifierCore
import com.example.chatbar.domain.card.SharedImportCoreInspection
import com.example.chatbar.domain.card.SharedImportImageInfo
import com.example.chatbar.domain.card.SharedImportKind
import com.example.chatbar.domain.card.SharedImportModelTemplateDecoder
import com.example.chatbar.domain.card.SillyTavernCardMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal data class DesktopImportFocus(val kind: SharedImportKind, val targetId: String)
internal data class DesktopUnknownImport(val bytes: ByteArray, val displayName: String)
internal data class DesktopUnresolvedModelImport(val expected: ModelConfig) {
    val targetId: String get() = expected.id
}
internal data class DesktopUnifiedImportState(
    val busy: Boolean = false,
    val unknown: DesktopUnknownImport? = null,
    val unsupported: Boolean = false,
    val imageDeferred: Boolean = false,
    val unresolvedModel: DesktopUnresolvedModelImport? = null,
    val focus: DesktopImportFocus? = null,
    val status: String? = null,
    val error: String? = null,
)

/** One user-picked file; shared classifier and existing typed transfer own all resource semantics. */
internal class DesktopUnifiedImportController(
    private val typed: DesktopTypedTransferController,
    private val models: ModelRepository,
    private val templates: ModelTemplateTransferService,
    private val picker: DesktopFilePicker,
    private val writer: DesktopExternalFileWriterFacade = DesktopExternalFileWriterFacade.Default,
    json: Json,
    private val readModelDurable: suspend (String) -> JsonFileStorage.EntityReadResult<ModelConfig> = models::readDurableModel,
    private val afterModelPrepared: (ModelConfig) -> Unit = {},
    private val afterModelCommitted: () -> Unit = {},
) {
    private val classifier = SharedImportClassifierCore(
        sillyTavernMapper = SillyTavernCardMapper(AuthoritativeCharacterTransferPromptPolicy),
        modelTemplateDecoder = SharedImportModelTemplateDecoder<ModelTemplatePackage>(templates::decode),
        json = json,
    )
    private val mutableState = MutableStateFlow(DesktopUnifiedImportState())
    val state = mutableState.asStateFlow()
    private var waitingForTyped: SharedImportKind? = null

    suspend fun chooseFile() {
        if (!mayBeginUnifiedImport()) return
        val path = picker.pickOpenFile(DesktopFileType("All files", emptyList())) ?: return
        importFile(path)
    }

    suspend fun importFile(path: Path) {
        if (mutableState.value.busy) return
        if (!mayBeginUnifiedImport()) return
        try {
            mutableState.value = mutableState.value.copy(busy = true, error = null, status = null,
                unknown = null, unsupported = false, imageDeferred = false, focus = null)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(path) }
            process(bytes, path.fileName.toString())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: "无法读取文件")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    private suspend fun process(bytes: ByteArray, displayName: String) {
        val imageInfo = withContext(Dispatchers.IO) { inspectImage(bytes) }
        route(classifier.inspect(bytes, displayName, imageInfo), bytes, displayName)
    }

    suspend fun tryManualTarget(kind: SharedImportKind) {
        val pending = mutableState.value.unknown ?: return
        if (mutableState.value.busy || !mayBeginUnifiedImport()) return
        try {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            val decoded = classifier.decodeAs(pending.bytes, kind, pending.displayName)
            route(decoded, pending.bytes, pending.displayName)
            if (mutableState.value.error == null) mutableState.value = mutableState.value.copy(unknown = null)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: "内容校验失败")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    private fun mayBeginUnifiedImport(): Boolean {
        val transfer = typed.state.value
        val message = when {
            transfer.unresolvedTransfer != null -> "请先重新核实之前的导入"
            transfer.busy || transfer.pendingConflict != null -> "请先完成当前导入决策"
            else -> return true
        }
        mutableState.value = mutableState.value.copy(error = message)
        return false
    }

    private suspend fun route(inspection: SharedImportCoreInspection<ModelTemplatePackage>, bytes: ByteArray,
        displayName: String) {
        when (inspection) {
            is SharedImportCoreInspection.Character -> routeTyped(SharedImportKind.CHARACTER) {
                typed.importCharacterRequest(inspection.request)
            }
            is SharedImportCoreInspection.Format -> routeTyped(SharedImportKind.FORMAT) {
                typed.importFormatDecoded(inspection.packageData)
            }
            is SharedImportCoreInspection.WorldBook -> routeTyped(SharedImportKind.WORLD_BOOK) {
                typed.importWorldBookDecoded(inspection.packageData)
            }
            is SharedImportCoreInspection.ModelTemplate -> importModel(inspection.packageData)
            is SharedImportCoreInspection.Image -> mutableState.value = mutableState.value.copy(
                imageDeferred = true, unknown = null, status = DesktopUiText.IMAGE_DEFERRED.zhCn)
            is SharedImportCoreInspection.Unknown -> mutableState.value = mutableState.value.copy(
                unknown = if (inspection.textLike) DesktopUnknownImport(bytes, displayName) else null,
                unsupported = !inspection.textLike, imageDeferred = false,
                status = if (inspection.textLike) DesktopUiText.UNKNOWN_MANUAL_NOTE.zhCn
                    else DesktopUiText.UNSUPPORTED_FILE.zhCn)
        }
    }

    private suspend fun routeTyped(kind: SharedImportKind, action: suspend () -> Unit) {
        if (typed.state.value.unresolvedTransfer != null) {
            mutableState.value = mutableState.value.copy(error = "请先重新核实之前的导入")
            return
        }
        waitingForTyped = kind
        action()
        acceptTypedResult(typed.state.value)
        if (typed.state.value.pendingConflict == null && typed.state.value.lastResult == null) {
            waitingForTyped = null
            typed.state.value.error?.let { mutableState.value = mutableState.value.copy(error = it) }
        }
    }

    /** Called when the existing conflict dialog finishes; only exact transfer result IDs can request focus. */
    fun acceptTypedResult(transfer: DesktopTypedTransferState) {
        val expectedKind = waitingForTyped ?: return
        val result = transfer.lastResult
        if (result != null && result.kind.name == expectedKind.name) {
            waitingForTyped = null
            mutableState.value = mutableState.value.copy(focus = DesktopImportFocus(expectedKind, result.targetId),
                unknown = null, status = DesktopUiText.IMPORT_COMPLETED.zhCn, error = null)
        } else if (!transfer.busy && transfer.pendingConflict == null && transfer.unresolvedTransfer == null) {
            waitingForTyped = null
        }
    }

    suspend fun importModelFile(path: Path) {
        if (mutableState.value.busy || mutableState.value.unresolvedModel != null) return
        try {
            mutableState.value = mutableState.value.copy(busy = true, error = null, status = null, focus = null)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(path) }
            val decoded = classifier.decodeAs(bytes, SharedImportKind.MODEL_TEMPLATE, path.fileName.toString())
                as SharedImportCoreInspection.ModelTemplate
            importModel(decoded.packageData)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: "模型模板校验失败")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    suspend fun chooseModelTemplate() {
        if (mutableState.value.unresolvedModel != null || mutableState.value.busy) return
        picker.pickOpenFile(DesktopTypedTransferController.JSON_FILES)?.let { importModelFile(it) }
    }

    private enum class ModelCommitOutcome { PRECOMMIT, COMMITTED, INDETERMINATE }

    private suspend fun classifyModel(expected: ModelConfig): ModelCommitOutcome {
        val result = try { readModelDurable(expected.id) }
        catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { return ModelCommitOutcome.INDETERMINATE }
        return when (result) {
            is JsonFileStorage.EntityReadResult.Valid -> if (result.value == expected) ModelCommitOutcome.COMMITTED
                else ModelCommitOutcome.INDETERMINATE
            JsonFileStorage.EntityReadResult.Missing -> ModelCommitOutcome.PRECOMMIT
            is JsonFileStorage.EntityReadResult.Corrupt,
            is JsonFileStorage.EntityReadResult.ReadError -> ModelCommitOutcome.INDETERMINATE
        }
    }

    private suspend fun importModel(packageData: ModelTemplatePackage) {
        if (mutableState.value.unresolvedModel != null) {
            mutableState.value = mutableState.value.copy(error = "请先重新核实模型模板导入")
            return
        }
        val observation = ModelTemplateImportObservation()
        try {
            val imported = templates.importNewObserved(packageData, observation, afterModelPrepared)
            afterModelCommitted()
            completeModel(imported)
        } catch (error: Throwable) {
            val expected = observation.expected
            val outcome = withContext(NonCancellable) {
                when {
                    observation.committed -> ModelCommitOutcome.COMMITTED
                    expected == null -> ModelCommitOutcome.PRECOMMIT
                    else -> runCatching { classifyModel(expected) }.getOrDefault(ModelCommitOutcome.INDETERMINATE)
                }
            }
            when (outcome) {
                ModelCommitOutcome.COMMITTED -> withContext(NonCancellable) { completeModel(requireNotNull(expected)) }
                ModelCommitOutcome.INDETERMINATE -> mutableState.value = mutableState.value.copy(
                    unresolvedModel = DesktopUnresolvedModelImport(requireNotNull(expected)),
                    error = "模型模板提交状态无法确认；请重新核实目标 ${expected.id}", status = null)
                ModelCommitOutcome.PRECOMMIT -> if (error !is CancellationException)
                    mutableState.value = mutableState.value.copy(error = error.message ?: "模型模板导入失败")
            }
            if (error is CancellationException) throw error
        }
    }

    private suspend fun completeModel(model: ModelConfig) {
        val refreshed = runCatching { models.refreshModelsFromStorage() }.isSuccess
        mutableState.value = mutableState.value.copy(unresolvedModel = null,
            focus = DesktopImportFocus(SharedImportKind.MODEL_TEMPLATE, model.id),
            status = DesktopUiText.MODEL_TEMPLATE_IMPORTED.zhCn + if (refreshed) "" else " 模型列表刷新失败。",
            error = null, unknown = null)
    }

    suspend fun recheckModelImport() {
        val unresolved = mutableState.value.unresolvedModel ?: return
        if (mutableState.value.busy) return
        try {
            mutableState.value = mutableState.value.copy(busy = true)
            when (classifyModel(unresolved.expected)) {
                ModelCommitOutcome.COMMITTED -> withContext(NonCancellable) { completeModel(unresolved.expected) }
                ModelCommitOutcome.PRECOMMIT -> mutableState.value = mutableState.value.copy(
                    unresolvedModel = null, error = null,
                    status = DesktopUiText.MODEL_TEMPLATE_PRECOMMIT.zhCn)
                ModelCommitOutcome.INDETERMINATE -> mutableState.value = mutableState.value.copy(
                    error = "仍无法确认模型模板提交状态；请稍后重新核实。")
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    suspend fun exportModel(id: String, displayName: String) {
        if (mutableState.value.busy) return
        try {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            val raw = templates.exportJson(id)
            val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "model-template" }
            val destination = picker.pickSaveFile(DesktopTypedTransferController.JSON_FILES, "$safe.json")
                ?: return
            withContext(Dispatchers.IO) { writer.writeText(destination, raw) }
            mutableState.value = mutableState.value.copy(status = DesktopUiText.MODEL_TEMPLATE_EXPORTED.zhCn)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "导出失败")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    fun dismissNotice() {
        mutableState.value = mutableState.value.copy(unknown = null, unsupported = false,
            imageDeferred = false, error = null, status = null)
    }

    fun consumeFocus() { mutableState.value = mutableState.value.copy(focus = null) }
}

/** ImageIO decodes bytes; file name, extension and picker filter contribute no classification evidence. */
internal fun inspectImage(bytes: ByteArray): SharedImportImageInfo? = runCatching {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            val mime = when (reader.formatName.lowercase()) {
                "png" -> "image/png"
                "jpeg", "jpg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                else -> return@use null
            }
            reader.read(0) ?: return@use null
            SharedImportImageInfo(mime, reader.getWidth(0), reader.getHeight(0),
                animatedGif = mime == "image/gif" && reader.getNumImages(true) > 1)
        } finally { reader.dispose() }
    }
}.getOrNull()
