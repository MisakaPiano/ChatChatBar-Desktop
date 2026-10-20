@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopChatScreenshotUiTest {
    private val edt=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable)=SwingUtilities.invokeLater(block)}
    @Test fun `screenshot internal close returns to host content without invoking application close`():Unit=runBlocking(edt) {
        val f=DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize();val c=f.c.primaryChatController;c.refresh();c.selectSession("session")
            var appCloseRequests=0
            val scene=ImageComposeScene(900,700) {
                DesktopChatScreenshotHost(c,c.state.value) {
                    val start=LocalDesktopChatScreenshot.current
                    StudioAction("打开截图"){start!!(f.source,null)}
                    StudioAction("模拟主窗口关闭请求"){appCloseRequests++}
                }
            }
            fun nodes():List<SemanticsNode> {
                fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap{walk(it.rootSemanticsNode)}
            }
            fun actions(label:String)=nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true && it.config.contains(SemanticsActions.OnClick) }
            suspend fun until(check:()->Boolean){withTimeout(7000){while(true){scene.render(System.nanoTime()).close();yield();if(check())break;delay(10)}}}
            try {
                until{actions("打开截图").isNotEmpty()}
                actions("打开截图").single().config[SemanticsActions.OnClick].action!!()
                until{actions("关闭").isNotEmpty()}
                actions("关闭").single().config[SemanticsActions.OnClick].action!!()
                until{actions("关闭").none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("模拟主窗口关闭请求")!=true }}
                assertEquals(0,appCloseRequests)
                actions("模拟主窗口关闭请求").single().config[SemanticsActions.OnClick].action!!()
                assertEquals(1,appCloseRequests);assertTrue(f.requests.isEmpty())
            } finally {scene.close()}
        } finally {f.close()}
    }
    @Test fun `compact and wide selection previews PNG with keyboard dismissal and no generation`():Unit=runBlocking(edt) {
        val f=DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize();val c=f.c.primaryChatController;c.refresh();c.selectSession("session")
            for(width in listOf(360,1100)) {
                val state=c.state.value
                val scene=ImageComposeScene(width,800) {
                    DesktopChatScreenshotHost(c,state) {
                        val start=LocalDesktopChatScreenshot.current
                        StudioAction("打开截图"){start!!(f.source,null)}
                    }
                }
                fun nodes():List<SemanticsNode> {
                    fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap{walk(it.rootSemanticsNode)}
                }
                fun node(label:String)=nodes().single {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true && it.config.contains(SemanticsActions.OnClick)}
                suspend fun until(check:()->Boolean){withTimeout(7000){while(true){scene.render(System.nanoTime()).close();yield();if(check())break;delay(10)}}}
                try {
                    until{nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("打开截图")==true}}
                    node("打开截图").config[SemanticsActions.OnClick].action!!()
                    until{nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{s->s.startsWith("预览 PNG") }==true}}
                    val preview=nodes().single{it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{s->s.startsWith("预览 PNG") }==true}
                    assertTrue(preview.boundsInRoot.right<=width)
                    preview.config[SemanticsActions.OnClick].action!!()
                    until{nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("保存 PNG")==true}}
                    assertTrue(node("保存 PNG").config.contains(SemanticsActions.RequestFocus))
                    println("SCREENSHOT_KEYBOARD width=$width focused=${nodes().filter { it.config.getOrNull(SemanticsProperties.Focused)==true }.map { it.id }}")
                    scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp))
                    until{nodes().none{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("保存 PNG")==true}}
                    assertTrue(f.requests.isEmpty())
                }finally{scene.close()}
            }
        }finally{f.close()}
    }
}
