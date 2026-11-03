@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.chat.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.*
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

internal data class DesktopScreenshotRequest(
    val title: String, val state: DesktopPrimaryChatState, val messages: List<ChatMessage>,
    val selected: Set<String>, val voices: Map<String,List<VoiceMessagePlacement>> = emptyMap(),
    // Retained constructor compatibility only; export-local contentOptions own full expansion.
    val statusExpanded: Map<String,Boolean> = emptyMap(), val background: String? = null,
    val contentOptions: DesktopScreenshotContentOptions = DesktopScreenshotContentOptions(),
)
internal data class DesktopScreenshotPng(val bytes: ByteArray, val width: Int, val height: Int,
    val resourceHashes: Map<String, String> = emptyMap())

internal fun desktopScreenshotHeaderAvatarReference(message: ChatMessage, state: DesktopPrimaryChatState): String? =
    when (message.role) {
        MessageRole.USER -> state.globalPlayerAvatar
        MessageRole.ASSISTANT -> state.selectedCharacter?.avatar
        else -> null
    }

internal class DesktopScreenshotLimitException(val category: String, val actual: Long, val limit: Long) :
    IllegalArgumentException("$category: $actual / $limit")

internal fun desktopScreenshotFailureDetails(error: Exception): String = when (error) {
    is DesktopScreenshotLimitException -> when (error.category) {
        "TEXT" -> "所选原文共 ${error.actual} 字符，当前上限 ${error.limit}；请分次选择。"
        "RESOURCE_COUNT" -> "本次需读取 ${error.actual} 张不同图片，当前上限 ${error.limit}。"
        "RESOURCE_BYTES" -> "图片文件合计 ${error.actual} 字节，当前资源预算 ${error.limit} 字节。"
        "RESOURCE_PIXELS" -> "图片解码合计 ${error.actual} 像素，当前资源预算 ${error.limit} 像素。"
        "CAPTURE_BYTES" -> "分块预览合计 ${error.actual} 字节，当前预览预算 ${error.limit} 字节。"
        "PAGE_COUNT" -> "本次需要 ${error.actual} 个预览分块，当前上限 ${error.limit}。"
        "HEIGHT", "PIXELS" -> "单个渲染分块超过尺寸预算（${error.category} ${error.actual}/${error.limit}）。"
        else -> "截图尺寸无效（${error.category} ${error.actual}/${error.limit}）。"
    }
    else -> "渲染未完成；异常类型：${error.javaClass.simpleName}。原数据未修改。"
}

/** Static CCB rendering, never OS/Robot capture. Every resource is supplied by an owned resource reader. */
internal object DesktopChatScreenshotRenderer {
    const val MAX_HEIGHT = 32_000
    const val MAX_PIXELS = 8_000_000L
    private const val MAX_PAGES = 128
    private const val MAX_CAPTURE_BYTES = 64L * 1024 * 1024
    private val edt = object: CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    fun validatePlan(width: Int, height: Int) {
        if (width !in 320..1600) throw DesktopScreenshotLimitException("WIDTH", width.toLong(), 1600)
        if (height !in 1..MAX_HEIGHT) throw DesktopScreenshotLimitException("HEIGHT", height.toLong(), MAX_HEIGHT.toLong())
        if (width.toLong()*height > MAX_PIXELS) throw DesktopScreenshotLimitException("PIXELS", width.toLong()*height, MAX_PIXELS)
    }
    suspend fun render(request: DesktopScreenshotRequest, colors: DesktopSemanticColors,
        read: (String)->ByteArray, width: Int = 640): DesktopScreenshotPng =
        renderFrames(request, colors, read, width, paged = false, pageRows = null) { _, _ -> }.single()

    /** Full selected layout, bounded raster surfaces. No text is dropped at a page boundary. */
    suspend fun renderPages(request: DesktopScreenshotRequest, colors: DesktopSemanticColors,
        read: (String)->ByteArray, width: Int = 640, pageRows: Int? = null,
        onPage: (Int, Int) -> Unit = { _, _ -> }): List<DesktopScreenshotPng> =
        renderFrames(request, colors, read, width, paged = true, pageRows, onPage)

