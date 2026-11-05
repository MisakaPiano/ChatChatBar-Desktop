@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.moment.MomentSender
import com.example.chatbar.domain.moment.MomentSenderOption
import com.example.chatbar.domain.moment.MomentPostEditing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopMomentsSettings(controller: DesktopMomentsController) {
    val state by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var images by remember(state.autoImages) { mutableStateOf(state.autoImages) }
    var minimum by remember(state.minHours) { mutableStateOf(state.minHours.toString()) }
    var maximum by remember(state.maxHours) { mutableStateOf(state.maxHours.toString()) }
    fun act(action: suspend () -> Unit) { scope.launch {
        try { error = null; action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "朋友圈设置未保存，请核对输入和数据状态" }
    } }
    DesktopManagementHeading("朋友圈")
    StudioSwitch("启用朋友圈", state.enabled) { act { controller.setEnabled(!state.enabled) } }
    StatusText("动态与聊天主线分开保存。启用后可手动生成；本机调度需另行确认。应用关闭时不生成。")
    if (state.enabled) {
        StudioSwitch("自动生成配图", images) { images = !images }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { LabeledField("最短间隔（小时）", minimum) { minimum = it } }
            Box(Modifier.weight(1f)) { LabeledField("最长间隔（小时）", maximum) { maximum = it } }
        }
        BootstrapButton("保存朋友圈设置", secondary = true) {
            val min = minimum.toIntOrNull(); val max = maximum.toIntOrNull()
            if (min == null || max == null || min !in 1..24 || max !in min..24) error = "间隔应为1–24小时，最短不超过最长"
            else act { controller.configure(images, min, max) }
        }
        StatusText(if (state.scheduling) "本机调度运行中" else "本机调度已暂停；到朋友圈页可查看计划并明确恢复")
    }
    error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
}

