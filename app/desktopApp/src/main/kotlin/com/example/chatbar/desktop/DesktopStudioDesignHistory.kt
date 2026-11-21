package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.domain.image.NovelAiDesignConversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopStudioDesignHistory(
    conversations: List<NovelAiDesignConversation>,
    currentId: String?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val time = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
    if (conversations.isEmpty()) {
        StatusText("暂无设计历史")
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pending = conversations.firstOrNull { it.id == pendingDeleteId && it.id != currentId }
        if (pending != null) {
            Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.muted).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("删除「${pending.title}」？", maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                StudioActions {
                    StudioAction("取消") { pendingDeleteId = null }
                    StudioAction("确认删除", enabled = !busy) {
                        pendingDeleteId = null
                        onDelete(pending.id)
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(conversations, key = NovelAiDesignConversation::id) { conversation ->
                val isCurrent = conversation.id == currentId
                Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)
                        .semantics { contentDescription = "打开设计 · ${conversation.title}" }
                        .clickable(enabled = !busy, role = Role.Button) { onOpen(conversation.id) }
                        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BasicText(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                        StatusText(time.format(Instant.ofEpochMilli(conversation.updatedAt)))
                    }
                    if (isCurrent) {
                        StatusText("当前")
                    } else {
                        StudioAction("删除设计", enabled = !busy && pending == null,
                            accessibilityLabel = "删除设计 · ${conversation.title}") {
                            pendingDeleteId = conversation.id
                        }
                    }
                }
            }
        }
    }
}
