package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.desktop.security.DesktopCredentialCall
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class DesktopCharacterImageKind { COVER, AVATAR }
internal data class DesktopCharacterImageState(
    val open: Boolean = false, val kind: DesktopCharacterImageKind = DesktopCharacterImageKind.COVER,
    val busy: Boolean = false, val loading: Boolean = false, val title: String = "",
    val hint: String = "", val requirement: String = "", val manualPrompt: String = "",
    val freeform: Boolean = false, val warning: String? = null, val error: String? = null,
    val status: String = "", val promptText: String = "", val preview: ByteArray? = null,
    val candidate: ByteArray? = null, val crop: ByteArray? = null, val progress: Float = 0f,
    val imageSize: NovelAiImageSize? = null, val targetModel: NovelAiImageModel? = null,
    val designModelLabel: String = "", val reusedDesign: Boolean = false,
)

internal class DesktopCharacterImageController(
    private val editor: DesktopCharacterEditorController,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime, private val gate: DesktopDataOperationCoordinator,
    private val captureCredential: () -> DesktopCredentialCall,
    private val loadRequirement: suspend () -> String,
    private val design: suspend (CharacterCard, ModelConfig, String, String, (String) -> Unit) -> NovelAiPromptPlan,
    private val generateImage: suspend (NovelAiPromptPlan, NovelAiImageSize, NovelAiGenerationSettings,
        (ByteArray, Float) -> Unit, (String) -> Unit) -> ByteArray,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pin(val session: String, val basis: CharacterCard, val destination: CharacterCard, val personId: String?,
        val assign: (CharacterCard) -> Boolean)
    private data class Signature(val card: CharacterCard, val hint: String, val requirement: String,
        val manualPrompt: String, val model: NovelAiImageModel, val ratio: String)
    private val mutable = MutableStateFlow(DesktopCharacterImageState())
    val state = mutable.asStateFlow()
    val imageDiagnostics get() = runtime.diagnostics
    val imageDiagnosticGroup get() = pin?.session
    private var pin: Pin? = null
    private var job: Job? = null
    private val operationEpoch = java.util.concurrent.atomic.AtomicLong()
    private var requirementJob: Job? = null
    private var signature: Signature? = null
    private var plan: NovelAiPromptPlan? = null
    private var finalImage: ByteArray? = null
    private var plannedWith = ""
    private fun matches(target: Pin) = editor.canUseCardAi && editor.state.value.draftSessionId == target.session &&
        editor.state.value.card == target.basis

    fun revealTaskDraft(sessionId: String): Boolean {
        val target = pin ?: return false
        if (target.session != sessionId || !matches(target)) return false
        mutable.update { it.copy(open = true) }
        return true
    }

    fun openCover(destination: CharacterCard? = null, assign: ((CharacterCard) -> Boolean)? = null) =
        open(personId = null, destination = destination, assign = assign)
    fun openAvatar(personId: String) = open(personId, null, null)
    private fun open(personId: String?, destination: CharacterCard?, assign: ((CharacterCard) -> Boolean)?) {
        if (state.value.busy || !editor.canUseCardAi) return
        val current = editor.state.value
        val basis = current.card ?: return
        val session = current.draftSessionId ?: return
        val target = destination ?: basis
        val person = personId?.let { id -> target.characters.singleOrNull { it.id == id } ?: return }
        if (destination != null && assign == null) return
        requirementJob?.cancel()
        val next = Pin(session, basis, target, personId, assign ?: { editor.applyCardAi(session, basis, it) })
        pin = next; signature = null; plan = null; finalImage = null; plannedWith = ""
        mutable.value = DesktopCharacterImageState(open = true,
            kind = if (personId == null) DesktopCharacterImageKind.COVER else DesktopCharacterImageKind.AVATAR,
            freeform = target.editMode == CharacterEditMode.FREEFORM, title = person?.name ?: target.name,
            loading = personId == null)
        if (personId == null) requirementJob = scope.launch {
            var readingModel = false
            try {
                val requirement = gate.withNormalOperation { loadRequirement() }
                if (pin === next) mutable.update { it.copy(requirement = requirement) }
                readingModel = true
                val label = gate.withNormalOperation { models.defaultImageModel(settings.getAppSettings())?.desktopAccountLabel().orEmpty() }
                if (pin === next) mutable.update { it.copy(designModelLabel = label) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (pin === next) mutable.update { it.copy(warning = if (readingModel)
                "未能读取默认封面设计模型，请检查模型与账户设置；已读取的额外要求保留"
                else "未能读取 Studio 已保存的额外要求，请核对下方要求后再生成") } }
            finally { if (pin === next) mutable.update { it.copy(loading = false) } }
        }
    }

    private fun change(update: (DesktopCharacterImageState) -> DesktopCharacterImageState) {
        if (state.value.busy || state.value.loading) return
        val before = state.value; val next = update(before)
        if (before == next) return
        operationEpoch.incrementAndGet()
        signature = null; plan = null; finalImage = null; plannedWith = ""
        mutable.value = next.copy(error = null, status = "", promptText = "", preview = null, candidate = null, crop = null)
    }
    fun setHint(value: String) = change { it.copy(hint = value) }
    fun setRequirement(value: String) = change { it.copy(requirement = value) }
    fun setManualPrompt(value: String) = change { it.copy(manualPrompt = value) }
    fun setCrop(bytes: ByteArray, source: ByteArray? = state.value.candidate) {
        if (state.value.busy || source == null || state.value.candidate !== source || state.value.kind != DesktopCharacterImageKind.COVER) return
        DesktopImageEditing.decode(bytes)
        mutable.update { if (it.candidate === source && !it.busy) it.copy(crop = bytes) else it }
    }
    fun clearCrop() { if (!state.value.busy) mutable.update { it.copy(crop = null) } }
    fun generate(retry: Boolean = false) {
        if (state.value.busy || state.value.loading) return
        val target = pin ?: return
        if (!matches(target)) { mutable.update { it.copy(error = "当前卡片或人物已变化，请重新打开生成窗口", candidate = null) }; return }
        val before = state.value
        mutable.update { it.copy(busy = true, error = null, status = "正在准备生成", candidate = null, crop = null,
            preview = if (retry) it.preview else null, promptText = if (retry) it.promptText else "", progress = 0f) }
        val epoch = operationEpoch.incrementAndGet()
        job = scope.launch {
            var failureHint = "无法读取生图设置，请检查设置与数据目录"
            try {
                runtime.withCardDesign(target.session, creativeKind = DesktopOwnedWorkKind.CHARACTER_IMAGE,
                    usageContext = DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, cardId = target.destination.id,
                        editorSessionId = target.session, personId = target.personId)) { gate.withNormalOperation {
                    val fresh = settings.getAppSettings()
                    val avatar = target.personId != null
                    val key = Signature(target.destination, before.hint, before.requirement, before.manualPrompt,
                        fresh.novelAiImageModel, if (avatar) "" else fresh.novelAiImageAspectRatio)
                    if (!retry || key != signature) { plan = null; finalImage = null; plannedWith = ""; signature = key }
                    failureHint = "读取 NovelAI 账户凭据失败，请检查 API 账户；原凭据未修改"
                    val credential = captureCredential()
                    failureHint = "尚未配置 NovelAI 凭据，请先选择可用的生图账户"
                    require(!credential.read().isNullOrBlank()) { "NOVELAI_CREDENTIAL_UNAVAILABLE" }
                    withContext(credential) {
                        val reuseDesign = plan != null
                        val imagePlan = plan ?: (if (avatar) {
                            val person = target.destination.characters.single { it.id == target.personId }
                            val positive = if (target.destination.editMode == CharacterEditMode.FREEFORM) before.manualPrompt else person.imagePrompt
                            failureHint = if (target.destination.editMode == CharacterEditMode.FREEFORM)
                                "请先填写完整的 NovelAI 正向 Prompt" else "请先填写该人物的 NovelAI 生图 Prompt"
                            require(positive.isNotBlank()) { "AVATAR_PROMPT_REQUIRED" }
                            CharacterAvatarImagePolicy.promptPlan(
                                if (target.destination.editMode == CharacterEditMode.FREEFORM) "" else target.destination.defaultImagePrompt,
                                positive, target.destination.defaultImageNegativePrompt)
                        } else {
                            failureHint = "封面比例无效，请在图片生成设置中检查比例"
                            require(NovelAiImageSizePolicy.validationError(fresh.novelAiImageAspectRatio) == null) { "COVER_RATIO_INVALID" }
                            failureHint = "默认封面设计模型不可用，请检查模型与账户设置"
                            val model = requireNotNull(models.defaultImageModel(fresh)) { "DESIGN_MODEL_UNAVAILABLE" }
                            plannedWith = model.desktopAccountLabel()
                            mutable.update { it.copy(designModelLabel = plannedWith, reusedDesign = false, status = "正在设计封面 Prompt") }
                            failureHint = "封面设计模型认证未配置，请在模型设置中选择账户"
                            require(model.hasConfiguredAuthentication(fresh)) { "DESIGN_AUTHENTICATION_UNAVAILABLE" }
                            failureHint = "封面 Prompt 设计未完成，请检查设计模型后重试"
                            design(target.destination, model, before.hint, before.requirement) { text ->
                                mutable.update { it.copy(promptText = text, status = "正在设计封面 Prompt") }
                            }
                        }).also { plan = it }
                        val size = if (avatar) CharacterAvatarImagePolicy.imageSize
                            else NovelAiImageSizePolicy.resolve(fresh.novelAiImageAspectRatio, imagePlan.sizePreset)
                        mutable.update { it.copy(imageSize = size, targetModel = fresh.novelAiImageModel,
                            designModelLabel = if (avatar) "" else plannedWith, reusedDesign = !avatar && reuseDesign,
                            promptText = imagePlan.baseCaption, status = if (finalImage == null) "正在调用 NovelAI 生成候选" else "沿用已完成图片候选") }
                        failureHint = "NovelAI 图片生成未完成，请检查生图账户与网络；可重试未完成步骤"
                        val bytes = finalImage ?: generateImage(imagePlan, size,
                            NovelAiGenerationSettings.legacy(kotlin.random.Random.nextInt(0, Int.MAX_VALUE), model = fresh.novelAiImageModel),
                            { image, progress -> mutable.update { it.copy(preview = image, progress = progress) } },
                            { status -> mutable.update { it.copy(status = status) } }).also { finalImage = it }
                        if (matches(target)) mutable.update { it.copy(candidate = bytes, preview = bytes, progress = 1f,
                            status = "候选已完成，请核对后应用到草稿") }
                        else mutable.update { it.copy(error = "当前卡片或人物已变化，候选未应用") }
                    }
                } }
            } catch (cancelled: CancellationException) { mutable.update { it.copy(status = "已取消，原图片保留", candidate = null) }; throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = failureHint,
                status = "生成失败，原图片保留", candidate = null) } }
        }
        job?.invokeOnCompletion { cause ->
            mutable.update {
                if (operationEpoch.get() != epoch || pin !== target) it
                else if (cause is CancellationException) it.copy(busy = false, status = "已取消，原图片保留", candidate = null)
                else if (cause != null) it.copy(busy = false, error = "生图任务未完成，请重试", candidate = null)
                else it.copy(busy = false)
            }
        }
    }

    fun apply() {
        if (state.value.busy) return
        val target = pin ?: return
        val bytes = state.value.candidate ?: return
        val crop = state.value.crop
        if (!matches(target)) { mutable.update { it.copy(error = "当前卡片或人物已变化，候选未应用") }; return }
        mutable.update { it.copy(busy = true, error = null, status = "正在保存候选到草稿") }
        val epoch = operationEpoch.incrementAndGet()
        job = scope.launch {
            var committed = false
            try {
                val applied = runtime.withCardDesign(target.session, creativeKind = DesktopOwnedWorkKind.CHARACTER_IMAGE) { gate.withNormalOperation {
                    withContext(NonCancellable + Dispatchers.IO) {
                        editor.stageGeneratedCardImage(target.session, target.basis, target.destination, target.personId, bytes, crop, target.assign)
                            .also { committed = it }
                    }
                } }
                if (applied) { mutable.update { it.copy(busy = false) }; close() }
                else mutable.update { it.copy(error = "当前候选或草稿已变化，未应用图片", status = "候选保留") }
            } catch (cancelled: CancellationException) {
                if (committed) { mutable.update { it.copy(busy = false) }; close() }
                else mutable.update { it.copy(status = "已取消应用，候选保留") }
                throw cancelled
            } catch (_: Exception) { mutable.update { it.copy(error = "保存候选失败，已完成图片保留；可再次应用，不会重新生图", status = "保存失败") } }
        }
        job?.invokeOnCompletion { cause ->
            mutable.update {
                if (operationEpoch.get() != epoch || pin !== target) it
                else if (cause is CancellationException) it.copy(busy = false, status = "已取消应用，候选保留")
                else if (cause != null) it.copy(busy = false, error = "应用任务未完成，请核对草稿后重试")
                else it.copy(busy = false)
            }
        }
    }
    fun cancel() { job?.cancel() }
    fun close() { if (!state.value.busy) {
        requirementJob?.cancel(); pin = null; plan = null; finalImage = null; signature = null; plannedWith = ""
        mutable.value = DesktopCharacterImageState()
    } }
    suspend fun closeAndDrain() {
        job?.cancelAndJoin(); requirementJob?.cancelAndJoin(); scope.coroutineContext[Job]?.cancelAndJoin()
        pin = null; plan = null; finalImage = null; signature = null; plannedWith = ""; mutable.value = DesktopCharacterImageState()
    }
}
