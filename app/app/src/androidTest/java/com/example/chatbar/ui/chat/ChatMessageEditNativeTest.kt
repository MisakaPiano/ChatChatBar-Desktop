package com.example.chatbar.ui.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.lanSync.LanSyncGroupNativeApplyStatus
import com.example.chatbar.lanSync.LanSyncActivity
import com.example.chatbar.ui.kit.ChatBarTheme
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChatMessageEditNativeTest {
    @Test
    fun selectedVersionEditRetainsDraftAndNewPhotoAfterSignedSameVersionUpdate(): Unit = runBlocking {
        val app = fixtureApp()
        val card = CharacterCard(UUID.randomUUID().toString(), "Synthetic edit character", createdAt = 1, updatedAt = 1)
        val session = session(card, "Synthetic edit after nearby sync")
        val conversation = "conversation/${session.id}"
        val original = "Synthetic original active version"
        val incoming = "Synthetic version text from the other signed device"
        val draft = "Synthetic Android draft that must survive"
        val root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "synthetic-stale-edit-")
        val localSelection = png(root.resolve("selection.png"), Color.GREEN)
        var selected: Path? = null
        var received: Path? = null
        try {
            val signed = ChatSyncDeletedSessionNativeTest.SignedGroup(root, app)
            val source = signed.enroll(0, signed.sourceWorkspace, signed.sourcePlatform)
            val target = signed.enroll(1, signed.targetWorkspace, signed.targetPlatform)
            signed.sourceStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            signed.sourceChat.createSession(session)
            val message = signed.sourceChat.addMessage(ChatMessage(UUID.randomUUID().toString(), session.id, MessageRole.ASSISTANT,
                original, alternatives = listOf("Synthetic other version", original), alternativeVersionIds = listOf("other-v", "active-v"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "active-v", createdAt = 2, updatedAt = 2))
            signed.publish(source, setOf(conversation, "character_cards/${card.id}"))
            val first = requireNotNull(signed.receive(target).plan)
            assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, target.apply(first)?.status)
            val picker = ImagePicker()
            val ui = EditWindow(app.packageName)
            openChat(app, session.id, picker).use { scenario ->
                ui.openEditor(original, allowSegmentMenu = true)
                ui.editText(draft)
                selected = pick(app, scenario, picker, ui, localSelection)

                val imageDirectory = Files.createDirectories(root.resolve("source-data/images"))
                val sourceImage = png(imageDirectory.resolve("incoming.png"), Color.RED)
                val replacement = MessageAlternativeVersionPolicy.editCurrentContent(message, incoming)
                    .copy(images = listOf(sourceImage.toString()))
                assertTrue(signed.sourceChat.updateMessageIfUnchanged(message, replacement))
                signed.publish(source, setOf(conversation))
                val remote = signed.receive(target)
                assertTrue(remote.incomingReview?.reductions.orEmpty().isEmpty())
                assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, target.apply(requireNotNull(remote.plan))?.status)
                val applied = durable(app.chatRepository, message)
                assertEquals(incoming, applied.displayContent)
                assertEquals(listOf("other-v", "active-v"), applied.alternativeVersionIds)
                received = Paths.get(applied.images.single())
                assertArrayEquals(Files.readAllBytes(sourceImage), Files.readAllBytes(received!!))

                ui.tap("确认")
                ui.label("此消息在编辑期间已更新或移除。编辑内容和新选图片仍在。")
                ui.label("删除图片")
                ui.label(draft)
                assertEquals(applied, durable(ChatRepository(JsonFileStorage(app.filesDir.toPath())), message))
                assertArrayEquals(Files.readAllBytes(localSelection), Files.readAllBytes(selected!!))

                ui.tap("查看最新消息")
                ui.label(incoming)
                ui.tap("核对后继续编辑")
                ui.label(draft)
                ui.tap("确认")
                await("explicit save after comparing the approved current version") {
                    durable(app.chatRepository, message).displayContent == draft
                }
                val revised = durable(ChatRepository(JsonFileStorage(app.filesDir.toPath())), message)
                assertEquals(applied.alternativeVersionIds, revised.alternativeVersionIds)
                assertEquals(listOf("Synthetic other version", draft), revised.alternatives)
                assertEquals(setOf(received.toString(), selected.toString()), revised.images.toSet())
                assertArrayEquals(Files.readAllBytes(sourceImage), Files.readAllBytes(received!!))
            }
        } finally {
            app.chatRepository.deleteSession(session.id)
            app.characterRepository.delete(card.id)
            selected?.let(Files::deleteIfExists)
            received?.let(Files::deleteIfExists)
            check(root.toFile().deleteRecursively())
        }
    }

    @Test
    fun failedPhotoReplacementPreservesOldBytesAndDraftThenRetiresOnlyUnreferencedFiles(): Unit = runBlocking {
        val app = fixtureApp()
        val card = CharacterCard(UUID.randomUUID().toString(), "Synthetic image replacement", createdAt = 1, updatedAt = 1)
        val session = session(card, "Synthetic image replacement session")
        val other = session(card, "Synthetic independent reference owner")
        val images = Files.createDirectories(app.filesDir.toPath().resolve("images"))
        val old = png(Files.createTempFile(images, "chat_img_old-", ".png"), Color.BLUE)
        val shared = png(Files.createTempFile(images, "chat_img_shared-", ".png"), Color.RED)
        val generatedDir = Files.createTempDirectory(Files.createDirectories(images.resolve("generated")), "synthetic-")
        val archiveDir = Files.createTempDirectory(Files.createDirectories(images.resolve("save_slots")), "synthetic-")
        val generated = png(generatedDir.resolve("original.png"), Color.BLACK)
        val archived = png(archiveDir.resolve("original.png"), Color.WHITE)
        val originals = listOf(old, shared, generated, archived).associateWith(Files::readAllBytes)
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "synthetic-failed-replacement-")
        val localSelection = png(root.resolve("another.png"), Color.GREEN)
        val rows = app.filesDir.toPath().resolve("entities/chat_messages").toFile()
        val draft = "Synthetic replacement text saved after a physical write fault"
        var selected: Path? = null
        try {
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            app.chatRepository.createSession(other)
            val message = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Synthetic old photo message",
                images = originals.keys.map(Path::toString)))
            app.chatRepository.addMessage(ChatMessage.create(other.id, MessageRole.ASSISTANT, "Synthetic metadata reference",
                generatedImageMetadata = listOf(GeneratedImageMetadata(shared.toString(), "synthetic", negativePrompt = "",
                    sizePreset = "synthetic", width = 3, height = 3))))
            val ui = EditWindow(app.packageName)
            val picker = ImagePicker()
            openChat(app, session.id, picker).use { scenario ->
                ui.openEditor(message.content)
                ui.editText(draft)
                repeat(originals.size) { ui.tap("删除图片") }
                selected = pick(app, scenario, picker, ui, localSelection)
                check(rows.isDirectory && rows.setWritable(false, true))
                try {
                    assertTrue("android precommit storage fault must be real", !rows.canWrite())
                    ui.tap("确认")
                    ui.label("未能确认保存。编辑内容和图片仍在，请检查后重试。")
                    ui.label(draft)
                    ui.label("删除图片")
                    assertEquals(message, durable(ChatRepository(JsonFileStorage(app.filesDir.toPath())), message))
                    originals.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path)) }
                    assertArrayEquals(Files.readAllBytes(localSelection), Files.readAllBytes(selected!!))
                } finally {
                    check(rows.setWritable(true, true))
                }
                ui.tap("确认")
                await("durable replacement and unreferenced original collection") {
                    durable(app.chatRepository, message).displayContent == draft && !Files.exists(old)
                }
                val persisted = durable(ChatRepository(JsonFileStorage(app.filesDir.toPath())), message)
                assertEquals(listOf(selected.toString()), persisted.images)
                listOf(shared, generated, archived).forEach { path ->
                    assertArrayEquals(originals.getValue(path), Files.readAllBytes(path))
                }
                assertArrayEquals(Files.readAllBytes(localSelection), Files.readAllBytes(selected!!))
            }
        } finally {
            app.chatRepository.deleteSession(session.id)
            app.chatRepository.deleteSession(other.id)
            app.characterRepository.delete(card.id)
            listOfNotNull(old, shared, generated, archived, selected).forEach(Files::deleteIfExists)
            Files.deleteIfExists(generatedDir)
            Files.deleteIfExists(archiveDir)
            check(root.toFile().deleteRecursively())
        }
    }

    private suspend fun fixtureApp(): ChatBarApp {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncMessageEditFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        await("isolated Android app startup") { app.lanSyncStartupReady }
        check(!app.lanSyncOwner.hasGroupEvidence())
        return app
    }

    private fun session(card: CharacterCard, name: String) = ChatSession(UUID.randomUUID().toString(), card.id, name,
        longTermMemoryEnabled = false, automaticImageGenerationEnabled = false, createdAt = 1, updatedAt = 1)

    private suspend fun durable(repository: ChatRepository, target: ChatMessage): ChatMessage {
        val read = repository.readMessageDurable(target.id, target.sessionId)
        check(read is JsonFileStorage.EntityReadResult.Valid)
        return read.value
    }

    private fun png(path: Path, color: Int): Path = path.also {
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            Files.newOutputStream(path).use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun openChat(app: ChatBarApp, sessionId: String, picker: ImagePicker): ActivityScenario<LanSyncActivity> {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        return ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java)).onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ChatBarTheme { ChatScreen(sessionId = sessionId, onBack = activity::finish) }
                }
            }
        }
    }

    private suspend fun pick(app: ChatBarApp, scenario: ActivityScenario<LanSyncActivity>, picker: ImagePicker, ui: EditWindow, source: Path): Path {
        val images = app.filesDir.toPath().resolve("images")
        Files.createDirectories(images)
        fun entries() = Files.newDirectoryStream(images, "chat_img_*").use { it.toSet() }
        val before = entries()
        ui.tap("插入图片")
        await("Android visual media picker opened") { picker.request != null }
        scenario.onActivity { picker.returnImage(Uri.fromFile(source.toFile())) }
        ui.label("删除图片")
        return (entries() - before).single()
    }

    private suspend fun await(phase: String, condition: suspend () -> Boolean) {
        try { withTimeout(30_000) { while (!condition()) delay(50) } }
        catch (failure: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(phase, failure) }
    }

    private class ImagePicker : ActivityResultRegistry() {
        @Volatile var request: Int? = null
            private set
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia)
            check(request == null)
            request = requestCode
        }
        fun returnImage(uri: Uri) {
            val code = checkNotNull(request)
            request = null
            check(dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri)))
        }
    }

    private inner class EditWindow(private val packageName: String) {
        private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        private fun nodes(): Sequence<AccessibilityNodeInfo> = sequence {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::addLast)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && node.packageName?.toString() == packageName) yield(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
            }
        }
        private fun matches(node: AccessibilityNodeInfo, value: String) =
            node.text?.toString() == value || node.contentDescription?.toString() == value

        suspend fun label(label: String) = await("visible edit control: $label") { nodes().any { matches(it, label) } }

        suspend fun openEditor(message: String, allowSegmentMenu: Boolean = false) {
            action(message, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (allowSegmentMenu) {
                await("whole-message editor from a segment or ordinary action menu") {
                    perform("编辑整条消息", AccessibilityNodeInfo.ACTION_CLICK) ||
                        nodes().any { matches(it, "消息操作") } && perform("编辑", AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else tap("编辑")
            label("编辑消息")
        }

        suspend fun tap(value: String) = action(value, AccessibilityNodeInfo.ACTION_CLICK)

        suspend fun editText(value: String) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            await("real edit field can accept a draft") {
                nodes().firstOrNull { it.isEditable }?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            }
            label(value)
        }

        private suspend fun action(value: String, action: Int) = await("Android action $action on $value") {
            perform(value, action)
        }

        private fun perform(value: String, action: Int) =
            nodes().filter { matches(it, value) }.flatMap { node -> generateSequence(node) { it.parent }.take(4) }
                .firstOrNull { node -> node.isEnabled && node.actionList.any { it.id == action } }
                ?.performAction(action) == true
    }
}
