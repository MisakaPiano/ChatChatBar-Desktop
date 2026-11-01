package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopCharacterCardAiState(
    val open: Boolean = false, val operation: CharacterCardAiOperation = CharacterCardAiOperation.FILL,
    val busy: Boolean = false, val loading: Boolean = false,
    val request: String = "", val modelId: String? = null,
    val models: List<DesktopCardDesignOption> = emptyList(), val defaultModel: String = "",
    val status: String = "", val error: String? = null,
    val raw: String = "", val repair: String = "", val reasoning: String = "", val repairReasoning: String = "",
    val candidate: CharacterCard? = null, val before: CharacterCard? = null,
)

/** Owns one transient editor operation; durable writes remain with the ordinary editor save. */
internal class DesktopCharacterCardAiController(
    private val editor: DesktopCharacterEditorController,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime, private val gate: DesktopDataOperationCoordinator,
    private val stream: (CharacterCardAiOperation, CharacterCardAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pin(val session: String, val target: String?, val card: CharacterCard)
    private fun currentPin(): Pin? = editor.state.value.let { s ->
        if (!editor.canUseCardAi) null else s.draftSessionId?.let { session -> s.card?.let { Pin(session, s.targetId, it) } }
    }
    private val mutableState = MutableStateFlow(DesktopCharacterCardAiState())
    val state = mutableState.asStateFlow()
    private var pin: Pin? = null
    private var job: Job? = null
    private var optionsJob: Job? = null
    private var completedRaw = ""
    val diagnosticOwner get() = runtime.diagnostics
    val diagnosticSessionId get() = pin?.let { "character-card:${it.session}" }

    fun open(operation: CharacterCardAiOperation) {
        if (state.value.busy) return
        val target = currentPin() ?: return
        if (operation == CharacterCardAiOperation.FILL && target.card.editMode != CharacterEditMode.STRUCTURED) return
        if (pin != target || state.value.operation != operation) {
            completedRaw = ""; mutableState.value = DesktopCharacterCardAiState(operation = operation)
        }
        pin = target
        mutableState.update { it.copy(open = true) }
        refreshModels()
    }

    fun close() { if (!state.value.busy) mutableState.update { it.copy(open = false) } }
    fun refreshModels() {
        if (state.value.busy || state.value.loading) return
        val target = pin
        mutableState.update { it.copy(loading = true, error = null) }
        optionsJob = scope.launch {
            try {
                val result = gate.withNormalOperation {
                    val fresh = settings.getAppSettings()
                    models.availableChatModels(fresh).map { DesktopCardDesignOption(it.id, it.desktopAccountLabel()) } to
                        models.defaultChatModel(fresh)?.desktopAccountLabel().orEmpty()
                }
                if (pin == target) mutableState.update { it.copy(models = result.first, defaultModel = result.second) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (pin == target) mutableState.update { it.copy(error = "读取模型配置失败，请检查后重试") } }
            finally { if (pin == target) mutableState.update { it.copy(loading = false) } }
        }
    }

    private fun change(change: (DesktopCharacterCardAiState) -> DesktopCharacterCardAiState) {
        if (state.value.busy) return
        val before = state.value; val next = change(before)
        if (before != next) {
            completedRaw = ""
            mutableState.value = next.copy(candidate = null, before = null, status = "", error = null,
                raw = "", repair = "", reasoning = "", repairReasoning = "")
        }
    }
    fun setRequest(value: String) = change { it.copy(request = value) }
    fun selectModel(value: String?) = change { it.copy(modelId = value) }

    fun generate(retry: Boolean = false) {
        if (state.value.busy || job?.isActive == true) return
        val before = state.value; val target = pin
        if (target == null || target != currentPin()) {
            mutableState.update { it.copy(candidate = null, error = "当前编辑内容已变化，请关闭后重新打开 AI 编辑") }; return
        }
        if (before.request.isBlank()) {
            mutableState.update { it.copy(error = "请填写角色信息或改写要求") }; return
        }
        if (!retry) completedRaw = ""
        mutableState.update { it.copy(busy = true, error = null, candidate = null, before = target.card,
            status = "正在准备生成", repair = "", repairReasoning = "",
            raw = completedRaw, reasoning = if (completedRaw.isEmpty()) "" else it.reasoning) }
        job = scope.launch {
            var lastRecorder: DesktopTransportDiagnosticRecorder? = null
            try {
                val merged = runtime.withCardDesign(target.session) {
                    gate.withNormalOperation {
                        val fresh = settings.getAppSettings()
                        val model = if (before.modelId == null) models.defaultChatModel(fresh)
                            else models.availableChatModels(fresh).firstOrNull { it.id == before.modelId }
                        requireNotNull(model) { "MODEL_UNAVAILABLE" }
                        require(model.hasConfiguredAuthentication(fresh)) { "MODEL_AUTHENTICATION" }
                        val run = UUID.randomUUID().toString()
                        val generator = CharacterCardCandidateGenerator { operation, stage, messages, resolved -> flow {
                            val recorder = runtime.diagnostics.begin("$run:${stage.name}", "character-card:${target.session}", resolved)
                            lastRecorder = recorder
                            var terminal = false
                            try {
                                stream(operation, stage, messages, resolved, recorder).collect { event ->
                                    when (event) {
                                        StreamEvent.Done -> { terminal = true; recorder.finish(DesktopTaskStatus.COMPLETED, null, null) }
                                        is StreamEvent.Error -> { terminal = true; recorder.finish(DesktopTaskStatus.FAILED, "角色卡请求未完成", null) }
                                        else -> Unit
                                    }
                                    emit(event)
                                }
                                if (!terminal) recorder.finish(DesktopTaskStatus.FAILED, "缺少完成确认", null)
                            } catch (cancelled: CancellationException) {
                                recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                            } catch (error: Exception) {
                                recorder.finish(DesktopTaskStatus.FAILED, "角色卡请求未完成", null); throw error
                            }
                        } }
                        val checkpoint: (String) -> Unit = { completedRaw = it }
                        val raw: (String) -> Unit = { v -> mutableState.update { it.copy(raw = v) } }
                        val repair: (String) -> Unit = { v -> mutableState.update { it.copy(repair = v) } }
                        val reasoning: (CharacterCardAiStage, String) -> Unit = { stage, v -> mutableState.update {
                            if (stage == CharacterCardAiStage.GENERATE) it.copy(reasoning = v) else it.copy(repairReasoning = v)
                        } }
                        when (before.operation) {
                            CharacterCardAiOperation.FILL -> CharacterCardAiPayload.mergeFill(target.card,
                                generator.fill(target.card, CharacterCardAiPayload.fill(before.request, target.card), model,
                                    completedRaw = completedRaw, onCheckpoint = checkpoint, onRaw = raw,
                                    onRepair = repair, onReasoning = reasoning,
                                    onStatus = { v -> mutableState.update { it.copy(status = v) } }))
                            CharacterCardAiOperation.REWRITE -> CharacterRewritePolicy.mergeInto(target.card,
                                generator.rewrite(target.card, CharacterCardAiPayload.rewrite(before.request, target.card), model,
                                    completedRaw = completedRaw, onCheckpoint = checkpoint, onRaw = raw,
                                    onRepair = repair, onReasoning = reasoning))
                        }
                    }
                }
                if (target == currentPin()) mutableState.update { it.copy(candidate = merged, status = "候选已完成，请核对差异后应用") }
                else mutableState.update { it.copy(candidate = null, error = "当前编辑内容已变化，候选未应用") }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(status = "已取消，原编辑内容保留", candidate = null) }; throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is ModelResponseTruncatedException -> "模型输出已截断，请检查输出上限"
                    is com.example.chatbar.domain.prompt.AiTaskRefusalException -> "模型拒绝或过滤了本次输出"
                    is ModelRequestException -> "请求失败${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}"
                    else -> "生成或候选校验失败，请检查模型配置和请求日志"
                }
                lastRecorder?.finish(DesktopTaskStatus.FAILED, message, null)
                mutableState.update { it.copy(error = message, candidate = null, status = "可重试未完成步骤，或重新生成") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun cancel() { job?.cancel() }
    fun apply(): Boolean {
        if (state.value.busy) return false
        val target = pin ?: return false
        val result = state.value.candidate ?: return false
        val applied = editor.applyCardAi(target.session, target.card, result)
        if (applied) { completedRaw = ""; mutableState.update { it.copy(open = false, candidate = null) } }
        else mutableState.update { it.copy(error = "当前编辑目标或内容已变化，候选未应用") }
        return applied
    }

    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); optionsJob?.cancelAndJoin(); scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
