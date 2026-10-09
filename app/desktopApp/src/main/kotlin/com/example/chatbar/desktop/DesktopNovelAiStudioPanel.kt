package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopNovelAiStudioPanel(controller: DesktopNovelAiStudioController, onModelSettings: (String?) -> Unit = {}) {
    val draft by controller.draft.collectAsState()
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState(emptyList())
    val conversations by controller.designRepository.conversations.collectAsState()
    val currentDesignId by controller.designRepository.currentConversationId.collectAsState()
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
    DesktopDesignToolLifecycle(controller, auxiliary in setOf("AI 设计", "设计设置", "设计历史"))
    var currentImage by remember { mutableStateOf<java.nio.file.Path?>(null) }
    val clipboardFiles = remember { mutableListOf<java.nio.file.Path>() }
    val latestBusy by rememberUpdatedState(busy)
    DisposableEffect(controller) { onDispose { if (!latestBusy) clipboardFiles.forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } } } }
    var selectedResult by remember { mutableStateOf<String?>(null) }
    var seenResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultMode by remember { mutableStateOf("展开预览") }
    var resultFraction by remember { mutableStateOf(.49f) }
    var compactExpanded by remember { mutableStateOf(false) }
    var inlineResetRevision by remember { mutableIntStateOf(0) }
    var importedPreview by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var redoDraft by remember { mutableStateOf<Pair<NovelAiStudioDraft, NovelAiStudioDraft>?>(null) }
    var viewing by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingReuse by remember { mutableStateOf<Triple<NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage, NovelAiHistoryApplyMode>?>(null) }
    var imageError by remember { mutableStateOf("") }
    var authRevision by remember { mutableStateOf(0) }
    val authentication by produceState(DesktopDesignAuthentication(), draft?.aiDesignModelId, auxiliary, authRevision) { value = controller.designAuthentication() }
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
    LaunchedEffect(state.results) {
        selectedResult = desktopSelectNewResult(selectedResult, seenResults, state.results)
        seenResults = state.results
    }
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
                        StudioAction("复制正向 Prompt", icon = DesktopAppIcons.Copy) { desktopCopyText(d.copyPositivePrompt()) }
                        StudioAction("粘贴覆盖", icon = DesktopAppIcons.Copy) { scope.launch {
                            val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return@launch
                            controller.replace { NovelAiStudioPromptClipboard.apply(text, it) }
                        } }
                        StudioAction("清空", style = StudioActionStyle.TERTIARY) { scope.launch { controller.clearPrompts() } }
                        StudioToggle("中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    }
                    SearchableChoice("导入角色卡 Prompt", cards.map { it.id }, d.importedCharacterCardId, { id -> cards.first { it.id == id }.name }) { id -> scope.launch { controller.importCard(id) } }
                    StatusText("填充画风与基础负面词；角色 Prompt 仅供 AI 设计参考，不参与实际生图")
                    StudioDisclosure("画风", d.stylePrompt.lineSequence().firstOrNull().orEmpty().take(48)) {
                        StudioPromptField("画风", d.stylePrompt, controller.infrastructure, translation) { text -> edit { it.copy(stylePrompt = text) } }
                    }
                    StudioDisclosure("基础 Prompt", initiallyOpen = true) {
                        StudioPromptField("基础 Prompt", d.basePrompt, controller.infrastructure, translation) { text -> edit { it.copy(basePrompt = text) } }
                    }
                    StudioDisclosure("补充", initiallyOpen = d.extraPrompt.isNotBlank()) {
                        StudioPromptField("补充", d.extraPrompt, controller.infrastructure, translation) { text -> edit { it.copy(extraPrompt = text) } }
                    }
                    StudioDisclosure("基础负面 Prompt") {
                        StudioPromptField("基础负面 Prompt", d.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(negativePrompt = text) } }
                    }
                    DesktopStudioCharacters(d, controller.infrastructure, translation, ::edit)
                }
                "参数" -> {
                    StudioGenerationSettings(d, ::edit)
                }
                "设计设置" -> {
                        StudioToggle("V5 自然语言模式", d.aiDesignNaturalLanguageMode) { edit { it.copy(aiDesignNaturalLanguageMode = !it.aiDesignNaturalLanguageMode) } }
                        DesktopDesignModelChoice(d.aiDesignModelId, models, authentication.model) { id ->
                            edit { it.copy(aiDesignModelId = id) }
                        }
                        DesktopDesignField("额外要求", d.extraRequirement) { value -> scope.launch { controller.setDesignRequirement(value) } }
                        StudioAction("完成设置") { auxiliary = "AI 设计" }
                    }
                "设计历史" -> {
                    conversations.forEach { conversation ->
                        StudioActions {
                            StudioAction("${if (conversation.id == currentDesignId) "● " else ""}${conversation.title}", enabled = !busy) { scope.launch { controller.switchDesign(conversation.id); auxiliary = "AI 设计" } }
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
    Column(Modifier.fillMaxSize().desktopImageIngress(!busy, { imageError = "图片导入未完成；当前图片保留" }) { input -> imageAction {
        val file = input.paths.firstOrNull() ?: input.raster?.let { bytes ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.nio.file.Files.createTempFile("ccb-studio-clipboard-", ".png").also { java.nio.file.Files.write(it, bytes); clipboardFiles.add(it) }
            }
        }
        if (file != null) { currentImage = file; selectedResult = null; auxiliary = "当前图片"; controller.clearReverseCandidate() }
    } }.background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioAccountCluster(d.selectedModel, state.accountUi)
        StudioActions {
            StudioAction("刷新", enabled = !state.accountUi.loading, icon = DesktopAppIcons.Refresh, style = StudioActionStyle.TERTIARY) { scope.launch { controller.refreshAccount() } }
            StudioAction("AI 设计", icon = DesktopAppIcons.Chat) { auxiliary = "AI 设计" }
            StudioAction("图像引导", icon = DesktopAppIcons.Star) { auxiliary = "图像引导" }
            StudioAction("导入图片", icon = DesktopAppIcons.ImageAdd, onClick = ::pickImage)
            StudioAction("历史", icon = DesktopAppIcons.History) { auxiliary = "历史" }
            StudioAction("设置", icon = DesktopAppIcons.Settings) { auxiliary = "设置" }
        }
        if (state.status.isNotBlank()) StatusText(state.status.takeLast(300))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val prompt: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    section("Prompt")
                    StudioDisclosure("生成设置", d.activeSettings.sizeTier.displayName, initiallyOpen = true) { section("参数") }
                }
            }
            val narrow = maxWidth < 900.dp
            val width = maxWidth.value
            val resultPaths = desktopStudioFilmstrip(state.results, history)
            val owned = selectedResult?.takeIf { it in resultPaths } ?: if (currentImage == null) resultPaths.firstOrNull() else null
            val shownPath = owned?.let(controller.resources::resolveOwnedReference) ?: currentImage
            val intermediate = state.preview.takeIf { busy }
            val showingIntermediate = intermediate != null && resultMode != "仅缩略图"
            fun preview() { if (owned != null) { viewing = resultPaths; viewingIndex = resultPaths.indexOf(owned) } else importedPreview = shownPath }
            val result: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().border(1.dp, DesktopBootstrapColors.border).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioActions {
                        if (!narrow) CompactChoice("预览", listOf("展开预览", "仅缩略图", "预览专注"), resultMode, { it }) { resultMode = it }
                        DesktopStudioPreviewActions(shownPath != null, showingIntermediate, resultMode != "仅缩略图",
                            ::preview, { inlineResetRevision++ }, { currentImage = shownPath; auxiliary = "当前图片" })
                    }
                    if (resultMode != "仅缩略图" || narrow) Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (showingIntermediate) {
                            val bytes = requireNotNull(intermediate)
                            DesktopOwnedImage("intermediate-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxSize())
                        } else if (shownPath != null) DesktopImageZoomSurface(shownPath.toString(),
                            { java.nio.file.Files.readAllBytes(shownPath) },
                            Modifier.fillMaxSize().semantics { contentDescription = "Studio 内联图片预览" }, inlineResetRevision)
                        else Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.muted), contentAlignment = androidx.compose.ui.Alignment.Center) { StatusText("导入图片或生成后，在这里预览与复用") }
                    }
                    if (!narrow) {
                        if (resultMode == "仅缩略图") LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(resultPaths, key = { it }) { path -> DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(112.dp)
                                .border(if (owned == path) 3.dp else 1.dp, DesktopBootstrapColors.primary).clickable { selectedResult = path }) }
                        } else StudioFilmstrip(resultPaths, owned, controller.resources::readBytes) { selectedResult = it }
                        if (!showingIntermediate && owned != null) history.firstOrNull { entry -> entry.images.any { it.path == owned } }?.let { entry ->
                            val image = entry.images.first { it.path == owned }
                            StudioActions {
                                StudioAction("新种子重绘") { reuse(entry, image, NovelAiHistoryApplyMode.NEW_SEED) }
                                StudioAction("复用 Seed ${image.seed}") { reuse(entry, image, NovelAiHistoryApplyMode.SEED_ONLY) }
                            }
                        }
                        StudioAction(desktopGuidanceLabel(d.imageGuidance, d.selectedModel)) { auxiliary = "图像引导" }
                    }
                }
            }
            if (!narrow) Row(Modifier.fillMaxSize()) {
                if (resultMode != "预览专注") {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp)) { prompt() }
                    if (resultMode == "仅缩略图") Box(Modifier.width(12.dp).fillMaxHeight()
                        .semantics { contentDescription = "固定缩略图栏分隔线" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(DesktopBootstrapColors.border))
                    } else Box(Modifier.width(12.dp).fillMaxHeight()
                        .semantics { contentDescription = "调整预览宽度" }
                        .pointerInput(width) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                resultFraction = desktopStudioResizeFraction(resultFraction, delta.x, density, width)
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(DesktopBootstrapColors.primary))
                    }
                }
                Box(if (resultMode == "预览专注") Modifier.fillMaxSize() else Modifier.width((width * if (resultMode == "仅缩略图") .25f else resultFraction).dp).fillMaxHeight()) { result() }
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopStudioCompactResult(shownPath, intermediate, compactExpanded,
                    { compactExpanded = !compactExpanded }, ::preview,
                    { if (shownPath != null) { currentImage = shownPath; auxiliary = "当前图片" } })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { prompt() }
            }
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
        BootstrapButton(desktopGenerateLabel(busy, state.credentialConfigured, cost, state.status), enabled = busy || desktopCanGenerate(state, d, busy)) {
            if (busy) controller.stop() else scope.launch { controller.generate() }
        }
        }
    }
    auxiliary?.let { surface ->
        DesktopImageToolWindow(surface, { auxiliary = null },
            width = when (surface) { "设置", "设计设置" -> 560.dp; "图像引导" -> 680.dp; else -> 980.dp },
            onActivate = { authRevision++ }) {
                StudioActions { StudioAction(if (surface == "设计历史" || surface == "设计设置") "返回 AI 设计" else "返回 Studio") { auxiliary = if (surface == "设计历史" || surface == "设计设置") "AI 设计" else null } }
                if (surface == "AI 设计") DesktopDesignConversation(controller, d, busy, authentication,
                    onSettings = { auxiliary = "设计设置" }, onHistory = { auxiliary = "设计历史" },
                    onModelSettings = { auxiliary = null; onModelSettings(authentication.modelId) }, onApplied = { auxiliary = null })
                else if (surface == "历史") Box(Modifier.weight(1f).fillMaxWidth()) {
                    DesktopStudioHistory(controller, onApply = ::reuse,
                        onPreview = { paths, index -> viewing = paths; viewingIndex = index },
                        onUse = { path, target -> scope.launch { controller.useHistoryImage(path, target); auxiliary = "图像引导" } })
                }
                else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (surface == "设置") {
                    StudioActions {
                        StudioAction("检查 Danbooru 词库更新", enabled = !busy) { scope.launch { controller.checkCatalog() } }
                        if (state.catalogUpdate != null) StudioAction("下载并更新词库", enabled = !busy) { scope.launch { controller.installCatalog() } }
                    }
                        StudioToggle("复制时忽略画风", d.copyPositivePromptIgnoreStyle) { edit { it.copy(copyPositivePromptIgnoreStyle = !it.copyPositivePromptIgnoreStyle) } }
                        StudioToggle("本地中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    } else if (surface == "当前图片") {
                        currentImage?.let { path ->
                            DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxWidth().height(260.dp).clickable { importedPreview = path })
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
                            StudioDisclosure("Enhance / Upscale") {
                                DesktopImagePostProcessPanel(path, controller) { bytes -> imageAction {
                                    val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        java.nio.file.Files.createTempFile("ccb-postprocess-", ".png").also { java.nio.file.Files.write(it, bytes); clipboardFiles.add(it) }
                                    }
                                    currentImage = target; selectedResult = null; controller.clearReverseCandidate()
                                } }
                            }
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
    imageTools?.let { DesktopImageToolsDialog(it, controller.picker, { imageTools = null }, onProcessedImage = { bytes -> imageAction {
        val copy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.nio.file.Files.createTempFile("ccb-image-tools-", ".png").also { java.nio.file.Files.write(it, bytes); clipboardFiles.add(it) }
        }
        currentImage = copy; selectedResult = null; auxiliary = "当前图片"; controller.clearReverseCandidate()
    } }) }
    guidanceEditor?.let { DesktopImageToolsDialog(it.first, controller.picker, { guidanceEditor = null }, controller::applyGuidanceEdit, it.second, it.third) }
    metadataFile?.let { path -> androidx.compose.ui.window.DialogWindow(onCloseRequest = { metadataFile = null }, title = "选择导入的元数据") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusText(path.fileName.toString())
            StudioToggle("正面 Prompt", metadataSelection.positivePrompt) { metadataSelection = metadataSelection.copy(positivePrompt = !metadataSelection.positivePrompt) }
            StudioToggle("负面 Prompt", metadataSelection.negativePrompt) { metadataSelection = metadataSelection.copy(negativePrompt = !metadataSelection.negativePrompt) }
            CompactChoice("角色 Prompt", NovelAiCharacterImportMode.entries, metadataSelection.characterPrompts, { it.displayName }) { metadataSelection = metadataSelection.copy(characterPrompts = it) }
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

internal fun desktopStudioResizeFraction(current: Float, deltaPx: Float, density: Float, widthDp: Float): Float =
    (current - deltaPx / density / widthDp).coerceIn(.32f, .68f)

@Composable
internal fun DesktopStudioPreviewActions(hasSavedImage: Boolean, showingIntermediate: Boolean, zoomActive: Boolean,
    onViewer: () -> Unit, onReset: () -> Unit, onImageActions: () -> Unit) {
    if (showingIntermediate) {
        StatusText("生成中 · 未保存预览")
        if (hasSavedImage) StatusText("先前选择的图片未被本次生成替换")
    } else if (hasSavedImage) {
        StatusText("已保存图片")
        StudioAction("打开预览", onClick = onViewer)
        if (zoomActive) StudioAction("适应 / 重置", onClick = onReset)
        StudioAction("图像操作 / 用作", onClick = onImageActions)
    }
}

/** Narrow Studio keeps only current-image essentials above the Prompt. */
@Composable
internal fun DesktopStudioCompactResult(path: java.nio.file.Path?, intermediate: ByteArray?, expanded: Boolean,
    onExpand: () -> Unit, onViewer: () -> Unit, onImageActions: () -> Unit) {
    var resetRevision by remember(path) { mutableIntStateOf(0) }
    val showingIntermediate = intermediate != null
    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(112.dp).background(DesktopBootstrapColors.muted)
                .clickable(enabled = path != null || showingIntermediate) { onExpand() },
                contentAlignment = Alignment.Center) {
                when {
                    intermediate != null -> DesktopOwnedImage("intermediate-${intermediate.contentHashCode()}",
                        { intermediate }, Modifier.fillMaxSize())
                    path != null -> DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxSize())
                    else -> StatusText("暂无图片")
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusText(if (showingIntermediate) "生成中 · 未保存预览" else "当前图片")
                if (path != null || showingIntermediate) {
                    StudioAction(if (expanded) "收起预览" else "展开预览", onClick = onExpand)
                    if (showingIntermediate) {
                        if (path != null) StatusText("先前选择的图片未被本次生成替换")
                    } else StudioActions {
                        StudioAction("打开预览", onClick = onViewer)
                        StudioAction("图像操作 / 用作", onClick = onImageActions)
                    }
                } else StatusText("导入图片或生成后查看结果")
            }
        }
        if (expanded && showingIntermediate) DesktopOwnedImage("intermediate-${requireNotNull(intermediate).contentHashCode()}",
            { requireNotNull(intermediate) }, Modifier.fillMaxWidth().height(320.dp))
        else if (expanded && path != null) {
            StudioAction("适应 / 重置") { resetRevision++ }
            DesktopImageZoomSurface(path.toString(), { java.nio.file.Files.readAllBytes(path) },
                Modifier.fillMaxWidth().height(320.dp).semantics { contentDescription = "Studio 内联图片预览" }, resetRevision)
        }
    }
}

@Composable
internal fun DesktopDesignModelChoice(selected: String?, models: List<com.example.chatbar.data.local.entity.ModelConfig>,
    effectiveName: String, onSelect: (String?) -> Unit) {
    val t = LocalDesktopUiStrings.current
    val choices = models.map { DesktopPrimaryChoice(it.id, it.displayName) }
    val options = listOf<String?>(null) + choices.map { it.id } +
        listOfNotNull(selected?.takeUnless { id -> choices.any { it.id == id } })
    SearchableChoice("设计模型", options, selected,
        { id -> desktopDesignModelChoiceLabel(id, choices, effectiveName, t) }, onSelect)
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
    StudioAction("$label · ${options.indexOf(selected).takeIf { it >= 0 }?.let { text(options[it]) } ?: "选择"} ▾") { open = true; query = "" }
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
