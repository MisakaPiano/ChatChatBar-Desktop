@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.chat.*
import org.jetbrains.skia.EncodedImageFormat
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
    val statusExpanded: Map<String,Boolean> = emptyMap(), val background: String? = null,
)
internal data class DesktopScreenshotPng(val bytes: ByteArray, val width: Int, val height: Int)

/** Static CCB rendering, never OS/Robot capture. Every resource is supplied by an owned resource reader. */
internal object DesktopChatScreenshotRenderer {
    const val MAX_HEIGHT = 32_000
    const val MAX_PIXELS = 8_000_000L
    private val edt = object: CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    fun validatePlan(width: Int, height: Int) {
        require(width in 320..1600 && height in 1..MAX_HEIGHT && width.toLong()*height <= MAX_PIXELS) { "截图尺寸超过安全限制，请减少选择内容" }
    }
    suspend fun render(request: DesktopScreenshotRequest, colors: DesktopSemanticColors,
        read: (String)->ByteArray, width: Int = 640): DesktopScreenshotPng {
        require(width in 320..1600)
        require(request.messages.isNotEmpty() && request.messages.sumOf { it.displayContent.length.toLong() } <= 200_000)
        require(request.selected.isNotEmpty()) { "请选择截图内容" }
        val valid=request.messages.flatMap { roleplayScreenshotBlockIds(it,request.state.assistantSegmentedBubblesEnabled,
            request.voices[it.id].orEmpty().map { p->p.voice.id }) }.toSet()
        require(request.selected.all { it in valid }) { "截图选择已失效" }
        val images=linkedMapOf<String,SkiaImage>()
        var resourcePixels=0L
        var encodedBytes=0L
        try {
            val references=buildSet {
                request.background?.let(::add)
                request.messages.forEach { m ->
                    m.images.forEachIndexed { i,ref->if(roleplayImageBlockId(m.id,i) in request.selected) add(ref) }
                    val presented=desktopPresentMessage(m,request.state.selectedCharacter,request.state.globalPlayerName,
                        request.state.assistantSegmentedBubblesEnabled,session=request.state.selectedSession)
                    if(m.role==MessageRole.ASSISTANT) presented.segments.forEach { seg->desktopPresentedAvatarReference(seg.speaker,request.state.selectedCharacter)?.let(::add) }
                }
            }
            require(references.size<=40) { "截图图片数量超过限制" }
            withContext(Dispatchers.IO) { references.forEach { ref->
                val bytes=read(ref);encodedBytes+=bytes.size;require(encodedBytes<=32L*1024*1024) { "截图资源总大小超过限制" }
                val image=SkiaImage.makeFromEncoded(bytes)
                if (image.width.toLong()*image.height>MAX_PIXELS || resourcePixels+image.width.toLong()*image.height>MAX_PIXELS) {
                    image.close();error("截图图片像素超过安全限制")
                }
                resourcePixels+=image.width.toLong()*image.height;images[ref]=image
            } }
            return withContext(edt) {
                var height=0
                val body:@Composable ()->Unit = {
                    CompositionLocalProvider(LocalDesktopPalette provides colors) {
                        DesktopScreenshotContent(request,images.mapValues { it.value.toComposeImageBitmap() },Modifier
                            .width(width.dp).wrapContentHeight(unbounded=true).onSizeChanged { height=it.height })
                    }
                }
                // Measurement uses a one-pixel surface; no unbounded raster allocation.
                renderComposeScene(width,1,content=body).close()
                validatePlan(width,height)
                val measured=height
                renderComposeScene(width,measured,content=body).use { image->
                    check(height==measured) { "截图布局发生变化，请重新预览" }
                    val data=checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
                    data.use { DesktopScreenshotPng(it.bytes,width,measured) }
                }
            }
        } finally { images.values.forEach { it.close() } }
    }

    /** Explicit new destination only; no external file overwrite and no broad cleanup. */
    fun save(png: DesktopScreenshotPng, target: Path) {
        val absolute=target.toAbsolutePath().normalize()
        require(absolute.fileName.toString().endsWith(".png",true)) { "请选择 PNG 文件名" }
        require(!Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) { "文件已存在，请选择新文件名" }
        val parent=absolute.parent.toRealPath();require(parent==absolute.parent) { "保存目录已变化" }
        val part=parent.resolve(".ccb-screenshot-${UUID.randomUUID()}.part")
        try {
            Files.newOutputStream(part,StandardOpenOption.CREATE_NEW).use { it.write(png.bytes) }
            check(parent.toRealPath()==parent)
            Files.move(part,absolute)
        } finally { Files.deleteIfExists(part) }
    }
}

