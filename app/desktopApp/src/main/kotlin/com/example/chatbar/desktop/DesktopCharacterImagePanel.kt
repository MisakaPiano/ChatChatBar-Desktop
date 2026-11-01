@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopCharacterImageOverlay(controller: DesktopCharacterImageController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    var cropping by remember(controller) { mutableStateOf(false) }
    var viewing by remember(controller) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(state.candidate) { if (state.candidate == null) cropping = false }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxHeight().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
                .padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopManagementHeading(
                        (if (state.kind == DesktopCharacterImageKind.COVER) "封面候选 · " else "人物头像候选 · ") + state.title) }
                    DesktopIconAction("关闭生图候选", DesktopAppIcons.Close, enabled = !state.busy, onClick = controller::close)
                }
                StatusText("生成会调用已配置的 NovelAI 账户；先核对候选，应用只改变草稿，保存仍需另行确认。")
                if (state.kind == DesktopCharacterImageKind.COVER) {
                    StatusText((if (state.reusedDesign) "已完成的封面设计来自：" else "封面设计模型：") +
                        state.designModelLabel.ifBlank { if (state.loading) "正在读取…" else "未配置可用模型" })
                    CharacterImageTextField("图片内容要求（可选）", state.hint, !state.busy && !state.loading, controller::setHint)
                    CharacterImageTextField("额外要求（初次读取 Studio 已保存内容）", state.requirement, !state.busy && !state.loading, controller::setRequirement)
                } else {
                    StatusText("头像固定为 512 × 512；按人物 Prompt 直接生图，不调用 AI 设计 Prompt。")
                    if (state.freeform) CharacterImageTextField("完整 NovelAI 正向 Prompt", state.manualPrompt,
                        !state.busy && !state.loading, controller::setManualPrompt)
                    else StatusText("使用卡片画风、所选人物的生图 Prompt 和卡片负面词。")
                }
                if (state.loading) StatusText("正在读取已保存要求…")
                state.warning?.let { StatusText(it, DesktopBootstrapColors.warning) }
                if (state.status.isNotBlank()) StatusText(state.status)
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                state.imageSize?.let { StatusText("${state.targetModel?.name.orEmpty()} · ${it.width} × ${it.height}") }
                state.preview?.let { bytes ->
                    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes) {
                        value = withContext(Dispatchers.IO) { DesktopImageEditing.decode(bytes, longestSide = 800).toComposeImageBitmap() }
                    }
                    bitmap?.let { Image(it, "生成图片候选", Modifier.fillMaxWidth().height(320.dp).clickable { viewing = bytes }, contentScale = ContentScale.Fit) }
                }
                if (state.crop != null) StatusText("已选择独立头像裁剪；完整图片仍用作聊天背景。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton("生成新图片", enabled = !state.busy && !state.loading) { controller.generate() }
                    if (state.busy) BootstrapButton("取消生图", secondary = true, onClick = controller::cancel)
                    if (!state.busy && state.error != null) BootstrapButton("重试未完成步骤", secondary = true) { controller.generate(retry = true) }
                    if (state.kind == DesktopCharacterImageKind.COVER && state.candidate != null) {
                        BootstrapButton("裁剪卡片头像", secondary = true, enabled = !state.busy) { cropping = true }
                        if (state.crop != null) BootstrapButton("取消头像裁剪", secondary = true, enabled = !state.busy, onClick = controller::clearCrop)
                    }
                    BootstrapButton("应用图片到草稿", enabled = !state.busy && state.candidate != null, onClick = controller::apply)
                }
                DesignTextDisclosure("生图 Prompt", state.promptText)
            }
        }
    }
    if (cropping) state.candidate?.let { bytes -> DesktopImageWorkspace(bytes, "裁剪卡片头像", { cropping = false }) {
        controller.setCrop(it, bytes); cropping = false
    } }
    viewing?.let { bytes -> DesktopTransientImagePreview("character-generated-candidate", { bytes }) { viewing = null } }
}

@Composable
private fun CharacterImageTextField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    StatusText(label)
    BasicTextField(value, change, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp)
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).background(DesktopBootstrapColors.input)
        .padding(10.dp).verticalScroll(rememberScrollState()), enabled = enabled, cursorBrush = DesktopBootstrapColors.inputCaret,
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
}
