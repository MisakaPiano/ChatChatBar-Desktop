package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.*

@Composable
internal fun DesktopSaveSlotPanel(controller: DesktopSaveSlotController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    var name by remember(state.sessionId) { mutableStateOf("会话存档") }
    var images by remember { mutableStateOf(SaveSlotImagePolicy.NONE) }
    var audio by remember { mutableStateOf(true) }
    var restore by remember(state.sessionId) { mutableStateOf<SaveSlotSummary?>(null) }
    var deletion by remember(state.sessionId) { mutableStateOf<SaveSlotSummary?>(null) }
    var card by remember(state.bindings) { mutableStateOf(state.bindings?.session?.characterCardId) }
    var books by remember(state.bindings) { mutableStateOf(state.bindings?.session?.extraWorldBookIds.orEmpty()) }
    var confirmBinding by remember(state.bindings) { mutableStateOf(false) }
    DesktopScreenshotWindow(onClose = controller::closePanel, title = "会话存档", content = {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusText("会话存档")
            StatusText("保存当前消息、会话设置和记忆。导入先加入列表，恢复时保留恢复前存档。")
            ActionRow {
                BootstrapButton("导入存档", secondary = true, enabled = !state.busy) { controller.import() }
                BootstrapButton("复制整个会话", secondary = true, enabled = !state.busy) { controller.duplicate() }
                BootstrapButton("更换会话资源…", secondary = true, enabled = !state.busy) { controller.loadBindings() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(name, { name = it.take(120) }, Modifier.weight(1f)
                    .border(1.dp, DesktopBootstrapColors.border).padding(10.dp),
                    enabled = !state.busy, singleLine = true, textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
                BootstrapButton("保存新存档", enabled = !state.busy && name.isNotBlank()) { controller.create(name, images, audio) }
            }
            ActionRow {
                listOf(SaveSlotImagePolicy.ORIGINAL to "原图", SaveSlotImagePolicy.COMPRESSED to "压缩图片", SaveSlotImagePolicy.NONE to "不含图片").forEach { (value, label) ->
                    BootstrapButton(label, secondary = images != value, enabled = !state.busy) { images = value }
                }
                BootstrapButton(if (audio) "包含录音" else "不含录音", secondary = !audio, enabled = !state.busy) { audio = !audio }
            }
            if (state.message.isNotBlank()) StatusText(state.message)
            state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            if (state.busy) ActionRow {
                StatusText("关闭窗口不会丢弃已开始的操作。")
                BootstrapButton("停止操作", secondary = true) { controller.stop() }
            }
            restore?.let { slot ->
                StatusText("恢复「${slot.name}」将替换当前会话的消息、设置和记忆。现有角色卡绑定保持不变；先前聊天会自动保存。")
                ActionRow {
                    BootstrapButton("确认恢复", enabled = !state.busy) { restore = null; controller.restore(slot) }
                    BootstrapButton("取消", secondary = true) { restore = null }
                }
            }
            deletion?.let { slot ->
                StatusText("删除存档「${slot.name}」？只移除这份存档及其独占包；当前聊天、其他存档和共享媒体不会删除。为失败恢复保留的副本不会在此清除。")
                ActionRow {
                    BootstrapButton("确认删除存档", enabled = !state.busy) { deletion = null; controller.delete(slot) }
                    BootstrapButton("取消删除", secondary = true, enabled = !state.busy) { deletion = null }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.bindings?.let { choices ->
                    StatusText("选择角色卡（旧卡仍存在也可切换）：")
                    ActionRow { choices.characters.forEach { value ->
                        BootstrapButton(value.name, secondary = card != value.id, enabled = !state.busy) { card = value.id; confirmBinding = false }
                    } }
                    StatusText("附加世界书（角色卡自身继承的世界书继续跟随角色卡）：")
                    ActionRow { choices.books.forEach { value ->
                        BootstrapButton(value.name, secondary = value.id !in books, enabled = !state.busy) {
                            books = if (value.id in books) books - value.id else books + value.id
                            confirmBinding = false
                        }
                    } }
                    StatusText("保留聊天、消息版本、原格式卡与录音；历史气泡的角色名称和头像可能随新卡显示。重置世界书持续/冷却计时，并先保存保全存档。")
                    ActionRow {
                        BootstrapButton(if (confirmBinding) "确认更换资源" else "检查并更换…",
                            enabled = !state.busy && choices.characters.any { it.id == card }) {
                            if (confirmBinding) controller.rebind(requireNotNull(card), books) else confirmBinding = true
                        }
                        BootstrapButton("取消", secondary = true) { controller.closeBindings() }
                    }
                }
                if (state.slots.isEmpty() && !state.busy) StatusText("当前会话还没有存档。")
                state.slots.forEach { slot ->
                    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusText(slot.name)
                        val summary = slot
                        StatusText("${summary.messageCount} 条消息 · ${summary.imageCount} 张图片 · ${summary.audioCount} 条录音")
                        ActionRow {
                            BootstrapButton("恢复…", enabled = !state.busy) { restore = slot }
                            BootstrapButton("导出文件", secondary = true, enabled = !state.busy) { controller.export(slot) }
                            BootstrapButton("删除…", secondary = true, enabled = !state.busy) { restore = null; deletion = slot }
                        }
                    }
                }
            }
            BootstrapButton("返回聊天", secondary = true) { controller.closePanel() }
        }
    })
}