    private suspend fun renderFrames(request: DesktopScreenshotRequest, colors: DesktopSemanticColors,
        read: (String)->ByteArray, width: Int, paged: Boolean, pageRows: Int?,
        onPage: (Int, Int) -> Unit): List<DesktopScreenshotPng> {
        require(width in 320..1600)
        require(request.messages.isNotEmpty())
        val characters = request.messages.sumOf { it.displayContent.length.toLong() }
        if (characters > 200_000) throw DesktopScreenshotLimitException("TEXT", characters, 200_000)
        require(request.selected.isNotEmpty()) { "请选择截图内容" }
        val valid=request.messages.flatMap { desktopScreenshotBlockIds(it,request.state.assistantSegmentedBubblesEnabled) }.toSet()
        require(request.selected.all { it in valid }) { "截图选择已失效" }
        val images=linkedMapOf<String,SkiaImage>()
        val resourceHashes=linkedMapOf<String,String>()
        var resourcePixels=0L
        var encodedBytes=0L
        try {
            val references=buildSet {
                request.background?.takeIf { request.contentOptions.includeBackground }?.let(::add)
                request.messages.forEach { m ->
                    if (request.selected.none { roleplayBlockMessageId(it) == m.id }) return@forEach
                    m.images.forEachIndexed { i,ref->if(roleplayImageBlockId(m.id,i) in request.selected) add(ref) }
                    val presented=desktopPresentMessage(m,request.state.selectedCharacter,request.state.globalPlayerName,
                        request.state.assistantSegmentedBubblesEnabled,session=request.state.selectedSession)
                    if (!desktopScreenshotMessageVisible(m, presented, request.selected, request.contentOptions)) return@forEach
                    val speakerHeaders = desktopScreenshotSpeakerHeaders(presented, request.selected, request.contentOptions)
                    if (presented.showWholeMessageHeader) desktopScreenshotHeaderAvatarReference(m, request.state)?.let(::add)
                    if(m.role==MessageRole.ASSISTANT) presented.segments.forEachIndexed { index, seg ->
                        val id = if (presented.segmented) seg.blockId else roleplayLegacyTextBlockId(m.id)
                        if (id in request.selected && index in speakerHeaders && desktopScreenshotIncludes(seg, request.contentOptions))
                            desktopPresentedAvatarReference(seg.speaker,request.state.selectedCharacter)?.let(::add)
                    }
                }
            }
            if (references.size > 40) throw DesktopScreenshotLimitException("RESOURCE_COUNT", references.size.toLong(), 40)
            withContext(Dispatchers.IO) { references.forEach { ref->
                currentCoroutineContext().ensureActive()
                val bytes=read(ref);encodedBytes+=bytes.size
                if (encodedBytes > 32L*1024*1024) throw DesktopScreenshotLimitException("RESOURCE_BYTES", encodedBytes, 32L*1024*1024)
                resourceHashes[ref]=resourceHash(bytes)
                val image=SkiaImage.makeFromEncoded(bytes)
                if (image.width.toLong()*image.height>MAX_PIXELS || resourcePixels+image.width.toLong()*image.height>MAX_PIXELS) {
                    val pixels = resourcePixels + image.width.toLong()*image.height
                    image.close();throw DesktopScreenshotLimitException("RESOURCE_PIXELS", pixels, MAX_PIXELS)
                }
                resourcePixels+=image.width.toLong()*image.height;images[ref]=image
            } }
            return withContext(edt) {
                var height=0
                var top = 0
                var renderTop = 0
                var viewportRows = 1
                val bitmaps = images.mapValues { it.value.toComposeImageBitmap() }
                val body:@Composable ()->Unit = {
                    CompositionLocalProvider(LocalDesktopPalette provides colors) {
                        Box(Modifier.width(width.dp).height(viewportRows.dp).background(colors.background).drawBehind {
                            bitmaps[request.background]?.takeIf { request.contentOptions.includeBackground }?.let { bitmap ->
                                val pageRows = desktopScreenshotPdfRows(width)
                                val scale = maxOf(width.toFloat() / bitmap.width, pageRows.toFloat() / bitmap.height)
                                val cropWidth = (width / scale).toInt().coerceIn(1, bitmap.width)
                                val cropHeight = (pageRows / scale).toInt().coerceIn(1, bitmap.height)
                                clipRect {
                                    var y = -(renderTop % pageRows)
                                    while (y < size.height) {
                                        drawImage(bitmap, srcOffset = IntOffset((bitmap.width - cropWidth) / 2, (bitmap.height - cropHeight) / 2),
                                            srcSize = IntSize(cropWidth, cropHeight), dstOffset = IntOffset(0, y),
                                            dstSize = IntSize(width, pageRows), alpha = request.state.backgroundOpacity.coerceIn(0f, 1f))
                                        y += pageRows
                                    }
                                }
                            }
                        }) {
                        DesktopScreenshotContent(request,bitmaps,Modifier
                            // Every viewport shares the full layout's top origin, even when its
                            // height is smaller than the content. The default vertical centering
                            // otherwise shifts each slice before the explicit page translation.
                            .width(width.dp).wrapContentHeight(align=Alignment.Top, unbounded=true).offset { IntOffset(0, -renderTop) }
                            .onSizeChanged { height=it.height })
                        }
                    }
                }
                // Measurement uses a one-pixel surface; no unbounded raster allocation.
                renderComposeScene(width,1,content=body).close()
                if (!paged) validatePlan(width,height)
                check(height > 0) { "截图布局未完成" }
                val measured=height
                val maximumRows = minOf(MAX_HEIGHT, (MAX_PIXELS / width).toInt())
                val rows = if (!paged) measured else pageRows?.also { require(it in 1..maximumRows) }
                    ?: (maximumRows - 1024).coerceAtLeast(1)
                val count = (measured.toLong() + rows - 1) / rows
                if (count > MAX_PAGES) throw DesktopScreenshotLimitException("PAGE_COUNT", count, MAX_PAGES.toLong())
                val frames = mutableListOf<DesktopScreenshotPng>()
                var captureBytes = 0L
                val hashes = resourceHashes.toMap()
                while (top < measured) {
                    currentCoroutineContext().ensureActive()
                    val chunkRows = minOf(rows, measured - top)
                    // Keep the round-rectangle/text rasterizer away from a viewport edge, then
                    // copy only exact owned rows. Both surfaces remain individually bounded.
                    val guard = if (paged) minOf(512, (maximumRows - chunkRows) / 2) else 0
                    renderTop = (top - guard).coerceAtLeast(0)
                    viewportRows = minOf(measured, top + chunkRows + guard) - renderTop
                    validatePlan(width, chunkRows)
                    validatePlan(width, viewportRows)
                    onPage(frames.size + 1, count.toInt())
                    renderComposeScene(width,viewportRows,content=body).use { image ->
                        check(height==measured) { "截图布局发生变化，请重新预览" }
                        currentCoroutineContext().ensureActive()
                        val bytes = if (viewportRows == chunkRows && renderTop == top)
                            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                        else Bitmap().use { bitmap ->
                            check(bitmap.allocN32Pixels(width, chunkRows))
                            check(image.readPixels(bitmap, 0, top - renderTop))
                            SkiaImage.makeFromBitmap(bitmap).use { cropped ->
                                checkNotNull(cropped.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                            }
                        }
                        captureBytes += bytes.size
                        if (captureBytes > MAX_CAPTURE_BYTES) throw DesktopScreenshotLimitException("CAPTURE_BYTES", captureBytes, MAX_CAPTURE_BYTES)
                        frames += DesktopScreenshotPng(bytes, width, chunkRows, hashes)
                    }
                    top += chunkRows
                    yield()
                }
                frames.toList()
            }
        } finally { images.values.forEach { it.close() } }
    }

    private fun resourceHash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun verifyResources(png: DesktopScreenshotPng, read: (String) -> ByteArray) {
        verifyResourceHashes(png.resourceHashes, read)
    }
    fun verifyResourceHashes(hashes: Map<String, String>, read: (String) -> ByteArray) {
        hashes.forEach { (reference, hash) ->
            check(resourceHash(read(reference)) == hash) { "截图资源已变化，请重新预览" }
        }
    }

    /** Explicit new destination only; no external file overwrite and no broad cleanup. */
    fun save(png: DesktopScreenshotPng, target: Path) {
        writeDesktopScreenshotFile(target, "png") { it.write(png.bytes) }
    }
}

/** Every screenshot format shares the same new-file-only publication boundary. */
internal fun writeDesktopScreenshotFile(target: Path, extension: String,
    beforePublish: () -> Unit = {}, write: (java.io.OutputStream) -> Unit) {
        val absolute=target.toAbsolutePath().normalize()
        require(absolute.fileName.toString().endsWith(".$extension",true)) { "请选择正确的导出文件格式" }
        require(!Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) { "文件已存在，请选择新文件名" }
        val parent=absolute.parent.toRealPath();require(parent==absolute.parent) { "保存目录已变化" }
        val part=parent.resolve(".ccb-screenshot-${UUID.randomUUID()}.part")
        try {
            Files.newOutputStream(part,StandardOpenOption.CREATE_NEW).use(write)
            beforePublish()
            check(parent.toRealPath()==parent)
            Files.move(part,absolute)
        } finally { Files.deleteIfExists(part) }
}

/** Reprove captured messages/resources after the chooser, before CREATE_NEW export. */
internal suspend fun saveDesktopScreenshotVerified(png: DesktopScreenshotPng,
    verifyMessages: suspend () -> Unit, read: (String) -> ByteArray, choose: suspend () -> Path?): Path? {
    verifyMessages()
    withContext(Dispatchers.IO) { DesktopChatScreenshotRenderer.verifyResources(png, read) }
    val target = choose() ?: return null
    verifyMessages()
    withContext(Dispatchers.IO) {
        DesktopChatScreenshotRenderer.verifyResources(png, read)
        DesktopChatScreenshotRenderer.save(png, target)
    }
    return target
}

@Composable
private fun DesktopScreenshotContent(r:DesktopScreenshotRequest, images:Map<String,ImageBitmap>,modifier:Modifier) {
    val c=LocalDesktopPalette.current
    Column(modifier) {
        BasicText(r.title,Modifier.fillMaxWidth().background(c.card).padding(12.dp),style=TextStyle(color=c.foreground,fontSize=18.sp))
        r.messages.forEach { m ->
            val p=desktopPresentMessage(m,r.state.selectedCharacter,r.state.globalPlayerName,r.state.assistantSegmentedBubblesEnabled,session=r.state.selectedSession)
            val speakerHeaders=desktopScreenshotSpeakerHeaders(p,r.selected,r.contentOptions)
            val selectedForMessage=r.selected.filter { roleplayBlockMessageId(it)==m.id }
            if(selectedForMessage.isNotEmpty() && desktopScreenshotMessageVisible(m,p,r.selected,r.contentOptions)) Box(Modifier.fillMaxWidth().padding(10.dp),
                contentAlignment=if(m.role==MessageRole.USER)Alignment.TopEnd else Alignment.TopStart) {
                Column(Modifier.fillMaxWidth(if(p.enclosingCard) .86f else 1f),verticalArrangement=Arrangement.spacedBy(7.dp),
                horizontalAlignment=if(m.role==MessageRole.USER)Alignment.End else Alignment.Start) {
                if(p.showWholeMessageHeader) Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.role==MessageRole.USER)Arrangement.End else Arrangement.Start,
                    verticalAlignment=Alignment.CenterVertically) {
                    val avatar=images[desktopScreenshotHeaderAvatarReference(m, r.state)]
                    if(avatar!=null)Image(avatar,null,Modifier.size(30.dp).clip(CircleShape), contentScale=ContentScale.Crop)
                    else Box(Modifier.size(30.dp).background(c.muted,CircleShape),contentAlignment=Alignment.Center) { BasicText(p.speakerLabel.take(1),style=TextStyle(color=c.foreground)) }
                    BasicText(p.speakerLabel,Modifier.padding(start=6.dp),style=TextStyle(color=c.mutedForeground,fontSize=12.sp))
                }
                Column(Modifier.then(if(p.enclosingCard) Modifier
                    .background((if(m.role==MessageRole.USER)c.primary else c.card).copy(alpha=.6f),
                        RoundedCornerShape(if(m.role==MessageRole.USER)10.dp else 3.dp,10.dp,10.dp,if(m.role==MessageRole.USER)3.dp else 10.dp))
                    .padding(horizontal=12.dp,vertical=10.dp) else Modifier.fillMaxWidth()), verticalArrangement=Arrangement.spacedBy(7.dp)) {
                m.images.forEachIndexed { index,ref -> if(roleplayImageBlockId(m.id,index) in r.selected) {
                    val bitmap=images[ref] ?: error("截图图片未加载")
                    Image(bitmap,null,Modifier.fillMaxWidth().heightIn(max=400.dp))
                } }
                p.segments.forEachIndexed { index,seg ->
                    val id=if(p.segmented)checkNotNull(seg.blockId) else roleplayLegacyTextBlockId(m.id)
                    if(id in r.selected && desktopScreenshotIncludes(seg, r.contentOptions)) DesktopRoleplaySegmentSurface(seg.kind,p.segmented,index in speakerHeaders,seg.speaker?.displayName,"export-$id",avatar={
                        val ref=desktopPresentedAvatarReference(seg.speaker,r.state.selectedCharacter)
                        val bitmap=images[ref]
                        if(bitmap!=null)Image(bitmap,null,Modifier.size(40.dp).clip(CircleShape), contentScale=ContentScale.Crop)
                        else Box(Modifier.size(40.dp).background(c.muted,CircleShape),contentAlignment=Alignment.Center){BasicText((seg.speaker?.displayName?:p.speakerLabel).take(1),style=TextStyle(color=c.foreground))}
                    }) { color,style ->
                        if(seg.kind==RoleplaySegmentKind.STATUS) {
                            BasicText(desktopScreenshotStatusLabel(seg),style=TextStyle(color=c.mutedForeground,fontSize=12.sp))
                            BasicText(seg.text,style=style.copy(color=color))
                        } else DesktopMarkdownText(seg.markdownSource,if(!p.segmented && m.role==MessageRole.USER) Color.White else color,typography=DesktopChatTypography(r.state.chatBubbleFontScale),textStyle=style)
                    }
                }
                }
            }
            }
        }
    }
}
