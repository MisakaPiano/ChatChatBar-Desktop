package com.example.chatbar.ui.chat

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.lanSync.LanSyncGroupNativeApplyStatus
import com.example.chatbar.domain.lanSync.LanSyncGroupNode
import com.example.chatbar.domain.lanSync.LanSyncGroupNodeView
import com.example.chatbar.domain.lanSync.LanSyncPlatform
import com.example.chatbar.domain.lanSync.LanSyncScope
import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.syncStore.CiphertextObjectKey
import com.example.chatbar.domain.syncStore.CiphertextObjectKind
import com.example.chatbar.domain.syncStore.CommitCursor
import com.example.chatbar.domain.syncStore.FileCiphertextStore
import com.example.chatbar.domain.syncStore.SyncCausalVersion
import com.example.chatbar.domain.syncStore.SyncDeliveryKind
import com.example.chatbar.domain.syncStore.SyncDeliveryTarget
import com.example.chatbar.domain.syncStore.SyncDeviceEncryptionKey
import com.example.chatbar.domain.syncStore.SyncDeviceLedger
import com.example.chatbar.domain.syncStore.SyncDeviceLedgerRecord
import com.example.chatbar.domain.syncStore.SyncGroupCustody
import com.example.chatbar.domain.syncStore.SyncGroupKeyDelivery
import com.example.chatbar.domain.syncStore.SyncGroupMember
import com.example.chatbar.domain.syncStore.SyncGroupTrust
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.domain.syncStore.SyncStreamingCipher
import com.example.chatbar.domain.syncStore.SyncVersionRef
import com.example.chatbar.lanSync.LanSyncActivity
import com.example.chatbar.lanSync.androidLanSyncResourcePath
import com.example.chatbar.lanSync.validateAndroidLanSyncResource
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Android composer and incoming reduction admission, using synthetic keys and local ciphertext transport. */
class ChatSyncDeletedSessionNativeTest {
    @Test
    fun confirmedIncomingDeletionKeepsOpenAndReopenedComposerDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncDeletedChatFixture") == "true")
        exerciseDeletion(failFirstReload = false)
    }

    @Test
    fun aCommittedDeletionReloadsTheOldAndroidPageOnTheNextInspection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncDeletedChatReloadFixture") == "true")
        exerciseDeletion(failFirstReload = true)
    }

    private suspend fun exerciseDeletion(failFirstReload: Boolean) {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val ui = ChatWindow(app.packageName)
        await { app.lanSyncStartupReady }
        check(!app.lanSyncOwner.hasGroupEvidence())

        val sessionId = UUID.randomUUID().toString()
        val cardId = UUID.randomUUID().toString()
        val oldMessageId = UUID.randomUUID().toString()
        val modelId = UUID.randomUUID().toString()
        val conversation = "conversation/$sessionId"
        val cardUnit = "character_cards/$cardId"
        val draftText = "Synthetic draft to recover after incoming deletion"
        val oldText = "Synthetic message previously received"
        val sessionTitle = "Synthetic session under review"
        val settings = app.settingsRepository.getAppSettings()
        val root = Files.createTempDirectory(app.noBackupFilesDir.canonicalFile.toPath(), "synthetic-removed-chat-")
        val data = app.filesDir.canonicalFile.toPath()
        val image = Files.createTempFile(Files.createDirectories(data.resolve("images")), "synthetic-removed-chat-", ".png")
        val images = listOf(image.toString())
        try {
            val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                Files.newOutputStream(image).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val original = Files.readAllBytes(image)
            app.modelRepository.saveModel(ModelConfig(modelId, "Synthetic local model", "http://127.0.0.1:9/v1",
                apiKey = "", modelName = "synthetic-local-chat", isMultimodal = true, createdAt = 1))
            app.settingsRepository.updateAppSettings {
                it.copy(modelConfigurationMode = ModelConfigurationMode.CUSTOM_API, defaultModelId = modelId,
                    allowCleartextModelApi = true, automaticFormatCheckEnabled = false, siliconFlowApiKey = "", ragInjectionMode = "OFF")
            }

            val fixture = SignedGroup(root, app)
            val source = fixture.enroll(0, fixture.sourceWorkspace, fixture.sourcePlatform)
            val target = fixture.enroll(1, fixture.targetWorkspace, fixture.targetPlatform)
            fixture.sourceStorage.saveEntity("character_cards", cardId,
                CharacterCard(cardId, "Synthetic chat character", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            fixture.sourceChat.createSession(ChatSession(sessionId, cardId, sessionTitle, longTermMemoryEnabled = false,
                automaticImageGenerationEnabled = false, createdAt = 1, updatedAt = 1))
            fixture.sourceChat.addMessage(ChatMessage(oldMessageId, sessionId, MessageRole.USER, oldText, createdAt = 2, updatedAt = 2))
            val originalVersion = fixture.publish(source, setOf(conversation, cardUnit))
            val firstTransfer = fixture.receive(target)
            val transferDiagnostic = describePlan("first reception", firstTransfer, conversation, originalVersion.reference())
            val firstPlan = requireNotNull(firstTransfer.plan) { transferDiagnostic }
            assertEquals(transferDiagnostic, setOf(originalVersion.reference()), firstPlan.advances[conversation]?.frontiers)
            assertEquals(transferDiagnostic, LanSyncGroupNativeApplyStatus.APPLIED, target.apply(firstPlan)?.status)
            assertEquals(oldText, app.chatRepository.getMessagesReadOnly(sessionId).single().content)
            val initial = target.inspect()
            val accepted = initial.nativeState.units[conversation]
            val initialDiagnostic = describePlan("before opening chat", initial, conversation, originalVersion.reference())
            assertEquals(initialDiagnostic, setOf(originalVersion.reference()), accepted?.frontiers)
            assertEquals(initialDiagnostic, originalVersion.changes.getValue(conversation).valueHash, accepted?.valueHash)
            val initialRows = readConversationRows(initial, conversation)

            lateinit var oldOwner: ChatViewModel
            lateinit var sendPosition: ChatWindow.SendPosition
            openChat(app, sessionId).use { scenario ->
                oldOwner = owner(scenario, sessionId)
                await { oldOwner.draftLoaded.value && oldOwner.isModelUsable.value && !oldOwner.isArchived.value }
                ui.label(sessionTitle)
                ui.liveSendButton()
                ui.replaceDraftText(draftText)
                withContext(Dispatchers.Main) { oldOwner.addDraftImage(images.single()) }
                ui.label("待发图片")
                await { app.chatRepository.getComposerDraft(sessionId).let { it.content == draftText && it.imagePaths == images } }
                sendPosition = ui.recordSendPosition()

                scenario.moveToState(Lifecycle.State.STARTED)
                val beforeDelete = target.inspect()
                val pageChanges = changedFields(initialRows, readConversationRows(beforeDelete, conversation))
                fixture.sourceChat.deleteSession(sessionId)
                val deletion = fixture.publish(source, setOf(conversation))
                val tombstone = requireNotNull(deletion.changes[conversation]) { "signed version lacks the selected conversation" }
                assertNull("signed deletion retained content", tombstone.content)
                assertNull("signed deletion retained a value hash", tombstone.valueHash)
                assertEquals("signed deletion did not acknowledge the previous conversation", setOf(originalVersion.reference()), tombstone.previous)

                val pending = fixture.receive(target)
                val diagnostic = describePlan("after signed deletion", pending, conversation, deletion.reference()) +
                    "; pageChanges=$pageChanges; lastChanges=" +
                    changedFields(readConversationRows(beforeDelete, conversation), readConversationRows(pending, conversation))
                val history = requireNotNull(pending.reconciliation.history) { diagnostic }
                assertTrue(diagnostic, history.isAncestorOrSelf(originalVersion.reference(), deletion.reference()))
                assertEquals(diagnostic, setOf(deletion.reference()), history.frontier(conversation).map { it.version }.toSet())
                val review = requireNotNull(pending.incomingReview) { diagnostic }
                assertEquals(diagnostic, setOf(conversation), review.reductions.keys)
                assertEquals(setOf(sessionId, oldMessageId), review.reductions.getValue(conversation).current.map { it.id }.toSet())
                assertTrue(review.reductions.getValue(conversation).proposed.isEmpty())
                assertEquals(sessionId, app.chatRepository.requireDurableSession(sessionId).id)
                val approval = review.acknowledge(review.requiredBindings)
                if (failFirstReload) {
                    val previousRevision = app.chatRepository.lanSyncRevision.value
                    val previousReloads = fixture.reloadAttempts
                    fixture.failNextReload = true
                    val failure = try {
                        target.applyIncoming(approval)
                        error("Signed deletion did not report the injected app reload failure")
                    } catch (error: IllegalStateException) {
                        error
                    }
                    assertEquals("SYNC_GROUP_NATIVE_RELOAD_PENDING", failure.message)
                    assertEquals("SYNTHETIC_ANDROID_RELOAD_FAILURE", failure.cause?.message)
                    assertEquals(previousReloads + 1, fixture.reloadAttempts)
                    assertTrue(sessionId in fixture.lastReloadSessions)
                    assertDurableRemoval(app, sessionId, oldMessageId)
                    assertEquals(previousRevision, app.chatRepository.lanSyncRevision.value)

                    scenario.moveToState(Lifecycle.State.RESUMED)
                    ui.label(sessionTitle)
                    ui.liveSendButton()
                    assertFalse(oldOwner.sessionMissing.value)
                    assertEquals(draftText, oldOwner.composerDraft.value.text)
                    assertEquals(images, oldOwner.composerDraft.value.images)
                    assertArrayEquals(original, Files.readAllBytes(image))

                    val reloaded = target.inspectLocal()
                    assertEquals(previousReloads + 2, fixture.reloadAttempts)
                    assertTrue(sessionId in fixture.lastReloadSessions)
                    assertEquals(previousRevision + 1, app.chatRepository.lanSyncRevision.value)
                    assertEquals(pending.nativeState.completedSequence + 1, reloaded.nativeState.completedSequence)
                    assertNull(reloaded.nativeState.pendingTransaction)
                    assertEquals(setOf(deletion.reference()), reloaded.nativeState.units[conversation]?.frontiers)
                    assertTrue(reloaded.incomingReview?.reductions.orEmpty().isEmpty())
                    val steady = target.inspectLocal()
                    assertEquals(reloaded.nativeState, steady.nativeState)
                    assertEquals(previousReloads + 2, fixture.reloadAttempts)
                    assertEquals(previousRevision + 1, app.chatRepository.lanSyncRevision.value)
                } else {
                    assertEquals(diagnostic, LanSyncGroupNativeApplyStatus.APPLIED, target.applyIncoming(approval)?.status)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                }
                await { oldOwner.sessionMissing.value }
                assertRemovedScreen(ui)
                ui.checkDisabledSend(sendPosition, touch = true)
                assertEquals(draftText, oldOwner.composerDraft.value.text)
                assertEquals(images, oldOwner.composerDraft.value.images)
                assertFalse(withContext(Dispatchers.Main) { oldOwner.sendMessage(draftText, images) })
                assertDurableRemoval(app, sessionId, oldMessageId)
                assertArrayEquals(original, Files.readAllBytes(image))
            }

            openChat(app, sessionId).use { reopened ->
                val cold = owner(reopened, sessionId)
                assertNotSame(oldOwner, cold)
                await { cold.draftLoaded.value && cold.sessionMissing.value }
                assertRemovedScreen(ui)
                ui.checkDisabledSend(sendPosition, touch = false)
                assertEquals(draftText, cold.composerDraft.value.text)
                assertEquals(images, cold.composerDraft.value.images)
                val reopenedRepository = ChatRepository(JsonFileStorage(data, app.lanSyncOwner.gate))
                val retained = reopenedRepository.getComposerDraft(sessionId)
                assertEquals(draftText, retained.content)
                assertEquals(images, retained.imagePaths)
                assertNull(retained.submittedMessageId)
                assertDurableRemoval(app, sessionId, oldMessageId)
                assertArrayEquals(original, Files.readAllBytes(image))
            }
        } finally {
            app.chatRepository.deleteSession(sessionId)
            app.characterRepository.delete(cardId)
            app.modelRepository.deleteModel(modelId)
            app.settingsRepository.saveAppSettings(settings)
            Files.deleteIfExists(image)
            check(root.toFile().deleteRecursively())
        }
    }

    private fun openChat(app: ChatBarApp, sessionId: String): ActivityScenario<LanSyncActivity> =
        ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java)).onActivity { activity ->
            activity.setContent { ChatBarTheme { ChatScreen(sessionId = sessionId, onBack = activity::finish) } }
        }

    private fun owner(scenario: ActivityScenario<LanSyncActivity>, sessionId: String): ChatViewModel {
        lateinit var viewModel: ChatViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it, ChatViewModelFactory(sessionId))[sessionId, ChatViewModel::class.java] }
        return viewModel
    }

    private fun describePlan(phase: String, view: LanSyncGroupNodeView, unit: String, expected: SyncVersionRef): String {
        val plan = view.plan
        val row = plan?.units?.get(unit)
        val applied = view.nativeState.units[unit]
        val history = view.reconciliation.history
        val observations = view.reconciliation.observations.takeLast(8).map { "${it.stage}/${it.code}" }
        return "$phase; node=${view.stage}; row=${row?.stage}/${row?.code}; " +
            "applied=${applied != null}; currentEqualsApplied=${row?.currentHash == applied?.valueHash}; " +
            "currentPresent=${row?.currentHash != null}; appliedFrontiers=${applied?.frontiers?.size}; " +
            "rowFrontiers=${row?.frontiers?.size}; componentSize=${plan?.components?.get(unit)?.size}; " +
            "historyContainsExpected=${history?.contains(expected)}; versionState=${history?.state(expected.id)}/${history?.failure(expected.id)}; " +
            "observations=$observations"
    }

    private fun readConversationRows(view: LanSyncGroupNodeView, unit: String): Map<String, JsonObject> =
        view.plan?.before?.units()?.get(unit).orEmpty().associate { it.type to it.body }

    private fun changedFields(before: Map<String, JsonObject>, after: Map<String, JsonObject>): List<String> =
        (before.keys + after.keys).sorted().take(8).mapNotNull { type ->
            val old = before[type].orEmpty()
            val new = after[type].orEmpty()
            val fields = (old.keys + new.keys).sorted().filter { old[it] != new[it] }.take(12)
            fields.takeIf { it.isNotEmpty() }?.let { "$type:$it" }
        }

    private suspend fun assertRemovedScreen(ui: ChatWindow) {
        ui.label("会话已删除，草稿和图片已保留在本机。")
        ui.label("复制草稿")
        ui.label("复制图片位置")
        ui.label("待发图片")
    }

    /** Observe the Android accessibility tree without replacing the production window recomposer. */
    private class ChatWindow(private val packageName: String) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val automation = instrumentation.uiAutomation

        data class SendPosition(val send: Rect, val draftImage: Rect)

        suspend fun label(value: String) = awaitNode(value) {
            it.text?.toString() == value || it.contentDescription?.toString() == value
        }

        suspend fun liveSendButton() = awaitNode("enabled Send control") {
            isSend(it) && it.isEnabled
        }

        suspend fun recordSendPosition(): SendPosition {
            val send = bounds(liveSendButton())
            val image = bounds(label("待发图片"))
            check(!send.isEmpty && !image.isEmpty && send.centerX() > image.right && send.centerY() >= image.bottom) {
                "Enabled Send must be below and to the right of the draft thumbnail. send=$send image=$image; ${describeWindow()}"
            }
            return SendPosition(send, image)
        }

        suspend fun replaceDraftText(value: String) {
            val field = awaitNode("enabled chat composer") {
                it.isEnabled && it.isEditable &&
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT in it.actionList
            }
            val input = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            assertTrue("The real composer must accept the replacement text",
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
        }

        suspend fun checkDisabledSend(previous: SendPosition, touch: Boolean) {
            val image = bounds(label("待发图片"))
            val explicit = findNode(::isSend)?.let(::bounds)?.takeUnless { it.isEmpty }
            // Disabled Compose semantics can expose the icon on a different node or omit the control.
            // The same thumbnail remains immediately above the composer; use its measured position.
            val send = explicit ?: Rect(previous.send).apply {
                offset(image.left - previous.draftImage.left, image.bottom - previous.draftImage.bottom)
            }
            val x = send.centerX()
            val y = send.centerY()
            val root = automation.rootInActiveWindow
            check(root != null && root.packageName?.toString() == packageName && bounds(root).contains(x, y) &&
                !send.isEmpty && x > image.right && y >= image.bottom) {
                "Send touch target was outside the Android chat composer. send=$send image=$image explicit=${explicit != null}; ${describeWindow()}"
            }
            val clickable = nodes().filter {
                it.packageName?.toString() == packageName && it.isVisibleToUser && it.isEnabled &&
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in it.actionList
            }.firstOrNull { node ->
                val area = bounds(node)
                area.contains(x, y) && (isSend(node) ||
                    area.width() <= maxOf(send.width(), previous.send.width()) * 3 &&
                    area.height() <= maxOf(send.height(), previous.send.height()) * 3)
            }
            check(clickable == null) {
                "Removed chat still exposes an enabled click at Send. send=$send; ${describeWindow()}"
            }
            if (!touch) return
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    send.exactCenterX(), send.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue("Android must deliver the synthetic touch at $send", automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
        }

        private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

        private fun isSend(node: AccessibilityNodeInfo): Boolean =
            node.contentDescription?.toString() == "发送"

        private suspend fun awaitNode(description: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val found = withTimeoutOrNull(15_000) {
                var node = findNode(matches)
                while (node == null) {
                    delay(50)
                    node = findNode(matches)
                }
                node
            }
            return checkNotNull(found) { "Visible Android chat control unavailable: $description; ${describeWindow()}" }
        }

        private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
            nodes().firstOrNull { it.packageName?.toString() == packageName && it.isVisibleToUser && matches(it) }

        private fun nodes(): Sequence<AccessibilityNodeInfo> = sequence {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::addLast)
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 128) {
                val node = pending.removeFirst()
                yield(node)
                for (index in 0 until minOf(node.childCount, 128)) node.getChild(index)?.let(pending::addLast)
            }
        }

        private fun describeWindow(): String {
            val known = setOf("发送", "中断生成", "添加图片", "全屏编辑", "待发图片", "复制草稿", "复制图片位置")
            return nodes().filter { it.packageName?.toString() == packageName && it.isVisibleToUser }
                .take(24).joinToString(prefix = "visible=[", postfix = "]") { node ->
                    val label = listOfNotNull(node.contentDescription?.toString(), node.text?.toString()).firstOrNull { it in known }
                    val click = AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in node.actionList
                    "${node.className?.toString()?.substringAfterLast('.')}/${label ?: "other"}" +
                        "/enabled=${node.isEnabled}/click=$click/editable=${node.isEditable}/${bounds(node).toShortString()}"
                }
        }
    }

    private suspend fun assertDurableRemoval(app: ChatBarApp, sessionId: String, oldMessageId: String) {
        assertEquals(JsonFileStorage.EntityReadResult.Missing, app.chatRepository.readSessionDurable(sessionId))
        assertEquals(JsonFileStorage.EntityReadResult.Missing, app.chatRepository.readMessageDurable(oldMessageId, sessionId))
        assertTrue(app.chatRepository.getMessagesReadOnly(sessionId).isEmpty())
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(25)
    }

    private class SignedGroup(private val root: Path, private val app: ChatBarApp) {
        private val identities = List(2) { LanSyncTlsIdentity.create() }
        private val recipients = identities.map { SyncDeviceEncryptionKey.create(it.fingerprint) }
        private val cipher = SyncStreamingCipher.create()
        private val members = identities.zip(recipients).associate { (identity, recipient) ->
            identity.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded)
        }
        private val unsigned = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch,
            cipher.keyCommitment(), LanSyncScope.schemas.keys, members, emptySet(), "unsigned")
        private val sign = identities.first()::signSyncGroupPayload
        private val genesis = unsigned.copy(signature = Base64.getEncoder().encodeToString(sign(unsigned.signingBytes())))
        private val trust = SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(identities.first().fingerprint).certificate)
        private val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        private val backend = FileCiphertextStore(root.resolve("ciphertext"))
        private val delivery = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        private val sourceData = Files.createDirectory(root.resolve("source-data"))
        private val targetData = app.filesDir.canonicalFile.toPath()
        val sourceStorage = JsonFileStorage(sourceData)
        val sourceChat = ChatRepository(sourceStorage)
        val sourceWorkspace = LanSyncWorkspace(sourceData, root.resolve("source-workspace"), { sourceData.resolve(it) })
        val targetWorkspace = LanSyncWorkspace(targetData, root.resolve("target-workspace"),
            { androidLanSyncResourcePath(targetData, app.filesDir.toPath(), it) },
            validateResource = ::validateAndroidLanSyncResource)
        val sourcePlatform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) = Unit
        }
        var failNextReload = false
        var reloadAttempts = 0
            private set
        var lastReloadSessions = emptySet<String>()
            private set

        val targetPlatform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) {
                app.momentScheduler.withLanSyncMaintenance {
                    AiBackgroundWorkManager.withLanSyncMaintenance {
                        app.lanSyncOwner.gate.exclusive {
                            app.novelAiStudioRepository.prepareForLanSync()
                            work()
                        }
                    }
                }
            }
            override suspend fun reload(sessionIds: Set<String>) {
                reloadAttempts++
                lastReloadSessions = sessionIds
                if (failNextReload) {
                    failNextReload = false
                    throw IOException("SYNTHETIC_ANDROID_RELOAD_FAILURE")
                }
                app.characterRepository.refreshFromStorage()
                app.chatRepository.reloadAfterLanSync()
            }
        }

        suspend fun enroll(index: Int, workspace: LanSyncWorkspace, platform: LanSyncPlatform): LanSyncGroupNode {
            val identity = identities[index]
            val recipient = recipients[index]
            val custody = SyncGroupCustody(Files.createDirectory(root.resolve("custody-$index")), cipher.group, recipient,
                { it.reversedArray() }, { it.reversedArray() })
            val enrolled = custody.enroll(trust, listOf(genesis))
            val capsule = SyncGroupKeyDelivery.create(ledger, cipher, identity.fingerprint, SyncStoreId.random(), sign)
            val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)
            custody.rememberKey(capsule.openTentative(trust, recipient, key).admit(enrolled))
            return LanSyncGroupNode(Files.createDirectory(root.resolve("node-$index")), workspace, platform, identity, trust, custody,
                recipient, backend, { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, ::validateAndroidLanSyncResource)
                .also { it.initializeNewEnrollment() }
        }

        suspend fun publish(source: LanSyncGroupNode, units: Set<String>): SyncCausalVersion {
            val review = source.inspect().review(units)
            val commit = source.commit(review, review.requiredBindings)
            source.upload(commit.entry.request.operation, backend, delivery)
            return commit.entry.version
        }

        suspend fun receive(target: LanSyncGroupNode): LanSyncGroupNodeView {
            var cursor: CommitCursor? = null
            do { cursor = target.discover(backend, cursor).nextCursor } while (cursor != null)
            return target.inspect()
        }
    }
}
