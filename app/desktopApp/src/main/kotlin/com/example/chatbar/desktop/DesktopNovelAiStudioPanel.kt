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
    var designEditorRevision by remember { mutableStateOf(0) }
    var selectedResult by remember { mutableStateOf<String?>(null) }
    var importedPreview by remember { mutableStateOf<java.nio.file.Path?>(null) }
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
    LaunchedEffect(state.results) { selectedResult = state.results.firstOrNull() }
    fun reuse(entry: NovelAiGenerationHistoryEntry, image: NovelAiGenerationHistoryImage, mode: NovelAiHistoryApplyMode) {
        if (controller.historyReuseNeedsConfirmation(entry, mode)) pendingReuse = Triple(entry, image, mode)
        else scope.launch { if (controller.applyHistory(entry, image, mode)) auxiliary = null }
    }
    fun pickImage() { scope.launch {
        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp", "gif", "apng")))?.let {
            currentImage = it; selectedResult = null; auxiliary = "当前图片"; controller.clearReverseCandidate()
        }
    } }
    val section: @Composable (String) -> Unit = { section ->
            when (section) {
                "Prompt" -> {
                    StudioActions {
                        StatusText("Prompt")
                        StudioAction("粘贴覆盖", icon = DesktopAppIcons.Copy) { scope.launch {
                            val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return@launch
                            controller.replace { NovelAiStudioPromptClipboard.apply(text, it) }
                        } }
                        StudioAction("清空（保留画风）", style = StudioActionStyle.TERTIARY) { scope.launch { controller.replace { it.clearPromptsExceptStyle() } } }
                        StudioToggle("中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    }
                    SearchableChoice("导入角色卡 Prompt", cards.map { it.id }, d.importedCharacterCardId, { id -> cards.first { it.id == id }.name }) { id -> scope.launch { controller.importCard(id) } }
                    StatusText("填充画风；角色 Prompt 仅供 AI 设计参考，不参与实际生图")
                    StudioDisclosure("画风", d.stylePrompt.lineSequence().firstOrNull().orEmpty().take(48)) {
                        StudioPromptField("画风", d.stylePrompt, controller.infrastructure, translation) { text -> edit { it.copy(stylePrompt = text) } }
                    }
                    StudioPromptField("基础 Prompt", d.basePrompt, controller.infrastructure, translation) { text -> edit { it.copy(basePrompt = text) } }
                    StudioDisclosure("补充", initiallyOpen = d.extraPrompt.isNotBlank()) {
                        StudioPromptField("补充", d.extraPrompt, controller.infrastructure, translation) { text -> edit { it.copy(extraPrompt = text) } }
                    }
                    d.characters.forEach { character -> key(character.id) {
                        StudioPromptField("角色 ${d.characters.indexOf(character) + 1}", character.prompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(prompt = text) else c }) } }
                        StudioDisclosure("角色负面") {
                            StudioPromptField("角色负面", character.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(negativePrompt = text) else c }) } }
                        }
                        StudioActions {
                            listOf(-1 to "上移", 1 to "下移").forEach { (offset, label) ->
                                StudioAction(label, enabled = d.characters.indexOf(character) + offset in d.characters.indices) {
                                    edit { draft -> val list = draft.characters.toMutableList(); val from = list.indexOfFirst { it.id == character.id }
                                        val to = from + offset
                                        if (from >= 0 && to in list.indices) { val moved = list.removeAt(from); list.add(to, moved) }
                                        draft.copy(characters = list) }
                                }
                            }
                        }
                        StudioAction("移除此角色") { edit { it.copy(characters = it.characters.filterNot { c -> c.id == character.id }) } }
                    } }
                    StudioDisclosure("负面 Prompt") {
                        StudioPromptField("负面 Prompt", d.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(negativePrompt = text) } }
                    }
                    StudioActions {
                        StudioAction("添加角色", enabled = d.characters.size < d.selectedModel.maxCharacters) { edit { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) } }
                        StudioAction("PNG 元数据导入") { scope.launch {
                            controller.picker.pickOpenFile(DesktopFileType("PNG", listOf("png")))?.let { metadataFile = it; metadataSelection = NovelAiStudioMetadataSelection() }
                        } }
                    }
                }
                "参数" -> {
                    StudioGenerationSettings(d, ::edit)
                }
                "AI 设计" -> {
                    StudioActions {
                        StudioAction("新对话", enabled = !busy) { newDesign = true; designEditorRevision++; edit { it.copy(imageDescription = "") } }
                        StudioAction("设计历史") { auxiliary = "设计历史" }
                        StudioAction("设计设置") { auxiliary = "设计设置" }
                    }
                    StatusText("设计模型 · " + (models.firstOrNull { it.id == d.aiDesignModelId }?.displayName ?: "跟随默认设计模型"))
                    if (newDesign || currentDesignId == null) StatusText("描述想要的画面。首条消息设计完整 Prompt，之后可继续提出修改。")
                    conversations.filter { !newDesign && it.id == currentDesignId }.forEach { conversation ->
                        if (conversation.id == currentDesignId) conversation.turns.forEach { turn -> key(turn.id) {
                            var edited by remember(turn.userText) { mutableStateOf(turn.userText) }
                            DesktopDesignField("需求", edited, turn.id) { edited = it }
                            turn.reply?.let { DesktopDesignResult(it) }
                            if (turn.status != NovelAiDesignTurnStatus.COMPLETED) StatusText(turn.error.ifBlank { turn.status.name }, DesktopBootstrapColors.warning)
                            turn.attachedStudioPrompt?.let { StatusText("本轮附加 Studio：基础 + ${it.characterPrompts.size} 角色") }
                            StudioActions {
                                StudioAction("编辑并分支", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, editedText = edited) } }
                                StudioAction("重试", enabled = !busy && turn.status != NovelAiDesignTurnStatus.COMPLETED) { scope.launch { controller.retryDesign(conversation, turn) } }
                                StudioAction("重新设计", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, regenerate = true) } }
                                turn.reply?.let { reply -> StudioAction("应用到 Studio", enabled = !busy, style = StudioActionStyle.PRIMARY) { scope.launch { controller.applyDesign(reply); auxiliary = null } } }
                            }
                        } }
                    }
                    DesktopDesignField("画面需求", d.imageDescription, "request-$designEditorRevision-$currentDesignId") { text -> edit { it.copy(imageDescription = text) } }
                    StudioToggle("附加当前 Studio 正面 Prompt", attachPrompt) { attachPrompt = !attachPrompt }
                    StatusText("Enter / Shift+Enter 换行；点击发送提交，输入法候选确认不会发送")
                    if (attachPrompt) StatusText("已附加：基础 + ${d.characters.size} 角色；不含画风与负面 Prompt")
                    StudioActions {
                        StudioAction(if (newDesign || currentDesignId == null) "发送" else "发送修改", enabled = !busy) { scope.launch { if (controller.design(newConversation = newDesign, attach = attachPrompt)) { attachPrompt = false; newDesign = false } } }
                        StudioAction("停止", enabled = busy) { controller.stop() }
                    }
                }
                "设计设置" -> {
                        StudioToggle("V5 自然语言模式", d.aiDesignNaturalLanguageMode) { edit { it.copy(aiDesignNaturalLanguageMode = !it.aiDesignNaturalLanguageMode) } }
                        SearchableChoice("设计模型", models.map { it.id }, d.aiDesignModelId, { id -> models.first { it.id == id }.displayName }) { id -> edit { it.copy(aiDesignModelId = id) } }
                        var requirement by remember { mutableStateOf("") }
                        LaunchedEffect(controller) { requirement = controller.designRequirement() }
                        DesktopDesignField("额外要求", requirement) { requirement = it; scope.launch { controller.setDesignRequirement(it) } }
                        StudioAction("完成设置") { auxiliary = "AI 设计" }
                    }
                "设计历史" -> {
                    conversations.forEach { conversation ->
                        StudioActions {
                            StudioAction("${if (conversation.id == currentDesignId) "● " else ""}${conversation.title}", enabled = !busy) { scope.launch { controller.switchDesign(conversation.id); newDesign = false; auxiliary = "AI 设计" } }
                            StudioAction("删除设计", enabled = !busy && conversation.id != currentDesignId) { scope.launch { controller.deleteDesign(conversation.id) } }
                        }
                    }
                }
                "图像引导" -> {
                    desktopImageUseTargets(d.selectedModel).forEach { target -> StudioAction("导入 · ${target.displayName}") { scope.launch {
                        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp")))?.let { controller.importGuidance(it, target) }
                    } } }
                    val g = d.imageGuidance
                    fun change(transform: (NovelAiImageGuidanceDraft) -> NovelAiImageGuidanceDraft) = edit { it.copy(imageGuidance = transform(it.imageGuidance)) }
                    CompactChoice("操作", NovelAiGenerationAction.entries, g.action, { it.displayName }) { value -> change { it.copy(action = value) } }
                    CompactChoice("参考", NovelAiReferenceMode.entries, g.referenceMode, { it.name }) { value -> change { it.copy(referenceMode = value) } }
                    g.baseImage?.let { asset ->
                        DesktopOwnedImage(asset.path, controller.resources::readBytes, Modifier.fillMaxWidth().height(200.dp))
                        StudioAction("编辑基图 / 聚焦框 / 蒙版") { scope.launch {
                            guidanceEditor = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Triple(controller.resources.readBytes(asset.path), d.imageGuidance.maskImage?.let { controller.resources.readBytes(it.path) }, d.imageGuidance.focusedInpaintRegion) }
                        } }
                    }
                    StudioField("Minimum Context 32–96", g.focusedInpaintMinimumContext.toString()) { text -> text.toIntOrNull()?.let { value -> change { it.copy(focusedInpaintMinimumContext = value) } } }
                    StudioField("图生图 Strength", g.imageToImageStrength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(imageToImageStrength = value) } } }
                    StudioField("Noise", g.imageToImageNoise.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(imageToImageNoise = value) } } }
                    StudioField("Inpaint Strength", g.inpaintStrength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(inpaintStrength = value) } } }
                    CompactChoice("精确参考类型", NovelAiPreciseReferenceType.entries, g.preciseReference.type, { it.displayName }) { value -> change { it.copy(preciseReference = it.preciseReference.copy(type = value)) } }
                    StudioField("参考 Strength", g.preciseReference.strength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(strength = value)) } } }
                    StudioField("Fidelity", g.preciseReference.fidelity.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(fidelity = value)) } } }
                    g.vibes.forEach { vibe ->
                        StatusText("Vibe ${g.vibes.indexOf(vibe) + 1}")
                        StudioField("信息提取", vibe.informationExtracted.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id) v.copy(informationExtracted = value, encodedVibe = null) else v }) } } }
                        StudioField("强度", vibe.strength.toString()) { text -> text.toFloatOrNull()?.let { value -> change { it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id) v.copy(strength = value) else v }) } } }
                        StudioAction("移除 Vibe") { change { it.copy(vibes = it.vibes.filterNot { v -> v.id == vibe.id }) } }
                    }
                    StudioToggle("归一化 Vibe 强度", g.normalizeVibeStrengths) { change { it.copy(normalizeVibeStrengths = !it.normalizeVibeStrengths) } }
                    StudioAction("重置图像引导") { scope.launch { controller.replace { it.copy(imageGuidance = NovelAiImageGuidanceDraft()) } } }
                    g.validationError(d.selectedModel)?.let { StatusText(it) }
                }
                "结果" -> {
                    state.preview?.let { bytes -> DesktopOwnedImage("intermediate-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxWidth().height(280.dp)) }
                    LazyColumn(Modifier.fillMaxWidth().height(560.dp)) { items(state.results, key = { it }) { path -> DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(280.dp).clickable { viewing = state.results; viewingIndex = state.results.indexOf(path) }) } }
                }
            }
    }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioAccountCluster(d.selectedModel, state.accountUi)
        StudioActions {
            StudioAction("刷新", enabled = !state.accountUi.loading, icon = DesktopAppIcons.Refresh, style = StudioActionStyle.TERTIARY) { scope.launch { controller.refreshAccount() } }
            StudioAction("AI 设计", icon = DesktopAppIcons.Chat) { auxiliary = "AI 设计" }
            StudioAction("图像引导", icon = DesktopAppIcons.Star) { auxiliary = "图像引导" }
            StudioAction("导入图片", icon = DesktopAppIcons.ImageAdd, onClick = ::pickImage)
            if (currentImage != null) StudioAction("图像工具", icon = DesktopAppIcons.Tools) { auxiliary = "当前图片" }
            StudioAction("历史", icon = DesktopAppIcons.History) { auxiliary = "历史" }
            StudioAction("设置", icon = DesktopAppIcons.Settings) { auxiliary = "设置" }
        }
        if (state.status.isNotBlank()) StatusText(state.status.takeLast(300))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val prompt: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    section("Prompt")
                    StudioAction(if (generationExpanded) "收起生成设置" else "生成设置 · ${d.activeSettings.sizeTier.displayName}") { generationExpanded = !generationExpanded }
                    if (generationExpanded) section("参数")
                }
            }
            val availableHeight = maxHeight.value
            val result: @Composable () -> Unit = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val previewHeight = desktopResultHeight(maxWidth.value, availableHeight).dp
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val resultPaths = desktopStudioFilmstrip(state.results, history)
                    StatusText("当前图片 / 结果预览")
                    if (state.preview != null && busy) {
                        val bytes = requireNotNull(state.preview)
                        DesktopOwnedImage("progress-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxWidth().height(previewHeight))
                    } else if (currentImage != null && selectedResult == null) {
                        val imported = requireNotNull(currentImage)
                        DesktopOwnedImage(imported.toString(), { java.nio.file.Files.readAllBytes(imported) }, Modifier.fillMaxWidth().height(previewHeight).clickable { importedPreview = imported })
                        StudioFilmstrip(resultPaths, null, controller.resources::readBytes) { selectedResult = it }
                    } else if (resultPaths.isNotEmpty()) {
                        val path = selectedResult?.takeIf { it in resultPaths } ?: resultPaths.first()
                        val index = resultPaths.indexOf(path)
                        DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(previewHeight).clickable { viewing = resultPaths; viewingIndex = index })
                        StudioActions {
                            StudioAction("上一张", enabled = index > 0) { selectedResult = resultPaths[index - 1] }
                            StatusText("${index + 1}/${resultPaths.size}")
                            StudioAction("下一张", enabled = index < resultPaths.lastIndex) { selectedResult = resultPaths[index + 1] }
                        }
                        StudioFilmstrip(resultPaths, path, controller.resources::readBytes) { selectedResult = it }
                        history.firstOrNull { entry -> entry.images.any { it.path == path } }?.let { entry ->
                            val image = entry.images.first { it.path == path }
                            StatusText("Seed ${image.seed}")
                            StudioActions { listOf(NovelAiHistoryApplyMode.NEW_SEED, NovelAiHistoryApplyMode.SEED_ONLY).forEach { mode ->
                                StudioAction(when (mode) { NovelAiHistoryApplyMode.FULL -> "复用参数"; NovelAiHistoryApplyMode.NEW_SEED -> "新种子重绘"; NovelAiHistoryApplyMode.SEED_ONLY -> "仅复用种子" }) { reuse(entry, image, mode) }
                            } }
                        }
                        StudioActions {
                            StudioAction("图像操作 / 用作") { currentImage = controller.resources.resolveOwnedReference(path); auxiliary = "当前图片" }
                            StudioAction("元数据 / 载入重绘") { metadataFile = controller.resources.resolveOwnedReference(path); metadataSelection = NovelAiStudioMetadataSelection() }
                        }
                    } else Box(Modifier.fillMaxWidth().height(260.dp).background(DesktopBootstrapColors.muted)
                        .border(1.dp, DesktopBootstrapColors.border), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        StatusText("导入图片或生成后，在这里预览与复用")
                    }
                    currentImage?.let { path ->
                        StudioAction("当前图片 · 工具与图像引导") { auxiliary = "当前图片" }
                    }
                    StudioAction(desktopGuidanceLabel(d.imageGuidance, d.selectedModel)) { auxiliary = "图像引导" }
                } }
            }
            if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState())) { prompt() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { result() }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { prompt(); result() }
        }
        StudioActions {
            usage?.let { tokens ->
                Column(Modifier.widthIn(min = 140.dp, max = 220.dp)) { StudioTokenBar("正向 Tokens", tokens.positive, tokens.limit); StudioTokenBar("负向 Tokens", tokens.negative, tokens.limit) }
            }
            StudioAction("撤销上次载入/重置", enabled = !busy, style = StudioActionStyle.TERTIARY) { scope.launch {
                val before = controller.draft.value
                controller.undo()
                val after = controller.draft.value
                redoDraft = if (before != null && after != null && before != after) after to before else null
            } }
            StudioAction("重做", enabled = !busy && redoDraft?.first == d, style = StudioActionStyle.TERTIARY) { scope.launch {
                redoDraft?.takeIf { it.first == controller.draft.value }?.let { saved -> controller.replace { saved.second } }; redoDraft = null
            } }
            StudioAction("复制正向 Prompt", style = StudioActionStyle.TERTIARY) { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(d.copyPositivePrompt()), null) }
        BootstrapButton(desktopGenerateLabel(busy, state.credentialConfigured, cost, state.status), enabled = busy || desktopCanGenerate(state, d, busy)) {
            if (busy) controller.stop() else scope.launch { controller.generate() }
        }
        }
    }
    auxiliary?.let { surface ->
        androidx.compose.ui.window.DialogWindow(onCloseRequest = { auxiliary = null }, title = surface,
            state = androidx.compose.ui.window.rememberDialogState(width = when (surface) { "设置", "设计设置" -> 520.dp; "图像引导" -> 640.dp; else -> 880.dp }, height = when (surface) { "设置" -> 360.dp; "设计设置" -> 460.dp; else -> 740.dp })) {
            Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioActions { StatusText(surface); StudioAction(if (surface == "设计历史" || surface == "设计设置") "返回 AI 设计" else "返回 Studio") { auxiliary = if (surface == "设计历史" || surface == "设计设置") "AI 设计" else null } }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (surface == "设置") {
                    StudioActions {
                        StudioAction("检查 Danbooru 词库更新", enabled = !busy) { scope.launch { controller.checkCatalog() } }
                        if (state.catalogUpdate != null) StudioAction("下载并更新词库", enabled = !busy) { scope.launch { controller.installCatalog() } }
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
                                StudioAction("更换图片", onClick = ::pickImage)
                                StudioAction("移除当前图片") { currentImage = null; auxiliary = null; controller.clearReverseCandidate() }
                                StudioAction("元数据") { metadataFile = path; metadataSelection = NovelAiStudioMetadataSelection() }
                                StudioAction("打码 / 旋转 / 隐私 / 保存") { imageAction { imageTools = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); java.nio.file.Files.readAllBytes(path) } } }
                                StudioAction("复制图片") { imageAction { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); copyDesktopImage(java.nio.file.Files.readAllBytes(path), path)
                                } } }
                                StudioAction("反推 Prompt", enabled = !busy) { imageAction {
                                    reverseSource = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES); java.nio.file.Files.readAllBytes(path) }
                                    controller.clearReverseCandidate(); controller.reverseImage(requireNotNull(reverseSource))
                                } }
                                StudioAction("显示文件") { imageAction { ProcessBuilder("explorer.exe", "/select,", path.toString()).start() } }
                            }
                            StudioActions { desktopImageUseTargets(d.selectedModel).forEach { target -> StudioAction("用作 · ${target.displayName}", enabled = !busy) { scope.launch { controller.importGuidance(path, target); auxiliary = "图像引导" } } } }
                            StatusText(state.reverseProgress.stage)
                            if (state.reverseProgress.content.isNotBlank()) {
                                StatusText("反推过程 / 流式结果")
                                androidx.compose.foundation.text.selection.SelectionContainer { DesktopMarkdownText(state.reverseProgress.content) }
                            }
                            if (state.reverseProgress.reasoning.isNotBlank()) {
                                var reasoningOpen by remember { mutableStateOf(false) }
                                StudioAction(if (reasoningOpen) "收起反推思考" else "查看反推思考") { reasoningOpen = !reasoningOpen }
                                if (reasoningOpen) androidx.compose.foundation.text.selection.SelectionContainer { DesktopMarkdownText(state.reverseProgress.reasoning) }
                            }
                            if (imageError.isNotEmpty()) StatusText(imageError)
                            if (busy) StudioAction("停止当前任务") { controller.stop() }
                            if (!busy && reverseSource != null && state.reverseCandidate == null && state.reverseProgress.stage.isNotBlank())
                                StudioAction("重试反推") { scope.launch { controller.reverseImage(requireNotNull(reverseSource)) } }
                    state.reverseCandidate?.let { candidate ->
                        StatusText("反推候选：${candidate.baseCaption}")
                        candidate.characterCaptions.forEach { StatusText(it.prompt) }
                        StudioActions {
                            StudioAction("应用反推", enabled = !busy) { scope.launch { controller.applyReverseCandidate(); auxiliary = null } }
                            StudioAction("重试反推", enabled = !busy && reverseSource != null) { scope.launch { reverseSource?.let { controller.reverseImage(it) } } }
                            StudioAction("返回 / 保留当前 Prompt") { controller.clearReverseCandidate() }
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
            StatusText("这条历史没有保留原始引导图片。仍可复用设置或 Seed，但结果无法准确复现原图。")
            StudioActions {
                StudioAction("取消") { pendingReuse = null }
                StudioAction(if (mode == NovelAiHistoryApplyMode.SEED_ONLY) "确认仅复用种子" else "确认新种子复用") { scope.launch {
                    if (controller.applyHistory(entry, image, mode, warningConfirmed = true)) { pendingReuse = null; auxiliary = null }
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
            StudioAction("导入") { scope.launch { controller.importMetadata(path, metadataSelection); metadataFile = null } }
            StudioAction("取消") { metadataFile = null }
        }
    } }
    if (viewing.isNotEmpty()) DesktopImageViewer(viewing, viewingIndex, controller.resources, controller.picker) { viewing = emptyList() }
    importedPreview?.let { path -> DesktopTransientImagePreview(path.toString(), {
        require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
        java.nio.file.Files.readAllBytes(path)
    }) { importedPreview = null } }
}

@Composable
internal fun StudioField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border)
            .background(DesktopBootstrapColors.input).padding(9.dp), textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioActions(content: @Composable RowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
@Composable
internal fun StudioToggle(label: String, selected: Boolean, action: () -> Unit) = StudioSwitch(label, selected, action)
@Composable
internal fun <T> SearchableChoice(label: String, options: List<T>, selected: T?, text: (T) -> String, choose: (T) -> Unit) {
    if (!studioUsesSearchDialog(options.size)) { CompactChoice(label, options, selected, text, choose); return }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    StudioAction("$label · ${options.firstOrNull { it == selected }?.let(text) ?: "选择"} ▾") { open = true; query = "" }
    if (open) androidx.compose.ui.window.DialogWindow(onCloseRequest = { open = false }, title = label, state = androidx.compose.ui.window.rememberDialogState(width = 520.dp, height = 560.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioField("搜索", query) { query = it }
            LazyColumn(Modifier.weight(1f)) { items(options.filter { text(it).contains(query, ignoreCase = true) }) { item ->
                StudioAction("${if (item == selected) "✓ " else ""}${text(item)}") { choose(item); open = false }
            } }
            StudioAction("取消") { open = false }
        }
    }
}
