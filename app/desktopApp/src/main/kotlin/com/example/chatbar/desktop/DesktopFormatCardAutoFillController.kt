package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopCardDesignOption(val id: String, val name: String)

/** Private in-memory form/candidate; only explicit apply enters the editor's ordinary draft path. */
internal data class DesktopFormatCardAutoFillState(
    val open: Boolean = false, val loadingOptions: Boolean = false, val busy: Boolean = false,
    val characters: List<DesktopCardDesignOption> = emptyList(),
    val models: List<DesktopCardDesignOption> = emptyList(), val defaultModelName: String = "",
    val characterId: String? = null, val modelId: String? = null, val request: String = "",
    val sourceCharacterName: String = "", val status: String = "", val error: String? = null,
    val rawText: String = "", val repairText: String = "", val reasoning: String = "",
    val repairReasoning: String = "", val validationIssue: String? = null,
    val candidate: FormatCardAutoFillDraft? = null,
)

internal class DesktopFormatCardAutoFillController(
    private val editor: DesktopFormatCardEditorController,
    private val characters: CharacterRepository,
    private val settings: SettingsRepository,
    private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime,
    private val gate: DesktopDataOperationCoordinator,
    private val stream: (FormatCardAutoFillStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pin(val session: String, val card: String)
    private var pin: Pin? = null
    private var job: Job? = null
    private var optionsJob: Job? = null
    private val mutableState = MutableStateFlow(DesktopFormatCardAutoFillState())
    val state = mutableState.asStateFlow()
    val diagnostics get() = runtime.diagnostics.entries
    val diagnosticOwner get() = runtime.diagnostics
    val diagnosticSessionId get() = pin?.let { "format-card:${it.session}" }
    private fun currentPin(): Pin? = editor.state.value.let { s ->
        s.draftSessionId?.let { session -> s.card?.let { Pin(session, it.id) } }
    }

    fun open() {
        if (!editor.canAutoFill || state.value.busy) return
        val current = currentPin() ?: return
        if (pin != current) {
            optionsJob?.cancel()
            mutableState.value = DesktopFormatCardAutoFillState()
            pin = current
        }
        mutableState.update { it.copy(open = true) }
        refreshOptions()
    }

    fun close() { if (!state.value.busy) mutableState.update { it.copy(open = false) } }

    fun revealTaskDraft(sessionId: String): Boolean {
        if (pin?.session != sessionId || currentPin() != pin) return false
        mutableState.update { it.copy(open = true) }
        return true
    }

    fun refreshOptions() {
        if (state.value.busy || state.value.loadingOptions) return
        val originalPin = pin
        mutableState.update { it.copy(loadingOptions = true, error = null) }
        optionsJob = scope.launch {
            try {
                val options = gate.withNormalOperation {
                    val freshSettings = settings.getAppSettings()
                    Triple(characters.getAll().map { DesktopCardDesignOption(it.id, it.name) },
                        models.availableChatModels(freshSettings).map { DesktopCardDesignOption(it.id, it.desktopAccountLabel()) },
                        models.defaultChatModel(freshSettings)?.desktopAccountLabel().orEmpty())
                }
                if (pin == originalPin) mutableState.update { it.copy(characters = options.first,
                    models = options.second, defaultModelName = options.third) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (pin == originalPin) mutableState.update { it.copy(error = "读取角色卡或模型失败，请检查对应配置后重试") }
            } finally {
                if (pin == originalPin) mutableState.update { it.copy(loadingOptions = false) }
            }
        }
    }

    private fun changeForm(change: (DesktopFormatCardAutoFillState) -> DesktopFormatCardAutoFillState) {
        if (state.value.busy) return
        val before = state.value
        val next = change(before)
        if (next != before) mutableState.value = next.copy(candidate = null, status = "", error = null,
            rawText = "", repairText = "", reasoning = "", repairReasoning = "", validationIssue = null)
    }
    fun selectCharacter(id: String) = changeForm { it.copy(characterId = id) }
    fun selectModel(id: String?) = changeForm { it.copy(modelId = id) }
    fun setRequest(value: String) = changeForm { it.copy(request = value) }

    fun generate() {
        val before = state.value
        if (before.busy || job?.isActive == true) return
        val target = pin
        if (target == null || target != currentPin() || !editor.canAutoFill) {
            mutableState.update { it.copy(candidate = null, error = "仅可填充正文和用户工具均为空的新格式卡") }; return
        }
        val characterId = before.characterId
        if (characterId == null) {
            mutableState.update { it.copy(error = "请选择一张已保存角色卡") }; return
        }
        val requestedName = editor.state.value.card!!.name
        mutableState.value = before.copy(busy = true, status = "正在准备生成", error = null,
            candidate = null, rawText = "", repairText = "", reasoning = "", repairReasoning = "", validationIssue = null)
        job = scope.launch {
            var lastRecorder: DesktopTransportDiagnosticRecorder? = null
            try {
                val candidate = runtime.withCardDesign(target.session, formatCardCandidate = true) {
                    gate.withNormalOperation {
                        val character = characters.getById(characterId) ?: error("SOURCE_DELETED")
                        val freshSettings = settings.getAppSettings()
                        val model = if (before.modelId == null) models.defaultChatModel(freshSettings)
                            else models.availableChatModels(freshSettings).firstOrNull { it.id == before.modelId }
                        requireNotNull(model) { "MODEL_UNAVAILABLE" }
                        require(model.hasConfiguredAuthentication(freshSettings)) { "MODEL_AUTHENTICATION" }
                        mutableState.update { it.copy(sourceCharacterName = character.name) }
                        val runId = UUID.randomUUID().toString()
                        FormatCardAutoFillGenerator { stage, messages, resolved ->
                            flow {
                                val recorder = runtime.diagnostics.begin("$runId:${stage.name}", "format-card:${target.session}", resolved,
                                    DesktopUsageContext(DesktopUsageSource.FORMAT_CARD, taskId = runId,
                                        editorSessionId = target.session, subjectId = target.card))
                                lastRecorder = recorder
                                var terminal = false
                                try {
                                    stream(stage, messages, resolved, recorder).collect { event ->
                                        when (event) {
                                            StreamEvent.Done -> { terminal = true; recorder.finish(DesktopTaskStatus.COMPLETED, null, null) }
                                            is StreamEvent.Error -> { terminal = true; recorder.finish(DesktopTaskStatus.FAILED, "格式卡请求未完成", null) }
                                            else -> Unit
                                        }
                                        emit(event)
                                    }
                                    if (!terminal) recorder.finish(DesktopTaskStatus.FAILED, "缺少完成确认", null)
                                } catch (cancelled: CancellationException) {
                                    recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                                } catch (error: Exception) {
                                    recorder.finish(DesktopTaskStatus.FAILED, "格式卡请求未完成", null); throw error
                                }
                            }
                        }.generateStreaming(character, before.request, requestedName, model,
                            onStatus = { value -> mutableState.update { it.copy(status = value) } },
                            onRawText = { value -> mutableState.update { it.copy(rawText = value) } },
                            onRepairText = { value -> mutableState.update { it.copy(repairText = value) } },
                            onValidationIssue = { value ->
                                lastRecorder?.finish(DesktopTaskStatus.FAILED, "候选格式校验失败", null)
                                mutableState.update { it.copy(validationIssue = value) }
                            },
                            onReasoningText = { stage, value -> mutableState.update {
                                if (stage == FormatCardAutoFillStage.GENERATE) it.copy(reasoning = value)
                                else it.copy(repairReasoning = value)
                            } })
                    }
                }
                if (target == currentPin() && editor.canAutoFill) mutableState.update { it.copy(candidate = candidate) }
                else mutableState.update { it.copy(candidate = null, error = "编辑目标或内容已变化，候选未应用") }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(status = "已取消生成", candidate = null) }; throw cancelled
            } catch (error: Exception) {
                val detail = when (error) {
                    is com.example.chatbar.domain.prompt.AiTaskRefusalException -> "模型拒绝或过滤了本次输出"
                    is ModelResponseTruncatedException -> "模型输出已截断"
                    is ModelRequestException -> "模型请求未完成${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}"
                    else -> "请检查角色卡、模型配置或请求日志"
                }
                lastRecorder?.finish(DesktopTaskStatus.FAILED, detail, null)
                mutableState.update { it.copy(status = "生成失败，可重新生成", error = "$detail；原编辑内容未改变", candidate = null) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun cancel() { if (state.value.busy) { mutableState.update { it.copy(status = "正在取消生成") }; job?.cancel() } }
    fun apply(): Boolean {
        if (state.value.busy) return false
        val candidate = state.value.candidate ?: return false
        val target = pin ?: return false
        val applied = editor.applyAutoFill(target.session, target.card, candidate)
        if (applied) mutableState.update { it.copy(open = false, candidate = null) }
        else mutableState.update { it.copy(error = "当前正文、工具或编辑目标已变化，未应用候选") }
        return applied
    }

    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); optionsJob?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
