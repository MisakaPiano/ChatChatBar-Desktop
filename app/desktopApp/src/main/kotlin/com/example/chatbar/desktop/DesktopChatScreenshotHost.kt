package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Image as SkiaImage

/** Selection owns immutable message/version snapshots. Preview/export fail if ownership changes. */
@Composable
internal fun DesktopChatScreenshotHost(controller: DesktopPrimaryChatController, state: DesktopPrimaryChatState,
    content: @Composable ()->Unit) {
    val scope=rememberCoroutineScope()
    var messages by remember(state.selectedSession?.id) { mutableStateOf<List<ChatMessage>?>(null) }
    var voices by remember(state.selectedSession?.id) { mutableStateOf<Map<String,List<VoiceMessagePlacement>>>(emptyMap()) }
    var selected by remember(state.selectedSession?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember(state.selectedSession?.id) { mutableStateOf<Map<String,Boolean>>(emptyMap()) }
    var png by remember(state.selectedSession?.id) { mutableStateOf<DesktopScreenshotPng?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val colors=LocalDesktopPalette.current
    val screenshotFocus=remember { FocusRequester() }
    val start:(ChatMessage,String?)->Unit = { clicked,block ->
        scope.launch {
            if(!busy) {
                busy=true;error=null;saved=false
                try {
                    val timeline=controller.screenshotTimeline()
                    check(timeline.any { it==clicked }) { "消息已变化，请重新选择" }
                    val currentVoices=linkedMapOf<String,List<VoiceMessagePlacement>>()
                    for(m in timeline) currentVoices[m.id]=controller.voices?.repository?.placementsForMessage(m).orEmpty()
                    val ids=roleplayScreenshotBlockIds(clicked,state.assistantSegmentedBubblesEnabled,currentVoices[clicked.id].orEmpty().map { it.voice.id })
                    check(block==null || block in ids) { "截图片段已变化，请重新选择" }
                    messages=timeline;voices=currentVoices;selected=if(block==null)ids.toSet() else setOf(block);expanded=emptyMap();png=null
                } catch(e:CancellationException){throw e}
                catch(_:Exception){error="无法读取截图内容，请检查消息和资源后重新选择"}
                finally{busy=false}
            }
        }
    }
    CompositionLocalProvider(LocalDesktopChatScreenshot provides start, content=content)
    val snapshot=messages
    if(snapshot!=null) Popup(alignment=Alignment.Center, onDismissRequest={if(!busy){messages=null;png=null;error=null}}, properties=PopupProperties(focusable=true)) {
        LaunchedEffect(snapshot,png) { screenshotFocus.requestFocus() }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if(event.key == Key.Escape && event.type == KeyEventType.KeyDown && !busy) { messages=null;png=null;error=null;true } else false
        }.focusRequester(screenshotFocus).focusable().background(DesktopBootstrapColors.background).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                BasicText(if(png==null)"选择长截图内容" else "PNG 截图预览",Modifier.weight(1f),style=TextStyle(color=DesktopBootstrapColors.foreground))
                StudioAction("关闭",enabled=!busy) { messages=null;png=null;error=null }
            }
            error?.let{StatusText(it,DesktopBootstrapColors.destructive)}
            if(saved)StatusText("PNG 已保存")
            val result=png
            if(result==null) {
                val all=snapshot.flatMap { m->roleplayScreenshotBlockIds(m,state.assistantSegmentedBubblesEnabled,voices[m.id].orEmpty().map{it.voice.id}) }.toSet()
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(snapshot,key={it.id}) { m ->
                        val ids=roleplayScreenshotBlockIds(m,state.assistantSegmentedBubblesEnabled,voices[m.id].orEmpty().map{it.voice.id})
                        if(ids.isNotEmpty()) Column {
                            StudioAction("整条 · ${m.role} · ${m.displayContent.take(36)}",selected=ids.all{it in selected},enabled=!busy) {
                                selected=toggleChatScreenshotMessageSelection(selected,ids,all)
                            }
                            ids.forEach { id->
                                val label=when {
                                    "::image::" in id->"图片 ${id.substringAfterLast("::")}"
                                    "::voice::" in id->"录音 ${voices[m.id].orEmpty().firstOrNull{roleplayVoiceBlockId(m.id,it.voice.id)==id}?.voice?.characterName.orEmpty()}"
                                    id.endsWith("::legacy")->m.displayContent.take(80)
                                    else->parseRoleplayTextSegments(m.displayContent).getOrNull(id.substringAfterLast("::").toIntOrNull()?:-1)?.displayText?.take(80).orEmpty()
                                }
                                StudioAction("${if(id in selected) "☑" else "☐"} $label",enabled=!busy) { selected=toggleChatScreenshotSelection(selected,id,all) }
                                val segmentIndex=id.substringAfterLast("::").toIntOrNull()
                                val seg=segmentIndex?.let{parseRoleplayTextSegments(m.displayContent).getOrNull(it)}
                                if("::text::" in id && seg?.kind==RoleplaySegmentKind.STATUS) StudioAction("截图中的状态：${if(expanded[id]?:seg.statusDefaultExpanded)"展开" else "收起"}",enabled=!busy) {
                                    expanded=expanded+(id to !(expanded[id]?:seg.statusDefaultExpanded))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    StudioAction("取消选择",enabled=!busy){selected=emptySet()}
                    StudioAction(if(busy)"正在生成预览…" else "预览 PNG · ${selected.size}项",enabled=!busy && selected.isNotEmpty()) {
                        scope.launch {
                            busy=true;error=null
                            try {
                                val chosen=orderedChatScreenshotMessages(snapshot,selected,state.assistantSegmentedBubblesEnabled)
                                controller.verifyScreenshotMessages(chosen,voices)
                                val preferred=state.selectedCharacter?.id?.let{controller.backgroundLibrary?.preferred(it)}
                                val background=desktopEffectiveBackground(state.selectedSession?.chatBackground,preferred,state.selectedCharacter?.chatBackground).first
                                val request=DesktopScreenshotRequest(state.selectedSession?.title?:"聊天",state,chosen,selected,voices,expanded,background)
                                val rendered=DesktopChatScreenshotRenderer.render(request,colors,{ ref -> controller.characterResources.readScreenshotImageBytes(ref) })
                                controller.verifyScreenshotMessages(chosen,voices)
                                png=rendered
                            } catch(e:CancellationException){throw e}
                            catch(_:Exception){error="截图生成失败或内容已变化；请减少选择、检查图片资源后重试，原数据未修改"}
                            finally{busy=false}
                        }
                    }
                }
            } else {
                val image=remember(result){SkiaImage.makeFromEncoded(result.bytes)}
                DisposableEffect(image){onDispose{image.close()}}
                BoxWithConstraints(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Image(image.toComposeImageBitmap(),"聊天截图预览",Modifier.width(maxWidth).height((result.height.toFloat()/result.width*maxWidth.value).coerceAtLeast(1f).dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    StudioAction("返回选择",enabled=!busy){png=null;saved=false}
                    StudioAction("保存 PNG",enabled=!busy){scope.launch {
                        busy=true;error=null
                        try {
                            val chosen=orderedChatScreenshotMessages(snapshot,selected,state.assistantSegmentedBubblesEnabled)
                            controller.verifyScreenshotMessages(chosen,voices)
                            val target=withContext(Dispatchers.IO){controller.imagePicker.pickSaveFile(DesktopFileType("PNG 图片",listOf("png")),buildChatScreenshotFileName(state.selectedSession?.title?:"聊天"))}
                            if(target!=null){controller.verifyScreenshotMessages(chosen,voices);withContext(Dispatchers.IO){DesktopChatScreenshotRenderer.save(result,target)};saved=true}
                        }catch(e:CancellationException){throw e}
                        catch(_:Exception){error="无法保存 PNG；请确认新文件名、目录和消息版本，预览已保留"}
                        finally{busy=false}
                    }}
                }
            }
        }
    }
    else error?.let { text->Row(Modifier.background(DesktopBootstrapColors.card).padding(8.dp)){StatusText(text);StudioAction("关闭提示"){error=null}} }
}
