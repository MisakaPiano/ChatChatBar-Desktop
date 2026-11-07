package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Shared by the actual Studio image surface and its synthetic Scene regression. */
@Composable
internal fun DesktopReversePromptPane(
    state: DesktopNovelAiStudioState, busy: Boolean,
    onSceneChange: (String) -> Unit, onConfirm: () -> Unit, onStop: () -> Unit,
    onRetry: () -> Unit, retryAvailable: Boolean, onApply: () -> Unit, onDismiss: () -> Unit,
    naturalLanguage: Boolean = false, onExpandScene: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText(if (naturalLanguage) "反推模式：自然语言 · V5" else "反推模式：结构化 Tag")
        if (state.reverseProgress.stage.isNotBlank()) StatusText(state.reverseProgress.stage)
        state.reverseSceneDescription?.let { scene ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText("场景描述 · 可编辑，确认后生成 Prompt") }
                onExpandScene?.let { expand -> StudioAction("展开", enabled = !busy,
                    icon = DesktopAppIcons.ExpandComposer, accessibilityLabel = "展开反推场景描述", onClick = expand) }
            }
            BasicTextField(scene, onSceneChange,
                Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 210.dp)
                    .border(1.dp, DesktopBootstrapColors.border).background(DesktopBootstrapColors.input)
                    .padding(9.dp).semantics { contentDescription = "反推场景描述" },
                readOnly = busy, cursorBrush = DesktopBootstrapColors.inputCaret,
                textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
            StudioAction("确认场景并设计 Prompt", enabled = !busy && scene.isNotBlank(), onClick = onConfirm)
        }
        if (state.reverseProgress.content.isNotBlank()) {
            StudioDisclosure("反推过程 / 流式结果") { SelectionContainer { DesktopMarkdownText(state.reverseProgress.content) } }
        }
        if (state.reverseProgress.reasoning.isNotBlank()) {
            StudioDisclosure("反推思考") { SelectionContainer { DesktopMarkdownText(state.reverseProgress.reasoning) } }
        }
        if (state.reverseBusy) StudioAction("停止图片反推", onClick = onStop)
        state.reverseCandidate?.let { candidate ->
            StatusText("反推候选：${candidate.baseCaption}")
            candidate.characterCaptions.forEach { StatusText(it.prompt) }
            StudioAction("应用反推", enabled = !busy, onClick = onApply)
        }
        if (state.reverseProgress.stage.isNotBlank()) StudioActions {
            StudioAction("重试识图", enabled = !busy && retryAvailable, onClick = onRetry)
            StudioAction("返回 / 保留当前 Prompt", onClick = onDismiss)
        }
    }
}
