package com.example.chatbar.desktop

import com.example.chatbar.domain.image.NovelAiImageUseTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DesktopExternalImportPhase { CHECKING, READY, CONFLICT, PREVIEW, COMPLETE, UNCERTAIN, ERROR, RESTART }
internal data class DesktopExternalImportState(val item: DesktopExternalFile? = null, val kind: DesktopExternalImportKind = DesktopExternalImportKind.UNKNOWN,
    val phase: DesktopExternalImportPhase = DesktopExternalImportPhase.CHECKING, val busy: Boolean = false,
    val message: String = "", val shown: Boolean = true, val canChooseType: Boolean = false, val targetId: String? = null,
    val imageTargets: List<NovelAiImageUseTarget> = listOf(NovelAiImageUseTarget.IMAGE_TO_IMAGE, NovelAiImageUseTarget.INPAINT))

private object ExternalImportUiDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = !java.awt.EventQueue.isDispatchThread()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = java.awt.EventQueue.invokeLater(block)
}

/** Orchestrates existing import owners. A return/idle flag alone never acknowledges a file. */
internal class DesktopExternalImportController(
    val queue: DesktopExternalFileQueue,
    private val gate: DesktopDataOperationCoordinator,
    val typed: DesktopTypedTransferController,
    val models: DesktopModelTemplateTransferController,
    val merge: DesktopMergeImportController,
    val saves: DesktopSaveSlotController,
    private val studio: DesktopNovelAiStudioController,
    private val imageBusy: () -> Boolean,
    dispatcher: CoroutineDispatcher = ExternalImportUiDispatcher,
    private val unavailable: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DesktopExternalImportState())
    val state = mutable.asStateFlow()
    private var prepared: DesktopExternalPrepared? = null
    private var attempt: DesktopExternalImportAttempt? = null
    private var typedEvidence: DesktopUnresolvedTransfer? = null
    private var modelEvidence: DesktopUnresolvedModelTemplateImport? = null
    private var operation: Job? = null
    private var delegated: Job? = null
    private var closed = false
    init {
        scope.launch {
            queue.state.collect { snapshot ->
                val next = snapshot.files.firstOrNull()
                if (next?.id != state.value.item?.id) mutex.withLock {
                    mutable.value = DesktopExternalImportState(item = next, busy = next != null)
                    prepared = null; attempt = null; typedEvidence = null; modelEvidence = null
                    if (next != null) inspectHead(next)
                }
            }
        }
    }
    private suspend fun inspectHead(item: DesktopExternalFile) {
        try {
            prepared = DesktopExternalImportInspector.prepare(item.path)
            val targets = if (prepared!!.kind == DesktopExternalImportKind.IMAGE)
                desktopImageUseTargets(studio.repository.loadDraft().selectedModel) else state.value.imageTargets
            mutable.update { it.copy(kind = prepared!!.kind,
                imageTargets = targets,
                canChooseType = prepared!!.kind == DesktopExternalImportKind.UNKNOWN,
                phase = if (item.attempted) DesktopExternalImportPhase.UNCERTAIN else DesktopExternalImportPhase.READY,
                message = if (item.attempted) "上次已开始处理此文件，结果需要核对。不会因重启而重新导入；请查看对应页面后结束此项。"
                else "请确认文件类型和导入位置。当前资料尚未改变。") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(phase = DesktopExternalImportPhase.ERROR, message = "文件读取或格式检查未完成；原文件未修改。可以重试或跳过此文件。") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun show(value: Boolean) { mutable.update { it.copy(shown = value) } }
    fun previewImage(itemId: String): ByteArray {
        check(state.value.item?.id == itemId && state.value.kind == DesktopExternalImportKind.IMAGE)
        val source = requireNotNull(prepared)
        DesktopArchiveFiles.safe(source.file, false)
        check(java.nio.file.Files.size(source.file) <= DesktopExternalImportInspector.MAX_TYPED_BYTES)
        val bytes = java.nio.file.Files.readAllBytes(source.file)
        if (source.sha256 != null) check(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == source.sha256)
        return bytes
    }
    fun choose(kind: DesktopExternalImportKind) = action {
        check(state.value.phase == DesktopExternalImportPhase.READY && prepared?.kind == DesktopExternalImportKind.UNKNOWN)
        val targets = if (kind == DesktopExternalImportKind.IMAGE) desktopImageUseTargets(studio.repository.loadDraft().selectedModel) else state.value.imageTargets
        mutable.update { it.copy(kind = kind, imageTargets = targets, message = "类型已选择，请确认导入位置。") }
    }
    fun retryInspection() = action {
        check(state.value.phase != DesktopExternalImportPhase.UNCERTAIN)
        if (prepared == null) state.value.item?.let { inspectHead(it) }
        else mutable.update { it.copy(phase = DesktopExternalImportPhase.READY, message = "可以重新检查或导入当前文件。") }
    }
    fun import(sessionId: String? = null, password: CharArray? = null,
        imageTarget: NovelAiImageUseTarget = NovelAiImageUseTarget.IMAGE_TO_IMAGE) = action(password) {
        requireAdmission()
        val source = requireNotNull(prepared)
        when (state.value.kind) {
            DesktopExternalImportKind.CHARACTER, DesktopExternalImportKind.FORMAT, DesktopExternalImportKind.WORLD_BOOK -> {
                check(!typed.state.value.busy && typed.state.value.pendingConflict == null && typed.state.value.unresolvedTransfer == null) { "请先完成当前卡片导入或核对未确认的结果。" }
                DesktopExternalImportInspector.requireTyped(source, state.value.kind)
                dispatch()
                withContext(requireNotNull(attempt)) { when (state.value.kind) {
                    DesktopExternalImportKind.CHARACTER -> typed.importCharacter(source.file)
                    DesktopExternalImportKind.FORMAT -> typed.importFormat(source.file)
                    else -> typed.importWorldBook(source.file)
                } }
                observeTyped()
            }
            DesktopExternalImportKind.MODEL_TEMPLATE -> {
                check(!models.state.value.busy && models.state.value.unresolved == null) { "请先完成当前模型导入或核对未确认的结果。" }
                DesktopExternalImportInspector.requireTyped(source, state.value.kind)
                dispatch()
                withContext(requireNotNull(attempt)) { models.importModelFile(source.file) }
                observeModel()
            }
            DesktopExternalImportKind.BACKUP -> {
                check(!merge.state.value.busy && !merge.state.value.ready && !merge.state.value.restartRequired) { "请先处理数据页中已有的备份预览。" }
                dispatch()
                awaitDelegated(merge.inspectFile(source.file, password))
                val result = merge.state.value
                check(result.ready) { result.error ?: result.message }
                mutable.update { it.copy(phase = DesktopExternalImportPhase.PREVIEW, message = "备份已检查，尚未合并。请到数据页选择冲突和保存位置；也可结束此项检查。") }
            }
            DesktopExternalImportKind.SAVE_SLOT -> {
                check(!saves.state.value.busy) { "请先完成当前存档操作。" }
                val id = requireNotNull(sessionId) { "请先选择接收存档的会话。" }
                awaitDelegated(saves.openFileContext(id))
                val opened = saves.state.value
                check(opened.sessionId == id && !opened.busy)
                check(opened.error == null) { opened.error.orEmpty() }
                dispatch()
                awaitDelegated(saves.importFile(source.file, requireNotNull(attempt)))
                val result = saves.state.value
                if (attempt!!.matches(result.externalImportReceipt)) complete("存档已导入所选会话的存档列表；当前聊天未被恢复或替换。", result.externalImportReceipt?.targetId)
                else mutable.update { it.copy(phase = DesktopExternalImportPhase.UNCERTAIN,
                    message = result.error ?: "存档导入尚未确认，请保留文件并核对存档列表。不会自动重发。") }
            }
            DesktopExternalImportKind.IMAGE -> {
                check(!imageBusy()) { "Studio 正在生成或处理图片，请结束后重试；没有取消现有任务。" }
                check(imageTarget in desktopImageUseTargets(studio.repository.loadDraft().selectedModel)) { "当前 Studio 模型不支持这个图片用途，请重新选择。" }
                DesktopExternalImportInspector.requireTyped(source, state.value.kind)
                check(!imageBusy()) { "Studio 正在处理图片，请结束后重试；没有取消现有任务。" }
                dispatch()
                if (studio.importGuidance(source.file, imageTarget)) complete("图片已保存到 Studio 的所选图像引导位置；尚未生成图片。")
                else mutable.update { it.copy(phase = DesktopExternalImportPhase.UNCERTAIN,
                    message = "${studio.state.value.status}。图片应用结果尚未确认，请到 Studio 核对；不会自动重发。") }
            }
            DesktopExternalImportKind.UNKNOWN -> error("请选择正确类型，再使用对应的严格检查。")
        }
    }
    fun resolve(choice: DesktopTransferConflictAction) = action {
        requireAdmission()
        check(attempt != null && state.value.phase == DesktopExternalImportPhase.CONFLICT)
        withContext(requireNotNull(attempt)) { typed.resolveConflict(choice) }
        if (choice == DesktopTransferConflictAction.CANCEL && typed.state.value.pendingConflict == null) finish()
        else observeTyped()
    }
    fun recheck() = action {
        requireAdmission()
        if (attempt != null && modelEvidence != null && models.state.value.unresolved === modelEvidence) {
            withContext(requireNotNull(attempt)) { models.recheckUnresolvedImport() }; observeModel()
        } else if (attempt != null && typedEvidence != null && typed.state.value.unresolvedTransfer === typedEvidence) {
            withContext(requireNotNull(attempt)) { typed.recheckUnresolvedTransfer() }; observeTyped()
        }
        else mutable.update { it.copy(message = "请到对应页面核对上次结果；当前进程没有上次提交的证明，不会猜测成功或重新导入。") }
    }
    private fun observeTyped() {
        val owned = requireNotNull(attempt)
        val result = typed.state.value
        when {
            result.unresolvedTransfer != null -> {
                typedEvidence = result.unresolvedTransfer
                mutable.update { it.copy(phase = DesktopExternalImportPhase.UNCERTAIN, message = "导入写入结果未确认，不能自动重发。请重新核对持久化结果。") }
            }
            result.pendingConflict != null -> mutable.update { it.copy(phase = DesktopExternalImportPhase.CONFLICT, message = "发现同名内容，请选择覆盖、作为新内容导入或取消。") }
            owned.matches(result.externalImportReceipt) -> complete("导入已写入。列表刷新或后续处理若有提示，请在管理页核对；不会重复导入。", result.externalImportReceipt?.targetId)
            else -> error(result.error ?: "导入没有产生已确认的提交；请检查当前操作后重试。")
        }
    }
    private fun observeModel() {
        val owned = requireNotNull(attempt)
        val result = models.state.value
        if (result.unresolved != null) {
            modelEvidence = result.unresolved
            mutable.update { it.copy(phase = DesktopExternalImportPhase.UNCERTAIN, message = "模型写入结果未确认，请核对；不会自动重发。") }
        }
        else if (owned.matches(result.externalImportReceipt)) complete("模型模板已导入为独立模型；模板不会携带 API 密钥。", result.externalImportReceipt?.targetId)
        else error(result.error ?: "模型导入没有产生已确认的提交。")
    }
    private fun complete(message: String, targetId: String? = null) { mutable.update { it.copy(phase = DesktopExternalImportPhase.COMPLETE, message = message, targetId = targetId) } }
    private fun dispatch() {
        requireAdmission()
        val item = requireNotNull(state.value.item)
        queue.markAttempted(item.id)
        attempt = DesktopExternalImportAttempt(item.id); typedEvidence = null; modelEvidence = null
    }
    private fun requireAdmission() {
        check(!unavailable() && gate.state == DesktopDataOperationCoordinatorState.OPEN) { "资料当前不可写入，请等待目录或备份操作结束；若要求重启，请先正常重启。" }
    }
    fun next() = action { check(state.value.phase == DesktopExternalImportPhase.COMPLETE); finish() }
    fun skip(confirmedUncertain: Boolean = false) = action {
        check(state.value.phase != DesktopExternalImportPhase.UNCERTAIN || confirmedUncertain)
        check(!merge.state.value.restartRequired) { "合并已进入启动切换阶段，请正常重启；不会继续导入后面的文件。" }
        if (state.value.phase == DesktopExternalImportPhase.PREVIEW) {
            check(!merge.state.value.busy)
            awaitDelegated(merge.discardExternal())
            check(!merge.state.value.ready)
        }
        if (state.value.phase == DesktopExternalImportPhase.CONFLICT) {
            typed.resolveConflict(DesktopTransferConflictAction.CANCEL)
            check(typed.state.value.pendingConflict == null)
        }
        finish()
    }
    private suspend fun finish() {
        val item = requireNotNull(state.value.item)
        prepared?.let { DesktopExternalImportInspector.release(it) }
        prepared = null
        check(queue.acknowledge(item.id))
    }
    fun stop() {
        when (state.value.kind) {
            DesktopExternalImportKind.BACKUP, DesktopExternalImportKind.SAVE_SLOT -> delegated?.cancel()
            else -> operation?.cancel()
        }
    }
    private suspend fun awaitDelegated(candidate: Job?) {
        val job = requireNotNull(candidate) { "导入器当前繁忙或已关闭；本次文件仍在队列。" }
        delegated = job
        try { job.join() } finally { if (!job.isActive && delegated === job) delegated = null }
    }
    private fun action(password: CharArray? = null, work: suspend () -> Unit) {
        if (closed || state.value.busy || !mutex.tryLock()) { password?.fill('\u0000'); return }
        mutable.update { it.copy(busy = true, message = "正在处理当前文件，请等待；不会开始下一项。") }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { work() }
            catch (cancelled: CancellationException) {
                val committed = attempt?.matches(typed.state.value.externalImportReceipt) == true ||
                    attempt?.matches(models.state.value.externalImportReceipt) == true || attempt?.matches(saves.state.value.externalImportReceipt) == true
                if (committed) complete("导入已写入，停止发生在后续处理中。请核对管理页，不要重复导入。")
                else mutable.update { it.copy(phase = DesktopExternalImportPhase.UNCERTAIN, message = "操作已停止，结果尚未确认；请核对当前导入结果，不会自动重发。") }
                throw cancelled
            } catch (error: Exception) { mutable.update {
                val retained = it.phase.takeIf { phase -> phase in setOf(DesktopExternalImportPhase.COMPLETE, DesktopExternalImportPhase.UNCERTAIN, DesktopExternalImportPhase.CONFLICT) }
                it.copy(phase = retained ?: DesktopExternalImportPhase.ERROR,
                    message = if (retained == DesktopExternalImportPhase.COMPLETE) "导入已确认写入；队列或暂存清理尚未完成，请再次结束此项，不要重复导入。"
                    else error.message ?: "操作未完成；当前文件仍保留在队列。")
            } }
            finally { password?.fill('\u0000'); mutable.update { it.copy(busy = false) }; mutex.unlock() }
        }
    }
    suspend fun closeAndDrain() {
        closed = true; queue.stopAccepting()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        delegated?.cancelAndJoin(); delegated = null
        prepared?.let { source -> withContext(NonCancellable) {
            try { DesktopExternalImportInspector.release(source); prepared = null }
            catch (_: Exception) { mutable.update { it.copy(message = "暂存副本无法安全清理，已保留；原文件未修改。") } }
        } }
    }
}
