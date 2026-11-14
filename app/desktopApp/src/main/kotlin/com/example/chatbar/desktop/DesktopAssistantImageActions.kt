package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun desktopAssistantImageActionVisible(message: ChatMessage, state: DesktopPrimaryChatState, normalActions: Boolean) =
    normalActions && message.role == MessageRole.ASSISTANT && message.displayContent.isNotBlank() &&
        state.selectedSession?.id == message.sessionId && state.messages.any { it == message }

/** Footer-only actions and source-anchored task state; media stays at its existing message location. */
@Composable
internal fun DesktopAssistantImageActions(message: ChatMessage, state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController, normalActions: Boolean, interactionEnabled: Boolean = true,
    actionBar: @Composable (@Composable () -> Unit) -> Unit = { controls ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() }
    }) {
    if (!normalActions || state.selectedSession?.id != message.sessionId || state.messages.none { it == message }) {
        actionBar({})
        return
    }
    val scope = rememberCoroutineScope()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val running = desktopImageSourceBusy(tasks, message.sessionId, message.id)
    var imageStatus by remember(message.id) { mutableStateOf("") }
    var requirementsOpen by remember(message.id) { mutableStateOf(false) }
    var imageHint by remember(message.id) { mutableStateOf("") }
    var preference by remember(message.id) { mutableStateOf("") }
    var requestedSource by remember(message.id) { mutableStateOf<ChatMessage?>(null) }
    var persistPreference by remember(message.id) { mutableStateOf(false) }
    var batchConfig by remember(message.id) { mutableStateOf(ImageBatchConfiguration()) }
    var confirmed by remember(message.id, batchConfig) { mutableStateOf(false) }
    var previewRefresh by remember(message.id) { mutableStateOf(0) }
    var handledPreviewRefresh by remember(message.id) { mutableStateOf(0) }
    var preview by remember(message.id) { mutableStateOf("") }
    var previewBusy by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(requirementsOpen, batchConfig, previewRefresh) {
        if (requirementsOpen && batchConfig.validationError() == null) {
            previewBusy = true
            val force = previewRefresh != handledPreviewRefresh
            handledPreviewRefresh = previewRefresh
            try { preview = controller.imageRegeneration?.previewManualImageRun(message, batchConfig, force).orEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { preview = "费用预览不可用；开始后仍逐批重新核对，未知时停止图片请求。" }
            finally { previewBusy = false }
        }
    }
    val confirmedPlan = if (confirmed && batchConfig.validationError() == null) batchConfig.freeze(ImageBatchOrigin.CHAT_MANUAL) else null
    LaunchedEffect(message, requirementsOpen) {
        if (requirementsOpen && requestedSource != message) {
            requirementsOpen = false; imageStatus = "来源回复已更改，请重新打开生图计划"
        }
    }
    val generate: (DesktopChatImageRequirements?) -> Unit = { requirements ->
        val source = requestedSource ?: message
        scope.launch {
        try {
            requireNotNull(controller.imageRegeneration).generateFromAssistant(source, requirements)
            imageStatus = "已提交图片任务；忙时排队，可在过程区停止"; requirementsOpen = false
            controller.refreshAfterTerminalTask(message.sessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DesktopChatImagePreflightException) { imageStatus = failure.result.reason }
        catch (_: Exception) { imageStatus = "无法启动图片任务，请检查生图配置与任务状态" }
    } }
    val openRequirements: (Boolean) -> Unit = { savePreference -> scope.launch {
        try {
            val readiness = requireNotNull(controller.imageRegeneration).preflight(message)
            if (readiness != DesktopChatImagePreflight.READY) imageStatus = readiness.reason
            else {
                requestedSource = message; imageHint = ""; preference = state.selectedSession.imagePromptPreference
                persistPreference = savePreference; batchConfig = ImageBatchConfiguration(); confirmed = false; requirementsOpen = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { imageStatus = "无法读取生图配置，请检查后重试" }
    }; Unit }
    actionBar {
        if (desktopAssistantImageActionVisible(message, state, normalActions) && controller.imageRegeneration != null) {
            DesktopChatIconAction("生成图片", DesktopAppIcons.ImageAdd, enabled = !running && interactionEnabled) {
                openRequirements(false)
            }
            DesktopChatIconAction("生图要求", DesktopAppIcons.Settings, enabled = interactionEnabled) {
                openRequirements(true)
            }
        }
    }
    if (imageStatus.isNotBlank()) StatusText(imageStatus)
    controller.imageRegeneration?.let { owner ->
        val batches by owner.batchProgress.collectAsState()
        batches[message.id]?.let { progress ->
            StatusText(progress.label())
            if (running && owner.batchRunning(message.id)) StudioActions {
                if (progress.stage == ImageBatchStage.PAUSED || owner.batchPaused(message.id))
                    BootstrapButton("继续批次") { owner.resumeBatch(message.id) }
                else BootstrapButton("暂停批次", secondary = true) { owner.pauseBatch(message.id) }
                StatusText("暂停在当前批次完成后生效；停止请使用下方任务按钮。")
            }
        }
    }
    val processStates by controller.taskRuntime.imageProgress.states.collectAsState()
    // Runtime history is newest-first. One source process area; dismiss reveals older history.
    desktopImageTasksForMessage(tasks, message).firstOrNull()?.let { task ->
        DesktopChatImageProcessCard(task, processStates[task.taskId], !running,
            onStop = { controller.stop(task.taskId) },
            onRetry = { scope.launch {
                try {
                    controller.taskRuntime.retryImageTask(task.taskId)
                    controller.refreshAfterTerminalTask(message.sessionId)
                } catch (_: Exception) { imageStatus = "无法重试，请检查来源消息、设置与任务状态" }
            } },
            onDismiss = { controller.taskRuntime.dismissImageTask(task.taskId) })
    }
    if (requirementsOpen) DesktopImageToolWindow("生图要求与批次计划", { requirementsOpen = false }, width = 650.dp, height = 740.dp) {
        DesktopChatImageRequirementsForm(imageHint, preference, { imageHint = it }, { preference = it }, running || !interactionEnabled,
            imageStatus, { requirementsOpen = false }, canGenerate = confirmedPlan != null,
            options = {
                DesktopImageBatchOptions(batchConfig, ImageBatchOrigin.CHAT_MANUAL, { batchConfig = it }, confirmed, { confirmed = it })
                StatusText(if (previewBusy) "正在查询费用预览……" else preview)
                BootstrapButton("刷新费用预览", enabled = !previewBusy, secondary = true) { previewRefresh++ }
            }) {
            val latest = if (batchConfig.validationError() == null) batchConfig.freeze(ImageBatchOrigin.CHAT_MANUAL) else null
            if (interactionEnabled && confirmed && latest == confirmedPlan)
                confirmedPlan?.let { generate(DesktopChatImageRequirements(imageHint, preference, persistPreference, it)) }
        }
    }
}

@Composable
internal fun DesktopChatImageRequirementsForm(imageHint: String, preference: String, onHint: (String) -> Unit,
    onPreference: (String) -> Unit, running: Boolean, status: String, onCancel: () -> Unit, onGenerate: () -> Unit) =
    DesktopChatImageRequirementsForm(imageHint, preference, onHint, onPreference, running, status, onCancel,
        canGenerate = true, options = null, onGenerate = onGenerate)

@Composable
internal fun DesktopChatImageRequirementsForm(imageHint: String, preference: String, onHint: (String) -> Unit,
    onPreference: (String) -> Unit, running: Boolean, status: String, onCancel: () -> Unit,
    canGenerate: Boolean, options: (@Composable () -> Unit)? = null, onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioField("图片内容提示", imageHint, onChange = onHint)
        StudioField("生图偏好", preference, onChange = onPreference)
        StatusText("取消不会保存；直接生成使用会话已保存的偏好。")
        options?.invoke()
        if (status.isNotBlank()) StatusText(status)
        }
        StudioActions {
            BootstrapButton("取消", onClick = onCancel)
            BootstrapButton("生成", enabled = !running && canGenerate, onClick = onGenerate)
        }
    }
}
