@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.*
import com.example.chatbar.data.local.entity.*
import java.nio.file.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopVoiceUxTest {
    @Test fun `MCI resumes paused alias without opening or playing from zero again`() = runBlocking {
        val calls=mutableListOf<String>();var mode="playing"
        val out=DesktopWindowsAudioOutput { c-> calls+=c;when {
            c.contains(" length ")->"12000"
            c.startsWith("pause ")->{mode="paused";""}
            c.startsWith("resume ")->{mode="playing";""}
            c.contains(" mode ")->mode
            c.contains(" position ")->"4321"
            else->""
        } }
        try {out.playFormal(Path.of("synthetic.mp3"));assertEquals(4321L,out.pause());out.resume()
            assertEquals(4321L,out.positionMs());assertEquals(1,calls.count{it.startsWith("open ")})
            assertEquals(1,calls.count{it.contains("from 0")});assertEquals(1,calls.count{it.startsWith("resume ")})
        } finally {out.closeAndDrain()}
    }
    @Test fun `manual queue crosses user turn using only current assistant audio and no network`()=runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f->
            f.seed();f.enqueue();f.done(f.runtime.generate(f.message,"alice"))
            f.chats.addMessage(ChatMessage("user",f.session.id,MessageRole.USER,"skip",createdAt=2,updatedAt=2))
            val next=f.chats.addMessage(ChatMessage("next",f.session.id,MessageRole.ASSISTANT,"<n=\"Alice\"/>[next]()",createdAt=3,updatedAt=3))
            f.enqueue();f.done(f.runtime.generate(next,"alice"))
            val a=f.repo.placementsForMessage(f.message).single().voice;val b=f.repo.placementsForMessage(next).single().voice
            val requests=f.server.requestCount;f.runtime.foreground(f.session.id).join();f.runtime.play(a,f.message)?.join()
            withTimeout(5000){while(!f.runtime.playback.state.value.playing)delay(10)}
            assertEquals(listOf(a.id,b.id),f.runtime.playback.state.value.queueVoiceIds)
            f.output.playing=false;withTimeout(5000){while(f.runtime.playback.state.value.currentVoiceId!=b.id)delay(10)}
            assertEquals(requests,f.server.requestCount);f.runtime.foreground(null).join();assertFalse(f.output.playing)
        }
    }
    @Test fun `pause keeps real position and queue then resume or second stop releases session`()=runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f->
            f.seed();f.enqueue();f.done(f.runtime.generate(f.message,"alice"))
            val v=f.repo.placementsForMessage(f.message).single().voice
            f.runtime.playback.playSequence(listOf(v,v))
            withTimeout(3000){while(!f.runtime.playback.state.value.playing)delay(10)}
            val plays=f.output.plays
            f.runtime.playback.pause();delay(220)
            assertTrue(f.runtime.playback.state.value.paused)
            assertEquals(1234L,f.runtime.playback.state.value.positionMs)
            assertEquals(listOf(v.id,v.id),f.runtime.playback.state.value.queueVoiceIds)
            assertEquals(plays,f.output.plays)
            f.runtime.playback.resume();assertTrue(f.runtime.playback.state.value.playing)
            assertEquals(plays,f.output.plays)
            f.runtime.playback.pause();f.runtime.playback.stop()
            assertNull(f.runtime.playback.state.value.currentVoiceId);assertFalse(f.output.playing)
        }
    }
    private val edt=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable)=SwingUtilities.invokeLater(block)}
    @Test fun `single row deck and paused player fit compact and wide viewports`()=runBlocking(edt) {
        for(w in listOf(360,1100)) {
            var resumes=0;var stops=0
            val scene=ImageComposeScene(w,160){Column {
                DesktopVoiceDeckRow("SYNTHESIZING 1/5",{},{}, narrator={})
                DesktopVoicePlayerRow(DesktopVoicePlaybackState("v",listOf("v"),paused=true,positionMs=4321),{resumes++},{stops++})
            }}
            try {repeat(4){scene.render(System.nanoTime()).close();yield()}
                fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
                val nodes=scene.semanticsOwners.flatMap{walk(it.rootSemanticsNode)}
                fun node(s:String)=nodes.single{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s)==true}
                val p=node("继续播放");val s=node("停止并关闭播放器")
                assertEquals(p.boundsInRoot.top,s.boundsInRoot.top);assertTrue(s.boundsInRoot.right<=w)
                assertTrue(p.config.contains(SemanticsActions.RequestFocus))
                p.config[SemanticsActions.OnClick].action!!();s.config[SemanticsActions.OnClick].action!!()
                assertEquals(1,resumes);assertEquals(1,stops)
            }finally{scene.close()}
        }
    }
}
class DesktopVoicePauseWindowsTest {
    @Test fun `native Windows position remains frozen on pause and advances on resume`()=runBlocking {
        val p=Files.createTempFile("ccb-native-pause-",".mp3");Files.write(p,fishPreviewMp3(500));val out=DesktopWindowsAudioOutput()
        try {out.playFormal(p);withTimeout(4000){while(out.positionMs()<100)delay(20)}
            val at=out.pause();assertTrue(at>0);delay(180);assertEquals(at,out.positionMs());out.resume()
            withTimeout(4000){while(out.positionMs()<=at)delay(20)}
            println("NATIVE_PAUSE_MS=$at NATIVE_RESUMED_MS=${out.positionMs()}")
            out.stopAndDrain();java.io.RandomAccessFile(p.toFile(),"rw").use{f->f.channel.tryLock().use{assertNotNull(it)}}
        }finally{out.closeAndDrain();Files.deleteIfExists(p)}
        Unit
    }
}