@Composable
private fun DesktopScreenshotContent(r:DesktopScreenshotRequest, images:Map<String,ImageBitmap>,modifier:Modifier) {
    val c=LocalDesktopPalette.current
    Column(modifier.background(c.background).drawBehind {
        images[r.background]?.let { bitmap ->
            val w=size.width.toInt().coerceAtLeast(1)
            val h=(bitmap.height.toDouble()*w/bitmap.width).toInt().coerceAtLeast(1)
            var y=0
            while(y<size.height) { drawImage(bitmap,dstOffset=IntOffset(0,y),dstSize=IntSize(w,h));y+=h }
            drawRect(c.background.copy(alpha=.84f))
        }
    }) {
        BasicText(r.title,Modifier.fillMaxWidth().background(c.card).padding(12.dp),style=TextStyle(color=c.foreground,fontSize=18.sp))
        r.messages.forEach { m ->
            val p=desktopPresentMessage(m,r.state.selectedCharacter,r.state.globalPlayerName,r.state.assistantSegmentedBubblesEnabled,session=r.state.selectedSession)
            val selectedForMessage=r.selected.filter { roleplayBlockMessageId(it)==m.id }
            if(selectedForMessage.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp),
                horizontalAlignment=if(m.role==MessageRole.USER)Alignment.End else Alignment.Start) {
                if(p.showWholeMessageHeader) Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.role==MessageRole.USER)Arrangement.End else Arrangement.Start,
                    verticalAlignment=Alignment.CenterVertically) {
                    val avatar=if(m.role==MessageRole.ASSISTANT) images[r.state.selectedCharacter?.avatar] else null
                    if(avatar!=null)Image(avatar,null,Modifier.size(36.dp))
                    else Box(Modifier.size(36.dp).background(c.muted,CircleShape),contentAlignment=Alignment.Center) { BasicText(p.speakerLabel.take(1),style=TextStyle(color=c.foreground)) }
                    BasicText(p.speakerLabel,Modifier.padding(start=6.dp),style=TextStyle(color=c.mutedForeground,fontSize=12.sp))
                }
                m.images.forEachIndexed { index,ref -> if(roleplayImageBlockId(m.id,index) in r.selected) {
                    val bitmap=images[ref] ?: error("截图图片未加载")
                    Image(bitmap,null,Modifier.fillMaxWidth().heightIn(max=400.dp))
                } }
                if(p.segmented) DesktopScreenshotVoiceRows(r,m,r.voices[m.id].orEmpty().filter { it.segmentIndex==null })
                p.segments.forEachIndexed { index,seg ->
                    val id=if(p.segmented)checkNotNull(seg.blockId) else roleplayLegacyTextBlockId(m.id)
                    if(id in r.selected) DesktopRoleplaySegmentSurface(seg.kind,p.segmented,index in p.speakerHeaderIndexes,seg.speaker?.displayName,"export-$id",avatar={
                        val ref=desktopPresentedAvatarReference(seg.speaker,r.state.selectedCharacter)
                        val bitmap=images[ref]
                        if(bitmap!=null)Image(bitmap,null,Modifier.size(40.dp))
                        else Box(Modifier.size(40.dp).background(c.muted,CircleShape),contentAlignment=Alignment.Center){BasicText((seg.speaker?.displayName?:p.speakerLabel).take(1),style=TextStyle(color=c.foreground))}
                    }) { color,style ->
                        if(seg.kind==RoleplaySegmentKind.STATUS) {
                            BasicText("状态 / 选项",style=TextStyle(color=c.mutedForeground,fontSize=12.sp))
                            if(r.statusExpanded[id]?:seg.statusDefaultExpanded)BasicText(seg.text,style=style.copy(color=color))
                        } else DesktopMarkdownText(seg.markdownSource,color,typography=DesktopChatTypography(r.state.chatBubbleFontScale),textStyle=style)
                    }
                    if(p.segmented) DesktopScreenshotVoiceRows(r,m,r.voices[m.id].orEmpty().filter { it.segmentIndex==seg.index && it.segmentIndex!=null })
                }
                if(!p.segmented) DesktopScreenshotVoiceRows(r,m,r.voices[m.id].orEmpty())
            }
        }
    }
}

@Composable
private fun DesktopScreenshotVoiceRows(r:DesktopScreenshotRequest,m:ChatMessage,rows:List<VoiceMessagePlacement>) {
    rows.filter { roleplayVoiceBlockId(m.id,it.voice.id) in r.selected }.forEach { v->
        BasicText("▶ ${v.voice.characterName} · ${v.voice.durationMs/1000}s",Modifier.border(1.dp,DesktopBootstrapColors.border).padding(8.dp),
            style=TextStyle(color=DesktopBootstrapColors.foreground,fontSize=12.sp))
    }
}
