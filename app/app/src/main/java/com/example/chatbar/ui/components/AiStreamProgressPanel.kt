package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.chat.AiStreamProgress
import com.example.chatbar.domain.chat.AiStreamSnapshot
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlinx.coroutines.delay

@Composable
fun AiStreamProgressPanel(progress: AiStreamProgress) {
    val requests by progress.snapshots.collectAsState()
    if (requests.isEmpty()) return
    var history by remember(progress) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbText("AI 实时输出", style = ChatBarTheme.typography.heading)
        val visible = if (history) requests.asReversed() else requests.takeLast(1)
        visible.forEach { request ->
            key(request.requestId) { AiRequestPreview(request) }
        }
        if (requests.size > 1) {
            CbButton(if (history) "收起前序阶段" else "查看前序阶段（${requests.size - 1}）",
                { history = !history }, variant = ButtonVariant.Ghost)
        }
    }
}

@Composable
private fun AiRequestPreview(request: AiStreamSnapshot) {
    var now by remember(request.requestId) { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(request.requestId, request.active) {
        while (request.active) {
            now = System.nanoTime()
            delay(1_000)
        }
    }
    var expanded by remember(request.requestId) { mutableStateOf(true) }
    CbText("${request.title} · ${request.model}", style = ChatBarTheme.typography.label)
    val waiting = ((now - request.lastUpdateNanos) / 1_000_000_000L).coerceAtLeast(0)
    CbText(request.status + if (request.active) " · ${waiting} 秒前更新" else "",
        color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
    if (request.reasoning.isEmpty()) {
        CbText("接口尚未返回可展示的思考内容", style = ChatBarTheme.typography.caption,
            color = ChatBarTheme.colors.mutedForeground)
    } else {
        CbButton(if (expanded) "收起思考过程" else "展开思考过程",
            { expanded = !expanded }, variant = ButtonVariant.Ghost)
        if (expanded) StreamingPreviewText(request.reasoning)
    }
    if (request.content.isNotEmpty()) {
        CbText("生成内容", style = ChatBarTheme.typography.label)
        StreamingPreviewText(request.content)
    }
}

@Composable
private fun StreamingPreviewText(text: String) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress) {
        follow = scroll.value >= scroll.maxValue - 24
    }
    LaunchedEffect(text, scroll.maxValue) {
        if (follow && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue)
    }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(scroll)) {
            CbText(text, style = ChatBarTheme.typography.caption)
        }
    }
}
