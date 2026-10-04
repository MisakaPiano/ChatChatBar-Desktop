package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.domain.card.ModelTemplateImportObservation
import com.example.chatbar.domain.card.ModelTemplatePackage
import com.example.chatbar.domain.card.ModelTemplateTransferService
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class DesktopUnresolvedModelTemplateImport(val expected: ModelConfig) {
    val targetId: String get() = expected.id
}

internal data class DesktopModelTemplateTransferState(
    val busy: Boolean = false,
    val unresolved: DesktopUnresolvedModelTemplateImport? = null,
    val pendingTargetId: String? = null,
    val deliveryError: String? = null,
    val deliveryAttempt: Long = 0,
    val status: String? = null,
    val error: String? = null,
)

/** Explicit Models-section JSON transfer; the shared service owns Package semantics. */
internal class DesktopModelTemplateTransferController(
    private val models: ModelRepository,
    private val templates: ModelTemplateTransferService,
    private val picker: DesktopFilePicker,
    private val writer: DesktopExternalFileWriterFacade = DesktopExternalFileWriterFacade.Default,
    private val readModelDurable: suspend (String) -> JsonFileStorage.EntityReadResult<ModelConfig> = models::readDurableModel,
    private val afterModelPrepared: (ModelConfig) -> Unit = {},
    private val afterModelCommitted: () -> Unit = {},
    private val refreshModels: suspend () -> Unit = models::refreshModelsFromStorage,
) {
    private val mutableState = MutableStateFlow(DesktopModelTemplateTransferState())
    val state = mutableState.asStateFlow()

    suspend fun chooseModelTemplate() {
        if (mutableState.value.busy || mutableState.value.unresolved != null) return
        picker.pickOpenFile(DesktopTypedTransferController.JSON_FILES)?.let { importModelFile(it) }
    }

    suspend fun importModelFile(path: Path) {
        if (mutableState.value.busy || mutableState.value.unresolved != null) return
        try {
            mutableState.value = mutableState.value.copy(busy = true, error = null, status = null,
                pendingTargetId = null, deliveryError = null)
            val raw = withContext(Dispatchers.IO) { Files.readString(path, Charsets.UTF_8) }
            importModel(templates.decode(raw))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: "模型模板校验失败")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    private enum class CommitOutcome { PRECOMMIT, COMMITTED, INDETERMINATE }

    private suspend fun classify(expected: ModelConfig): CommitOutcome {
        val result = try { readModelDurable(expected.id) }
        catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { return CommitOutcome.INDETERMINATE }
        return when (result) {
            is JsonFileStorage.EntityReadResult.Valid -> if (result.value == expected) CommitOutcome.COMMITTED
                else CommitOutcome.INDETERMINATE
            JsonFileStorage.EntityReadResult.Missing -> CommitOutcome.PRECOMMIT
            is JsonFileStorage.EntityReadResult.Corrupt,
            is JsonFileStorage.EntityReadResult.ReadError -> CommitOutcome.INDETERMINATE
        }
    }

    private suspend fun importModel(packageData: ModelTemplatePackage) {
        val observation = ModelTemplateImportObservation()
        try {
            val imported = templates.importNewObserved(packageData, observation, afterModelPrepared)
            afterModelCommitted()
            complete(imported)
        } catch (error: Throwable) {
            val expected = observation.expected
            val outcome = withContext(NonCancellable) {
                when {
                    observation.committed -> CommitOutcome.COMMITTED
                    expected == null -> CommitOutcome.PRECOMMIT
                    else -> runCatching { classify(expected) }.getOrDefault(CommitOutcome.INDETERMINATE)
                }
            }
            when (outcome) {
                CommitOutcome.COMMITTED -> withContext(NonCancellable) {
                    if (mutableState.value.pendingTargetId != expected?.id)
                        finish(requireNotNull(expected), if (error is CancellationException) null else false)
                }
                CommitOutcome.INDETERMINATE -> mutableState.value = mutableState.value.copy(
                    unresolved = DesktopUnresolvedModelTemplateImport(requireNotNull(expected)),
                    error = "模型模板提交状态无法确认；请重新核实目标 ${expected.id}", status = null)
                CommitOutcome.PRECOMMIT -> if (error !is CancellationException)
                    mutableState.value = mutableState.value.copy(error = error.message ?: "模型模板导入失败")
            }
            if (error is CancellationException) throw error
        }
    }

    private suspend fun complete(model: ModelConfig) {
        val refreshed = try { refreshModels(); true }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { finish(model, null) }
            throw cancelled
        } catch (_: Exception) { false }
        finish(model, refreshed)
    }

    private fun finish(model: ModelConfig, refreshed: Boolean?) {
        mutableState.value = mutableState.value.copy(unresolved = null, pendingTargetId = model.id,
            deliveryError = null,
            status = DesktopUiText.MODEL_TEMPLATE_IMPORTED.zhCn + if (refreshed == false) " 模型列表刷新失败。" else "",
            error = null)
    }

    suspend fun recheckUnresolvedImport() {
        val unresolved = mutableState.value.unresolved ?: return
        if (mutableState.value.busy) return
        try {
            mutableState.value = mutableState.value.copy(busy = true)
            when (classify(unresolved.expected)) {
                CommitOutcome.COMMITTED -> complete(unresolved.expected)
                CommitOutcome.PRECOMMIT -> mutableState.value = mutableState.value.copy(
                    unresolved = null, error = null, status = DesktopUiText.MODEL_TEMPLATE_PRECOMMIT.zhCn)
                CommitOutcome.INDETERMINATE -> mutableState.value = mutableState.value.copy(
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
            val destination = picker.pickSaveFile(DesktopTypedTransferController.JSON_FILES, "$safe.json") ?: return
            withContext(Dispatchers.IO) { writer.writeText(destination, raw) }
            mutableState.value = mutableState.value.copy(status = DesktopUiText.MODEL_TEMPLATE_EXPORTED.zhCn)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "导出失败")
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    /** A model target remains pending until its exact editor has opened. */
    suspend fun deliverPendingTarget(open: suspend (String) -> Boolean) {
        val targetId = mutableState.value.pendingTargetId ?: return
        try {
            val opened = open(targetId)
            currentCoroutineContext().ensureActive()
            if (mutableState.value.pendingTargetId == targetId) {
                mutableState.value = if (opened) mutableState.value.copy(pendingTargetId = null, deliveryError = null)
                    else mutableState.value.copy(deliveryError = DesktopUiText.MODEL_TEMPLATE_TARGET_FAILED.zhCn)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (mutableState.value.pendingTargetId == targetId)
                mutableState.value = mutableState.value.copy(deliveryError = DesktopUiText.MODEL_TEMPLATE_TARGET_FAILED.zhCn)
        }
    }

    fun retryDelivery() { mutableState.value = mutableState.value.copy(deliveryError = null,
        deliveryAttempt = mutableState.value.deliveryAttempt + 1) }

    fun dismissDelivery() { mutableState.value = mutableState.value.copy(pendingTargetId = null, deliveryError = null) }
}
