@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopCharacterAppearanceOverlay(controller: DesktopCharacterAppearanceController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 800.dp).heightIn(max = 800.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopManagementHeading("图片补外貌／服装 · ${state.name}") }
                    DesktopIconAction("关闭图片文字候选", DesktopAppIcons.Close, enabled = !state.busy, onClick = controller::close)
                }
                StatusText("只生成外貌与服装文字，不替换人物头像；应用到草稿后仍需正常保存。未识别的空字段保留原内容。")
                state.preview?.let { bytes ->
                    val bitmap = remember(bytes) { DesktopImageEditing.decode(bytes).toComposeImageBitmap() }
                    Image(bitmap, "上传的人物参考图", Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Fit)
                }
                if (state.status.isNotBlank()) StatusText(state.status)
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                state.candidate?.let { draft ->
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusText("原外貌：\n${state.before?.appearance.orEmpty().ifBlank { "（空）" }}")
                        StatusText("外貌候选：\n${draft.appearance.ifBlank { "（保留原内容）" }}")
                        StatusText("原服装：\n${state.before?.clothing.orEmpty().ifBlank { "（空）" }}")
                        StatusText("服装候选：\n${draft.clothing.ifBlank { "（保留原内容）" }}")
                    } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.busy) BootstrapButton("取消图片识别", secondary = true, onClick = controller::cancel)
                    else BootstrapButton("重新识别", secondary = true, onClick = controller::generate)
                    BootstrapButton("应用外貌与服装到草稿", enabled = !state.busy && state.candidate != null) { controller.apply() }
                }
                DesignTextDisclosure("图片识别原文", state.raw, initiallyExpanded = state.candidate == null)
                DesignTextDisclosure("图片识别思考", state.reasoning)
            }
        }
    }
}
