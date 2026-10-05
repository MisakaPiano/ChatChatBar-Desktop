package com.example.chatbar.ui.moments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentSenderOption
import com.example.chatbar.ui.components.CbAvatar
import com.example.chatbar.ui.kit.*

@Composable
internal fun MomentPostEditor(
    post: MomentPost,
    senderOptions: List<MomentSenderOption>,
    onDismiss: () -> Unit,
    onSave: (String, String?, (String?) -> Unit) -> Unit
) {
    var text by rememberSaveable(post.id) { mutableStateOf(post.text) }
    var senderKey by rememberSaveable(post.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val selected = senderOptions.firstOrNull { it.key == senderKey }
    if (fullscreen) {
        FullscreenTextEditor(
            title = "朋友圈文案", text = text, onTextChange = {}, visible = true,
            onDismiss = { fullscreen = false }, onConfirm = { text = it; fullscreen = false }
        )
    } else CbDialog(
        title = "编辑朋友圈",
        onDismissRequest = { if (!saving) onDismiss() },
        dismiss = { CbButton("取消", onDismiss, enabled = !saving, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton(if (saving) "保存中…" else "保存", {
                saving = true
                error = null
                onSave(text, senderKey) { message ->
                    saving = false
                    error = message
                    if (message == null) onDismiss()
                }
            }, enabled = !saving && text.isNotBlank() && (senderKey == null || selected != null))
        }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("发布人物与头像") {
                CbAvatar(
                    imagePath = if (senderKey == null) post.senderAvatar else selected?.sender?.avatar,
                    contentDescription = selected?.sender?.name ?: post.senderName,
                    size = 48.dp, rounded = true, fallbackIcon = AppIcons.Face
                )
                CbSelect(
                    value = senderKey.orEmpty(),
                    options = listOf("") + senderOptions.map { it.key },
                    optionLabel = { key ->
                        if (key.isEmpty()) "保留原人物：${post.senderName}"
                        else (senderOptions.firstOrNull { it.key == key }?.sender?.name ?: "人物已删除，请重新选择") +
                            if (key == "card") "（角色卡）" else ""
                    },
                    onValueChange = { senderKey = it.takeIf(String::isNotEmpty) },
                    enabled = !saving
                )
            }
            CbField("朋友圈文案", onFullscreenEdit = { if (!saving) fullscreen = true }) {
                CbInput(text, { text = it }, singleLine = false, minLines = 5, enabled = !saving)
            }
            error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        }
    }
}
