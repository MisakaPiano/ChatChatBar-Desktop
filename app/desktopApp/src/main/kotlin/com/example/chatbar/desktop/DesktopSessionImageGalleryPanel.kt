package com.example.chatbar.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Refresh replaces the overview only. An opened viewer keeps its exact occurrence snapshot. */
@Composable
internal fun DesktopSessionImageGalleryWindow(sessionId: String, controller: DesktopPrimaryChatController,
    onClose: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<DesktopSessionImageGallerySnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<DesktopSessionImageGallerySnapshot, Int>?>(null) }
    var navigating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, revision) {
        loading = true; snapshot = null; error = null
        try { snapshot = withContext(Dispatchers.IO) { controller.sessionImageGallery(sessionId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法完整读取会话图片。请检查资料后重试；未改动原数据。" }
        finally { loading = false }
    }
    fun source(occurrence: DesktopSessionImageOccurrence) {
        if (navigating) return
        navigating = true
        scope.launch {
            try {
                if (controller.revealMessage(sessionId, occurrence.messageId)) onClose()
                else error = "来源消息已变化或不可用，请刷新图库后重试。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "暂时无法定位来源消息，请重试。" }
            finally { navigating = false }
        }
    }
    preview?.let { (captured, index) ->
        DesktopSessionImageViewer(captured, index, controller.characterResources, controller.imagePicker,
            onSource = ::source, onClose = { preview = null })
    }
    DesktopImageToolWindow("会话图片", onClose, width = 1000.dp, height = 760.dp) {
        DesktopSessionImageGalleryContent(snapshot, loading, error, navigating,
            read = controller.characterResources::readBytes,
            onRefresh = { revision++ }, onPreview = { index -> snapshot?.let { preview = it to index } },
            onSource = ::source)
    }
}

@Composable
internal fun DesktopSessionImageGalleryContent(snapshot: DesktopSessionImageGallerySnapshot?, loading: Boolean,
    error: String?, navigating: Boolean = false, read: (String) -> ByteArray,
    onRefresh: () -> Unit, onPreview: (Int) -> Unit, onSource: (DesktopSessionImageOccurrence) -> Unit) {
    val grid = rememberLazyGridState()
    val decodeAdmission = remember { DesktopImageDecodeAdmission() }
    val thumbnailSize: (IntSize) -> IntSize = remember { { source ->
        val ratio = minOf(1f, 320f / maxOf(source.width, source.height).coerceAtLeast(1))
        IntSize((source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1))
    } }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ManageHeading("会话图片")
                snapshot?.let { StatusText("${it.title} · 全部历史 ${it.occurrences.size} 处图片") }
            }
            BootstrapButton("刷新", secondary = true, enabled = !loading && !navigating, onClick = onRefresh)
        }
        StatusText("包含本会话尚未加载的历史消息；文字备选共用所属消息的图片。存档图片请从“存档”查看。")
        if (loading) StatusText("正在读取完整历史……")
        error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (navigating) StatusText("正在定位来源消息……")
        snapshot?.let { captured ->
            if (captured.omittedCount > 0) StatusText("另有 ${captured.omittedCount} 处图片在导入时被省略。")
            if (captured.occurrences.isEmpty()) StatusText("本会话没有可查看的图片。")
            else Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize().padding(end = 12.dp)
                    .semantics { contentDescription = "完整会话图片网格" }, state = grid,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(captured.occurrences.size, key = { captured.occurrences[it].key }) { index ->
                        val occurrence = captured.occurrences[index]
                        Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DesktopOwnedImage(occurrence.reference, read, Modifier.fillMaxWidth().aspectRatio(1f)
                                .clickable { onPreview(index) }.semantics { contentDescription = "查看会话图片 ${index + 1}" },
                                displayTargetForSource = thumbnailSize, decodeAdmission = decodeAdmission)
                            val role = when (occurrence.role) { MessageRole.USER -> "玩家"; MessageRole.ASSISTANT -> "回复"; else -> "系统" }
                            StatusText("${index + 1} · $role · ${desktopSessionImageTime(occurrence.createdAt)}")
                            BootstrapButton("回到消息", secondary = true, enabled = !navigating) { onSource(occurrence) }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

private fun desktopSessionImageTime(time: Long): String = if (time <= 0) "时间未记录" else
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(time))
