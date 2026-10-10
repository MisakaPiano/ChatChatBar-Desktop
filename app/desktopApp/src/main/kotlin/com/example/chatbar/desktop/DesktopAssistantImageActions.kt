package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun desktopAssistantImageActionVisible(message: ChatMessage, state: DesktopPrimaryChatState, normalActions: Boolean) =
    normalActions && message.role == MessageRole.ASSISTANT && message.displayContent.isNotBlank() &&
        state.selectedSession?.id == message.sessionId && state.messages.any { it == message }

/** Footer-only actions and source-anchored task state; media stays at its existing message location. */
@Composable
internal fun DesktopAssistantImageActions(message: ChatMessage, state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController, normalActions: Boolean) {
    if (!normalActions || state.selectedSession?.id != message.sessionId || state.messages.none { it == message }) return
    val scope = rememberCoroutineScope()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val running = tasks.any { it.sessionId == message.sessionId && it.status == DesktopTaskStatus.RUNNING }
    var imageStatus by remember(message.id) { mutableStateOf("") }
    var requirementsOpen by remember(message.id) { mutableStateOf(false) }
    var imageHint by remember(message.id) { mutableStateOf("") }
    var preference by remember(message.id) { mutableStateOf("") }
    val generate: (DesktopChatImageRequirements?) -> Unit = { requirements -> scope.launch {
        try {
            requireNotNull(controller.imageRegeneration).generateFromAssistant(message, requirements)
            imageStatus = "已启动图片任务"; requirementsOpen = false
            controller.refreshAfterTerminalTask(message.sessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DesktopChatImagePreflightException) { imageStatus = failure.result.reason }
        catch (_: Exception) { imageStatus = "无法启动图片任务，请检查生图配置与任务状态" }
    } }
    if (desktopAssistantImageActionVisible(message, state, normalActions) && controller.imageRegeneration != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DesktopChatIconAction("生成图片", DesktopAppIcons.ImageAdd, enabled = !running) { generate(null) }
            DesktopChatIconAction("生图要求", DesktopAppIcons.Settings, enabled = !running) {
                imageHint = ""; preference = state.selectedSession.imagePromptPreference; requirementsOpen = true
            }
        }
    }
    if (imageStatus.isNotBlank()) StatusText(imageStatus)
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
    if (requirementsOpen) DialogWindow(onCloseRequest = { requirementsOpen = false }, title = "生图要求") {
        DesktopChatImageRequirementsForm(imageHint, preference, { imageHint = it }, { preference = it }, running,
            imageStatus, { requirementsOpen = false }) { generate(DesktopChatImageRequirements(imageHint, preference)) }
    }
}

@Composable
internal fun DesktopChatImageRequirementsForm(imageHint: String, preference: String, onHint: (String) -> Unit,
    onPreference: (String) -> Unit, running: Boolean, status: String, onCancel: () -> Unit, onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioField("图片内容提示", imageHint, onChange = onHint)
        StudioField("生图偏好", preference, onChange = onPreference)
        StatusText("取消不会保存；直接生成使用会话已保存的偏好。")
        if (status.isNotBlank()) StatusText(status)
        StudioActions {
            BootstrapButton("取消", onClick = onCancel)
            BootstrapButton("生成", enabled = !running, onClick = onGenerate)
        }
    }
}
