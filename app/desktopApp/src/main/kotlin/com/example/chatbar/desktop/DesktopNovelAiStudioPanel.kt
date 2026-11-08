package com.example.chatbar.desktop

import androidx.compose.foundation.*
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
import com.example.chatbar.desktop.security.identity
import com.example.chatbar.desktop.security.DesktopAccountService
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopNovelAiStudioPanel(controller: DesktopNovelAiStudioController,
    navigation: (@Composable () -> Unit)? = null,
    captions: (@Composable () -> Unit)? = null,
    diagnostics: (@Composable () -> Unit)? = null,
    chromeRecorder: DesktopChromeLayoutRecorder? = null,
    onDataSettings: (() -> Unit)? = null,
    onModelSettings: (String?) -> Unit = {}) {
    val promptAuthority by controller.draft.collectAsState()
    CompositionLocalProvider(LocalDesktopStudioTextHistories provides controller.textHistories,
        LocalDesktopPromptAnnotationRevision provides (promptAuthority?.promptContentRevision ?: 0L)) {
        DesktopNovelAiStudioContent(controller, navigation, captions, diagnostics, chromeRecorder, onDataSettings, onModelSettings)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopNovelAiStudioContent(controller: DesktopNovelAiStudioController,
    navigation: (@Composable () -> Unit)?, captions: (@Composable () -> Unit)?, diagnostics: (@Composable () -> Unit)?,
    chromeRecorder: DesktopChromeLayoutRecorder?, onDataSettings: (() -> Unit)?, onModelSettings: (String?) -> Unit) {
    val draft by controller.draft.collectAsState()
    val state by controller.state.collectAsState()
    val editHistory by controller.repository.historyAvailability.collectAsState()
    val accounts = LocalDesktopApiAccounts.current
    val accountRevision = accounts?.store?.revision?.collectAsState()?.value
    val openedAccountService = accounts?.opened?.collectAsState()?.value
    var accountReadRetry by remember(controller) { mutableIntStateOf(0) }
    val accountRead = remember(accounts, accountRevision, accountReadRetry) {
        readDesktopStudioAccountIdentity { accounts?.store?.identity(DesktopAccountService.NOVELAI) }
    }
    val accountIdentity = (accountRead as? DesktopStudioAccountRead.Available)?.identity
    var usageRevision by remember(controller) { mutableStateOf(accountIdentity) }
    var processedReadRetry by remember(controller) { mutableIntStateOf(0) }
    var accountProblemDismissed by remember(controller, accountRevision, accountReadRetry) { mutableStateOf(false) }
    LaunchedEffect(controller, accounts, accountRevision, accountReadRetry) {
        val retryRequested = processedReadRetry != accountReadRetry
        processedReadRetry = accountReadRetry
        if (accountRead == DesktopStudioAccountRead.Unavailable) controller.invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE)
        else {
            if (usageRevision != accountIdentity) { usageRevision = accountIdentity; controller.invalidateAccount() }
            if (retryRequested) controller.refreshAccount()
        }
    }
    val history by controller.history.collectAsState(emptyList())
    val conversations by controller.designRepository.conversations.collectAsState()
    val currentDesignId by controller.designRepository.currentConversationId.collectAsState()
    var translation by remember { mutableStateOf(false) }
    val tasks by controller.taskEntries.collectAsState()
    val busy = tasks.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }
    val scope = rememberCoroutineScope()
    val accountProblemShowing = openedAccountService == null && !accountProblemDismissed &&
        (accountRead == DesktopStudioAccountRead.Unavailable || state.accountUi.error == STUDIO_ACCOUNT_READ_FAILURE)
    if (accountProblemShowing) {
        DesktopStudioCredentialProblem(accounts != null,
            onRetry = { accountReadRetry++ },
            onSettings = { accountProblemDismissed = true; accounts?.opened?.compareAndSet(null, DesktopAccountService.NOVELAI) },
            onDismiss = { accountProblemDismissed = true })
    }
    var imageTools by remember { mutableStateOf<ByteArray?>(null) }
    val reverseSceneEditor = remember(controller) { DesktopReverseSceneEditorPresentation() }
    reverseSceneEditor.editor?.let { editor ->
        DesktopReverseSceneEditor(editor, busy, { controller.applyReverseSceneEdit(editor, it) }, reverseSceneEditor::close,
            activationRevision = reverseSceneEditor.activationRevision)
    }
    var metadataFile by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var metadataPreview by remember { mutableStateOf<NovelAiStudioPngMetadata?>(null) }
    var metadataSelection by remember { mutableStateOf(NovelAiStudioMetadataSelection()) }
    var reverseSource by remember { mutableStateOf<ByteArray?>(null) }
    var viewingIndex by remember { mutableStateOf(0) }
    var guidanceEditor by remember { mutableStateOf<Triple<ByteArray, ByteArray?, NovelAiFocusedInpaintRegion?>?>(null) }
    var auxiliary by remember { mutableStateOf<String?>(null) }
    var auxiliaryActivation by remember { mutableIntStateOf(0) }
    fun openAuxiliary(surface: String) { auxiliary = surface; auxiliaryActivation++ }
    var showingPreset by remember { mutableStateOf(false) }
    var presetActivation by remember { mutableIntStateOf(0) }
    var showingFullPrompt by remember { mutableStateOf(false) }
    DesktopDesignToolLifecycle(controller, auxiliary in setOf("AI 设计", "设计设置", "设计历史"))
    var currentImage by remember { mutableStateOf<java.nio.file.Path?>(null) }
    val clipboardFiles = remember { mutableListOf<java.nio.file.Path>() }
    val postProcessImages = remember { mutableStateListOf<java.nio.file.Path>() }
    val latestBusy by rememberUpdatedState(busy)
    DisposableEffect(controller) { onDispose { if (!latestBusy) clipboardFiles.forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } } } }
    var selectedResult by remember { mutableStateOf<String?>(null) }
    var seenResults by remember { mutableStateOf<List<String>>(emptyList()) }
    val workspace = remember { DesktopStudioWorkspaceState() }
    var inlineResetRevision by remember { mutableIntStateOf(0) }
    var importedPreview by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var viewing by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingReuse by remember { mutableStateOf<Triple<NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage, NovelAiHistoryApplyMode>?>(null) }
    var imageError by remember { mutableStateOf("") }
    var authRevision by remember { mutableStateOf(0) }
    LaunchedEffect(controller) { controller.load(); translation = controller.translationEnabled() }
    fun edit(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) {
        val boundary = controller.textHistories.takeBoundary()
        scope.launch { controller.editFromField(boundary, change) }
    }
    fun imageAction(work: suspend () -> Unit) { scope.launch {
        try { imageError = ""; work() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { imageError = "图片操作未完成；来源保持不变，请检查文件" }
    } }
    val d = draft
    if (d == null) {
        DesktopStudioWorkspace(workspace, navigation = navigation, captions = captions, chromeRecorder = chromeRecorder,
            compact = {}, editor = { StatusText("正在加载 Studio…") }, footer = {}, preview = {})
        return
    }
    val cards by produceState(emptyList<com.example.chatbar.data.local.entity.CharacterCard>(), controller) { value = controller.availableCards() }
    var modelReadRetry by remember(controller) { mutableIntStateOf(0) }
    var modelProblemDismissed by remember(controller, accountRevision, modelReadRetry) { mutableStateOf(false) }
    val studioModels = rememberDesktopStudioModels(controller, d.aiDesignModelId, accountRevision, modelReadRetry, authRevision, auxiliary)
    val modelCatalog = studioModels.catalog
    val authentication = studioModels.authentication
    val models = (modelCatalog as? DesktopStudioModelCatalog.Available)?.models.orEmpty()
    val modelReadFailure = (modelCatalog as? DesktopStudioModelCatalog.Unavailable)?.failure
    if (modelReadFailure != null && !modelProblemDismissed && !accountProblemShowing && openedAccountService == null) {
        DesktopStudioModelProblem(modelReadFailure,
            onRetry = { modelReadRetry++ },
            onModelSettings = { modelProblemDismissed = true; onModelSettings(null) },
            onDataSettings = onDataSettings?.let { { modelProblemDismissed = true; it() } },
            onDismiss = { modelProblemDismissed = true })
    }
    val countingPrompt = NovelAiV5TextPromptPolicy.prepareForRequest(d.toPromptPlan(), d.activeSettings)
    val usage by produceState<NovelAiPromptTokenUsage?>(null, countingPrompt, d.selectedModel) {
        value = controller.infrastructure.countTokens(countingPrompt, d.selectedModel)
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
            currentImage = it; selectedResult = null; openAuxiliary("当前图片"); controller.clearReverseCandidate()
        }
    } }
    val guidanceImages: @Composable () -> Unit = {
        DesktopStudioGuidanceImages(d.imageGuidance, d.selectedModel, controller.resources::readBytes,
            onPreview = { asset -> imageAction {
                controller.resources.readBytes(asset.path)
                importedPreview = controller.resources.resolveOwnedReference(asset.path)
            } },
            onReplace = { binding -> imageAction {
                controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp")))?.let {
                    controller.importGuidance(it, binding.target, binding.vibeId, preserveMode = true, expectedAsset = binding.asset)
                }
            } },
            onRemove = { binding -> edit { it.copy(imageGuidance = desktopRemoveGuidanceImage(it.imageGuidance, binding)) } },
            onSettings = { openAuxiliary("图像引导") }, editingEnabled = !busy)
    }
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
                        if (controller.generationPresetAvailable) StudioAction("生成预设…", enabled = !busy) {
                            presetActivation++; showingPreset = true
                        }
                        StudioAction("完整 Prompt…", enabled = !busy) { showingFullPrompt = true }
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
                        if (d.activeSettings.isMedium) StatusText("Medium 使用固定负面词；已保存的自定义负面词将在 High 下恢复。")
                        else StudioPromptField("基础负面 Prompt", d.negativePrompt, controller.infrastructure, translation) { text -> edit { it.copy(negativePrompt = text) } }
                    }
                    DesktopStudioCharacters(d, controller.infrastructure, translation, ::edit)
                }
                "参数" -> {
                    CompositionLocalProvider(LocalDesktopStudioFieldScope provides "generation:${d.selectedModel}") {
                        StudioGenerationSettings(d, ::edit)
                    }
                }
                "设计设置" -> {
                        StudioToggle("V5 自然语言模式", d.aiDesignNaturalLanguageMode) { edit { it.copy(aiDesignNaturalLanguageMode = !it.aiDesignNaturalLanguageMode) } }
                        DesktopDesignModelChoice(d.aiDesignModelId, models, authentication.model) { id ->
                            edit { it.copy(aiDesignModelId = id) }
                        }
                        DesktopDesignField("额外要求", d.extraRequirement) { value -> edit { it.copy(extraRequirement = value) } }
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
                    guidanceImages()
                    StudioActions { desktopImageUseTargets(d.selectedModel).forEach { target -> StudioAction("导入 · ${target.displayName}") { scope.launch {
                        controller.picker.pickOpenFile(DesktopFileType("图片", listOf("png", "jpg", "jpeg", "webp")))?.let { controller.importGuidance(it, target) }
                    } } } }
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
                    DesktopGuidanceNumericControls(g, ::change)
                    StudioToggle("归一化 Vibe 强度", g.normalizeVibeStrengths) { change { it.copy(normalizeVibeStrengths = !it.normalizeVibeStrengths) } }
                    StudioAction("重置图像引导") { scope.launch { controller.replace { it.copy(imageGuidance = NovelAiImageGuidanceDraft()) } } }
                    g.validationError(d.selectedModel)?.let { StatusText(it) }
                }
                "结果" -> {
                    state.preview?.let { bytes -> DesktopOwnedImage("intermediate-${bytes.contentHashCode()}", { bytes }, Modifier.fillMaxWidth().height(280.dp)) }
                    LazyColumn(Modifier.fillMaxWidth().height(560.dp)) { items(state.results, key = { it }) { path -> DesktopOwnedImage(path, controller.resources::readBytes, Modifier.fillMaxWidth().height(280.dp).desktopViewerEntry(onClick = { viewing = state.results; viewingIndex = state.results.indexOf(path) })) } }
                }
            }
    }
    val resultPaths = desktopStudioFilmstrip(state.results, history)
    val owned = selectedResult?.takeIf { it in resultPaths } ?: if (currentImage == null) resultPaths.firstOrNull() else null
    val shownPath = owned?.let(controller.resources::resolveOwnedReference) ?: currentImage
    val intermediate = state.preview.takeIf { busy }
    fun previewPath(path: java.nio.file.Path?) {
        if (path == null) return
        val index = desktopStudioPreviewIndex(path, resultPaths, controller.resources::resolveOwnedReference)
        if (index >= 0) { viewing = resultPaths; viewingIndex = index }
        else importedPreview = path
    }
    fun preview() { if (owned != null) { viewing = resultPaths; viewingIndex = resultPaths.indexOf(owned) } else previewPath(shownPath) }
    val imageResultActions: @Composable (Boolean) -> Unit = { allowRecipe ->
        DesktopStudioResultActions(shownPath != null && intermediate == null, {
            currentImage = shownPath; openAuxiliary("当前图片"); controller.clearReverseCandidate()
        }) {
            if (allowRecipe && intermediate == null && owned != null)
                history.firstOrNull { entry -> entry.images.any { it.path == owned } }?.let { entry ->
                    val image = entry.images.first { it.path == owned }
                    StudioAction("新种子重绘") { reuse(entry, image, NovelAiHistoryApplyMode.NEW_SEED) }
                    StudioAction("复用 Seed ${image.seed}") { reuse(entry, image, NovelAiHistoryApplyMode.SEED_ONLY) }
                }
        }
    }
    DesktopStudioWorkspace(workspace,
        modifier = Modifier.desktopImageIngress(!busy, { imageError = "图片导入未完成；当前图片保留" }) { input -> imageAction {
        val file = input.paths.firstOrNull() ?: input.raster?.let { bytes ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.nio.file.Files.createTempFile("ccb-studio-clipboard-", ".png").also { java.nio.file.Files.write(it, bytes); clipboardFiles.add(it) }
            }
        }
        if (file != null) { currentImage = file; selectedResult = null; openAuxiliary("当前图片"); controller.clearReverseCandidate() }
    } },
        navigation = navigation, captions = captions, diagnostics = diagnostics, chromeRecorder = chromeRecorder,
        previewActions = { DesktopStudioPrimaryPreviewActions(shownPath != null && intermediate == null,
            { openAuxiliary("历史") }, ::preview) },
        inlinePreviewActions = {
            DesktopStudioPreviewActions(shownPath != null, intermediate != null, true) { inlineResetRevision++ }
        },
        compact = { expand -> Column {
            DesktopStudioCompactResult(shownPath, intermediate, expand, ::preview)
            imageResultActions(false)
        } },
        editor = {
            StudioAccountCluster(d.selectedModel, state.accountUi) {
                StudioAccountActions {
                    key(accountReadRetry) { DesktopStudioAccountControl() }
                    StudioAction("刷新", enabled = !state.accountUi.loading, icon = DesktopAppIcons.Refresh,
                        style = StudioActionStyle.TERTIARY) { accountReadRetry++ }
                }
            }
            if (modelReadFailure != null) {
                StatusText(modelReadFailure.text)
                StudioAction("查看模型读取问题", style = StudioActionStyle.TERTIARY) { modelProblemDismissed = false }
            }
            StudioActions {
                StudioAction("AI 设计", icon = DesktopAppIcons.Chat) { openAuxiliary("AI 设计") }
                StudioAction("图像引导", icon = DesktopAppIcons.Star) { openAuxiliary("图像引导") }
                StudioAction("导入图片", icon = DesktopAppIcons.ImageAdd, onClick = ::pickImage)
                DesktopImageLogEntry(controller.imageDiagnostics, state.generationTaskId)
                StudioAction("设置", icon = DesktopAppIcons.Settings) { openAuxiliary("设置") }
            }
            if (state.status.isNotBlank()) StatusText(state.status.takeLast(300))
            DesktopPostProcessHistoryNotice(controller, state.postProcessError)
            if (desktopGuidanceImageBindings(d.imageGuidance, d.selectedModel).isNotEmpty()) guidanceImages()

            section("Prompt")
            StudioDisclosure("生成设置", d.activeSettings.sizeTier.displayName, initiallyOpen = true) { section("参数") }
        },
        footer = {
            DesktopStudioFooter(
                usage = usage,
                undoEnabled = !busy && editHistory.undo,
                redoEnabled = !busy && editHistory.redo,
                generateLabel = desktopGenerateLabel(busy, state.credentialConfigured, cost, state.status),
                generateEnabled = busy || desktopCanGenerate(state, d, busy),
                onUndo = { scope.launch { controller.undo() } },
                onRedo = { scope.launch { controller.redo() } },
                onGenerate = { if (busy) controller.stop() else scope.launch { controller.generate() } })
        },
        preview = { rail ->
            if (rail) Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    StudioVerticalFilmstrip(resultPaths, owned, controller.resources::readBytes,
                        onPreview = { viewing = resultPaths; viewingIndex = resultPaths.indexOf(it) }) { selectedResult = it }
                }
                imageResultActions(false)
            }
            else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (intermediate != null) DesktopOwnedImage("intermediate-${intermediate.contentHashCode()}", { intermediate }, Modifier.fillMaxSize())
                    else if (shownPath != null) DesktopImageZoomSurface(shownPath.toString(),
                        { java.nio.file.Files.readAllBytes(shownPath) },
                        Modifier.fillMaxSize().semantics { contentDescription = "Studio 内联图片预览" }, inlineResetRevision,
                        onOpenViewer = ::preview, postProcessOutput = shownPath in postProcessImages)
                    else Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.muted), contentAlignment = Alignment.Center) {
                        StatusText("导入图片或生成后，在这里预览与复用")
                    }
                }
                StudioFilmstrip(resultPaths, owned, controller.resources::readBytes,
                    onPreview = { viewing = resultPaths; viewingIndex = resultPaths.indexOf(it) }) { selectedResult = it }
                imageResultActions(true)
            }
        })
    if (showingPreset) DesktopStudioPresetExchange(controller, busy, presetActivation) { showingPreset = false }
    if (showingFullPrompt) DesktopStudioFullPromptExchange(controller, busy) { showingFullPrompt = false }
    auxiliary?.let { surface ->
        DesktopImageToolWindow(surface, { auxiliary = null },
            width = when (surface) { "设置", "设计设置" -> 560.dp; "图像引导" -> 680.dp; else -> 980.dp },
            onActivate = { authRevision++ }, activationRevision = auxiliaryActivation) {
                StudioActions { StudioAction(if (surface == "设计历史" || surface == "设计设置") "返回 AI 设计" else "返回 Studio") { auxiliary = if (surface == "设计历史" || surface == "设计设置") "AI 设计" else null } }
                if (surface == "AI 设计") DesktopDesignConversation(controller, d, busy, authentication,
                    onSettings = { auxiliary = "设计设置" }, onHistory = { auxiliary = "设计历史" },
                    onModelSettings = { auxiliary = null; onModelSettings(authentication.modelId) }, onApplied = { auxiliary = null })
                else if (surface == "历史") Box(Modifier.weight(1f).fillMaxWidth()) {
                    DesktopStudioHistory(controller, onApply = ::reuse,
                        onPreview = { paths, index -> viewing = paths; viewingIndex = index },
                        onUse = { path, target -> scope.launch { if (controller.useHistoryImage(path, target)) openAuxiliary("图像引导") } })
                }
                else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.status.isNotBlank()) StatusText(state.status.takeLast(300))
                    if (imageError.isNotBlank()) StatusText(imageError)
                    if (surface == "设置") {
                    StudioActions {
                        StudioAction("检查 Danbooru 词库更新", enabled = !busy) { scope.launch { controller.checkCatalog() } }
                        if (state.catalogUpdate != null) StudioAction("下载并更新词库", enabled = !busy) { scope.launch { controller.installCatalog() } }
                    }
                        StudioToggle("复制时忽略画风", d.copyPositivePromptIgnoreStyle) { edit { it.copy(copyPositivePromptIgnoreStyle = !it.copyPositivePromptIgnoreStyle) } }
                        StudioToggle("本地中文注释", translation) { translation = !translation; scope.launch { controller.setTranslation(translation) } }
                    } else if (surface == "当前图片") {
                        if (currentImage == null) {
                            StatusText("选择图片，或从生成历史打开图片后使用图像工具。")
                            StudioAction("选择图片", onClick = ::pickImage)
                        }
                        currentImage?.let { path ->
                            DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxWidth().height(260.dp)
                                .desktopViewerEntry(onClick = { previewPath(path) }), postProcessOutput = path in postProcessImages)
                            StatusText(path.fileName.toString())
                            StudioActions {
                                StudioAction("更换图片", onClick = ::pickImage)
                                StudioAction("移除当前图片") { currentImage = null; auxiliary = null; controller.clearReverseCandidate() }
                                StudioAction("元数据") { imageAction {
                                    val preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES)
                                        requireNotNull(DesktopImageMetadata.readStudio(path.toString()))
                                    }
                                    metadataPreview = preview; metadataFile = path; metadataSelection = NovelAiStudioMetadataSelection()
                                } }
                                StudioAction("打码 / 旋转 / 隐私 / 保存") { imageAction { imageTools = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); java.nio.file.Files.readAllBytes(path) } } }
                                StudioAction("复制图片") { imageAction { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
                                    copyDesktopImage(java.nio.file.Files.readAllBytes(path), path, postProcessOutput = path in postProcessImages)
                                } } }
                                StudioAction("反推 Prompt", enabled = !busy) { imageAction {
                                    reverseSource = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES); java.nio.file.Files.readAllBytes(path) }
                                    controller.clearReverseCandidate(); controller.reverseImage(requireNotNull(reverseSource))
                                } }
                                StudioAction("显示文件") { imageAction { ProcessBuilder("explorer.exe", "/select,", path.toString()).start() } }
                            }
                            StudioActions { desktopImageUseTargets(d.selectedModel).forEach { target -> StudioAction("用作 · ${target.displayName}", enabled = !busy) { scope.launch {
                                if (controller.importGuidance(path, target)) openAuxiliary("图像引导")
                            } } } }
                            StudioDisclosure("Enhance / Upscale") {
                                DesktopImagePostProcessPanel(path, controller) { bytes -> imageAction {
                                    val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        java.nio.file.Files.createTempFile("ccb-postprocess-", ".png").also { java.nio.file.Files.write(it, bytes); clipboardFiles.add(it) }
                                    }
                                    postProcessImages.add(target)
                                    currentImage = target; selectedResult = null; controller.clearReverseCandidate()
                                } }
                            }
                            if (imageError.isNotEmpty()) StatusText(imageError)
                            if (busy && !state.reverseBusy) StudioAction("停止当前任务") { controller.stop() }
                            DesktopReversePromptPane(state, busy,
                                onSceneChange = controller::updateReverseSceneDescription,
                                onConfirm = { scope.launch { controller.confirmReverseSceneDescription() } },
                                onStop = controller::stopReverse,
                                onRetry = { scope.launch { reverseSource?.let { controller.reverseImage(it) } } },
                                retryAvailable = reverseSource != null,
                                onApply = { scope.launch { if (controller.applyReverseCandidate()) auxiliary = null } },
                                onDismiss = controller::clearReverseCandidate,
                                naturalLanguage = d.aiDesignNaturalLanguageMode,
                                onExpandScene = { reverseSceneEditor.open(controller::beginReverseSceneEdit) })
                        }
                    } else section(surface)
                }
        }
    }
    pendingReuse?.let { (entry, image, mode) -> androidx.compose.ui.window.DialogWindow(onCloseRequest = { pendingReuse = null }, title = "原始图像引导缺失", icon = desktopImageWindowIcon()) {
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
        currentImage = copy; selectedResult = null; openAuxiliary("当前图片"); controller.clearReverseCandidate()
    } }) }
    guidanceEditor?.let { DesktopImageToolsDialog(it.first, controller.picker, { guidanceEditor = null }, controller::applyGuidanceEdit, it.second, it.third) }
    metadataFile?.let { path -> androidx.compose.ui.window.DialogWindow(onCloseRequest = { metadataFile = null; metadataPreview = null }, title = "选择导入的元数据", icon = desktopImageWindowIcon(),
        state = androidx.compose.ui.window.rememberDialogState(size = rememberDesktopImageWindowSize(560.dp, 620.dp))) {
        DesktopImageDialogBody(Modifier.background(DesktopBootstrapColors.background).padding(16.dp), footer = {
            StudioActions {
                StudioAction("导入") { scope.launch { controller.importMetadata(path, metadataSelection); metadataFile = null; metadataPreview = null } }
                StudioAction("取消") { metadataFile = null; metadataPreview = null }
            }
        }) {
            StatusText(path.fileName.toString())
            DesktopStudioMetadataOptions(metadataPreview, metadataSelection) { metadataSelection = it }
        }
    } }
    if (viewing.isNotEmpty()) DesktopImageViewer(viewing, viewingIndex, controller.resources, controller.picker) { viewing = emptyList() }
    importedPreview?.let { path -> DesktopTransientImagePreview(path.toString(), {
        require(java.nio.file.Files.size(path) <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
        java.nio.file.Files.readAllBytes(path)
    }, postProcessOutput = path in postProcessImages) { importedPreview = null } }
}

