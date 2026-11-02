package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.chatbar.ui.kit.*

/** Separate versioned clipboard format; existing positive-only entry stays unchanged. */
@Composable
internal fun StudioFullPromptTextDialog(state: ImagePromptToolUiState, onCopy: () -> Unit, onRead: () -> Unit,
    onApply: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    val enabled = state.draftLoaded && !state.isBusy && !state.applyingHistory && !state.fullPromptReading && !state.fullPromptCopying
    CbDialog(onDismissRequest = onClose, title = "完整 Prompt 文本互换",
        confirm = { CbButton("关闭", onClose, variant = ButtonVariant.Outline) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            CbText("包含画风、全部正负词、角色顺序／位置／开关、位置应用开关、模型和自然语言模式。其他生成参数和参考资源保留，不会自动生图。")
            CbText("使用 CCB-FULL-PROMPT/1 格式。两端都需要带此入口的对应版本；旧正向复制和当前官方旧版不等于已支持完整格式。")
            CbButton(if (state.fullPromptCopying) "正在复制…" else "复制完整文本", onCopy, enabled = enabled, variant = ButtonVariant.Outline)
            CbButton(if (state.fullPromptReading) "正在读取…" else "读取剪贴板并预览", onRead, enabled = enabled,
                variant = ButtonVariant.Outline)
            val preview = state.fullPromptPreview
            if (preview == null) CbText("读取后先核对预览，确认才替换；取消不改变工作室，可用原撤销恢复替换。")
            else {
                CbText("${preview.selectedModel.displayName} · ${if (preview.naturalLanguageMode) "自然语言" else "标签"}模式")
                CbText("角色 ${preview.characters.count { it.enabled }}/${preview.characters.size} · 应用位置：${if (preview.useCharacterPositions) "开" else "关"}")
                FullPromptTextSection("画风", preview.stylePrompt)
                FullPromptTextSection("基础正向", preview.basePrompt)
                FullPromptTextSection("补充正向", preview.extraPrompt)
                FullPromptTextSection("全局负向", preview.negativePrompt)
                preview.characters.forEachIndexed { index, role ->
                    CbText("角色 ${index + 1} · ${if (role.enabled) "启用" else "停用"} · ${role.center?.let { "${it.x}, ${it.y}" } ?: "自动位置"}")
                    FullPromptTextSection("角色 ${index + 1} 正向", role.prompt)
                    FullPromptTextSection("角色 ${index + 1} 负向", role.negativePrompt)
                }
                CbButton("确认替换完整 Prompt", onApply, enabled = enabled)
                CbButton("取消文本导入", onCancel, enabled = !state.fullPromptReading, variant = ButtonVariant.Outline)
            }
        }
    }
}

@Composable private fun FullPromptTextSection(title: String, text: String) {
    var expanded by remember(title, text) { mutableStateOf(false) }
    CbButton("${if (expanded) "收起" else "展开"} $title · ${text.length} 字符", { expanded = !expanded }, variant = ButtonVariant.Ghost)
    if (expanded) {
        CbText(text.take(8192).ifEmpty { "（空）" })
        if (text.length > 8192) CbText("预览仅显示前 8192 字符；确认替换仍采用完整原文。")
    }
}
