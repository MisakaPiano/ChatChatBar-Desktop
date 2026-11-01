package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.search.*
import com.example.chatbar.domain.worldbook.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopWorldBookAiState(
    val open: Boolean = false, val operation: WorldBookAiOperation = WorldBookAiOperation.CREATE,
    val busy: Boolean = false, val loading: Boolean = false, val referenceLoading: Boolean = false,
    val request: String = "", val modelId: String? = null,
    val models: List<DesktopCardDesignOption> = emptyList(), val defaultModel: String = "",
    val sourceMode: CharacterResearchSourceMode = CharacterResearchSourceMode.NONE, val manualUrls: String = "",
    val reference: CharacterReferenceDocument? = null, val targetIds: Set<String> = emptySet(),
    val plans: List<WorldBookEntryPlanCandidate> = emptyList(), val contents: List<WorldBookContentCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(), val status: String = "", val error: String? = null,
    val warning: String = "", val research: ResearchDebugSnapshot? = null,
    val progress: List<String> = emptyList(),
    val outputs: List<WorldBookAiRawOutput> = emptyList(), val reasoning: String = "", val canResume: Boolean = false,
) {
    val candidateIds get() = if (operation == WorldBookAiOperation.CREATE) plans.map { it.candidateId } else contents.map { it.targetId }
}

