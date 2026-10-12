package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.voice.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopFishSettingsBindingTest {
    @Test fun `session save retains edits made during storage and only explicit second save persists them`() = runBlocking {
        sessionSaveFixture { f ->
            val editor = f.editor
            editor.editSessionSettings { it.copy(voiceLanguage = "  Japanese  ", audiobookModeEnabled = true) }
            editor.editSessionReplyLengthInput("801")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.gate.beforeNext = { entered.complete(Unit); release.await() }
            val save = async { editor.saveSessionSettings() }
            try {
                withTimeout(5_000) { entered.await() }
                assertTrue(editor.state.value.sessionSettingsSaving)
                editor.editSessionSettings { it.copy(voiceLanguage = "  Brazilian Portuguese  ",
                    audiobookModeEnabled = false, supplementarySetting = "later setting") }
                editor.editSessionReplyLengthInput("902")
                val newer = editor.state.value.sessionSettingsDraft
                val admissions = f.gate.admissions
                assertFalse(editor.saveSessionSettings())
                assertEquals(admissions, f.gate.admissions, "Duplicate save must not enter storage")
                editor.refresh()
                editor.selectSession(f.first)
                editor.discardSessionSettings()
                assertEquals(newer, editor.state.value.sessionSettingsDraft)
                release.complete(Unit)
                assertTrue(withTimeout(5_000) { save.await() })
                assertFalse(editor.state.value.sessionSettingsSaving)
                val persisted = assertNotNull(f.chats.getSession(f.first))
                assertEquals("Japanese", persisted.voiceLanguage)
                assertEquals(true, persisted.audiobookModeEnabled)
                assertEquals(801, persisted.replyLength)
                assertEquals(newer!!.voiceLanguage, editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertEquals("later setting", editor.state.value.sessionSettingsDraft!!.supplementarySetting)
                assertEquals(false, editor.state.value.sessionSettingsDraft!!.audiobookModeEnabled)
                assertEquals("902", editor.state.value.sessionReplyLengthInput)
                assertTrue(editor.state.value.sessionSettingsDirty)
                assertTrue(editor.saveSessionSettings())
                assertEquals("Brazilian Portuguese", f.chats.getSession(f.first)!!.voiceLanguage)
                assertEquals(902, f.chats.getSession(f.first)!!.replyLength)
                assertFalse(editor.state.value.sessionSettingsDirty)
                editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
                assertFalse(editor.state.value.sessionSettingsDirty)
                editor.editSessionSettings { it.copy(voiceLanguage = "   ", audiobookModeEnabled = null) }
                assertEquals("   ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertTrue(editor.saveSessionSettings())
                assertNull(f.chats.getSession(f.first)!!.voiceLanguage)
                assertNull(f.chats.getSession(f.first)!!.audiobookModeEnabled)
            } finally { release.complete(Unit); save.cancelAndJoin() }
        }
    }

    @Test fun `in flight session save blocks switch creation and leave without queuing a late action`() = runBlocking {
        sessionSaveFixture { f ->
            val editor = f.editor
            val secondBefore = f.chats.getSession(f.second)
            val sessionsBefore = f.chats.getAllSessions().map { it.id }.toSet()
            editor.editSessionSettings { it.copy(voiceLanguage = "Japanese") }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.gate.beforeNext = { entered.complete(Unit); release.await() }
            val save = async { editor.saveSessionSettings() }
            var left = false
            try {
                withTimeout(5_000) { entered.await() }
                editor.selectSession(f.second)
                editor.createSession(f.characterId)
                editor.requestSessionSettingsLeave { left = true }
                editor.resolveSessionSettingsLeave(save = false)
                editor.resolveSessionSettingsLeave(save = true)
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                assertTrue(editor.state.value.sessionSettingsSaving)
                assertNotNull(editor.state.value.status)
                assertFalse(left)
                release.complete(Unit)
                assertTrue(withTimeout(5_000) { save.await() })
                assertFalse(left)
                assertFalse(editor.state.value.sessionSettingsLeavePrompt)
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                assertEquals(sessionsBefore, f.chats.getAllSessions().map { it.id }.toSet())
                assertEquals(secondBefore, f.chats.getSession(f.second))
                editor.selectSession(f.second)
                assertEquals(f.second, editor.state.value.selectedSession!!.id)
                assertNull(editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertEquals("Japanese", f.chats.getSession(f.first)!!.voiceLanguage)
            } finally { release.complete(Unit); save.cancelAndJoin() }
        }
    }

    @Test fun `save and leave rechecks new edits and cancellation of pending leave intent`() = runBlocking {
        sessionSaveFixture { f ->
            val editor = f.editor
            editor.editSessionSettings { it.copy(voiceLanguage = "Japanese") }
            editor.selectSession(f.second)
            assertTrue(editor.state.value.sessionSettingsLeavePrompt)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.gate.beforeNext = { entered.complete(Unit); release.await() }
            val leave = launch { editor.resolveSessionSettingsLeave(save = true) }
            try {
                withTimeout(5_000) { entered.await() }
                editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
                editor.resolveSessionSettingsLeave(save = false)
                release.complete(Unit)
                withTimeout(5_000) { leave.join() }
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                assertTrue(editor.state.value.sessionSettingsDirty)
                assertTrue(editor.state.value.sessionSettingsLeavePrompt)
                editor.continueSessionSettingsEditing()
                assertTrue(editor.saveSessionSettings())
                editor.resolveSessionSettingsLeave(save = true)
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                editor.selectSession(f.second)
                assertEquals(f.second, editor.state.value.selectedSession!!.id)
            } finally { release.complete(Unit); leave.cancelAndJoin() }
        }
    }

    @Test fun `session storage failure and cancellation retain dirty draft unlock save and never leave`() = runBlocking {
        sessionSaveFixture { f ->
            val editor = f.editor
            editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
            val expected = editor.state.value.sessionSettingsDraft
            var left = false
            editor.requestSessionSettingsLeave { left = true }
            f.gate.beforeNext = { throw java.io.IOException("synthetic session write admission failure") }
            editor.resolveSessionSettingsLeave(save = true)
            assertFalse(left)
            assertNotNull(editor.state.value.error)
            assertEquals(expected, editor.state.value.sessionSettingsDraft)
            assertTrue(editor.state.value.sessionSettingsDirty)
            assertFalse(editor.state.value.sessionSettingsSaving)
            assertNull(f.chats.getSession(f.first)!!.voiceLanguage)

            val entered = CompletableDeferred<Unit>()
            f.gate.beforeNext = { entered.complete(Unit); awaitCancellation() }
            editor.requestSessionSettingsLeave { left = true }
            val leave = launch { editor.resolveSessionSettingsLeave(save = true) }
            try {
                withTimeout(5_000) { entered.await() }
                assertTrue(editor.state.value.sessionSettingsSaving)
                editor.editSessionReplyLengthInput("not-a-number")
                leave.cancelAndJoin()
                assertTrue(leave.isCancelled)
                assertFalse(left)
                assertNotNull(editor.state.value.error)
                assertEquals(expected, editor.state.value.sessionSettingsDraft)
                assertEquals("not-a-number", editor.state.value.sessionReplyLengthInput)
                assertTrue(editor.state.value.sessionSettingsDirty)
                assertFalse(editor.state.value.sessionSettingsSaving)
                assertFalse(editor.state.value.sessionSettingsLeavePrompt)
                assertNull(f.chats.getSession(f.first)!!.voiceLanguage)
                assertFalse(editor.saveSessionSettings())
                editor.editSessionReplyLengthInput("903")
                assertTrue(editor.saveSessionSettings())
                assertFalse(left)
                assertFalse(editor.state.value.sessionSettingsDirty)
                assertEquals("Brazilian Portuguese", f.chats.getSession(f.first)!!.voiceLanguage)
                assertEquals(903, f.chats.getSession(f.first)!!.replyLength)
            } finally { leave.cancelAndJoin() }
        }
    }

    @Test fun `selection refresh uses latest settings draft after its suspended window read`() = runBlocking {
        var afterRead: (suspend () -> Unit)? = null
        sessionSaveFixture(afterWindowRead = { val action = afterRead; afterRead = null; action?.invoke() }) { f ->
            val editor = f.editor
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            afterRead = { entered.complete(Unit); release.await() }
            val refresh = launch { editor.refresh() }
            try {
                withTimeout(5_000) { entered.await() }
                editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ", audiobookModeEnabled = true) }
                editor.editSessionReplyLengthInput("904")
                assertFalse(editor.saveSessionSettings(), "Do not queue a save behind an unfinished selection read")
                val expected = editor.state.value.sessionSettingsDraft
                release.complete(Unit)
                withTimeout(5_000) { refresh.join() }
                assertEquals(expected, editor.state.value.sessionSettingsDraft)
                assertEquals("904", editor.state.value.sessionReplyLengthInput)
                assertTrue(editor.state.value.sessionSettingsDirty)
                editor.selectSession(f.first)
                assertEquals(expected, editor.state.value.sessionSettingsDraft)
                editor.selectSession(f.second)
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                assertTrue(editor.state.value.sessionSettingsLeavePrompt)
                editor.resolveSessionSettingsLeave(save = true)
                assertEquals(f.second, editor.state.value.selectedSession!!.id)
                assertNull(editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertEquals("Brazilian Portuguese", f.chats.getSession(f.first)!!.voiceLanguage)
            } finally { release.complete(Unit); refresh.cancelAndJoin() }
        }
    }

    @Test fun `cancellation after confirmed session write preserves committed baseline and newer draft`() = runBlocking {
        var afterRead: (suspend () -> Unit)? = null
        sessionSaveFixture(afterWindowRead = { val action = afterRead; afterRead = null; action?.invoke() }) { f ->
            val editor = f.editor
            editor.editSessionSettings { it.copy(voiceLanguage = "Japanese") }
            var left = false
            editor.requestSessionSettingsLeave { left = true }
            val entered = CompletableDeferred<Unit>()
            afterRead = { entered.complete(Unit); awaitCancellation() }
            val saveAndLeave = launch { editor.resolveSessionSettingsLeave(save = true) }
            try {
                withTimeout(5_000) { entered.await() }
                // This seam is after repository acknowledgement, during the presentation refresh.
                assertEquals("Japanese", editor.state.value.selectedSession!!.voiceLanguage)
                assertFalse(editor.state.value.sessionSettingsDirty)
                editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
                saveAndLeave.cancelAndJoin()
                assertFalse(editor.state.value.sessionSettingsSaving)
                assertTrue(editor.state.value.sessionSettingsDirty)
                assertNotNull(editor.state.value.error)
                assertFalse(left)
                assertEquals("Japanese", f.chats.getSession(f.first)!!.voiceLanguage)
                assertEquals(" Brazilian Portuguese ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                editor.resolveSessionSettingsLeave(save = false)
                assertEquals(" Brazilian Portuguese ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertTrue(editor.saveSessionSettings())
                assertEquals("Brazilian Portuguese", f.chats.getSession(f.first)!!.voiceLanguage)
                assertFalse(left)
            } finally { saveAndLeave.cancelAndJoin() }
        }
    }

    @Test fun `already admitted leave and suspended selection cannot race a settings save or discard fresh edits`() = runBlocking {
        var afterRead: (suspend () -> Unit)? = null
        sessionSaveFixture(afterWindowRead = { val action = afterRead; afterRead = null; action?.invoke() }) { f ->
            val editor = f.editor
            val enteredLeave = CompletableDeferred<Unit>()
            val releaseLeave = CompletableDeferred<Unit>()
            val leave = launch { editor.requestSessionSettingsLeave { enteredLeave.complete(Unit); releaseLeave.await() } }
            try {
                withTimeout(5_000) { enteredLeave.await() }
                editor.editSessionSettings { it.copy(voiceLanguage = "Japanese") }
                assertFalse(editor.saveSessionSettings())
                assertFalse(editor.state.value.sessionSettingsSaving)
                assertNull(f.chats.getSession(f.first)!!.voiceLanguage)
                releaseLeave.complete(Unit)
                withTimeout(5_000) { leave.join() }
                editor.discardSessionSettings()
            } finally { releaseLeave.complete(Unit); leave.cancelAndJoin() }

            val enteredRead = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            afterRead = { enteredRead.complete(Unit); releaseRead.await() }
            val select = launch { editor.selectSession(f.second) }
            try {
                withTimeout(5_000) { enteredRead.await() }
                editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
                releaseRead.complete(Unit)
                withTimeout(5_000) { select.join() }
                assertEquals(f.first, editor.state.value.selectedSession!!.id)
                assertEquals(" Brazilian Portuguese ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
                assertTrue(editor.state.value.sessionSettingsDirty)
                assertTrue(editor.state.value.sessionSettingsLeavePrompt)
                editor.resolveSessionSettingsLeave(save = false)
                assertEquals(f.second, editor.state.value.selectedSession!!.id)
                assertNull(f.chats.getSession(f.first)!!.voiceLanguage)
                var obsoleteLeave = false
                editor.editSessionSettings { it.copy(voiceLanguage = "temporary") }
                editor.requestSessionSettingsLeave { obsoleteLeave = true }
                editor.editSessionSettings { it.copy(voiceLanguage = null) }
                assertFalse(editor.state.value.sessionSettingsDirty)
                editor.selectSession(f.first)
                assertFalse(editor.state.value.sessionSettingsLeavePrompt)
                editor.resolveSessionSettingsLeave(save = true)
                assertFalse(obsoleteLeave, "A leave intent from B must not execute after selecting A")
            } finally { releaseRead.complete(Unit); select.cancelAndJoin() }
        }
    }

    @Test fun `voice tag settings reuse auxiliary models without widening the chat list`() = runBlocking {
        fishFixture { _, c ->
            val auxiliary = ModelConfig.create("Auxiliary only", "https://fixture.invalid", "synthetic-key", "fixture")
                .copy(selectableForChat = false)
            c.modelRepository.saveModel(auxiliary)
            c.settingsRepository.saveAppSettings(AppSettings(voiceTagModelId = auxiliary.id))
            val editor = c.modelSettingsController
            editor.loadSettings()
            assertTrue(editor.state.value.availableChatModels.none { it.id == auxiliary.id })
            assertEquals("Auxiliary only", editor.state.value.availableAuxiliaryTextModels.single { it.id == auxiliary.id }.displayName)
            assertEquals(auxiliary.id, editor.state.value.settings!!.voiceTagModelId)
            c.modelRepository.deleteModel(auxiliary.id)
            editor.loadSettings()
            assertEquals(auxiliary.id, editor.state.value.settings!!.voiceTagModelId)
            assertTrue(editor.state.value.availableAuxiliaryTextModels.none { it.id == auxiliary.id })
            editor.editSettings { it.copy(voiceTagModelId = null) }
            editor.saveAppSettings()
            assertNull(editor.state.value.error)
            assertNull(c.settingsRepository.getAppSettings().voiceTagModelId)
        }
    }

    @Test fun `global save reconciles each current field and preserves player and credential drafts`() = runBlocking {
        fishFixture { root, c ->
            val gate = FishSettingsSaveGate()
            val repository = com.example.chatbar.data.repository.SettingsRepository(
                com.example.chatbar.data.local.JsonFileStorage(root.resolve("save-interleaving"), gate))
            repository.initialize()
            val editor = DesktopModelSettingsController(c.modelRepository, repository, c.formatCardRepository,
                c.effectiveModelResolver, c.modelDiscoveryService, c.presetModelCatalogSource)
            editor.loadSettings()
            editor.editSettings { it.copy(voiceTagModelId = "submitted-A") }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            gate.beforeNext = { entered.complete(Unit); release.await() }
            val save = launch { editor.saveAppSettings() }
            try {
                withTimeout(5_000) { entered.await() }
                assertFalse(save.isCompleted)
                assertTrue(editor.state.value.busy)
                editor.editSettings { it.copy(defaultModelId = "later-model", allowCleartextModelApi = true,
                    defaultContextWindowSize = "61", defaultFormatCardId = "later-format",
                    assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled,
                    excludeAssistantStatusFromHistory = !it.excludeAssistantStatusFromHistory,
                    fishAudioTtsModelId = FishAudioTtsModels.S1, voiceTagModelId = "later-B", audiobookModeEnabled = true,
                    playerName = "Later player", playerPersona = "Later persona") }
                editor.replaceFallbackCredential("synthetic-later-fallback")
                editor.openCredentialEditor()
                editor.editCredentialDraft("synthetic-independent-editor")
                val duringSave = editor.state.value.settings
                release.complete(Unit)
                withTimeout(5_000) { save.join() }
                assertNull(editor.state.value.error)
                assertEquals("submitted-A", repository.getAppSettings().voiceTagModelId)
                assertEquals(duringSave, editor.state.value.settings)
                assertTrue(editor.state.value.chatDefaultsDirty)
                assertTrue(editor.state.value.playerDirty)
                assertTrue(editor.state.value.credentialEditorOpen)
                assertTrue(editor.state.value.credentialDirty)
                editor.saveAppSettings()
                assertNull(editor.state.value.error)
                assertEquals("later-B", repository.getAppSettings().voiceTagModelId)
                assertFalse(editor.state.value.chatDefaultsDirty)
                assertEquals("Later player", editor.state.value.settings!!.playerName)
                assertTrue(editor.state.value.playerDirty)
                assertTrue(editor.state.value.credentialDirty)
                assertNotEquals("Later player", repository.getPlayerSetting().playerName)
                editor.editSettings { it.copy(voiceTagModelId = "discard-C") }
                editor.discardChatDefaults()
                assertEquals("later-B", editor.state.value.settings!!.voiceTagModelId)
                assertFalse(editor.state.value.chatDefaultsDirty)
                editor.editSettings { it.copy(voiceTagModelId = "failed-D") }
                gate.beforeNext = { throw java.io.IOException("synthetic storage failure") }
                editor.saveAppSettings()
                assertNotNull(editor.state.value.error)
                assertEquals("failed-D", editor.state.value.settings!!.voiceTagModelId)
                assertTrue(editor.state.value.chatDefaultsDirty)
                assertEquals("later-B", repository.getAppSettings().voiceTagModelId)
                editor.discardChatDefaults()
                assertEquals("later-B", editor.state.value.settings!!.voiceTagModelId)
            } finally {
                release.complete(Unit)
                save.cancelAndJoin()
            }
        }
    }

    @Test fun `session language normalizes at save and comparison while preserving raw draft and isolation`() = runBlocking {
        fishFixture { root, c ->
            val model = ModelConfig.create("fixture", "https://fixture.invalid", "synthetic-key", "fixture")
            c.modelRepository.saveModel(model)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id))
            val card = CharacterCard.create("fixture", greeting = "hello")
            c.characterRepository.save(card)
            val editor = c.primaryChatController
            editor.refresh(); editor.createSession(card.id)
            assertNull(editor.state.value.error)
            val first = assertNotNull(editor.state.value.selectedSession).id
            editor.editSessionSettings { it.copy(voiceLanguage = "  Brazilian Portuguese  ") }
            assertEquals("  Brazilian Portuguese  ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            assertTrue(editor.state.value.sessionSettingsDirty)
            editor.saveSessionSettings()
            assertNull(editor.state.value.error)
            assertEquals("Brazilian Portuguese", c.chatRepository.getSession(first)!!.voiceLanguage)
            editor.editSessionSettings { it.copy(voiceLanguage = " Brazilian Portuguese ") }
            assertFalse(editor.state.value.sessionSettingsDirty)
            editor.editSessionReplyLengthInput(editor.state.value.sessionReplyLengthInput)
            assertFalse(editor.state.value.sessionSettingsDirty)
            editor.discardSessionSettings()
            assertEquals("Brazilian Portuguese", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            editor.createSession(card.id)
            assertNull(editor.state.value.error)
            val second = assertNotNull(editor.state.value.selectedSession).id
            assertNotEquals(first, second)
            assertNull(editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            editor.editSessionSettings { it.copy(voiceLanguage = "   ") }
            assertEquals("   ", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            assertFalse(editor.state.value.sessionSettingsDirty)
            editor.saveSessionSettings()
            assertNull(c.chatRepository.getSession(second)!!.voiceLanguage)
            val existingFirst = assertNotNull(c.chatRepository.getSession(first))
            c.chatRepository.saveSessionSettingsDraft(existingFirst, existingFirst.copy(voiceLanguage = " Brazilian Portuguese "))
            editor.selectSession(first)
            editor.editSessionSettings { it.copy(voiceLanguage = "Brazilian Portuguese") }
            assertFalse(editor.state.value.sessionSettingsDirty)
            editor.editSessionReplyLengthInput(editor.state.value.sessionReplyLengthInput)
            assertFalse(editor.state.value.sessionSettingsDirty)
            editor.saveSessionSettings()
            assertNull(editor.state.value.error)
            assertEquals("Brazilian Portuguese", editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            editor.editSessionSettings { it.copy(voiceLanguage = "discard this language") }
            editor.discardSessionSettings()
            val reopened = com.example.chatbar.data.repository.ChatRepository(com.example.chatbar.data.local.JsonFileStorage(root))
            assertEquals("Brazilian Portuguese", reopened.getSession(first)!!.voiceLanguage)
            assertNull(reopened.getSession(second)!!.voiceLanguage)
        }
    }

    @Test fun `credential state never carries token and failed replace clear retain committed key`() = runBlocking {
        val secrets = InMemoryDesktopSecretStore()
        secrets.save(DesktopCredentialKey.NovelAiToken, "other")
        val c = DesktopFishCredentialController(DesktopFishAudioCredentials(secrets))
        c.refresh(); assertFalse(c.state.value.configured)
        assertFalse(c.save("  ")); assertNotNull(c.state.value.error)
        assertTrue(c.save(" synthetic-fish ")); assertTrue(c.state.value.configured)
        secrets.saveFailureOnCall = 3
        assertFalse(c.save("replacement")); assertTrue(c.state.value.configured)
        assertEquals("synthetic-fish", secrets.load(DesktopCredentialKey.FishAudioApiKey))
        secrets.deleteFailureOnCall = 1
        assertFalse(c.clear()); assertTrue(c.state.value.configured)
        assertFalse(c.state.value.toString().contains("synthetic-fish"))
        assertTrue(c.clear()); assertFalse(c.state.value.configured)
        assertEquals("other", secrets.load(DesktopCredentialKey.NovelAiToken))
        c.closeAndDrain(); assertFalse(c.save("after-close"))
    }

    @Test fun `global voice draft merge preserves concurrent settings and survives restart`() = runBlocking {
        fishFixture { root, c ->
            val settings = c.settingsRepository
            settings.saveAppSettings(AppSettings(defaultContextWindowSize = 30))
            val editor = c.modelSettingsController
            editor.loadSettings()
            editor.editSettings { it.copy(fishAudioTtsModelId = FishAudioTtsModels.S1,
                voiceTagModelId = "missing-explicit", audiobookModeEnabled = true) }
            assertTrue(editor.state.value.chatDefaultsDirty)
            settings.updateAppSettings { it.copy(imagePromptToolPreference = "concurrent-image", chatBubbleFontScale = 1.2f,
                defaultContextWindowSize = 41) }
            editor.saveAppSettings(); assertNull(editor.state.value.error)
            val saved = settings.getAppSettings()
            assertEquals("concurrent-image", saved.imagePromptToolPreference)
            assertEquals(1.2f, saved.chatBubbleFontScale)
            assertEquals(41, saved.defaultContextWindowSize)
            assertEquals("41", editor.state.value.settings!!.defaultContextWindowSize)
            assertFalse(editor.state.value.chatDefaultsDirty)
            assertEquals("missing-explicit", saved.voiceTagModelId)
            val reopened = com.example.chatbar.data.repository.SettingsRepository(com.example.chatbar.data.local.JsonFileStorage(root))
            val actual = reopened.getAppSettings()
            assertTrue(actual.audiobookModeEnabled); assertEquals(FishAudioTtsModels.S1, actual.fishAudioTtsModelId)
            assertEquals("missing-explicit", actual.voiceTagModelId)
            editor.editSettings { it.copy(voiceTagModelId = null) }; editor.discardChatDefaults()
            assertEquals("missing-explicit", editor.state.value.settings!!.voiceTagModelId)
        }
    }

    @Test fun `session voice inherit on off language save discard and second session isolation`() = runBlocking {
        fishFixture { _, c ->
            val model = ModelConfig.create("fixture", "https://fixture.invalid", "synthetic-session-fixture-key", "fixture")
            c.modelRepository.saveModel(model)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id))
            val card = CharacterCard.create("fixture", greeting = "hello")
            c.characterRepository.save(card)
            val editor = c.primaryChatController
            editor.refresh()
            assertTrue(c.effectiveModelResolver.status(c.settingsRepository.getAppSettings()).isUsable)
            assertEquals(model.id, c.settingsRepository.getAppSettings().defaultModelId)
            editor.createSession(card.id)
            assertNull(editor.state.value.error)
            assertTrue(editor.state.value.modelUsable)
            val first = assertNotNull(editor.state.value.selectedSession).id
            assertEquals(card.id, editor.state.value.selectedSession!!.characterCardId)
            editor.editSessionSettings { it.copy(audiobookModeEnabled = true, voiceLanguage = "日语") }
            editor.saveSessionSettings(); assertNull(editor.state.value.error)
            assertEquals("日语", editor.state.value.selectedSession!!.voiceLanguage)
            editor.createSession(card.id)
            assertNull(editor.state.value.error)
            val second = assertNotNull(editor.state.value.selectedSession).id
            assertNotEquals(first, second); assertNull(editor.state.value.sessionSettingsDraft!!.voiceLanguage)
            editor.editSessionSettings { it.copy(audiobookModeEnabled = false) }
            editor.selectSession(first)
            assertEquals(second, editor.state.value.selectedSession!!.id)
            assertTrue(editor.state.value.sessionSettingsLeavePrompt)
            editor.resolveSessionSettingsLeave(save = false)
            assertEquals(first, editor.state.value.selectedSession!!.id)
            assertEquals(true, editor.state.value.sessionSettingsDraft!!.audiobookModeEnabled)
            editor.editSessionSettings { it.copy(audiobookModeEnabled = null, voiceLanguage = null) }
            editor.saveSessionSettings()
            assertNull(editor.state.value.selectedSession!!.audiobookModeEnabled)
            assertNull(editor.state.value.selectedSession!!.voiceLanguage)
        }
    }

    @Test fun `both person modes bind exact snapshots without changing identity and discard stays local`() = runBlocking {
        fishFixture { _, c ->
            val editor = c.characterEditorController
            for (mode in CharacterEditMode.entries) {
                val person = CharacterInfo.create("Alice")
                val card = CharacterCard.create("card-$mode", greeting = "hello").copy(editMode = mode, characters = listOf(person))
                c.characterRepository.save(card); editor.openExisting(card.id)
                val draft = editor.state.value.draftSessionId!!
                val snapshot = FishAudioModel("private-ref", "Voice", visibility = "private",
                    author = FishAudioAuthor("author", "Author"), samples = listOf(FishAudioSample(audioUrl = "https://sample.invalid/a.mp3"))).toBinding()
                assertFalse(editor.bindFishVoice("obsolete", person.id, snapshot))
                assertTrue(editor.bindFishVoice(draft, person.id, snapshot))
                assertNull(c.characterRepository.getById(card.id)!!.characters.single().fishAudioVoice)
                assertTrue(editor.save())
                editor.closeClean(); editor.openExisting(card.id)
                assertEquals(person.id, editor.state.value.card!!.characters.single().id)
                assertEquals(snapshot, editor.state.value.card!!.characters.single().fishAudioVoice)
                assertFalse(editor.bindFishVoice(draft, person.id, null))
                assertTrue(editor.bindFishVoice(editor.state.value.draftSessionId!!, person.id, null))
                editor.discard(); editor.openExisting(card.id)
                assertEquals(snapshot, editor.state.value.card!!.characters.single().fishAudioVoice)
                editor.removeCharacter(person.id)
                assertFalse(editor.bindFishVoice(editor.state.value.draftSessionId!!, person.id, snapshot))
                editor.discard()
            }
        }
    }

    @Test fun `binding package JSON PNG duplicate and import preserve exact snapshots in both modes`() = runBlocking {
        fishFixture { _, c ->
            for (mode in CharacterEditMode.entries) {
                val voice = FishAudioModel("ref-$mode", "Voice", tags = listOf("fixture"), languages = listOf("zh"),
                    samples = listOf(FishAudioSample(text = "sample", audioUrl = "https://sample.invalid/a.mp3"))).toBinding()
                val card = CharacterCard.create("package-$mode", greeting = "hello").copy(editMode = mode,
                    characters = listOf(CharacterInfo.create("Alice").copy(fishAudioVoice = voice)))
                c.characterRepository.save(card)
                val core = c.characterTransfers
                val data = core.decode(core.exportJson(card.id))
                assertEquals(voice, data.card.characters.single().fishAudioVoice)
                val png = DesktopImageEditing.png(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB))
                val encoded = com.example.chatbar.domain.card.CharacterCardPngPackageCodec.attach(png, data, c.transferJson)
                val decoded = assertNotNull(core.decodePng(encoded))
                assertEquals(voice, decoded.card.characters.single().fishAudioVoice)
                val copied = core.duplicate(card.id)
                assertEquals(voice, copied.characters.single().fishAudioVoice)
                val imported = core.importNew(decoded.copy(card = decoded.card.copy(name = "import-$mode")))
                assertEquals(mode, imported.editMode)
                assertEquals(voice, imported.characters.single().fishAudioVoice)
            }
        }
    }

    @Test fun `late uncooperative query cannot replace new results or remain selectable after close`() = runBlocking {
        val old = CompletableDeferred<FishAudioModelPage>()
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val picker = DesktopFishPickerController(scope) { query ->
            if (query.title == "old") withContext(NonCancellable) { entered.complete(Unit); old.await() }
            else FishAudioModelPage(items = listOf(FishAudioModel("new", "New")), hasMore = false)
        }
        picker.search(FishAudioModelQuery(title = "old")); entered.await()
        picker.search(FishAudioModelQuery(title = "new"))
        assertEquals("new", picker.state.value.page.items.single().id)
        old.complete(FishAudioModelPage(items = listOf(FishAudioModel("old", "Old"))))
        yield(); assertNull(picker.selectable("old")); assertNotNull(picker.selectable("new"))
        picker.invalidate(FishAudioModelQuery(title = "changed")); assertNull(picker.selectable("new"))
        picker.close(); picker.search(); assertNull(picker.selectable("new")); scope.cancel()
    }

    @Test fun `closing picker cancels the owned request and cannot bind stale page`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val picker = DesktopFishPickerController(scope) {
            try { entered.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        picker.search(); entered.await(); picker.close()
        withTimeout(2_000) { cancelled.await() }
        assertNull(picker.selectable("anything")); scope.cancel()
    }
}

internal suspend fun fishFixture(block: suspend (java.nio.file.Path, DesktopAppContainer) -> Unit) {
    val root = Files.createTempDirectory("fish-settings-fixture-")
    val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { name ->
            when (name) {
                "presets/manifest.json" -> """{"entries":[{"type":"MODEL_CATALOG","version":1,"file":"presets/models/default-models.json"}]}"""
                "presets/models/default-models.json" -> """{"schemaVersion":2,"provider":"fixture","baseUrl":"https://fixture.invalid","chatModels":[]}"""
                else -> error("Unexpected fixture asset: $name")
            }.toByteArray()
        })
    try { block(root, container) } finally { container.close(); root.toFile().deleteRecursively() }
}

/** One explicit suspension/failure at real repository storage admission, without sleeps or fake saves. */
private class FishSettingsSaveGate : com.example.chatbar.data.operation.AppDataOperationGate {
    var beforeNext: (suspend () -> Unit)? = null
    override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
        val before = beforeNext
        beforeNext = null
        before?.invoke()
        return operation()
    }
}

private class SessionSettingsStorageGate : com.example.chatbar.data.operation.AppDataOperationGate {
    var beforeNext: (suspend () -> Unit)? = null
    var admissions = 0
        private set
    override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
        admissions++
        val before = beforeNext
        beforeNext = null
        before?.invoke()
        return operation()
    }
}

private data class SessionSaveFixture(
    val editor: DesktopPrimaryChatController,
    val chats: com.example.chatbar.data.repository.ChatRepository,
    val gate: SessionSettingsStorageGate,
    val characterId: String,
    val first: String,
    val second: String,
)

/** Real repositories/controller, synthetic credentials, and explicit storage admission suspension. */
private suspend fun sessionSaveFixture(
    afterWindowRead: suspend () -> Unit = {},
    block: suspend (SessionSaveFixture) -> Unit,
) = fishFixture { root, c ->
    val model = ModelConfig.create("fixture", "https://fixture.invalid", "synthetic-session-key", "fixture")
    c.modelRepository.saveModel(model)
    c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id))
    val card = CharacterCard.create("session save fixture", greeting = "hello")
    c.characterRepository.save(card)
    val gate = SessionSettingsStorageGate()
    val chats = com.example.chatbar.data.repository.ChatRepository(
        com.example.chatbar.data.local.JsonFileStorage(root.resolve("session-save-fixture"), gate))
    val sessions = com.example.chatbar.domain.chat.CharacterSessionService(c.characterRepository, chats, c.formatCardRepository)
    val first = sessions.createSessionForCharacter(card.id)
    val second = sessions.createSessionForCharacter(card.id)
    val editor = DesktopPrimaryChatController(c.characterRepository, chats, c.settingsRepository,
        c.effectiveModelResolver, c.formatCardRepository, c.worldBookRepository, sessions,
        c.characterResourceStore, taskRuntime = c.taskRuntime, afterWindowRead = afterWindowRead)
    try {
        editor.refresh()
        editor.selectSession(first)
        assertEquals(first, editor.state.value.selectedSession?.id)
        assertNull(editor.state.value.error)
        block(SessionSaveFixture(editor, chats, gate, card.id, first, second))
    } finally { editor.closeDraftPersistence() }
}
