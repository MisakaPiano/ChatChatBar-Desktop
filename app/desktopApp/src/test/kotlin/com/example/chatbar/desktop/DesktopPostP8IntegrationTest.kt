@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopPostP8IntegrationTest {
    private val edt=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable)=SwingUtilities.invokeLater(block)}
    private fun ImageComposeScene.nodes():List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return semanticsOwners.flatMap{walk(it.rootSemanticsNode)}
    }
    private fun SemanticsNode.label(s:String)=config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s)==true
    private suspend fun ImageComposeScene.until(check:()->Boolean){withTimeout(5000){while(true){render(System.nanoTime()).close();yield();if(check())break;delay(10)}}}
    @Test fun `same popup closes once on Escape or outside and clamps narrow width`():Unit=runBlocking(edt) {
        for(w in listOf(360,1100)) {
            var open by mutableStateOf(true);var closes=0;var actions=0
            val rows=listOf("回复","本段","整条").map{it to listOf(DesktopChatMenuEntry("复制",DesktopAppIcons.Copy,true){actions++})}
            val scene=ImageComposeScene(w,400){Box(Modifier.fillMaxSize()){if(open)DesktopChatContextPopup(Rect(0f,0f,10f,10f),rows){closes++;open=false}}}
            try {
                scene.until{scene.nodes().any{it.label("整条 · 复制")}}
                assertTrue(scene.nodes().single{it.label("整条 · 复制")}.boundsInRoot.right<=w)
                println("CONTEXT_FOCUS_BEFORE width=$w focused=${scene.nodes().filter{it.config.getOrNull(SemanticsProperties.Focused)==true}.map{it.id}}")
                scene.until{scene.nodes().any{it.config.getOrNull(SemanticsProperties.Focused)==true}}
                println("CONTEXT_FOCUS_READY width=$w focused=${scene.nodes().filter{it.config.getOrNull(SemanticsProperties.Focused)==true}.map{it.id}}")
                scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp))
                scene.until{!open};assertEquals(1,closes)
                open=true;scene.until{scene.nodes().any{it.label("整条 · 复制")}}
                val outside=Offset(w-1f,399f)
                scene.sendPointerEvent(PointerEventType.Press,outside,button=PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release,outside,button=PointerButton.Primary)
                scene.until{!open};assertEquals(2,closes);assertEquals(0,actions)
            }finally{scene.close()}
        }
    }
    @Test fun `multi character narration still needs explicit choice when only one voice is bound`():Unit=runBlocking(edt) {
        DesktopChatVoiceRuntimeTest.Fixture().use{f->
            f.seed();val card=f.characters.getById("card")!!.let{it.copy(characters=it.characters+CharacterInfo("bob","Bob"))}
            val message=f.message.copy(content="旁白。<n=\"Alice\"/>[hello]()")
            val state=DesktopPrimaryChatState(selectedSession=f.session,selectedCharacter=card)
            var open by mutableStateOf(true)
            val scene=ImageComposeScene(360,800){if(open)DesktopVoiceGenerationDialog(f.runtime,message,state,null){open=false}}
            try {
                scene.until{scene.nodes().any{it.label("授权并开始生成")}}
                fun auth()=scene.nodes().single{it.label("授权并开始生成")}
                assertTrue(auth().config.contains(SemanticsProperties.Disabled))
                scene.nodes().single{it.label("选择角色音色 · 尚未选择")}.config[SemanticsActions.OnClick].action!!()
                scene.until{scene.nodes().any{it.label("Alice · snapshot")}}
                scene.nodes().single{it.label("Alice · snapshot")}.config[SemanticsActions.OnClick].action!!()
                scene.until{!auth().config.contains(SemanticsProperties.Disabled)}
                assertEquals(0,f.server.requestCount)
                auth().config[SemanticsActions.RequestFocus].action!!()
                scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp))
                scene.until{!open};assertEquals(0,f.server.requestCount)
            }finally{scene.close()}
        }
    }
    @Test fun `effective audiobook controls single line right role choice and hides it when off`():Unit=runBlocking(edt) {
        DesktopChatVoiceRuntimeTest.Fixture().use{f->
            f.seed();val card=f.characters.getById("card")!!
            for(w in listOf(240,360,1100)) for(enabled in listOf(false,true)) {
                val state=DesktopPrimaryChatState(selectedSession=f.session.copy(audiobookModeEnabled=enabled),selectedCharacter=card)
                val scene=ImageComposeScene(w,160){DesktopVoiceDeck(f.runtime,state)}
                try {
                    repeat(4){scene.render(System.nanoTime()).close();yield()}
                    val deck=scene.nodes().filter{it.config.getOrNull(SemanticsProperties.TestTag)=="voice-role-dock"}
                    assertTrue(scene.nodes().none{it.config.getOrNull(SemanticsProperties.TestTag)=="voice-generation-deck"})
                    assertEquals(if(enabled)1 else 0,deck.size)
                    if(enabled){assertEquals(40f,deck.single().boundsInRoot.height);assertTrue(deck.single().boundsInRoot.right<=w)}
                    assertEquals(0,f.server.requestCount)
                }finally{scene.close()}
            }
        }
    }
    @Test fun `details expose independent controls for every batch including text confirmation`():Unit=runBlocking(edt) {
        val actions=mutableListOf<String>()
        val a=VoiceGenerationBatchState("a","s","m",VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION,
            textConfirmations=listOf(VoiceTextConfirmationState("t","Alice","hello","[happy] hello")))
        val b=VoiceGenerationBatchState("b","s","n",VoiceGenerationPhase.FAILED,errors=mapOf("t" to "synthetic failure"))
        val scene=ImageComposeScene(360,600){Column {
            DesktopVoiceBatchDetails(a,1,{actions+="a:$it"},{actions+="cancel:a"})
            DesktopVoiceBatchDetails(b,2,{actions+="b:$it"},{actions+="cancel:b"})
        }}
        try {
            scene.until{scene.nodes().any{it.label("任务 2 · 取消或关闭本批语音")}}
            scene.nodes().single{it.label("任务 1 · 采用 AI 文本并生成")}.config[SemanticsActions.OnClick].action!!()
            scene.nodes().single{it.label("任务 2 · 取消或关闭本批语音")}.config[SemanticsActions.OnClick].action!!()
            assertEquals(listOf("a:true","cancel:b"),actions)
        }finally{scene.close()}
    }
    @Test fun `replacement feedback identifies committed target and is cleared before failed replacement`():Unit=runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use{f->
            f.seed();f.enqueue();f.done(f.runtime.generate(f.message,"alice"))
            val old=f.repo.placementsForMessage(f.message).single().voice
            f.enqueue();f.done(f.runtime.generate(f.message,"alice",replace=old,editedTaggedText="hello"))
            val current=f.repo.get(old.id)!!
            val event=assertNotNull(f.runtime.coordinator.feedback.value)
            assertEquals(current.id,event.voiceId);assertEquals(current.sessionId,event.sessionId)
            assertTrue(event.text.contains("替换成功"));assertNotEquals(old.audioPath,current.audioPath)
            val requests=f.server.requestCount
            f.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            f.done(f.runtime.generate(f.message,"alice",replace=current,editedTaggedText="hello"))
            assertNull(f.runtime.coordinator.feedback.value)
            assertEquals(requests+1,f.server.requestCount);assertEquals(current,f.repo.get(current.id))
        }
    }

    @Test fun `cross reply playback refuses a starting voice owned by another message`():Unit=runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use{f->
            f.seed();f.enqueue();f.done(f.runtime.generate(f.message,"alice"))
            val next=f.chats.addMessage(ChatMessage("later-owner",f.session.id,MessageRole.ASSISTANT,"<n=\"Alice\"/>[next]()",createdAt=3,updatedAt=3))
            f.enqueue();f.done(f.runtime.generate(next,"alice"))
            val foreign=f.repo.placementsForMessage(next).single().voice
            f.runtime.foreground(f.session.id).join();val requests=f.server.requestCount
            assertNull(f.runtime.play(foreign,f.message));assertEquals(0,f.output.plays)
            assertEquals(requests,f.server.requestCount);assertNull(f.runtime.playback.state.value.currentVoiceId)
        }
    }

    @Test fun `message mutation invalidates an old play request waiting for player admission`():Unit=runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use{f->
            f.seed();f.enqueue();f.done(f.runtime.generate(f.message,"alice"))
            val voice=f.repo.placementsForMessage(f.message).single().voice
            f.runtime.foreground(f.session.id).join()
            val original=f.runtime.playbackIntent()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val holding=async(Dispatchers.IO){f.runtime.playback.withVoiceReleased("unrelated"){entered.complete(Unit);release.await()}}
            entered.await()
            val delayed=f.runtime.coordinator.playFrom(voice,listOf(voice),original)
            withTimeout(3000){while(f.runtime.playbackIntent()==original)delay(10)}
            val beforeMutation=f.runtime.playbackIntent()
            val mutation=async{f.runtime.mutateMessage(f.session.id,f.message.id){}}
            try {
                withTimeout(3000){while(f.runtime.playbackIntent()==beforeMutation)delay(10)}
            }finally{release.complete(Unit)}
            holding.await();delayed.join();mutation.await()
            assertEquals(0,f.output.plays);assertNull(f.runtime.playback.state.value.currentVoiceId)
        }
    }

}
