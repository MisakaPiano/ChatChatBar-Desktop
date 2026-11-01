package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopCharacterAppearanceState(
    val open: Boolean = false, val busy: Boolean = false, val name: String = "",
    val preview: ByteArray? = null, val raw: String = "", val reasoning: String = "",
    val status: String = "", val error: String? = null,
    val route: CharacterAppearanceImageModelRoute? = null,
    val before: CharacterAppearanceImageDraft? = null,
    val candidate: CharacterAppearanceImageDraft? = null,
)

/** Image understanding edits text only; it never assigns the selected file as an avatar. */
internal class DesktopCharacterAppearanceController(
    private val editor: DesktopCharacterEditorController,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime, private val gate: DesktopDataOperationCoordinator,
    private val filePicker: DesktopFilePicker,
    private val stream: (List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pin(val session: String, val card: CharacterCard, val person: String)
    private val mutable = MutableStateFlow(DesktopCharacterAppearanceState())
    val state = mutable.asStateFlow()
    private var pin: Pin? = null
    private var source: Path? = null
    private var image: DesktopCharacterReferenceImage? = null
    private var job: Job? = null
    private val operationEpoch = java.util.concurrent.atomic.AtomicLong()
    private fun matches(target: Pin): Boolean = editor.canUseCardAi &&
        editor.state.value.draftSessionId == target.session && editor.state.value.card == target.card

    fun open(personId: String) {
        if (state.value.busy || !editor.canUseCardAi) return
        val current = editor.state.value
        val card = current.card?.takeIf { it.editMode == CharacterEditMode.STRUCTURED } ?: return
        val person = card.characters.singleOrNull { it.id == personId } ?: return
        val session = current.draftSessionId ?: return
        val path = filePicker.pickOpenFile(DesktopFileType("人物参考图片", listOf("png", "jpg", "jpeg", "webp", "gif"))) ?: return
        pin = Pin(session, card, personId); source = path; image = null
        mutable.value = DesktopCharacterAppearanceState(open = true, name = person.name,
            before = CharacterAppearanceImageDraft(person.appearance, person.clothing))
        generate()
    }

    fun generate() {
        if (state.value.busy) return
        val target = pin ?: return
        if (!matches(target)) { mutable.update { it.copy(candidate = null, error = "当前人物或草稿已变化，请重新选择参考图片") }; return }
        mutable.update { it.copy(busy = true, candidate = null, error = null, route = null,
            raw = "", reasoning = "", status = "正在读取上传图片") }
        val epoch = operationEpoch.incrementAndGet()
        val usageTask = UUID.randomUUID().toString()
        job = scope.launch {
            try {
                val result = runtime.withCardDesign(target.session) { gate.withNormalOperation {
                    val prepared = image ?: withContext(Dispatchers.IO) {
                        readCharacterReferenceImage(requireNotNull(source))
                    }.also { image = it; mutable.update { before -> before.copy(preview = it.preview) } }
                    val core = CharacterAppearanceImageCore(models, CharacterAppearanceImageGateway { messages, model ->
                        val recorder = runtime.diagnostics.begin("appearance:${UUID.randomUUID()}", "character-card:${target.session}", model,
                            DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = usageTask,
                                cardId = target.card.id, editorSessionId = target.session, personId = target.person))
                        val raw = StringBuilder(); val reasoning = StringBuilder(); var done = false
                        try {
                            stream(messages, model, recorder).collect { event -> when (event) {
                                is StreamEvent.Delta -> { raw.append(event.text); mutable.update { it.copy(raw = raw.toString()) } }
                                is StreamEvent.ReasoningDelta -> { reasoning.append(event.text); mutable.update { it.copy(reasoning = reasoning.toString()) } }
                                is StreamEvent.Error -> throw event.asException()
                                StreamEvent.Done -> done = true
                                is StreamEvent.Usage -> Unit
                            } }
                            check(done && raw.isNotBlank()) { "APPEARANCE_INCOMPLETE" }
                            recorder.finish(DesktopTaskStatus.COMPLETED, null, null); raw.toString()
                        } catch (cancelled: CancellationException) {
                            recorder.onCancelled(); recorder.finish(DesktopTaskStatus.CANCELLED, null, null); throw cancelled
                        } catch (error: Exception) { recorder.finish(DesktopTaskStatus.FAILED, "图片提取文字未完成", null); throw error }
                    }, settings::getAppSettings)
                    core.generate(prepared.base64, target.card.characters.single { it.id == target.person }.name) { route ->
                        mutable.update { it.copy(route = route, status = if (route.usesLinkedVisionModel)
                            "正在使用关联视觉模型 · ${route.analysisModelLabel}" else "正在使用当前对话模型 · ${route.analysisModelLabel}") }
                    }
                } }
                if (matches(target)) mutable.update { it.copy(candidate = result.draft, status = "请核对外貌与服装候选，再应用到当前人物") }
                else mutable.update { it.copy(error = "当前人物或草稿已变化，候选未应用") }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(candidate = null, status = "已取消，原人物内容保留") }; throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is ModelResponseTruncatedException -> "图片识别输出被截断；请重试，或检查视觉模型服务端的输出限制"
                    is AiTaskRefusalException -> "模型拒绝或过滤了本次图片识别"
                    is ModelRequestException -> "图片识别请求失败${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}"
                    else -> "图片识别或结果校验失败，请检查图片、默认对话模型及其关联视觉模型"
                }
                mutable.update { it.copy(error = message, candidate = null, status = "图片识别失败，可重试") }
            }
        }
        job?.invokeOnCompletion { cause ->
            mutable.update {
                if (operationEpoch.get() != epoch || pin != target) it
                else if (cause is CancellationException) it.copy(busy = false, status = "已取消，原人物内容保留", candidate = null)
                else if (cause != null) it.copy(busy = false, error = "图片识别任务未完成，请重试", candidate = null)
                else it.copy(busy = false)
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun close() { if (!state.value.busy) { source = null; image = null; pin = null; mutable.value = DesktopCharacterAppearanceState() } }
    fun apply(): Boolean {
        if (state.value.busy) return false
        val target = pin ?: return false
        val draft = state.value.candidate ?: return false
        if (!matches(target)) { mutable.update { it.copy(error = "当前人物或草稿已变化，候选未应用") }; return false }
        val next = target.card.copy(characters = target.card.characters.map { person ->
            if (person.id == target.person) person.copy(
                appearance = draft.appearance.takeIf(String::isNotBlank) ?: person.appearance,
                clothing = draft.clothing.takeIf(String::isNotBlank) ?: person.clothing) else person
        })
        return editor.applyCardAi(target.session, target.card, next).also { if (it) close() }
    }
    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); scope.coroutineContext[Job]?.cancelAndJoin()
        source = null; image = null; pin = null; mutable.value = DesktopCharacterAppearanceState()
    }
}