@Composable
internal fun DesktopStudioPrimaryPreviewActions(hasPreview: Boolean,
    onHistory: () -> Unit, onViewer: () -> Unit) {
    StudioAction("历史", icon = DesktopAppIcons.History, onClick = onHistory)
    StudioAction("预览", enabled = hasPreview, icon = DesktopAppIcons.ExpandComposer, onClick = onViewer)
}

@Composable
internal fun DesktopStudioResultActions(hasSavedImage: Boolean, onImageActions: () -> Unit,
    recipeActions: @Composable () -> Unit = {}) {
    StudioActions {
        StudioAction("图像操作 / 用作", enabled = hasSavedImage, onClick = onImageActions)
        recipeActions()
    }
}

@Composable
internal fun DesktopStudioPreviewActions(hasSavedImage: Boolean, showingIntermediate: Boolean, zoomActive: Boolean,
    onReset: () -> Unit) {
    if (showingIntermediate) {
        StatusText("生成中 · 未保存预览")
        if (hasSavedImage) StatusText("先前选择的图片未被本次生成替换")
    } else if (hasSavedImage && zoomActive) StudioAction("适应 / 重置", onClick = onReset)
}

/** The compact image remains an entry to the inline pane; primary actions share the toolbar above. */
@Composable
internal fun DesktopStudioCompactResult(path: java.nio.file.Path?, intermediate: ByteArray?,
    onExpand: () -> Unit, onViewer: () -> Unit) {
    val showingIntermediate = intermediate != null
    Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp)
        .semantics { contentDescription = "Studio 当前图片摘要" }, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(88.dp).background(DesktopBootstrapColors.muted)
            .semantics { contentDescription = "显示图片面板" }
            .desktopViewerEntry(enabled = path != null || showingIntermediate, onClick = onExpand,
                onOpen = { if (showingIntermediate) onExpand() else if (path != null) onViewer() }),
            contentAlignment = Alignment.Center) {
            when {
                intermediate != null -> DesktopOwnedImage("intermediate-${intermediate.contentHashCode()}", { intermediate }, Modifier.fillMaxSize())
                path != null -> DesktopOwnedImage(path.toString(), { java.nio.file.Files.readAllBytes(path) }, Modifier.fillMaxSize())
                else -> StatusText("暂无图片")
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusText(if (showingIntermediate) "生成中 · 未保存预览" else "当前图片")
            if (showingIntermediate && path != null) StatusText("先前选择的图片未被本次生成替换")
            else if (!showingIntermediate && path == null) StatusText("导入图片或生成后查看结果")
        }
    }
}