@Composable
internal fun DesktopMomentsPanel(controller: DesktopMomentsController,
    onOpenSession: (String) -> Unit = {}, onOpenCard: (String, Boolean) -> Unit = { _, _ -> }) {
    val state by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    val colors = DesktopBootstrapColors; val clipboard = LocalClipboardManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var showingAlbum by remember { mutableStateOf(false) }
    var focusPostId by remember { mutableStateOf<String?>(null) }
    val timelineScroll = rememberLazyListState()
    var choosingCard by remember { mutableStateOf(false) }
    var debugGeneration by remember { mutableStateOf(false) }
    var showingDebug by remember { mutableStateOf(false) }
    var showingTools by remember { mutableStateOf(false) }
    var confirmingResume by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var recoveringTask by remember { mutableStateOf<MomentTask?>(null) }
    var editing by remember { mutableStateOf<MomentPost?>(null) }
    var versionPostId by remember { mutableStateOf<String?>(null) }
    var removingVersion by remember { mutableStateOf<Pair<MomentPost, String>?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MomentPost?>(null) }
    var viewing by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewIndex by remember { mutableStateOf(0) }
    var debugOperation by remember { mutableStateOf<String?>(null) }
    var confirmingImage by remember { mutableStateOf<MomentPost?>(null) }
    var editingImage by remember { mutableStateOf<Pair<MomentPost, NovelAiImageRegenerationDraft>?>(null) }
    var applyingImage by remember { mutableStateOf<Pair<MomentPost, Boolean>?>(null) }
    fun act(action: suspend () -> Unit) { scope.launch {
        try { error = null; action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "朋友圈操作未完成，请核对当前记录后重试；原数据保留" }
    } }
    fun openLinked(postId: String, cardSettings: Boolean, focusSwitch: Boolean = false) = act {
        val link = controller.linkedTarget(postId, cardSettings)
        if (link.id == null) error = link.error
        else if (cardSettings) onOpenCard(link.id, focusSwitch) else onOpenSession(link.id)
    }
    if (showingAlbum) {
        DesktopMomentAlbum(state.posts, state.cards.associate { it.id to it.name }, controller.resources,
            onClose = { showingAlbum = false }, onPost = { id -> focusPostId = id; showingAlbum = false })
        return
    }
    LaunchedEffect(focusPostId, state.posts) {
        focusPostId?.let { id ->
            val index = com.example.chatbar.domain.moment.MomentAlbumPolicy.timelineIndex(state.posts, id)
            if (index >= 0) timelineScroll.scrollToItem(index) else error = "该动态已删除或不可用"
            focusPostId = null
        }
    }
    LaunchedEffect(state.posts.maxOfOrNull { it.generatedAt }) { controller.markViewed() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize().align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DesktopManagementHeading("朋友圈") }
                StudioAction("生成动态", enabled = state.enabled && !state.busy, icon = DesktopAppIcons.Add) { debugGeneration = false; choosingCard = true }
                DesktopChatIconAction("朋友圈相册", DesktopAppIcons.ImageAdd) { showingAlbum = true }
                DesktopChatIconAction(if (showingTools) "收起操作" else "更多操作", DesktopAppIcons.More) { showingTools = !showingTools }
                if (state.busy) DesktopChatIconAction("停止生成", DesktopAppIcons.Stop, onClick = controller::stop)
            }
            if (showingTools) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BootstrapButton(if (state.scheduling) "暂停本机调度" else "恢复本机调度", secondary = true) {
                    if (state.scheduling) act { controller.pause() } else confirmingResume = true
                }
                BootstrapButton("计划与记录", secondary = true) { plans = true }
                BootstrapButton("刷新", secondary = true) { act { controller.refresh() } }
                if (controller.diagnostics != null) BootstrapButton("请求日志", secondary = true) { debugOperation = "GENERATE" }
                BootstrapButton("调试生成", enabled = state.enabled && !state.busy, variant = DesktopActionVariant.GHOST) {
                    debugGeneration = true; choosingCard = true
                }
                if (state.debug != null) BootstrapButton("本次调试交换", variant = DesktopActionVariant.GHOST) { showingDebug = true }
            }
            if (state.progress.isNotBlank()) StatusText(state.progress)
            if (state.streamedText.isNotBlank()) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                DesignTextDisclosure("本次生成输出", state.streamedText)
            }
            if (state.notice.isNotBlank()) StatusText(state.notice)
            (error ?: state.error)?.let { StatusText(it, colors.destructive) }
            if (state.posts.isEmpty()) StatusText("暂无动态。开启后不会因导入旧计划而自动生成。")
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = timelineScroll) {
                items(state.posts, key = { it.id }) { post ->
                    DesktopMomentFeedCard(post, controller.resources, state.busy,
                        onOpenSession = { openLinked(post.id, false) },
                        onOpenCard = { focusSwitch -> openLinked(post.id, true, focusSwitch) },
                        onPreviewAvatar = { post.senderAvatar?.let { viewing = listOf(it); viewIndex = 0 } },
                        onPreviewImage = {
                            viewing = state.posts.mapNotNull { it.imagePath?.takeIf(String::isNotBlank) }.distinct()
                            viewIndex = viewing.indexOf(post.imagePath).coerceAtLeast(0)
                        },
                        onEdit = { editing = post; editText = post.text },
                        onCopy = { clipboard.setText(AnnotatedString(post.text)) },
                        onLike = { act { controller.like(post.id) } },
                        onRetry = { act { controller.retry(post) } },
                        onGenerateImage = { confirmingImage = post },
                        onRegenerateImage = { act { editingImage = post to controller.imageDraft(post) } },
                        onVersions = { versionPostId = post.id },
                        onSelectVersion = { versionId -> act { controller.selectImageVersion(post, versionId) } },
                        onDelete = { deleting = post })
                }
            }
        }
        versionPostId?.let { id ->
            val post = state.posts.singleOrNull { it.id == id }
            if (post != null) MomentModal("动态图片版本", { versionPostId = null }) {
                val versions = DesktopMomentImageVersions.retained(post)
                StatusText("重新生成保留旧图；选择版本仅更换这条动态当前显示的图片。")
                versions.forEachIndexed { index, version ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusText("${index + 1} · " + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(version.createdAt)) + if (post.imagePath == version.imagePath) " · 当前" else "")
                        BootstrapButton("预览版本 ${index + 1}", secondary = true) {
                            viewing = versions.map { it.imagePath }; viewIndex = index
                        }
                        BootstrapButton("使用版本 ${index + 1}", secondary = post.imagePath != version.imagePath, enabled = !state.busy) {
                            act { controller.selectImageVersion(post, version.id) }
                        }
                        if (versions.size > 1) BootstrapButton("移除版本 ${index + 1}", secondary = true, enabled = !state.busy) {
                            removingVersion = post to version.id
                        }
                    }
                }
            }
        }
        removingVersion?.let { (post, versionId) -> MomentModal("移除图片版本", { removingVersion = null }) {
            StatusText("只从这条动态移除此版本。若它是当前图片，将改用最近保留的版本；其他动态或角色卡仍引用的文件会保留。")
            BootstrapButton("确认移除版本", variant = DesktopActionVariant.DESTRUCTIVE) {
                act { controller.removeImageVersion(post, versionId); removingVersion = null }
            }
        } }
        if (choosingCard) MomentModal(if (debugGeneration) "选择调试生成的角色卡" else "选择发动态的角色卡", { choosingCard = false }) {
            StatusText(if (debugGeneration) "调试立即生成会记录完整交换；配置NovelAI后会尝试配图，即使自动配图开关关闭。可能产生模型和配图费用。"
                else "将按此角色卡最近会话及现有模型设置生成，可能产生模型或配图费用。")
            state.cards.filter { it.momentsEnabled }.forEach { card ->
                BootstrapButton(card.name, enabled = !state.busy, secondary = true) {
                    act { controller.generate(card.id, debugGeneration); choosingCard = false }
                }
            }
        }
        if (confirmingResume) MomentModal("恢复本机调度", { confirmingResume = false }) {
            StatusText("将授权处理当前资料目录中已有的待处理计划，并准备后续计划，可能产生模型及配图费用。正常重开保留本机授权；导入或整体恢复后需要重新确认。中断记录仅能手动重试。")
            BootstrapButton("确认恢复本机调度") { act { controller.resumeScheduling(); confirmingResume = false } }
        }
        if (plans) MomentModal("朋友圈计划与记录", { plans = false }) {
            BootstrapButton("核对已保存动态的任务关联", secondary = true) { act { controller.reconcileSaved() } }
            StatusText("此项不发送AI请求。生成中或失败记录不会自动重试。")
            state.tasks.forEach { task ->
                val status = when (task.status) {
                    com.example.chatbar.data.local.entity.MomentTaskStatus.PENDING -> "待处理"
                    com.example.chatbar.data.local.entity.MomentTaskStatus.RUNNING -> "生成中或待核对"
                    com.example.chatbar.data.local.entity.MomentTaskStatus.COMPLETED -> "已完成"
                    com.example.chatbar.data.local.entity.MomentTaskStatus.SKIPPED -> "未发布"
                    com.example.chatbar.data.local.entity.MomentTaskStatus.FAILED -> "未完成"
                }
                StatusText("${state.cards.firstOrNull { it.id == task.characterCardId }?.name ?: "已删除角色卡"} · $status · " +
                    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(task.scheduledAt)))
                task.failureReason?.let { StatusText(it) }
                if (task.postId != null && state.posts.none { it.id == task.postId } &&
                    task.status in setOf(MomentTaskStatus.RUNNING, MomentTaskStatus.FAILED)) {
                    BootstrapButton("手动重试中断任务", secondary = true, enabled = !state.busy) { recoveringTask = task }
                }
            }
        }
        recoveringTask?.let { task -> MomentModal("重试中断任务？", { recoveringTask = null }) {
            StatusText("这项任务没有已保存的动态，先前请求是否已完成或计费无法确认。确认后会重新请求AI，可能再次产生费用；使用原任务和动态编号，不会自动重试其他记录。")
            BootstrapButton("确认重新请求", enabled = !state.busy) {
                act { controller.retryInterruptedTask(task); recoveringTask = null }
            }
        } }
        editing?.let { post -> MomentModal("编辑动态", { editing = null }) {
            val originalSender = MomentSender(post.senderCharacterId, post.senderName, post.senderAvatar)
            val original = MomentSenderOption("original", originalSender)
            var sender by remember(post.id) { mutableStateOf(original) }
            val people = MomentPostEditing.senderOptions(state.cards.firstOrNull { it.id == post.characterCardId })
            val options = listOf(original) + people
            CompactChoice("发布人物", options, sender, { option -> when (option.key) {
                "original" -> "原发布者 · ${option.sender.name}"
                "card" -> "角色卡 · ${option.sender.name}"
                else -> "人物 ${people.indexOf(option)} · ${option.sender.name}"
            } }) { sender = it }
            LabeledField("正文", editText, multiline = true) { editText = it }
            BootstrapButton("保存", enabled = editText.isNotBlank() && sender.sender.name.isNotBlank()) {
                act { controller.edit(post.id, editText, sender.sender); editing = null }
            }
        } }
        deleting?.let { post -> MomentModal("删除这条动态？", { deleting = null }) {
            StatusText("删除动态记录，仍被其他内容引用的图片会保留；历史任务不会因此自动重试。")
            BootstrapButton("确认删除", variant = DesktopActionVariant.DESTRUCTIVE) { act { controller.delete(post.id); deleting = null } }
        } }
        confirmingImage?.let { post -> MomentModal("生成动态配图", { confirmingImage = null }) {
            StatusText("使用原角色卡与动态图片构思设计并生成图片，正文和点赞保留。此操作可能产生模型及NovelAI费用。")
            BootstrapButton("确认生成", enabled = !state.busy) { act { controller.generateImage(post); confirmingImage = null } }
        } }
        applyingImage?.let { (post, avatar) -> MomentModal(if (avatar) "设为角色卡头像？" else "设为角色卡背景？", { applyingImage = null }) {
            StatusText("将替换这张动态所属角色卡的对应图片，原动态保留。")
            BootstrapButton("确认替换", enabled = !state.busy) { act { controller.useImageForCard(post, avatar); applyingImage = null } }
        } }
        editingImage?.let { (post, draft) -> MomentImageEditModal(draft, !state.busy, { editingImage = null }) { next ->
            act { controller.generateImage(post, next); editingImage = null }
        } }
        val resources = controller.resources
        if (viewing.isNotEmpty() && resources != null) DesktopImageViewer(viewing, viewIndex, resources, controller.picker,
            extraActions = { reference ->
                state.posts.firstOrNull { !it.isPlaceholder && it.imagePath == reference }?.let { post ->
                    ViewerToolbarSeparator()
                    DesktopChatIconAction("编辑Prompt重新生成", DesktopAppIcons.Reset, enabled = !state.busy) {
                        act { val draft = controller.imageDraft(post); viewing = emptyList(); editingImage = post to draft }
                    }
                    DesktopChatIconAction("设为角色卡头像", DesktopAppIcons.Portrait, enabled = !state.busy) {
                        viewing = emptyList(); applyingImage = post to true
                    }
                    DesktopChatIconAction("设为角色卡背景", DesktopAppIcons.Landscape, enabled = !state.busy) {
                        viewing = emptyList(); applyingImage = post to false
                    }
                }
            }) { viewing = emptyList() }
        val owner = controller.diagnostics
        if (showingDebug) MomentModal("本次朋友圈调试交换", { showingDebug = false }) {
            state.debug?.let { result ->
                result.errorMessage?.let { StatusText(it, colors.destructive) }
                result.exchanges.forEachIndexed { index, exchange ->
                    DesktopManagementHeading("${index + 1}. ${exchange.title}")
                    DesignTextDisclosure("输入 ${index + 1}", exchange.input)
                    DesignTextDisclosure("输出 ${index + 1}", exchange.output)
                }
            }
        }
        if (owner != null && debugOperation != null) MomentModal("朋友圈请求日志", { debugOperation = null }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectChip("发布判断", debugOperation == "JUDGE") { debugOperation = "JUDGE" }
                SelectChip("动态文案", debugOperation == "GENERATE") { debugOperation = "GENERATE" }
                SelectChip("动态配图", debugOperation == "IMAGE") { debugOperation = "IMAGE" }
            }
            DesktopChatDebugPanel(owner, "moments:$debugOperation", { debugOperation = null },
                modifier = Modifier.heightIn(min = 300.dp, max = 520.dp), imageGeneration = debugOperation == "IMAGE")
        }
    }
}

