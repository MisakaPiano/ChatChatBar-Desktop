package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopNovelAiStudioPanel(controller: DesktopNovelAiStudioController) {
    val draft by controller.draft.collectAsState()
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState(emptyList())
    val conversations by controller.designRepository.conversations.collectAsState()
    val currentDesignId by controller.designRepository.currentConversationId.collectAsState()
    var attachPrompt by remember { mutableStateOf(false) }
    var translation by remember { mutableStateOf(false) }
    val tasks by controller.taskEntries.collectAsState()
    val busy = tasks.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }
    val scope = rememberCoroutineScope()
    var imageTools by remember { mutableStateOf<ByteArray?>(null) }
    var metadataFile by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var metadataSelection by remember { mutableStateOf(NovelAiStudioMetadataSelection()) }
    var reverseSource by remember { mutableStateOf<ByteArray?>(null) }
    var viewingIndex by remember { mutableStateOf(0) }
    var guidanceEditor by remember { mutableStateOf<Triple<ByteArray, ByteArray?, NovelAiFocusedInpaintRegion?>?>(null) }
    var auxiliary by remember { mutableStateOf<String?>(null) }
    var currentImage by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var newDesign by remember { mutableStateOf(false) }
    var redoDraft by remember { mutableStateOf<Pair<NovelAiStudioDraft, NovelAiStudioDraft>?>(null) }
    var generationExpanded by remember { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingReuse by remember { mutableStateOf<Triple<NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage, NovelAiHistoryApplyMode>?>(null) }
    var imageError by remember { mutableStateOf("") }
    LaunchedEffect(controller) { controller.load(); translation = controller.translationEnabled() }
    fun edit(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) { scope.launch { controller.edit(change) } }
    fun imageAction(work: suspend () -> Unit) { scope.launch {
        try { imageError = ""; work() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { imageError = "图片操作未完成；来源保持不变，请检查文件" }
    } }
    val d = draft
    if (d == null) { StatusText("正在加载 Studio…"); return }
    val cards by produceState(emptyList<com.example.chatbar.data.local.entity.CharacterCard>(), controller) { value = controller.availableCards() }
    val models by produceState(emptyList<com.example.chatbar.data.local.entity.ModelConfig>(), controller) { value = controller.availableModels() }
    val usage by produceState<NovelAiPromptTokenUsage?>(null, d.toPromptPlan(), d.selectedModel) {
        value = controller.infrastructure.countTokens(d.toPromptPlan(), d.selectedModel)
    }
    val vibeMisses by produceState(0, d.selectedModel, d.imageGuidance, state.results) { value = controller.vibeCacheMisses(d) }
    val cost = runCatching { NovelAiImageCostEstimator.estimate(d.activeSettings, state.account, d.imageGuidance, vibeMisses) }.getOrNull()
    fun reuse(entry: NovelAiGenerationHistoryEntry, image: NovelAiGenerationHistoryImage, mode: NovelAiHistoryApplyMode) {
        if (controller.historyReuseNeedsConfirmation(entry, mode)) pendingReuse = Triple(entry, image, mode)
        else scope.launch { if (controller.applyHistory(entry, image, mode)) auxiliary = null }
    }
    fun pickImage() { scope.launch {
        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp", "gif", "apng")))?.let {
            currentImage = it; auxiliary = "当前图片"; controller.clearReverseCandidate()
        }
    } }
    val section: @Composable (String) -> Unit = { section ->
            when (section) {
                "Prompt" -> {
                    StatusText("角色卡 Prompt 来源")
                    StudioChoice("导入角色卡", cards.map { it.id }, d.importedCharacterCardId, { id -> cards.first { it.id == id }.name }) { id -> scope.launch { controller.importCard(id) } }
                    d.importedCharacterPromptSources.forEach { StatusText(it.name) }
                    StudioToggle("本地中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    StudioPromptField("画风", d.stylePrompt, controller.infrastructure, translation) { text -> edit { it.copy(stylePrompt = text) } }
                    StudioPromptField("基础 Prompt", d.basePrompt, controller.infrastructure, translation) { text -> edit { it.copy(basePrompt = text) } }
                    StudioPromptField("补充", d.extraPrompt, controller.infrastructure, translation) { text -> edit { it.copy(extraPrompt = text) } }
                    d.characters.forEach { character -> key(character.id) {
                        StudioPromptField("角色 ${d.characters.indexOf(character) + 1}", character.prompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(prompt = text) else c }) } }
                        StudioPromptField("角色负面", character.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(negativePrompt = text) else c }) } }
                        StudioActions {
                            listOf(-1 to "上移", 1 to "下移").forEach { (offset, label) ->
                                BootstrapButton(label, enabled = d.characters.indexOf(character) + offset in d.characters.indices) {
                                    edit { draft -> val list = draft.characters.toMutableList(); val from = list.indexOfFirst { it.id == character.id }
                                        val to = from + offset
                                        if (from >= 0 && to in list.indices) { val moved = list.removeAt(from); list.add(to, moved) }
                                        draft.copy(characters = list) }
                                }
                            }
                        }
                        BootstrapButton("移除此角色") { edit { it.copy(characters = it.characters.filterNot { c -> c.id == character.id }) } }
                    } }
                    StudioPromptField("负面 Prompt", d.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(negativePrompt = text) } }
                    StudioActions {
                        BootstrapButton("添加角色", enabled = d.characters.size < d.selectedModel.maxCharacters) { edit { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) } }
                        BootstrapButton("清空（保留画风）") { scope.launch { controller.replace { it.clearPromptsExceptStyle() } } }
                        BootstrapButton("复制正面 Prompt") { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(d.copyPositivePrompt()), null) }
                        BootstrapButton("粘贴覆盖") { scope.launch {
                            val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return@launch
                            controller.replace { NovelAiStudioPromptClipboard.apply(text, it) }
                        } }
                        BootstrapButton("PNG 元数据导入") { scope.launch {
                            controller.picker.pickOpenFile(DesktopFileType("PNG", listOf("png")))?.let { metadataFile = it; metadataSelection = NovelAiStudioMetadataSelection() }
                        } }
                    }
                    StudioToggle("复制时忽略画风", d.copyPositivePromptIgnoreStyle) { edit { it.copy(copyPositivePromptIgnoreStyle = !it.copyPositivePromptIgnoreStyle) } }
                }
                "参数" -> {
                    BootstrapButton("刷新账户额度") { scope.launch { controller.refreshAccount() } }
                    state.account?.let { StatusText("Anlas ${it.anlas} · ${if (it.active) "会员有效" else "会员未激活"}") }
                    cost?.let { StatusText("预估 ${it.kind} · Anlas ${it.anlas}；以服务端结算为准") }
                    StudioToggle("连续生成", d.continuousModeEnabled) { edit { it.copy(continuousModeEnabled = !it.continuousModeEnabled) } }
                    if (d.continuousModeEnabled) StudioField("目标图片数", d.continuousTargetCount.toString()) { text -> text.toIntOrNull()?.takeIf { it > 0 }?.let { value -> edit { it.copy(continuousTargetCount = value) } } }
                    StudioToggle("跟随角色卡/全局模型", d.followDefaultNovelAiImageModel) { edit { it.copy(followDefaultNovelAiImageModel = !it.followDefaultNovelAiImageModel) } }
                    StudioChoice("模型", NovelAiImageModel.entries, d.selectedModel, { it.displayName }) { model -> edit { it.copy(selectedModel = model, followDefaultNovelAiImageModel = false) } }
                    val s = d.activeSettings
                    fun change(transform: (NovelAiGenerationSettings) -> NovelAiGenerationSettings) = edit { it.withActiveSettings(transform(it.activeSettings)) }
                    StudioChoice("尺寸", NovelAiSizeTier.entries, s.sizeTier, { it.displayName }) { value -> change { it.copy(sizeTier = value, customWidth = null, customHeight = null) } }
                    StudioChoice("比例", NovelAiAspectRatio.entries, s.aspectRatio, { it.displayName }) { value -> change { it.copy(aspectRatio = value, customWidth = null, customHeight = null) } }
                    StudioField("自定义宽度（空 = 预设）", s.customWidth?.toString().orEmpty()) { text -> change { it.copy(customWidth = text.toIntOrNull()) } }
                    StudioField("自定义高度（空 = 预设）", s.customHeight?.toString().orEmpty()) { text -> change { it.copy(customHeight = text.toIntOrNull()) } }
                    StudioField("数量 1–4", s.count.toString()) { text -> text.toIntOrNull()?.let { value -> change { it.copy(count = value) } } }
                    StudioField("Steps 1–50", s.steps.toString()) { text -> text.toIntOrNull()?.let { value -> change { it.copy(steps = value) } } }
                    StudioField("Guidance 1–10", s.guidance.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(guidance = value) } } }
                    StudioField("CFG Rescale 0–1", s.cfgRescale.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(cfgRescale = value) } } }
                    StudioChoice("Sampler", NovelAiSampler.entries, s.sampler, { it.displayName }) { value -> change { it.copy(sampler = value) } }
                    StudioChoice("Seed", NovelAiSeedMode.entries, s.seedMode, { it.name }) { value -> change { it.copy(seedMode = value) } }
                    if (s.seedMode == NovelAiSeedMode.FIXED) StudioField("Seed", s.seed.toString()) { text -> text.toLongOrNull()?.let { value -> change { it.copy(seed = value) } } }
                    s.validationError(d.characters.size)?.let { StatusText(it) }
                }
                "AI 设计" -> {
                    StudioActions {
                        BootstrapButton("新对话", enabled = !busy) { newDesign = true; edit { it.copy(imageDescription = "") } }
                        BootstrapButton("设计历史") { auxiliary = "设计历史" }
                        BootstrapButton("设计设置") { auxiliary = "设计设置" }
                    }
                    StatusText("设计模型 · " + (models.firstOrNull { it.id == d.aiDesignModelId }?.displayName ?: "跟随默认设计模型"))
                    if (newDesign || currentDesignId == null) StatusText("描述想要的画面。首条消息设计完整 Prompt，之后可继续提出修改。")
                    conversations.filter { !newDesign && it.id == currentDesignId }.forEach { conversation ->
                        if (conversation.id == currentDesignId) conversation.turns.forEach { turn -> key(turn.id) {
                            var edited by remember(turn.userText) { mutableStateOf(turn.userText) }
                            StudioField("需求", edited) { edited = it }
                            StatusText(turn.reply?.displayText ?: turn.error.ifBlank { turn.status.name })
                            StudioActions {
                                BootstrapButton("编辑并分支", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, editedText = edited) } }
                                BootstrapButton("重试", enabled = !busy && turn.status != NovelAiDesignTurnStatus.COMPLETED) { scope.launch { controller.retryDesign(conversation, turn) } }
                                BootstrapButton("重新设计", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, regenerate = true) } }
                                turn.reply?.let { reply -> BootstrapButton("应用到 Studio", enabled = !busy) { scope.launch { controller.applyDesign(reply); auxiliary = null } } }
                            }
                        } }
                    }
                    StudioField("画面需求", d.imageDescription) { text -> edit { it.copy(imageDescription = text) } }
                    StudioToggle("附加当前 Studio 正面 Prompt", attachPrompt) { attachPrompt = !attachPrompt }
                    if (attachPrompt) StatusText("已附加：基础 + ${d.characters.size} 角色；不含画风与负面 Prompt")
                    StudioActions {
                        BootstrapButton(if (newDesign || currentDesignId == null) "发送" else "发送修改", enabled = !busy) { scope.launch { controller.design(newConversation = newDesign, attach = attachPrompt); attachPrompt = false; newDesign = false } }
                        BootstrapButton("停止", enabled = busy) { controller.stop() }
                    }
                }
                "设计设置" -> {
                        StudioToggle("V5 自然语言模式", d.aiDesignNaturalLanguageMode) { edit { it.copy(aiDesignNaturalLanguageMode = !it.aiDesignNaturalLanguageMode) } }
                        StudioChoice("设计模型", models.map { it.id }, d.aiDesignModelId, { id -> models.first { it.id == id }.displayName }) { id -> edit { it.copy(aiDesignModelId = id) } }
                        var requirement by remember { mutableStateOf("") }
                        LaunchedEffect(controller) { requirement = controller.designRequirement() }
                        StudioField("额外要求", requirement) { requirement = it; scope.launch { controller.setDesignRequirement(it) } }
                        BootstrapButton("完成设置") { auxiliary = "AI 设计" }
                    }
                "设计历史" -> {
                    conversations.forEach { conversation ->
                        StudioActions {
                            BootstrapButton("${if (conversation.id == currentDesignId) "● " else ""}${conversation.title}", enabled = !busy) { scope.launch { controller.switchDesign(conversation.id); newDesign = false; auxiliary = "AI 设计" } }
                            BootstrapButton("删除设计", enabled = !busy && conversation.id != currentDesignId) { scope.launch { controller.deleteDesign(conversation.id) } }
                        }
                    }
                }
                "图像引导" -> {
                    NovelAiImageUseTarget.entries.forEach { target -> BootstrapButton("导入 · ${target.displayName}") { scope.launch {
                        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp")))?.let { controller.importGuidance(it, target) }
                    } } }
                    val g = d.imageGuidance
                    fun change(transform: (NovelAiImageGuidanceDraft) -> NovelAiImageGuidanceDraft) = edit { it.copy(imageGuidance = transform(it.imageGuidance)) }
                    StudioChoice("操作", NovelAiGenerationAction.entries, g.action, { it.displayName }) { value -> change { it.copy(action = value) } }
                    StudioChoice("参考", NovelAiReferenceMode.entries, g.referenceMode, { it.name }) { value -> change { it.copy(referenceMode = value) } }
                    g.baseImage?.let { asset ->
                        DesktopOwnedImage(asset.path, controller.resources::readBytes, Modifier.fillMaxWidth().height(200.dp))
                        BootstrapButton("编辑基图 / 聚焦框 / 蒙版") { scope.launch {
                            guidanceEditor = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Triple(controller.resources.readBytes(asset.path), d.imageGuidance.maskImage?.let { controller.resources.readBytes(it.path) }, d.imageGuidance.focusedInpaintRegion) }
                        } }
                    }
                    StudioField("Minimum Context 32–96", g.focusedInpaintMinimumContext.toString()) { text -> text.toIntOrNull()?.let { value -> change { it.copy(focusedInpaintMinimumContext = value) } } }
                    StudioField("图生图 Strength", g.imageToImageStrength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(imageToImageStrength = value) } } }
                    StudioField("Noise", g.imageToImageNoise.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(imageToImageNoise = value) } } }
                    StudioField("Inpaint Strength", g.inpaintStrength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(inpaintStrength = value) } } }
                    StudioChoice("精确参考类型", NovelAiPreciseReferenceType.entries, g.preciseReference.type, { it.displayName }) { value -> change { it.copy(preciseReference = it.preciseReference.copy(type = value)) } }
                    StudioField("参考 Strength", g.preciseReference.strength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(strength = value)) } } }
                    StudioField("Fidelity", g.preciseReference.fidelity.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(fidelity = value)) } } }
                    g.vibes.forEach { vibe ->
                        StatusText("Vibe ${g.vibes.indexOf(vibe) + 1}")
                        StudioField("信息提取", vibe.informationExtracted.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id) v.copy(informationExtracted = value, encodedVibe = null) else v }) } } }
                        StudioField("强度", vibe.strength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id) v.copy(strength = value) else v }) } } }
                        BootstrapButton("移除 Vibe") { change { it.copy(vibes = it.vibes.filterNot { v -> v.id == vibe.id }) } }
                    }
                    StudioToggle("归一化 Vibe 强度", g.normalizeVibeStrengths) { change { it.copy(normalizeVibeStrengths = !it.normalizeVibeStrengths) } }
                    BootstrapButton("重置图像引导") { scope.launch { controller.replace { it.copy(imageGuidance = NovelAiImageGuidanceDraft()) } } }
                    g.validationError(d.selectedModel)?.let { StatusText(it) }
                }
                "结果" -> {
                    state.preview?.let { bytes -> DesktopOwnedImage("intermediate-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxWidth().height(280.dp)) }
                    LazyColumn(Modifier.fillMaxWidth().height(560.dp)) { items(state.results, key = { it }) { path -> DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(280.dp).clickable { viewing = state.results; viewingIndex = state.results.indexOf(path) }) } }
                }
            }
    }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusText(d.selectedModel.displayName)
            BootstrapButton(state.accountUi.displayAnlas?.let { "Anlas $it" } ?: "账户额度", enabled = !state.accountUi.loading) { scope.launch { controller.refreshAccount() } }
            state.accountUi.approximateV5Images?.let { StatusText("V5 约 $it 张") }
            if (state.accountUi.loading) StatusText("正在刷新账户…")
            BootstrapButton("AI 设计") { auxiliary = "AI 设计" }
            BootstrapButton("图像引导") { auxiliary = "图像引导" }
            BootstrapButton("导入图片", onClick = ::pickImage)
            BootstrapButton("图像工具", enabled = currentImage != null) { auxiliary = "当前图片" }
            BootstrapButton("历史") { auxiliary = "历史" }
            BootstrapButton("Studio 设置") { auxiliary = "设置" }
        }
        state.accountUi.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (state.status.isNotBlank()) StatusText(state.status.takeLast(300))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val prompt: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    section("Prompt")
                    BootstrapButton(if (generationExpanded) "收起生成设置" else "生成设置 · ${d.activeSettings.sizeTier.displayName}") { generationExpanded = !generationExpanded }
                    if (generationExpanded) section("参数")
                }
            }
            val result: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusText("当前图片 / 结果预览")
                    state.preview?.let { bytes -> DesktopOwnedImage("progress-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxWidth().height(220.dp)) }
                    if (state.results.isNotEmpty()) {
                        val index = viewingIndex.coerceIn(state.results.indices)
                        val path = state.results[index]
                        DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(260.dp).clickable { viewing = state.results; viewingIndex = index })
                        StudioActions {
                            BootstrapButton("上一张", enabled = index > 0) { viewingIndex = index - 1 }
                            StatusText("${index + 1}/${state.results.size}")
                            BootstrapButton("下一张", enabled = index < state.results.lastIndex) { viewingIndex = index + 1 }
                        }
                        history.firstOrNull { entry -> entry.images.any { it.path == path } }?.let { entry ->
                            val image = entry.images.first { it.path == path }
                            StatusText("Seed ${image.seed}")
                            StudioActions { NovelAiHistoryApplyMode.entries.forEach { mode ->
                                BootstrapButton(when (mode) { NovelAiHistoryApplyMode.FULL -> "复用参数"; NovelAiHistoryApplyMode.NEW_SEED -> "新种子重绘"; NovelAiHistoryApplyMode.SEED_ONLY -> "仅复用种子" }) { reuse(entry, image, mode) }
                            } }
                        }
                        StudioActions {
                            BootstrapButton("图像操作 / 用作") { currentImage = controller.resources.resolveOwnedReference(path); auxiliary = "当前图片" }
                            BootstrapButton("元数据 / 载入重绘") { metadataFile = controller.resources.resolveOwnedReference(path); metadataSelection = NovelAiStudioMetadataSelection() }
                        }
                    } else StatusText("导入图片或生成后，在这里预览与复用")
                    currentImage?.let { path ->
                        DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxWidth().height(160.dp).clickable { auxiliary = "当前图片" })
                        BootstrapButton("当前图片 · 工具与图像引导") { auxiliary = "当前图片" }
                    }
                    BootstrapButton("已配置图像引导 / 编辑") { auxiliary = "图像引导" }
                }
            }
            if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState())) { prompt() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { result() }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { result(); prompt() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
        StudioActions {
            usage?.let { StatusText("Tokens +${it.positive}/${it.limit} −${it.negative}/${it.limit}") }
            cost?.let { StatusText("预估 ${it.kind} · Anlas ${it.anlas}") }
            BootstrapButton("撤销上次载入/重置", enabled = !busy) { scope.launch {
                val before = controller.draft.value
                controller.undo()
                val after = controller.draft.value
                redoDraft = if (before != null && after != null && before != after) after to before else null
            } }
            BootstrapButton("重做", enabled = !busy && redoDraft?.first == d) { scope.launch {
                redoDraft?.takeIf { it.first == controller.draft.value }?.let { saved -> controller.replace { saved.second } }; redoDraft = null
            } }
            BootstrapButton("复制正面") { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(d.copyPositivePrompt()), null) }
        }
        }
        BootstrapButton(if (busy) "停止" else "生成", enabled = state.ready) {
            if (busy) controller.stop() else scope.launch { controller.generate() }
        }
        }
    }
    auxiliary?.let { surface ->
        androidx.compose.ui.window.DialogWindow(onCloseRequest = { auxiliary = null }, title = surface,
            state = androidx.compose.ui.window.rememberDialogState(width = 880.dp, height = 780.dp)) {
            Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioActions { StatusText(surface); BootstrapButton(if (surface == "设计历史" || surface == "设计设置") "返回 AI 设计" else "返回 Studio") { auxiliary = if (surface == "设计历史" || surface == "设计设置") "AI 设计" else null } }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (surface == "设置") {
                    StudioActions {
                        BootstrapButton("检查 Danbooru 词库更新", enabled = !busy) { scope.launch { controller.checkCatalog() } }
                        if (state.catalogUpdate != null) BootstrapButton("下载并更新词库", enabled = !busy) { scope.launch { controller.installCatalog() } }
                    }
                        StudioToggle("复制时忽略画风", d.copyPositivePromptIgnoreStyle) { edit { it.copy(copyPositivePromptIgnoreStyle = !it.copyPositivePromptIgnoreStyle) } }
                        StudioToggle("本地中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    } else if (surface == "历史") {
                        DesktopStudioHistory(controller, onApply = ::reuse,
                            onPreview = { paths, index -> viewing = paths; viewingIndex = index },
                            onUse = { path, target -> scope.launch { controller.useHistoryImage(path, target); auxiliary = "图像引导" } })
                    } else if (surface == "当前图片") {
                        currentImage?.let { path ->
                            DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxWidth().height(260.dp))
                            StatusText(path.fileName.toString())
                            StudioActions {
                                BootstrapButton("更换图片", onClick = ::pickImage)
                                BootstrapButton("移除当前图片") { currentImage = null; auxiliary = null; controller.clearReverseCandidate() }
                                BootstrapButton("元数据") { metadataFile = path; metadataSelection = NovelAiStudioMetadataSelection() }
                                BootstrapButton("打码 / 旋转 / 隐私 / 保存") { imageAction { imageTools = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); java.nio.file.Files.readAllBytes(path) } } }
                                BootstrapButton("复制图片") { imageAction { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); copyDesktopImage(java.nio.file.Files.readAllBytes(path), path)
                                } } }
                                BootstrapButton("反推 Prompt", enabled = !busy) { imageAction {
                                    reverseSource = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES); java.nio.file.Files.readAllBytes(path) }
                                    controller.clearReverseCandidate(); controller.reverseImage(requireNotNull(reverseSource))
                                } }
                                BootstrapButton("显示文件") { imageAction { ProcessBuilder("explorer.exe", "/select,", path.toString()).start() } }
                            }
                            StudioActions { NovelAiImageUseTarget.entries.forEach { target -> BootstrapButton("用作 · ${target.displayName}", enabled = !busy) { scope.launch { controller.importGuidance(path, target); auxiliary = "图像引导" } } } }
                            StatusText(state.reverseProgress.stage)
                            if (state.reverseProgress.content.isNotBlank()) {
                                StatusText("反推过程 / 流式结果")
                                androidx.compose.foundation.text.selection.SelectionContainer { DesktopMarkdownText(state.reverseProgress.content) }
                            }
                            if (state.reverseProgress.reasoning.isNotBlank()) {
                                var reasoningOpen by remember { mutableStateOf(false) }
                                BootstrapButton(if (reasoningOpen) "收起反推思考" else "查看反推思考") { reasoningOpen = !reasoningOpen }
                                if (reasoningOpen) androidx.compose.foundation.text.selection.SelectionContainer { DesktopMarkdownText(state.reverseProgress.reasoning) }
                            }
                            if (imageError.isNotEmpty()) StatusText(imageError)
                            if (busy) BootstrapButton("停止当前任务") { controller.stop() }
                            if (!busy && reverseSource != null && state.reverseCandidate == null && state.reverseProgress.stage.isNotBlank())
                                BootstrapButton("重试反推") { scope.launch { controller.reverseImage(requireNotNull(reverseSource)) } }
                    state.reverseCandidate?.let { candidate ->
                        StatusText("反推候选：${candidate.baseCaption}")
                        candidate.characterCaptions.forEach { StatusText(it.prompt) }
                        StudioActions {
                            BootstrapButton("应用反推", enabled = !busy) { scope.launch { controller.applyReverseCandidate(); auxiliary = null } }
                            BootstrapButton("重试反推", enabled = !busy && reverseSource != null) { scope.launch { reverseSource?.let { controller.reverseImage(it) } } }
                            BootstrapButton("返回 / 保留当前 Prompt") { controller.clearReverseCandidate() }
                        }
                    }
                        }
                    } else section(surface)
                }
            }
        }
    }
    pendingReuse?.let { (entry, image, mode) -> androidx.compose.ui.window.DialogWindow(onCloseRequest = { pendingReuse = null }, title = "原始图像引导缺失") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusText("这条历史没有保留原始引导图片，无法完整复现。可仅复用种子，或明确载入可复用的 Prompt / 参数后重新选择图片引导。")
            StudioActions {
                BootstrapButton("取消") { pendingReuse = null }
                BootstrapButton("仅复用种子") { scope.launch { controller.applyHistory(entry, image, NovelAiHistoryApplyMode.SEED_ONLY); pendingReuse = null } }
                BootstrapButton("载入可复用参数（不完整复现）") { scope.launch {
                    if (controller.applyHistory(entry, image, mode, allowMissingGuidance = true)) { pendingReuse = null; auxiliary = null }
                } }
            }
        }
    } }
    imageTools?.let { DesktopImageToolsDialog(it, controller.picker, { imageTools = null }) }
    guidanceEditor?.let { DesktopImageToolsDialog(it.first, controller.picker, { guidanceEditor = null }, controller::applyGuidanceEdit, it.second, it.third) }
    metadataFile?.let { path -> androidx.compose.ui.window.DialogWindow(onCloseRequest = { metadataFile = null }, title = "选择导入的元数据") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusText(path.fileName.toString())
            StudioToggle("正面 Prompt", metadataSelection.positivePrompt) { metadataSelection = metadataSelection.copy(positivePrompt = !metadataSelection.positivePrompt) }
            StudioToggle("负面 Prompt", metadataSelection.negativePrompt) { metadataSelection = metadataSelection.copy(negativePrompt = !metadataSelection.negativePrompt) }
            StudioToggle("角色 Prompt", metadataSelection.characterPrompts) { metadataSelection = metadataSelection.copy(characterPrompts = !metadataSelection.characterPrompts) }
            StudioToggle("生成参数", metadataSelection.generationSettings) { metadataSelection = metadataSelection.copy(generationSettings = !metadataSelection.generationSettings) }
            StudioToggle("Seed", metadataSelection.seed) { metadataSelection = metadataSelection.copy(seed = !metadataSelection.seed) }
            StudioToggle("图像引导", metadataSelection.imageGuidance) { metadataSelection = metadataSelection.copy(imageGuidance = !metadataSelection.imageGuidance) }
            BootstrapButton("导入") { scope.launch { controller.importMetadata(path, metadataSelection); metadataFile = null } }
            BootstrapButton("取消") { metadataFile = null }
        }
    } }
    if (viewing.isNotEmpty()) DesktopImageViewer(viewing, viewingIndex, controller.resources, controller.picker) { viewing = emptyList() }
}

@Composable
internal fun StudioField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border)
            .background(DesktopBootstrapColors.input).padding(9.dp), textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
    }
}
@Composable
internal fun StudioActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
@Composable
internal fun StudioToggle(label: String, selected: Boolean, action: () -> Unit) = BootstrapButton("${if (selected) "☑" else "☐"} $label", onClick = action)
@Composable
internal fun <T> StudioChoice(label: String, options: List<T>, selected: T?, text: (T) -> String, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    BootstrapButton("$label · ${options.firstOrNull { it == selected }?.let(text) ?: "选择"} ▾") { open = true; query = "" }
    if (open) androidx.compose.ui.window.DialogWindow(onCloseRequest = { open = false }, title = label) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioField("搜索", query) { query = it }
            LazyColumn(Modifier.weight(1f)) { items(options.filter { text(it).contains(query, ignoreCase = true) }) { item ->
                BootstrapButton("${if (item == selected) "✓ " else ""}${text(item)}") { choose(item); open = false }
            } }
            BootstrapButton("取消") { open = false }
        }
    }
}
