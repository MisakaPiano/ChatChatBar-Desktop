package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.search.*
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import java.nio.file.Files
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
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
    val sourceMode: CharacterResearchSourceMode = CharacterResearchSourceMode.NONE,
    val manualUrls: String = "", val reference: CharacterReferenceDocument? = null,
    val referenceLoading: Boolean = false, val sourceDefaultsApplied: Boolean = false,
    val sourcePreferenceError: String? = null,
    val research: ResearchDebugSnapshot? = null,
    val sourceOutput: Map<String, Pair<String, String>> = emptyMap(),
    val images: List<DesktopCharacterReferenceImage> = emptyList(), val imagesLoading: Boolean = false,
)

/** Owns one transient editor operation; durable writes remain with the ordinary editor save. */
internal class DesktopCharacterCardAiController(
    private val editor: DesktopCharacterEditorController,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime, private val gate: DesktopDataOperationCoordinator,
    private val stream: (CharacterCardAiOperation, CharacterCardAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
    private val streamResearch: ((CharacterResearchAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>)? = null,
    private val prepareResearch: DesktopCharacterResearchPreparation? = null,
    private val filePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    private val describeImage: (suspend (String, ModelConfig, (String) -> Unit, ProviderTransportDiagnostics) -> String)? = null,
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
    private val operationEpoch = java.util.concurrent.atomic.AtomicLong()
    private var optionsJob: Job? = null
    private var completedRaw = ""
    private var researchCheckpoint: ResearchDebugSnapshot? = null
    private var referenceJob: Job? = null
    private var preferenceJob: Job? = null
    private var imagesJob: Job? = null
    private var imagesToken: String? = null
    private var imageCheckpoint: ImageUnderstandingResult? = null
    private var imageRoute: Triple<String, Boolean, String?>? = null
    val diagnosticOwner get() = runtime.diagnostics
    val diagnosticSessionId get() = pin?.let { "character-card:${it.session}" }

    fun open(operation: CharacterCardAiOperation) {
        if (state.value.busy) return
        val target = currentPin() ?: return
        if (operation == CharacterCardAiOperation.FILL && target.card.editMode != CharacterEditMode.STRUCTURED) return
        if (pin != target || state.value.operation != operation) {
            operationEpoch.incrementAndGet()
            optionsJob?.cancel()
            referenceJob?.cancel()
            imagesToken = null; imagesJob?.cancel(); imageCheckpoint = null; imageRoute = null
            completedRaw = ""; researchCheckpoint = null
            mutableState.value = DesktopCharacterCardAiState(operation = operation)
        }
        pin = target
        mutableState.update { it.copy(open = true) }
        refreshModels()
    }

    fun close() { if (!state.value.busy) {
        operationEpoch.incrementAndGet()
        imagesToken = null; imagesJob?.cancel()
        mutableState.update { it.copy(imagesLoading = false) }
        if (state.value.images.isNotEmpty()) {
            completedRaw = ""; researchCheckpoint = null; imageCheckpoint = null; imageRoute = null
            mutableState.update { it.copy(images = emptyList(), candidate = null, research = null,
                sourceOutput = emptyMap(), raw = "", repair = "", reasoning = "", repairReasoning = "") }
        }
        mutableState.update { it.copy(open = false, imagesLoading = false) }
    } }
    fun refreshModels() {
        if (state.value.busy || state.value.loading) return
        val target = pin
        val operation = state.value.operation
        mutableState.update { it.copy(loading = true, error = null) }
        optionsJob = scope.launch {
            try {
                val result = gate.withNormalOperation {
                    val fresh = settings.getAppSettings()
                    Triple(models.availableChatModels(fresh).map { DesktopCardDesignOption(it.id, it.desktopAccountLabel()) },
                        models.defaultChatModel(fresh)?.desktopAccountLabel().orEmpty(),
                        if (operation == CharacterCardAiOperation.FILL) fresh.characterAutoFillResearchSourceMode
                            else fresh.characterRewriteResearchSourceMode)
                }
                if (pin == target && state.value.operation == operation) mutableState.update { it.copy(models = result.first, defaultModel = result.second,
                    sourceMode = if (!it.sourceDefaultsApplied && prepareResearch != null) result.third else it.sourceMode,
                    sourceDefaultsApplied = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (pin == target && state.value.operation == operation) mutableState.update { it.copy(error = "读取模型配置失败，请检查后重试") } }
            finally { if (pin == target && state.value.operation == operation) mutableState.update { it.copy(loading = false) } }
        }
    }

    private fun change(retainResearch: Boolean = false, change: (DesktopCharacterCardAiState) -> DesktopCharacterCardAiState) {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading) return
        val before = state.value; val next = change(before)
        if (before != next) {
            operationEpoch.incrementAndGet()
            completedRaw = ""
            if (!retainResearch) researchCheckpoint = null
            imageCheckpoint = null; imageRoute = null
            mutableState.value = next.copy(candidate = null, before = null, status = "", error = null,
                raw = "", repair = "", reasoning = "", repairReasoning = "",
                research = if (retainResearch) before.research else null,
                sourceOutput = if (retainResearch) before.sourceOutput else emptyMap())
        }
    }
    fun setRequest(value: String) = change { it.copy(request = value) }
    fun selectModel(value: String?) = change(retainResearch = true) { it.copy(modelId = value) }
    fun setManualUrls(value: String) = change { it.copy(manualUrls = value) }
    fun clearReference() = change { it.copy(reference = null) }
    fun clearImages() = change { it.copy(images = emptyList()) }
    fun removeImage(id: String) = change { it.copy(images = it.images.filterNot { image -> image.id == id }) }
    fun moveImage(id: String, offset: Int) = change { current ->
        val images = current.images.toMutableList(); val from = images.indexOfFirst { it.id == id }
        if (from >= 0 && from + offset in images.indices) images.add(from + offset, images.removeAt(from))
        current.copy(images = images)
    }

    fun chooseImages() {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading || state.value.operation != CharacterCardAiOperation.FILL) return
        val target = pin ?: return
        val paths = filePicker.pickOpenFiles(DesktopFileType("参考图片", listOf("png", "jpg", "jpeg", "webp", "gif")))
        if (paths.isEmpty()) return
        val token = UUID.randomUUID().toString(); imagesToken = token
        mutableState.update { it.copy(imagesLoading = true, error = null) }
        imagesJob = scope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { paths.map { path -> ensureActive(); readCharacterReferenceImage(path) } }
                if (imagesToken == token && target == currentPin() && state.value.open && state.value.operation == CharacterCardAiOperation.FILL) {
                    completedRaw = ""; researchCheckpoint = null; imageCheckpoint = null; imageRoute = null
                    mutableState.update { it.copy(images = it.images + batch, candidate = null, before = null,
                        research = null, sourceOutput = emptyMap(), raw = "", repair = "", reasoning = "", repairReasoning = "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (imagesToken == token && target == pin) mutableState.update { it.copy(error = "读取参考图片失败；本批未添加，请检查文件与图片大小") } }
            finally { if (imagesToken == token && target == pin) mutableState.update { it.copy(imagesLoading = false) } }
        }
    }
    fun selectSourceMode(value: CharacterResearchSourceMode) {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading) return
        change { it.copy(sourceMode = value, sourceDefaultsApplied = true, sourcePreferenceError = null) }
        val operation = state.value.operation; val target = pin
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            try { gate.withNormalOperation { settings.updateAppSettings {
                if (operation == CharacterCardAiOperation.FILL) it.copy(characterAutoFillResearchSourceMode = value)
                else it.copy(characterRewriteResearchSourceMode = value)
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (target == pin) mutableState.update {
                it.copy(sourcePreferenceError = "保存资料偏好失败，本次选择仍保留")
            } }
        }
    }

    fun chooseReference() {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading) return
        val target = pin ?: return
        val operation = state.value.operation
        val path = filePicker.pickOpenFile(DesktopFileType("参考文档", listOf("txt", "md", "json"))) ?: return
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
                if (target == currentPin() && state.value.operation == operation) {
                    completedRaw = ""; researchCheckpoint = null; imageCheckpoint = null; imageRoute = null
                    mutableState.update { it.copy(reference = document, candidate = null, before = null,
                        research = null, sourceOutput = emptyMap(), raw = "", repair = "", reasoning = "", repairReasoning = "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (target == pin && state.value.operation == operation) mutableState.update { it.copy(error = "读取参考文档失败；请使用非空 UTF-8 文本，最多 500 万字符") } }
            finally { if (target == pin && state.value.operation == operation) mutableState.update { it.copy(referenceLoading = false) } }
        }
    }

    fun generate(retry: Boolean = false) {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading || job?.isActive == true) return
        val before = state.value; val target = pin
        if (target == null || target != currentPin()) {
            mutableState.update { it.copy(candidate = null, error = "当前编辑内容已变化，请关闭后重新打开 AI 编辑") }; return
        }
        if (before.request.isBlank() && before.reference == null && before.images.isEmpty() &&
            !(before.sourceMode.usesManualUrls() && before.manualUrls.isNotBlank())) {
            mutableState.update { it.copy(error = "请填写角色信息或改写要求") }; return
        }
        val urlCheck = validateManualResearchUrls(before.manualUrls)
        if (before.sourceMode.usesManualUrls() && (!urlCheck.isValid || urlCheck.urls.isEmpty())) {
            mutableState.update { it.copy(error = if (urlCheck.errors.isEmpty()) "请填写至少一个有效网址" else urlCheck.errors.joinToString("；")) }; return
        }
        if ((before.sourceMode != CharacterResearchSourceMode.NONE || before.reference != null) && (prepareResearch == null || streamResearch == null)) {
            mutableState.update { it.copy(error = "所选外部资料准备服务不可用，未发出生成请求") }; return
        }
        if (!retry) { completedRaw = ""; researchCheckpoint = null; imageCheckpoint = null; imageRoute = null }
        mutableState.update { it.copy(busy = true, error = null, candidate = null, before = target.card,
            status = "正在准备生成", repair = "", repairReasoning = "",
            research = if (retry) it.research else null,
            sourceOutput = if (retry) it.sourceOutput else emptyMap(),
            raw = completedRaw, reasoning = if (completedRaw.isEmpty()) "" else it.reasoning) }
        val epoch = operationEpoch.incrementAndGet()
        job = scope.launch {
            var lastRecorder: DesktopTransportDiagnosticRecorder? = null
            var preparingSources = false
            var preparingImages = false
            try {
                val merged = runtime.withCardDesign(target.session) {
                    gate.withNormalOperation {
                        val fresh = settings.getAppSettings()
                        val model = if (before.modelId == null) models.defaultChatModel(fresh)
                            else models.availableChatModels(fresh).firstOrNull { it.id == before.modelId }
                        requireNotNull(model) { "MODEL_UNAVAILABLE" }
                        require(model.hasConfiguredAuthentication(fresh)) { "MODEL_AUTHENTICATION" }
                        val run = UUID.randomUUID().toString()
                        val route = Triple(model.id, model.isMultimodal, model.visionModelId)
                        preparingImages = before.images.isNotEmpty()
                        val imageContext = if (before.images.isEmpty()) ImageUnderstandingResult() else {
                            if (imageRoute != route) imageCheckpoint = null
                            imageCheckpoint ?: ImageUnderstandingService(models) { encoded, vision, delta ->
                                val describe = requireNotNull(describeImage) { "IMAGE_UNDERSTANDING_UNAVAILABLE" }
                                val recorder = runtime.diagnostics.begin("$run:IMAGE:${UUID.randomUUID()}", "character-card:${target.session}", vision,
                                    DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = run, cardId = target.card.id))
                                lastRecorder = recorder
                                try {
                                    describe(encoded, vision, delta, recorder).also { recorder.finish(DesktopTaskStatus.COMPLETED, null, null) }
                                } catch (cancelled: CancellationException) {
                                    recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                                } catch (error: Exception) { recorder.finish(DesktopTaskStatus.FAILED, "参考图片识别未完成", null); throw error }
                            }.prepare(before.images.map { it.base64 }, model, requireUnderstanding = true, announceDirect = true,
                                onStatus = { value -> mutableState.update { it.copy(status = value) } },
                                onDescriptionText = { index, text -> mutableState.update { it.copy(sourceOutput = it.sourceOutput +
                                    ("image-$index" to ("参考图片 ${index + 1} 识别" to text))) } })
                                .also { imageCheckpoint = it; imageRoute = route }
                        }
                        preparingImages = false
                        val researchGateway = CharacterResearchAiGateway { stage, messages, resolved, onDelta, onReasoning ->
                            val sourceStream = requireNotNull(streamResearch) { "RESEARCH_TRANSPORT_UNAVAILABLE" }
                            val recorder = runtime.diagnostics.begin("$run:RESEARCH:${stage.name}", "character-card:${target.session}", resolved,
                                DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = run, cardId = target.card.id))
                            lastRecorder = recorder
                            val raw = StringBuilder(); var done = false
                            try {
                                sourceStream(stage, messages, resolved, recorder).collect { event -> when (event) {
                                    is StreamEvent.Delta -> { raw.append(event.text); onDelta(event.text) }
                                    is StreamEvent.ReasoningDelta -> onReasoning(event.text)
                                    is StreamEvent.Error -> throw event.asException()
                                    StreamEvent.Done -> done = true
                                    is StreamEvent.Usage -> Unit
                                } }
                                check(done && raw.isNotBlank()) { "RESEARCH_STREAM_INCOMPLETE" }
                                recorder.finish(DesktopTaskStatus.COMPLETED, null, null); raw.toString()
                            } catch (cancelled: CancellationException) {
                                recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                            } catch (error: Exception) {
                                recorder.finish(DesktopTaskStatus.FAILED, "资料研究请求未完成", null); throw error
                            }
                        }
                        val researchOptions = CharacterResearchOptions(before.sourceMode,
                            if (before.sourceMode.usesManualUrls()) urlCheck.urls else emptyList())
                        val needsResearch = before.request.isNotBlank() || before.reference != null || researchOptions.hasManualUrlSource()
                        preparingSources = prepareResearch != null && needsResearch
                        val brief = if (prepareResearch == null || !needsResearch) null else try {
                            prepareResearch.invoke(before.request, target.card, model, researchOptions,
                                listOfNotNull(before.reference), researchCheckpoint,
                                { snapshot -> researchCheckpoint = snapshot; mutableState.update { it.copy(research = snapshot) } },
                                { key, title, text -> mutableState.update { it.copy(sourceOutput = it.sourceOutput + (key to (title to text))) } },
                                { value -> mutableState.update { it.copy(status = value) } }, researchGateway)
                        } catch (error: Throwable) {
                            error.rethrowIfAiTaskTerminalFailure()
                            if (before.reference != null || before.sourceMode.usesManualUrls()) throw error
                            mutableState.update { it.copy(status = "外部资料研究失败，继续使用角色卡现有资料；可查看请求日志") }
                            null
                        }
                        preparingSources = false
                        val briefJson = brief?.takeIf(ResearchBrief::hasContent)?.let {
                            val json = CharacterCardAiPayload.promptJson
                            json.parseToJsonElement(json.encodeToString(ResearchBrief.serializer(), it))
                        }
                        val generator = CharacterCardCandidateGenerator { operation, stage, messages, resolved -> flow {
                            val recorder = runtime.diagnostics.begin("$run:${stage.name}", "character-card:${target.session}", resolved,
                                DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = run, cardId = target.card.id))
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
                                generator.fill(target.card, CharacterCardAiPayload.fill(before.request, target.card, briefJson,
                                    CharacterAutoFillImageContext(imageContext.hasSourceImages, imageContext.descriptions)), model,
                                    directImages = imageContext.directImageBase64s,
                                    completedRaw = completedRaw, onCheckpoint = checkpoint, onRaw = raw,
                                    onRepair = repair, onReasoning = reasoning,
                                    onStatus = { v -> mutableState.update { it.copy(status = v) } }))
                            CharacterCardAiOperation.REWRITE -> CharacterRewritePolicy.mergeInto(target.card,
                                generator.rewrite(target.card, CharacterCardAiPayload.rewrite(before.request, target.card, briefJson), model,
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
                    is CharacterCardJsonRepairTruncatedException -> "JSON 格式修复达到模型输出上限，请调高上限后重试"
                    is ModelResponseTruncatedException -> "模型输出已截断，请检查输出上限"
                    is com.example.chatbar.domain.prompt.AiTaskRefusalException -> "模型拒绝或过滤了本次输出"
                    is ModelRequestException -> "请求失败${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}"
                    else -> if (preparingImages) "参考图片识别失败，请检查当前模型与关联视觉模型；未生成候选"
                        else if (preparingSources) "外部资料准备失败，请检查所选网页、参考文档和研究日志；未生成候选"
                        else "生成或候选校验失败，请检查模型配置和请求日志"
                }
                lastRecorder?.finish(DesktopTaskStatus.FAILED, message, null)
                mutableState.update { it.copy(error = message, candidate = null, status = "可重试未完成步骤，或重新生成") }
            }
        }
        job?.invokeOnCompletion { cause ->
            mutableState.update {
                if (operationEpoch.get() != epoch) it
                else if (cause is CancellationException) it.copy(busy = false, status = "已取消，原编辑内容保留", candidate = null)
                else if (cause != null) it.copy(busy = false, error = "角色卡任务未完成，请重试", candidate = null)
                else it.copy(busy = false)
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun regenerateFinal() { if (!state.value.busy) { completedRaw = ""; generate(retry = true) } }
    fun openCandidateCover() {
        val target = pin ?: return
        val candidate = state.value.candidate ?: return
        if (state.value.busy || target != currentPin()) return
        editor.cardImages?.openCover(candidate) { result ->
            val before = mutableState.value
            if (before.busy || before.candidate != candidate || target != currentPin()) false
            else mutableState.compareAndSet(before, before.copy(candidate = result))
        }
    }
    fun apply(): Boolean {
        if (state.value.busy || state.value.referenceLoading || state.value.imagesLoading) return false
        val target = pin ?: return false
        val result = state.value.candidate ?: return false
        val applied = editor.applyCardAi(target.session, target.card, result)
        if (applied) { completedRaw = ""; mutableState.update { it.copy(open = false, candidate = null) } }
        else mutableState.update { it.copy(error = "当前编辑目标或内容已变化，候选未应用") }
        return applied
    }

    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); optionsJob?.cancelAndJoin(); referenceJob?.cancelAndJoin(); preferenceJob?.cancelAndJoin(); imagesJob?.cancelAndJoin()
        imagesToken = null; imageCheckpoint = null; imageRoute = null
        mutableState.update { it.copy(images = emptyList()) }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