@Composable
internal fun DesktopDesignModelChoice(selected: String?, models: List<com.example.chatbar.data.local.entity.ModelConfig>,
    effectiveName: String, onSelect: (String?) -> Unit) {
    val t = LocalDesktopUiStrings.current
    val choices = models.map { DesktopPrimaryChoice(it.id, it.desktopAccountLabel()) }
    val options = listOf<String?>(null) + choices.map { it.id } +
        listOfNotNull(selected?.takeUnless { id -> choices.any { it.id == id } })
    SearchableChoice("设计模型", options, selected,
        { id -> desktopDesignModelChoiceLabel(id, choices, effectiveName, t) }, onSelect)
}

@Composable
internal fun StudioField(label: String, value: String, onChange: (String) -> Unit) {
    val editor = rememberStudioTextHistory(label, value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(editor.value, { editor.edit(it, onChange) }, Modifier.fillMaxWidth()
            .studioTextHistoryKeys(editor, onChange).border(1.dp, DesktopBootstrapColors.border)
            .background(DesktopBootstrapColors.input).padding(9.dp), cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioActions(content: @Composable RowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
@Composable
internal fun StudioToggle(label: String, selected: Boolean, action: () -> Unit) = StudioSwitch(label, selected, action = action)
@Composable
internal fun <T> SearchableChoice(label: String, options: List<T>, selected: T?, text: (T) -> String, choose: (T) -> Unit) {
    if (!studioUsesSearchDialog(options.size)) { CompactChoice(label, options, selected, text, choose); return }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    StudioAction("$label · ${options.indexOf(selected).takeIf { it >= 0 }?.let { text(options[it]) } ?: "选择"} ▾") { open = true; query = "" }
    if (open) androidx.compose.ui.window.DialogWindow(onCloseRequest = { open = false }, title = label, icon = desktopImageWindowIcon(), state = androidx.compose.ui.window.rememberDialogState(width = 520.dp, height = 560.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioField("搜索", query) { query = it }
            LazyColumn(Modifier.weight(1f)) { items(options.filter { text(it).contains(query, ignoreCase = true) }) { item ->
                StudioAction("${if (item == selected) "✓ " else ""}${text(item)}") { choose(item); open = false }
            } }
            StudioAction("取消") { open = false }
        }
    }
}