@Composable
private fun MomentImageEditModal(original: NovelAiImageRegenerationDraft, enabled: Boolean, close: () -> Unit,
    generate: (NovelAiImageRegenerationDraft) -> Unit) {
    var draft by remember(original) { mutableStateOf(original) }
    var width by remember(original) { mutableStateOf(original.width.toString()) }
    var height by remember(original) { mutableStateOf(original.height.toString()) }
    val w = width.toIntOrNull(); val h = height.toIntOrNull()
    MomentModal("编辑动态配图 Prompt", close) {
        StatusText("保留原图尺寸；每次请求使用新Seed。成功后保存为新版本，由你选择是否使用；正文、点赞与发布时间不变。")
        LabeledField("画风", draft.stylePrompt, multiline = true) { draft = draft.copy(stylePrompt = it) }
        LabeledField("基础 Prompt", draft.baseCaption, multiline = true) { draft = draft.copy(baseCaption = it) }
        LabeledField("负面 Prompt", draft.negativePrompt, multiline = true) { draft = draft.copy(negativePrompt = it) }
        draft.characterPrompts.forEachIndexed { index, character ->
            LabeledField("角色 ${index + 1}", character.prompt, multiline = true) { text ->
                draft = draft.copy(characterPrompts = draft.characterPrompts.mapIndexed { i, c -> if (i == index) c.copy(prompt = text) else c })
            }
            LabeledField("角色 ${index + 1} 负面", character.negativePrompt, multiline = true) { text ->
                draft = draft.copy(characterPrompts = draft.characterPrompts.mapIndexed { i, c -> if (i == index) c.copy(negativePrompt = text) else c })
            }
            BootstrapButton("移除此角色", secondary = true) { draft = draft.removeCharacterPrompt(index) }
        }
        BootstrapButton("添加角色", enabled = draft.characterPrompts.size < NOVEL_AI_MAX_CHARACTER_PROMPTS, secondary = true) { draft = draft.addCharacterPrompt() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { LabeledField("宽度", width) { width = it } }
            Box(Modifier.weight(1f)) { LabeledField("高度", height) { height = it } }
        }
        BootstrapButton("确认使用新Seed生成", enabled = enabled && draft.canRegenerate && w != null && h != null && w > 0 && h > 0) {
            if (w != null && h != null) generate(draft.copy(width = w, height = h))
        }
        BootstrapButton("重置", secondary = true) { draft = original; width = original.width.toString(); height = original.height.toString() }
    }
}

@Composable
private fun MomentModal(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 850.dp).fillMaxWidth().heightIn(max = 780.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopManagementHeading(title) }
                    DesktopIconAction("关闭", DesktopAppIcons.Close, onClick = close)
                }
                content()
            }
        }
    }
}
