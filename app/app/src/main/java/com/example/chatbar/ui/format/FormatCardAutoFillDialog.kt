package com.example.chatbar.ui.format

import com.example.chatbar.ui.components.AiStreamProgressPanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor

private data class FormatAutoFillModelOption(val id: String?, val label: String)

@Composable
internal fun FormatCardAutoFillDialog(viewModel: FormatCardEditViewModel) {
    val state by viewModel.autoFillState.collectAsState()
    var fullscreenRequest by remember { mutableStateOf(false) }
    var showRawOutput by remember { mutableStateOf(false) }
    val busy = state.isGenerating
    val characters = viewModel.autoFillCharacters
    val selectedCharacter = characters.firstOrNull { it.id == viewModel.autoFillCharacterId }
    val modelOptions = buildList {
        add(FormatAutoFillModelOption(null, viewModel.autoFillDefaultModelName.takeIf(String::isNotBlank)
            ?.let { "默认模型：$it" } ?: "默认模型（未配置）"))
        viewModel.autoFillModels.forEach {
            add(FormatAutoFillModelOption(it.id, it.displayName.ifBlank { it.modelName }))
        }
    }
    val selectedModel = modelOptions.firstOrNull { it.id == viewModel.autoFillModelId }
    val modelReady = selectedModel != null &&
        (selectedModel.id != null || viewModel.autoFillDefaultModelName.isNotBlank())
    val ready = !busy && !viewModel.autoFillOptionsLoading && viewModel.autoFillOptionsError == null &&
        selectedCharacter != null && modelReady && viewModel.canAutoFill

    // FullscreenTextEditor is activity-hosted; the dialog window must leave composition.
    if (fullscreenRequest) {
        FullscreenTextEditor(
            title = "定制要求（可选）",
            text = viewModel.autoFillRequest,
            onTextChange = viewModel::updateAutoFillRequest,
            visible = true,
            onDismiss = { fullscreenRequest = false }
        )
        return
    }

    CbDialog(
        onDismissRequest = viewModel::closeAutoFill,
        title = "AI 自动填充格式卡",
        dismissOnClickOutside = false,
        dismissOnBackPress = !busy,
        dismiss = {
            if (busy) {
                CbButton("取消生成", viewModel::cancelAutoFill, variant = ButtonVariant.Destructive)
            } else {
                CbButton("关闭", viewModel::closeAutoFill, variant = ButtonVariant.Ghost)
            }
        },
        confirm = {
            CbButton(
                "应用",
                viewModel::applyAutoFill,
                enabled = !busy && state.draft != null && viewModel.canAutoFill
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CbText("让 AI 从角色设定出发，设计专属写法与回复样式。预览后应用，保存时才创建格式卡。",
                color = ChatBarTheme.colors.mutedForeground)

            if (viewModel.autoFillOptionsLoading) {
                CbText("正在读取角色卡和模型…", color = ChatBarTheme.colors.mutedForeground)
            }
            viewModel.autoFillOptionsError?.let {
                CbText(it, color = ChatBarTheme.colors.destructive)
                CbButton("重新读取", viewModel::refreshAutoFillOptions, enabled = !busy, variant = ButtonVariant.Outline)
            }

            CbField("目标角色卡") {
                CbSelect(
                    value = selectedCharacter,
                    options = characters,
                    optionLabel = { it.name },
                    onValueChange = { viewModel.selectAutoFillCharacter(it.id) },
                    placeholder = if (viewModel.autoFillCharacterId == null) "选择一张已保存角色卡" else "所选角色卡已不可用，请重选",
                    enabled = !busy && !viewModel.autoFillOptionsLoading
                )
            }
            if (characters.isEmpty() && !viewModel.autoFillOptionsLoading && viewModel.autoFillOptionsError == null) {
                CbText("暂无角色卡，请先创建并保存一张角色卡。", color = ChatBarTheme.colors.mutedForeground)
            }
            CbField("本次使用模型") {
                CbSelect(
                    value = selectedModel,
                    options = modelOptions,
                    optionLabel = { it.label },
                    onValueChange = { viewModel.selectAutoFillModel(it.id) },
                    placeholder = "所选模型已不可用，请重选",
                    enabled = !busy && !viewModel.autoFillOptionsLoading
                )
            }
            if (!modelReady && !viewModel.autoFillOptionsLoading) {
                CbText("请在管理页配置对话模型，或选择其他可用模型。", color = ChatBarTheme.colors.mutedForeground)
            }
            CbField(
                label = "定制要求（可选）",
                description = "留空时，由 AI 根据角色卡设计。",
                onFullscreenEdit = if (busy) null else ({ fullscreenRequest = true })
            ) {
                CbInput(
                    value = viewModel.autoFillRequest,
                    onValueChange = viewModel::updateAutoFillRequest,
                    placeholder = "例如：偏轻松日常，多些角色间对白，状态栏简短，不需要行动选项。",
                    singleLine = false,
                    minLines = 4,
                    enabled = !busy
                )
            }
            if (viewModel.name.isNotBlank()) {
                CbText("保留已填名称：${viewModel.name}", color = ChatBarTheme.colors.mutedForeground)
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CbSpinner()
                    CbText(state.status, modifier = Modifier.weight(1f))
                }
            } else {
                CbButton(
                    if (state.rawText.isEmpty() && state.draft == null) "生成候选" else "重新生成",
                    viewModel::generateAutoFill,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready,
                    variant = ButtonVariant.Secondary
                )
                if (state.status.isNotBlank()) CbText(state.status, color = ChatBarTheme.colors.mutedForeground)
            }
            state.validationIssue?.let {
                CbText("首次候选校验未通过：$it", color = ChatBarTheme.colors.warning,
                    style = ChatBarTheme.typography.caption)
            }
            state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
            AiStreamProgressPanel(viewModel.autoFillProgress)

            state.draft?.let { draft ->
                CbDivider()
                CbText("候选预览", style = ChatBarTheme.typography.heading)
                CbText("依据角色卡：${state.sourceCharacterName}", color = ChatBarTheme.colors.mutedForeground)
                CbText(viewModel.name.takeIf(String::isNotBlank) ?: draft.name, style = ChatBarTheme.typography.heading)
                CbText(draft.content)
                CbDivider()
                CbText("用户工具（${draft.userTools.size}）", style = ChatBarTheme.typography.label)
                if (draft.userTools.isEmpty()) CbText("无用户工具", color = ChatBarTheme.colors.mutedForeground)
                draft.userTools.forEachIndexed { index, tool ->
                    when (tool.type) {
                        FormatCardUserToolType.RANDOM_NUMBER ->
                            CbText("${index + 1}. 随机数：${tool.minimum} 至 ${tool.maximum}（含边界）")
                        FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> {
                            CbText("${index + 1}. 强提示词尾缀", style = ChatBarTheme.typography.label)
                            CbText(tool.text)
                        }
                    }
                }
            }

            if (state.rawText.isNotBlank() || state.repairText.isNotBlank()) {
                CbDivider()
                val rawVisible = busy || state.draft == null || showRawOutput
                if (!busy && state.draft != null) {
                    CbButton(if (showRawOutput) "收起原始输出" else "查看原始输出",
                        { showRawOutput = !showRawOutput }, variant = ButtonVariant.Ghost)
                }
                if (rawVisible) {
                    CbText("生成原文", style = ChatBarTheme.typography.label)
                    CbText(state.rawText, style = ChatBarTheme.typography.caption)
                    if (state.repairText.isNotBlank()) {
                        CbText("JSON 修复原文", style = ChatBarTheme.typography.label)
                        CbText(state.repairText, style = ChatBarTheme.typography.caption)
                    }
                }
            }
        }
    }
}