/** Candidate and source lifetime is bound to one exact editor draft; normal save remains separate. */
internal class DesktopWorldBookAiController(
    private val editor: DesktopWorldBookEditorController,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime, private val gate: DesktopDataOperationCoordinator,
    private val stream: (WorldBookAiOperation, WorldBookAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
    private val research: (WorldBookAiGateway) -> WorldBookResearchPort,
    private val filePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pin(val session: String, val target: String?, val book: WorldBook)
    private data class ResumeModel(val model: ModelConfig, val targets: List<WorldBookEntry>)
    private val mutableState = MutableStateFlow(DesktopWorldBookAiState())
    val state = mutableState.asStateFlow()
    private var pin: Pin? = null
    private val epoch = AtomicLong()
    private var job: Job? = null
    private var optionsJob: Job? = null
    private var referenceJob: Job? = null
    private var preferenceJob: Job? = null
    private var createCheckpoint: WorldBookCreateCheckpoint? = null
    private var fillCheckpoint: WorldBookFillCheckpoint? = null
    private var resumeModel: ResumeModel? = null
    val diagnosticOwner get() = runtime.diagnostics
    val diagnosticSessionId get() = pin?.let { "world-book:${it.session}" }
    val targets get() = pin?.book?.entries.orEmpty().filter { it.content.isBlank() }

    private fun currentPin(): Pin? = editor.state.value.let { s ->
        if (!editor.canUseAi) null else s.draftSessionId?.let { session -> s.book?.let { Pin(session, s.targetId, it) } }
    }
    fun revealTaskDraft(sessionId: String): Boolean {
        if (pin?.session != sessionId || currentPin() != pin) return false
        mutableState.update { it.copy(open = true) }
        return true
    }
    private fun clearCheckpoint() { createCheckpoint = null; fillCheckpoint = null; resumeModel = null }

    fun open(operation: WorldBookAiOperation) {
        if (state.value.busy || operation !in listOf(WorldBookAiOperation.CREATE, WorldBookAiOperation.FILL)) return
        val target = currentPin() ?: return
        if (pin == target && state.value.operation == operation && state.value.open) return
        close()
        pin = target
        val token = epoch.incrementAndGet()
        mutableState.value = DesktopWorldBookAiState(open = true, operation = operation, loading = true,
            targetIds = target.book.entries.filter { it.content.isBlank() }.map { it.id }.toSet())
        optionsJob = scope.launch {
            try {
                val result = gate.withNormalOperation {
                    val fresh = settings.getAppSettings()
                    Triple(models.availableChatModels(fresh).map { DesktopCardDesignOption(it.id, it.desktopAccountLabel()) },
                        models.defaultChatModel(fresh)?.desktopAccountLabel().orEmpty(), fresh.worldBookAiResearchSourceMode)
                }
                if (epoch.get() == token) mutableState.update { it.copy(models = result.first, defaultModel = result.second, sourceMode = result.third) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.get() == token) mutableState.update { it.copy(error = "读取模型和资料设置失败，请关闭后重新打开") } }
        }
        optionsJob?.invokeOnCompletion { if (epoch.get() == token) mutableState.update { it.copy(loading = false) } }
    }

    private fun change(change: (DesktopWorldBookAiState) -> DesktopWorldBookAiState) {
        val before = state.value
        if (before.busy || before.loading || before.referenceLoading) return
        val next = change(before)
        if (next == before) return
        epoch.incrementAndGet(); clearCheckpoint()
        mutableState.value = next.copy(plans = emptyList(), contents = emptyList(), selectedIds = emptySet(), outputs = emptyList(),
            reasoning = "", research = null, progress = emptyList(), warning = "", status = "", error = null, canResume = false)
    }
    fun setRequest(value: String) = change { it.copy(request = value) }
    fun selectModel(id: String?) = change { it.copy(modelId = id) }
    fun setManualUrls(value: String) = change { it.copy(manualUrls = value) }
    fun clearReference() = change { it.copy(reference = null) }
    fun selectTarget(id: String, selected: Boolean) = change {
        if (targets.none { target -> target.id == id }) it else it.copy(targetIds = if (selected) it.targetIds + id else it.targetIds - id)
    }
    fun selectSource(mode: CharacterResearchSourceMode) {
        if (state.value.busy || state.value.loading || state.value.referenceLoading) return
        change { it.copy(sourceMode = mode) }
        val target = pin
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            try { gate.withNormalOperation { settings.updateAppSettings { it.copy(worldBookAiResearchSourceMode = mode) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update {
                if (pin == target && it.sourceMode == mode) it.copy(error = "资料模式偏好未能保存；本次仍使用已选模式") else it
            } }
        }
    }
    fun selectCandidate(id: String, selected: Boolean) {
        if (!state.value.busy && id in state.value.candidateIds) mutableState.update {
            it.copy(selectedIds = if (selected) it.selectedIds + id else it.selectedIds - id)
        }
    }
    fun selectAll(selected: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(selectedIds = if (selected) it.candidateIds.toSet() else emptySet()) }
    }

    fun chooseReference() {
        if (state.value.busy || state.value.loading || state.value.referenceLoading) return
        val target = pin ?: return
        val path = filePicker.pickOpenFile(DesktopFileType("临时参考文档", listOf("txt", "md", "json"))) ?: return
        val token = epoch.incrementAndGet()
        mutableState.update { it.copy(referenceLoading = true, error = null) }
        referenceJob = scope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val maximumBytes = 5_000_000 * 4 + 3
                    val bytes = Files.newInputStream(path).use { it.readNBytes(maximumBytes + 1) }
                    require(bytes.size <= maximumBytes)
                    val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                    require(text.isNotBlank() && text.length <= 5_000_000)
                    CharacterReferenceDocument(path.fileName.toString(), text)
                }
                if (epoch.get() == token && target == currentPin()) {
                    clearCheckpoint()
                    mutableState.update { it.copy(reference = document, plans = emptyList(), contents = emptyList(), selectedIds = emptySet(),
                        outputs = emptyList(), research = null, progress = emptyList(), reasoning = "", canResume = false, status = "", warning = "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.get() == token) mutableState.update { it.copy(error = "参考文档读取失败；请选择五百万字符以内的 UTF-8 文本") } }
        }
        referenceJob?.invokeOnCompletion { if (epoch.get() == token) mutableState.update { it.copy(referenceLoading = false) } }
    }

    fun generate(resume: Boolean = false) {
        val before = state.value
        val target = pin ?: return
        if (before.busy || before.loading || before.referenceLoading) return
        if (target != currentPin()) { mutableState.update { it.copy(error = "编辑内容已变化，请关闭候选并重新打开") }; return }
        if (!resume) clearCheckpoint()
        val token = epoch.incrementAndGet()
        mutableState.update { it.copy(busy = true, error = null, status = "正在准备世界书 AI", reasoning = "",
            plans = if (resume) it.plans else emptyList(), contents = if (resume) it.contents else emptyList(),
            selectedIds = if (resume) it.selectedIds else emptySet(), outputs = if (resume) it.outputs else emptyList(),
            research = if (resume) it.research else null, warning = if (resume) it.warning else "",
            progress = if (resume) it.progress else emptyList(),
            canResume = resume && (createCheckpoint != null || fillCheckpoint != null)) }
        fun publish(change: (DesktopWorldBookAiState) -> DesktopWorldBookAiState) {
            mutableState.update { if (epoch.get() == token) change(it) else it }
        }
        fun candidates(plans: List<WorldBookEntryPlanCandidate>, contents: List<WorldBookContentCandidate>) = publish { old ->
            val ids = if (before.operation == WorldBookAiOperation.CREATE) plans.map { it.candidateId } else contents.map { it.targetId }
            val added = ids.toSet() - old.candidateIds.toSet()
            old.copy(plans = plans, contents = contents, selectedIds = old.selectedIds.intersect(ids.toSet()) + added, canResume = true)
        }
        job = scope.launch {
            try {
                runtime.withCardDesign(target.session, creativeKind = DesktopOwnedWorkKind.WORLD_BOOK_AI) {
                    gate.withNormalOperation {
                        val fresh = settings.getAppSettings()
                        val model = requireNotNull(if (before.modelId == null) models.defaultChatModel(fresh)
                            else models.availableChatModels(fresh).firstOrNull { it.id == before.modelId }) { "MODEL_UNAVAILABLE" }
                        require(model.hasConfiguredAuthentication(fresh)) { "MODEL_AUTHENTICATION" }
                        val frozenTargets = target.book.entries.filter { it.id in before.targetIds && it.content.isBlank() }
                        val signature = ResumeModel(model, frozenTargets)
                        if (resumeModel != null && resumeModel != signature) {
                            clearCheckpoint()
                            publish { it.copy(plans = emptyList(), contents = emptyList(), selectedIds = emptySet(), outputs = emptyList(),
                                research = null, progress = emptyList(), canResume = false, warning = "模型或目标已变化，原断点失效，已开始新任务") }
                        }
                        resumeModel = signature
                        val run = UUID.randomUUID().toString()
                        val gateway = WorldBookAiGateway { operation, stage, messages, actual, delta, thought ->
                            val recorder = runtime.diagnostics.begin("$run:${operation.name}:${stage.name}:${UUID.randomUUID()}", "world-book:${target.session}", actual,
                                DesktopUsageContext(DesktopUsageSource.WORLD_BOOK, taskId = run,
                                    editorSessionId = target.session, subjectId = target.book.id))
                            val raw = StringBuilder(); var done = false
                            try {
                                stream(operation, stage, messages, actual, recorder).collect { event -> when (event) {
                                    is StreamEvent.Delta -> { raw.append(event.text); delta(event.text) }
                                    is StreamEvent.ReasoningDelta -> { thought(event.text); publish { it.copy(reasoning = it.reasoning + event.text) } }
                                    is StreamEvent.Error -> throw event.asException()
                                    StreamEvent.Done -> done = true
                                    is StreamEvent.Usage -> Unit
                                } }
                                check(done && raw.isNotBlank()) { "WORLD_BOOK_STREAM_INCOMPLETE" }
                                recorder.finish(DesktopTaskStatus.COMPLETED, null, null); raw.toString()
                            } catch (cancelled: CancellationException) {
                                recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                            } catch (error: Exception) { recorder.finish(DesktopTaskStatus.FAILED, "世界书 AI 请求未完成", null); throw error }
                        }
                        val core = WorldBookAiCore({ model }, gateway, research(gateway))
                        val options = CharacterResearchOptions(before.sourceMode, before.manualUrls.lines().map(String::trim).filter(String::isNotBlank))
                        val status: (String) -> Unit = { value -> publish {
                            it.copy(status = value, progress = if (it.progress.lastOrNull() == value) it.progress else it.progress + value)
                        } }
                        val debug: (ResearchDebugSnapshot) -> Unit = { value -> publish { it.copy(research = value) } }
                        val visible: (String, String, String) -> Unit = { key, title, text -> publish { old ->
                            val output = WorldBookAiRawOutput(key, title, text)
                            val index = old.outputs.indexOfFirst { it.key == key }
                            old.copy(outputs = if (index < 0) old.outputs + output else old.outputs.toMutableList().also { it[index] = output })
                        } }
                        when (before.operation) {
                            WorldBookAiOperation.CREATE -> core.createEntriesStreaming(before.request, target.book, model, options, before.reference,
                                createCheckpoint, onCheckpoint = { cp -> createCheckpoint = cp; candidates(cp.candidates, emptyList()) },
                                onStatus = status, onResearchDebug = debug, onVisibleOutput = visible).also { result ->
                                publish { it.copy(warning = result.warning.ifBlank { it.warning }, canResume = false) }
                            }
                            WorldBookAiOperation.FILL -> core.fillEmptyContentsStreaming(before.request, target.book, frozenTargets, model, options, before.reference,
                                fillCheckpoint, onCheckpoint = { cp -> fillCheckpoint = cp; candidates(emptyList(), cp.candidates) },
                                onStatus = status, onResearchDebug = debug, onVisibleOutput = visible).also {
                                publish { it.copy(canResume = false) }
                            }
                            else -> error("INVALID_OPERATION")
                        }
                    }
                }
                publish { it.copy(status = "候选已完成，请核对并选中后应用；尚未保存世界书") }
            } catch (cancelled: CancellationException) {
                publish { it.copy(status = "已停止；保留已完成批次，可继续或选中应用") }; throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is com.example.chatbar.domain.prompt.AiTaskRefusalException -> "模型拒绝或过滤了本次输出，未继续修复"
                    is ModelResponseTruncatedException -> "模型输出被截断；已完成批次保留，请检查服务设置后重试"
                    is ModelRequestException -> "请求未完成${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}，可查看请求日志"
                    else -> "世界书 AI 未完成，请检查模型、资料来源、空正文目标及请求日志"
                }
                publish { it.copy(error = message, status = "已完成批次保留，未改写编辑内容") }
            }
        }
        job?.invokeOnCompletion { cause -> publish { old ->
            when (cause) {
                is CancellationException -> old.copy(busy = false, status = "已停止；保留已完成批次，可继续或选中应用")
                null -> old.copy(busy = false)
                else -> old.copy(busy = false, error = "世界书 AI 未完成，已保留编辑内容")
            }
        } }
    }

    fun cancel() { job?.cancel() }
    fun apply(): Boolean {
        val current = state.value; val target = pin ?: return false
        if (current.busy || current.referenceLoading || current.selectedIds.isEmpty() || target != currentPin()) {
            mutableState.update { it.copy(error = "当前编辑目标或内容已变化，或没有选中候选；未应用") }; return false
        }
        val entries = if (current.operation == WorldBookAiOperation.CREATE)
            WorldBookAiPolicy.applyCreatedEntries(target.book.entries, current.plans, current.selectedIds)
        else WorldBookAiPolicy.applyFilledContents(target.book.entries, current.contents, current.selectedIds)
        val applied = editor.applyAi(target.session, target.book, entries)
        if (applied) close() else mutableState.update { it.copy(error = "编辑内容已变化，候选未应用") }
        return applied
    }
    fun close() {
        if (state.value.busy) return
        epoch.incrementAndGet(); optionsJob?.cancel(); referenceJob?.cancel()
        clearCheckpoint(); pin = null; mutableState.value = DesktopWorldBookAiState()
    }
    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); optionsJob?.cancelAndJoin(); referenceJob?.cancelAndJoin(); preferenceJob?.cancelAndJoin()
        epoch.incrementAndGet(); clearCheckpoint(); pin = null
        mutableState.value = DesktopWorldBookAiState()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
