package com.example.chatbar.ui.chat

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.image.GlobalImageGenerationConcurrencyGate
import com.example.chatbar.domain.voice.*
import com.example.chatbar.ui.components.ChatBubble
import com.example.chatbar.ui.components.ChatBubbleSegmentAction
import com.example.chatbar.ui.kit.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production controls/registry/commit; reply and TTS providers are synthetic barriers, not paid transports. */
class ChatGenerationConcurrencyControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stopSyntheticWork() { scope.cancel() }

    @Test fun wholeOldMessageLongPressAdmitsVoiceWhileReplyRuns() = controlChain(segmented = false)
    @Test fun oldSegmentLongPressAdmitsVoiceAndKeepsMutationsDisabled() = controlChain(segmented = true)

    private fun controlChain(segmented: Boolean) {
        val files = File(compose.activity.cacheDir, "concurrency-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(compose.activity) { override fun getFilesDir(): File = files }
        val chats = ChatRepository(JsonFileStorage(context))
        val voices = VoiceMessageRepository(JsonFileStorage(context))
        val message = ChatMessage(id = "old", sessionId = "synthetic", role = MessageRole.ASSISTANT,
            content = if (segmented) "<n=\"Synthetic\"/>[Completed old dialogue]()" else "Completed old message",
            currentAlternativeVersionId = "v1", createdAt = 1, updatedAt = 1)
        runBlocking {
            chats.createSession(ChatSession(id = "synthetic", characterCardId = "card", title = "synthetic",
                narrativeMode = ChatNarrativeMode.ADVANCE, createdAt = 1, updatedAt = 1))
            chats.addMessage(message); voices.initialize()
        }
        val source = VoiceGenerationSourceSnapshot.capture(message)
        val committer = VoiceGenerationCommitter(chats, voices, deleteUncommittedAudio = { File(it).delete() })
        val frozenReplyMode = runBlocking { requireNotNull(chats.getSession("synthetic")).narrativeMode }
        var nextMode by mutableStateOf(frozenReplyMode)
        val modeSave = AtomicReference<Job?>()
        val replyEntered = CompletableDeferred<Unit>(); val voiceEntered = CompletableDeferred<Unit>(); val releaseVoice = CompletableDeferred<Unit>()
        val reply = scope.launch { LocalDataMaintenance.enter().use { replyEntered.complete(Unit); awaitCancellation() } }
        runBlocking { replyEntered.await() }
        val jobs = VoiceBatchJobRegistry(scope)
        val voiceJob = AtomicReference<Job?>()
        val mutations = AtomicInteger(0)
        var showMessage by mutableStateOf(false)
        var selectedSegment by mutableStateOf<ChatBubbleSegmentAction?>(null)
        fun speak() {
            voiceJob.set(jobs.launch(VoiceBatchJobRegistry.Target("voice", "synthetic", "old")) {
                LocalDataMaintenance.enter().use {
                    voiceEntered.complete(Unit); releaseVoice.await()
                    val path = File(files, "synthetic.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath
                    val anchor = if (segmented) voices.ensureAnchors(message).single().anchor.id else null
                    val voice = GeneratedVoiceMessage.create("synthetic", "old", source.versionId, anchor,
                        0, "dialogue", "Synthetic", "Completed old dialogue", taggedText = "Completed old dialogue",
                        characterId = "person", characterName = "Synthetic", voice = FishAudioVoiceBinding("synthetic", "Synthetic"),
                        fishModelId = "s1", audioPath = path, durationMs = 100, byteLength = 3)
                    committer.commit(source, voice)
                }
            })
            showMessage = false; selectedSegment = null
        }
        compose.setContent {
            ChatBarTheme {
                Column(Modifier.padding(top = 40.dp)) {
                    CbText("合成新回复运行中")
                    Box(Modifier.testTag("next-mode")) {
                        ChatNarrativeModeToggle(nextMode, frozenReplyMode, enabled = true, onSelect = { selected ->
                            nextMode = selected
                            modeSave.set(scope.launch { chats.setNarrativeMode("synthetic", selected) })
                        })
                    }
                    val handlers = completedMessageLongPressActions(false,
                        onMessage = { showMessage = true }, onSegment = { selectedSegment = it })
                    ChatBubble(message, Modifier.testTag("completed-message"), showMessageMeta = false,
                        assistantSegmentedBubblesEnabled = segmented, onLongPress = handlers.message, onSegmentLongPress = handlers.segment)
                }
                if (showMessage) {
                    ChatMessageActionMenu(true, true, false, true, "为整条生成语音", true, null, true, true,
                        onDismiss = { showMessage = false }, onEdit = { mutations.incrementAndGet() }, onScreenshot = {},
                        onSpeak = ::speak, onRepair = { mutations.incrementAndGet() }, onDelete = { mutations.incrementAndGet() },
                        onRegenerate = { mutations.incrementAndGet() })
                }
                selectedSegment?.let { segment ->
                    CbAnchoredActionPopup(segment.anchorBounds, { selectedSegment = null }, title = "片段操作") {
                        SegmentActionRow(SegmentActionScope.Segment, listOf(
                            SegmentMenuAction("编辑", "编辑本段", AppIcons.Edit, mutatesMessage = true, onClick = { mutations.incrementAndGet() }),
                            SegmentMenuAction("删除", "删除本段", AppIcons.Delete, mutatesMessage = true, onClick = { mutations.incrementAndGet() }),
                            SegmentMenuAction("语音", "为本段生成语音", AppIcons.Volume, onClick = ::speak)
                        ), responding = true)
                    }
                }
            }
        }
        compose.onNodeWithTag("next-mode").performTouchInput { click() }
        compose.onNodeWithContentDescription("叙事模式，${ChatNarrativeMode.FOCUS.label}")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "本次${frozenReplyMode.shortLabel}，下次${ChatNarrativeMode.FOCUS.shortLabel}"))
        runBlocking { modeSave.get()!!.join(); assertEquals(ChatNarrativeMode.FOCUS, chats.getSession("synthetic")?.narrativeMode) }
        if (segmented) {
            compose.onNodeWithText("Completed old dialogue").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("编辑本段").assertIsNotEnabled()
            compose.onNodeWithContentDescription("删除本段").assertIsNotEnabled()
            capture("segment-while-reply.png")
            compose.onNodeWithContentDescription("为本段生成语音").assertIsEnabled().performClick()
        } else {
            compose.onNodeWithTag("completed-message").performTouchInput { longClick() }
            for (label in listOf("编辑", "删除", "AI 修复格式", "重新生成")) compose.onNodeWithText(label).assertIsNotEnabled()
            capture("whole-while-reply.png")
            compose.onNodeWithText("为整条生成语音").assertIsEnabled().performClick()
        }
        compose.waitUntil(10_000) { voiceEntered.isCompleted }
        assertTrue(reply.isActive); assertTrue(voiceJob.get()!!.isActive); assertEquals(0, mutations.get())
        assertEquals(ChatNarrativeMode.ADVANCE, frozenReplyMode)
        runBlocking { reply.cancelAndJoin() }
        assertTrue(voiceJob.get()!!.isActive)
        releaseVoice.complete(Unit)
        runBlocking {
            voiceJob.get()!!.join(); assertEquals(1, voices.listForMessage("old").size)
            assertEquals(ChatNarrativeMode.FOCUS, chats.getMessage("old", "synthetic")?.narrativeMode)
        }
    }

    @Test fun productionProgressCardCancelsOnlyItsBatch() {
        val jobs = VoiceBatchJobRegistry(scope)
        val aEntered = CompletableDeferred<Unit>(); val bEntered = CompletableDeferred<Unit>(); val imageEntered = CompletableDeferred<Unit>()
        val reply = scope.launch { awaitCancellation() }
        val a = jobs.launch(VoiceBatchJobRegistry.Target("a", "session", "old")) { aEntered.complete(Unit); awaitCancellation() }
        val b = jobs.launch(VoiceBatchJobRegistry.Target("b", "other", "other")) { bEntered.complete(Unit); awaitCancellation() }
        val image = scope.launch { GlobalImageGenerationConcurrencyGate.instance.run { imageEntered.complete(Unit); awaitCancellation() } }
        runBlocking { aEntered.await(); bEntered.await(); imageEntered.await() }
        compose.setContent {
            ChatBarTheme {
                Column(Modifier.padding(top = 40.dp)) {
                    FishAudioGenerationCard(VoiceGenerationBatchState("a", "session", "old", VoiceGenerationPhase.SYNTHESIZING),
                        onCancel = { jobs.cancel("a") }, onReviewText = {}, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed()
        capture("scoped-voice-cancel.png", compose.onRoot())
        compose.onNodeWithText("取消").performClick()
        runBlocking { a.join() }
        assertTrue(a.isCancelled); assertTrue(b.isActive); assertTrue(image.isActive); assertTrue(reply.isActive)
    }

    @Test fun staleSourceAndStreamingOrScreenshotRowsDoNotStartNewVoiceWork() {
        val message = ChatMessage("old", "session", MessageRole.ASSISTANT, "original", createdAt = 1, updatedAt = 1)
        val snapshot = VoiceGenerationSourceSnapshot.capture(message)
        val changed = message.copy(content = "rewritten")
        val invoked = AtomicInteger(0)
        compose.setContent {
            ChatBarTheme {
                ChatMessageActionMenu(true, true, false, true, "为整条生成语音", snapshot.matches(changed, true),
                    "语音来源已改变，请重新打开消息操作", true, true, {}, {}, {}, { invoked.incrementAndGet() }, {}, {}, {})
            }
        }
        compose.onNodeWithText("为整条生成语音").assertIsNotEnabled().performTouchInput { click() }
        capture("stale-source-menu.png")
        assertEquals(0, invoked.get())
        assertNull(completedMessageLongPressActions(false, streaming = true, onMessage = {}, onSegment = {}).message)
        assertNull(completedMessageLongPressActions(true, onMessage = {}, onSegment = {}).segment)
    }

    private fun capture(name: String, renderedRoot: SemanticsNodeInteraction? = null) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Root capture waits for PixelCopy; UIAutomation may otherwise catch the initial blank window.
        val bitmap = renderedRoot?.captureToImage()?.asAndroidBitmap()
            ?: checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "concurrency-evidence").apply { mkdirs() }
            File(folder, name).outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
