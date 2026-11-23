package com.example.chatbar.ui.chat

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
import com.example.chatbar.lanSync.LanSyncActivity
import com.example.chatbar.lanSync.androidLanSyncResourcePath
import com.example.chatbar.lanSync.validateAndroidLanSyncResource
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real Android composer and incoming reduction admission, using synthetic keys and local ciphertext transport. */
class ChatSyncDeletedSessionNativeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun confirmedIncomingDeletionKeepsOpenAndReopenedComposerDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncDeletedChatFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
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
            fixture.publish(source, setOf(conversation, cardUnit))
            target.apply(requireNotNull(fixture.receive(target).plan))
            assertEquals(oldText, app.chatRepository.getMessagesReadOnly(sessionId).single().content)

            lateinit var oldOwner: ChatViewModel
            openChat(app, sessionId).use { scenario ->
                oldOwner = owner(scenario, sessionId)
                await { oldOwner.draftLoaded.value && oldOwner.isModelUsable.value && !oldOwner.isArchived.value }
                compose.onNodeWithText(sessionTitle).assertIsDisplayed()
                compose.onNodeWithContentDescription("发送").assertIsEnabled()
                compose.onNode(hasSetTextAction()).performTextReplacement(draftText)
                compose.runOnIdle { oldOwner.addDraftImage(images.single()) }
                compose.onNodeWithContentDescription("待发图片").assertIsDisplayed()
                await { app.chatRepository.getComposerDraft(sessionId).let { it.content == draftText && it.imagePaths == images } }

                scenario.moveToState(Lifecycle.State.STARTED)
                fixture.sourceChat.deleteSession(sessionId)
                fixture.publish(source, setOf(conversation))
                val pending = fixture.receive(target)
                val review = requireNotNull(pending.incomingReview)
                assertEquals(setOf(conversation), review.reductions.keys)
                assertEquals(setOf(sessionId, oldMessageId), review.reductions.getValue(conversation).current.map { it.id }.toSet())
                assertTrue(review.reductions.getValue(conversation).proposed.isEmpty())
                assertEquals(sessionId, app.chatRepository.requireDurableSession(sessionId).id)
                assertTrue(target.applyIncoming(review.acknowledge(review.requiredBindings)) != null)

                scenario.moveToState(Lifecycle.State.RESUMED)
                await { oldOwner.sessionMissing.value }
                assertRemovedScreen()
                compose.onNodeWithContentDescription("发送").performTouchInput { click() }
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
                assertRemovedScreen()
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

    private fun assertRemovedScreen() {
        compose.onNodeWithText("会话已删除，草稿和图片已保留在本机。").assertIsDisplayed()
        compose.onNodeWithText("复制草稿").assertIsDisplayed()
        compose.onNodeWithText("复制图片位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("待发图片").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
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
            assetResource = { reference ->
                app.assets.open(reference.removePrefix("asset:").removePrefix("/")).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 32 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            },
            validateResource = ::validateAndroidLanSyncResource)
        val sourcePlatform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) = Unit
        }
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

        suspend fun publish(source: LanSyncGroupNode, units: Set<String>) {
            val review = source.inspect().review(units)
            val commit = source.commit(review, review.requiredBindings)
            source.upload(commit.entry.request.operation, backend, delivery)
        }

        suspend fun receive(target: LanSyncGroupNode): LanSyncGroupNodeView {
            var cursor: CommitCursor? = null
            do { cursor = target.discover(backend, cursor).nextCursor } while (cursor != null)
            return target.inspect()
        }
    }
}
