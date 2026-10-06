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
    var page by remember { mutableStateOf("Prompt") }
    var viewing by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(controller) { controller.load(); translation = controller.translationEnabled() }
    fun edit(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) { scope.launch { controller.edit(change) } }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton("打开图像工具") { scope.launch {
                controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp", "gif", "apng")))?.let {
                    imageTools = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(it) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); java.nio.file.Files.readAllBytes(it) }
                }
            } }
            listOf("Prompt", "参数", "AI 设计", "图像引导", "结果", "历史").forEach { label ->
                BootstrapButton(if (page == label) "● $label" else label) { page = label }
            }
        }
        StatusText(state.status)
        val d = draft
        if (d == null) { StatusText("正在加载 Studio…"); return@Column }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                "Prompt" -> {
                    StudioActions {
                        BootstrapButton("检查 Danbooru 词库更新", enabled = !busy) { scope.launch { controller.checkCatalog() } }
                        if (state.catalogUpdate != null) BootstrapButton("下载并更新词库", enabled = !busy) { scope.launch { controller.installCatalog() } }
                    }
                    StudioToggle("本地中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    val usage by produceState<NovelAiPromptTokenUsage?>(null, d.toPromptPlan(), d.selectedModel) {
                        value = controller.infrastructure.countTokens(d.toPromptPlan(), d.selectedModel)
                    }
                    usage?.let { StatusText("Tokens 正面 ${it.positive}/${it.limit} · 负面 ${it.negative}/${it.limit}") }
                    StudioPromptField("画风", d.stylePrompt, controller.infrastructure, translation) { text -> edit { it.copy(stylePrompt = text) } }
                    StudioPromptField("基础 Prompt", d.basePrompt, controller.infrastructure, translation) { text -> edit { it.copy(basePrompt = text) } }
                    StudioPromptField("补充", d.extraPrompt, controller.infrastructure, translation) { text -> edit { it.copy(extraPrompt = text) } }
                    StudioPromptField("负面 Prompt", d.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(negativePrompt = text) } }
                    d.characters.forEach { character -> key(character.id) {
                        StudioPromptField("角色 ${d.characters.indexOf(character) + 1}", character.prompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(prompt = text) else c }) } }
                        StudioPromptField("角色负面", character.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) c.copy(negativePrompt = text) else c }) } }
                        BootstrapButton("移除此角色") { edit { it.copy(characters = it.characters.filterNot { c -> c.id == character.id }) } }
                    } }
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
                    val cost = runCatching { NovelAiImageCostEstimator.estimate(d.activeSettings, state.account, d.imageGuidance,
                        d.imageGuidance.vibes.count { it.encodedVibe.isNullOrBlank() }) }.getOrNull()
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
                    BootstrapButton("选择图片反推", enabled = !busy) { scope.launch {
                        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp")))?.let { path ->
                            reverseSource = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES)
                                java.nio.file.Files.readAllBytes(path)
                            }
                            controller.clearReverseCandidate()
                            controller.reverseImage(requireNotNull(reverseSource))
                        }
                    } }
                    state.reverseCandidate?.let { candidate ->
                        StatusText("反推候选：${candidate.baseCaption}")
                        candidate.characterCaptions.forEach { StatusText(it.prompt) }
                        StudioActions {
                            BootstrapButton("应用反推", enabled = !busy) { scope.launch { controller.applyReverseCandidate(); page = "Prompt" } }
                            BootstrapButton("重试反推", enabled = !busy && reverseSource != null) { scope.launch { reverseSource?.let { controller.reverseImage(it) } } }
                            BootstrapButton("返回 / 保留当前 Prompt") { controller.clearReverseCandidate() }
                        }
                    }
                    StudioField("画面需求", d.imageDescription) { text -> edit { it.copy(imageDescription = text) } }
                    StudioToggle("V5 自然语言模式", d.aiDesignNaturalLanguageMode) { edit { it.copy(aiDesignNaturalLanguageMode = !it.aiDesignNaturalLanguageMode) } }
                    val models by produceState(emptyList<com.example.chatbar.data.local.entity.ModelConfig>(), controller) { value = controller.availableModels() }
                    StudioChoice("设计模型", models.map { it.id }, d.aiDesignModelId, { id -> models.first { it.id == id }.displayName }) { id -> edit { it.copy(aiDesignModelId = id) } }
                    val cards by produceState(emptyList<com.example.chatbar.data.local.entity.CharacterCard>(), controller) { value = controller.availableCards() }
                    StudioChoice("导入角色卡参考", cards.map { it.id }, d.importedCharacterCardId, { id -> cards.first { it.id == id }.name }) { id -> scope.launch { controller.importCard(id) } }
                    d.importedCharacterPromptSources.forEach { StatusText(it.name) }
                    StudioToggle("附加当前 Studio 正面 Prompt", attachPrompt) { attachPrompt = !attachPrompt }
                    StudioActions {
                        BootstrapButton("发送修改", enabled = !busy) { scope.launch { controller.design(attach = attachPrompt); attachPrompt = false } }
                        BootstrapButton("开始新设计", enabled = !busy) { scope.launch { controller.design(newConversation = true, attach = attachPrompt); attachPrompt = false } }
                    }
                    conversations.forEach { conversation ->
                        StudioActions {
                            BootstrapButton("${if (conversation.id == currentDesignId) "● " else ""}${conversation.title}", enabled = !busy) { scope.launch { controller.switchDesign(conversation.id) } }
                            BootstrapButton("删除设计", enabled = !busy && conversation.id != currentDesignId) { scope.launch { controller.deleteDesign(conversation.id) } }
                        }
                        if (conversation.id == currentDesignId) conversation.turns.forEach { turn -> key(turn.id) {
                            var edited by remember(turn.userText) { mutableStateOf(turn.userText) }
                            StudioField("需求", edited) { edited = it }
                            StatusText(turn.reply?.displayText ?: turn.error.ifBlank { turn.status.name })
                            StudioActions {
                                BootstrapButton("编辑并分支", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, editedText = edited) } }
                                BootstrapButton("重试", enabled = !busy && turn.status != NovelAiDesignTurnStatus.COMPLETED) { scope.launch { controller.retryDesign(conversation, turn) } }
                                BootstrapButton("重新设计", enabled = !busy) { scope.launch { controller.retryDesign(conversation, turn, regenerate = true) } }
                                turn.reply?.let { reply -> BootstrapButton("应用到 Studio", enabled = !busy) { scope.launch { controller.applyDesign(reply); page = "Prompt" } } }
                            }
                        } }
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
                "历史" -> {
                    StudioActions {
                        BootstrapButton("删除选中 ${state.selected.size}", enabled = state.selected.isNotEmpty()) { confirmDelete = true }
                        if (confirmDelete) BootstrapButton("确认删除") { scope.launch { controller.deleteSelected(); confirmDelete = false } }
                        if (confirmDelete) BootstrapButton("取消") { confirmDelete = false }
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(600.dp)) {
                    items(history.flatMap { entry -> entry.images.map { entry to it } }, key = { it.first.id + ":" + it.second.path }) { (entry, image) ->
                        StatusText("${java.util.Date(entry.createdAt)} · ${entry.recipe.settings.model.displayName}")
                        run {
                            val selection = NovelAiHistoryImageSelection(entry.id, image.path)
                            DesktopOwnedImage(image.path, controller.resources::readBytes, Modifier.fillMaxWidth().height(220.dp).clickable { viewing = entry.images.map { it.path }; viewingIndex = entry.images.indexOf(image) })
                            StatusText("Seed ${image.seed}")
                            StudioActions { NovelAiImageUseTarget.entries.forEach { target -> BootstrapButton("用作${target.displayName}") { scope.launch {
                                controller.useHistoryImage(image.path, target); page = "图像引导"
                            } } } }
                            StudioActions {
                                BootstrapButton(if (selection in state.selected) "取消选择" else "选择") { controller.toggleSelection(selection) }
                                NovelAiHistoryApplyMode.entries.forEach { mode -> BootstrapButton(when (mode) { NovelAiHistoryApplyMode.FULL -> "载入重绘"; NovelAiHistoryApplyMode.NEW_SEED -> "载入新种子"; NovelAiHistoryApplyMode.SEED_ONLY -> "仅种子" }) { scope.launch { controller.applyHistory(entry, image, mode); page = "Prompt" } } }
                            }
                        }
                    }
                    }
                }
            }
        }
        StudioActions {
            BootstrapButton("生成", enabled = state.ready && !busy) { scope.launch { controller.generate(); page = "结果" } }
            BootstrapButton("停止", enabled = busy) { controller.stop() }
            BootstrapButton("撤销上次载入/重置", enabled = !busy) { scope.launch { controller.undo() } }
        }
    }
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
    StatusText(label)
    StudioActions { options.forEach { item -> BootstrapButton("${if (item == selected) "● " else ""}${text(item)}") { choose(item) } } }
}
