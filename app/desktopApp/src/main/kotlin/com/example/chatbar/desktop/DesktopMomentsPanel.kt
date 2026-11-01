@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal fun DesktopMomentsPanel(controller: DesktopMomentsController) {
    val state by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    val colors = DesktopBootstrapColors; val clipboard = LocalClipboardManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var choosingCard by remember { mutableStateOf(false) }
    var debugGeneration by remember { mutableStateOf(false) }
    var showingDebug by remember { mutableStateOf(false) }
    var confirmingResume by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var recoveringTask by remember { mutableStateOf<MomentTask?>(null) }
    var editing by remember { mutableStateOf<MomentPost?>(null) }
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
    LaunchedEffect(state.posts.maxOfOrNull { it.generatedAt }) { controller.markViewed() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DesktopManagementHeading("朋友圈")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BootstrapButton("生成动态", enabled = state.enabled && !state.busy) { debugGeneration = false; choosingCard = true }
                BootstrapButton(if (state.scheduling) "暂停本机调度" else "恢复本机调度", secondary = true) {
                    if (state.scheduling) act { controller.pause() } else confirmingResume = true
                }
                BootstrapButton("计划与记录", secondary = true) { plans = true }
                BootstrapButton("刷新", secondary = true) { act { controller.refresh() } }
                if (controller.diagnostics != null) BootstrapButton("请求日志", secondary = true) { debugOperation = "JUDGE" }
                BootstrapButton("调试生成", enabled = state.enabled && !state.busy, variant = DesktopActionVariant.GHOST) {
                    debugGeneration = true; choosingCard = true
                }
                if (state.debug != null) BootstrapButton("本次调试交换", variant = DesktopActionVariant.GHOST) { showingDebug = true }
                if (state.busy) BootstrapButton("停止生成", secondary = true, onClick = controller::stop)
            }
            if (state.progress.isNotBlank()) StatusText(state.progress)
            if (state.streamedText.isNotBlank()) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                DesignTextDisclosure("本次生成输出", state.streamedText)
            }
            if (state.notice.isNotBlank()) StatusText(state.notice)
            (error ?: state.error)?.let { StatusText(it, colors.destructive) }
            if (state.posts.isEmpty()) StatusText("暂无动态。开启后不会因导入旧计划而自动生成。")
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.posts, key = { it.id }) { post ->
                    Row(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(10.dp)).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val avatar = post.senderAvatar; val resources = controller.resources
                        if (!avatar.isNullOrBlank() && resources != null) DesktopOwnedImage(avatar, resources::readBytes,
                            Modifier.size(42.dp).clip(CircleShape).clickable { viewing = listOf(avatar); viewIndex = 0 }, crop = true)
                        else Box(Modifier.size(42.dp).background(colors.secondary, CircleShape), contentAlignment = Alignment.Center) {
                            StatusText(post.senderName.take(1))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BasicText(post.senderName, style = TextStyle(color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            if (post.isPlaceholder) {
                                StatusText(post.failureReason ?: "生成尚未完成", colors.destructive)
                                BootstrapButton("从已完成阶段重试", enabled = !state.busy, secondary = true) { act { controller.retry(post) } }
                            } else {
                                SelectionContainer { BasicText(post.text, style = TextStyle(color = colors.foreground, fontSize = 14.sp)) }
                                val image = post.imagePath
                                if (!image.isNullOrBlank() && resources != null) DesktopOwnedImage(image, resources::readBytes,
                                    Modifier.sizeIn(maxWidth = 300.dp).fillMaxWidth().aspectRatio(1f).clickable {
                                        viewing = state.posts.mapNotNull { it.imagePath?.takeIf(String::isNotBlank) }.distinct()
                                        viewIndex = viewing.indexOf(image).coerceAtLeast(0)
                                    }, crop = true)
                                else BootstrapButton("未生成图片 · 点击生成私密配图", enabled = !state.busy,
                                    variant = DesktopActionVariant.SECONDARY) { confirmingImage = post }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusText(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(post.generatedAt)))
                                if (post.isPrivate) StatusText("仅你可见")
                                if (!post.isPlaceholder) {
                                    BootstrapButton("${if (post.userLiked) "已赞" else "赞"} ${post.displayLikeCount}",
                                        variant = DesktopActionVariant.GHOST) { act { controller.like(post.id) } }
                                    DesktopIconAction("编辑动态文字", DesktopAppIcons.Edit) { editing = post; editText = post.text }
                                    DesktopIconAction("复制动态文字", DesktopAppIcons.Copy) { clipboard.setText(AnnotatedString(post.text)) }
                                }
                                DesktopIconAction("删除动态", DesktopAppIcons.Close) { deleting = post }
                            }
                        }
                    }
                }
            }
        }
        if (choosingCard) MomentModal(if (debugGeneration) "选择调试生成的角色卡" else "选择发动态的角色卡", { choosingCard = false }) {
            StatusText(if (debugGeneration) "调试立即生成会记录完整交换，绕过发布判断的拒绝结果；配置NovelAI后会尝试配图，即使自动配图开关关闭。可能产生模型和配图费用。"
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("发布判断", debugOperation == "JUDGE") { debugOperation = "JUDGE" }
                SelectChip("动态文案", debugOperation == "GENERATE") { debugOperation = "GENERATE" }
            }
            DesktopChatDebugPanel(owner, "moments:$debugOperation", { debugOperation = null }, modifier = Modifier.heightIn(min = 300.dp, max = 520.dp))
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
        StatusText("保留原图尺寸；每次请求使用新Seed。成功后只替换图片，正文、点赞与发布时间不变。")
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
