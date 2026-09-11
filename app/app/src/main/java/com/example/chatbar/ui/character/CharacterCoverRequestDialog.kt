package com.example.chatbar.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.FullscreenTextEditor
import kotlinx.coroutines.CancellationException

@Composable
internal fun CharacterCoverRequestDialog(
    previous: CharacterCoverImageUiState,
    loadDefaultRequirement: suspend () -> String,
    onDismiss: () -> Unit,
    onGenerate: (String, String) -> Unit
) {
    var content by remember { mutableStateOf(previous.imageContentHint) }
    var requirement by remember { mutableStateOf(previous.finalPromptRequirement) }
    var loading by remember { mutableStateOf(previous.sourceSignature.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreenField by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (loading) {
            try {
                requirement = loadDefaultRequirement()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "读取工作室特殊要求失败：${failure.message ?: "未知错误"}，请手动填写。"
            } finally {
                loading = false
            }
        }
    }
    fullscreenField?.let { field ->
        FullscreenTextEditor(
            title = field,
            text = if (field == "画面内容") content else requirement,
            onTextChange = { if (field == "画面内容") content = it else requirement = it },
            visible = true,
            onDismiss = { fullscreenField = null }
        )
        return
    }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "AI 设计封面",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("生成封面", { onGenerate(content, requirement) }, enabled = !loading) }
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CbText("生成后可查看完整图片、裁剪头像；应用时背景保留原图。")
            CbField(
                label = "画面内容",
                description = "可选；留空时根据角色卡设计。",
                onFullscreenEdit = { fullscreenField = "画面内容" }
            ) {
                CbInput(content, { content = it }, singleLine = false, minLines = 3)
            }
            CbField(
                label = "特殊要求",
                description = if (loading) "正在读取生图工作室…" else "默认读取生图工作室，可修改；仅用于本次封面。",
                onFullscreenEdit = if (loading) null else ({ fullscreenField = "特殊要求" })
            ) {
                CbInput(requirement, { requirement = it }, singleLine = false, minLines = 3, enabled = !loading)
            }
            error?.let { CbText(it) }
        }
    }
}
