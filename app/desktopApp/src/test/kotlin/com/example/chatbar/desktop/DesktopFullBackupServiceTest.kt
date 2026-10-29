package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.entity.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopFullBackupServiceTest {
    @Test fun `selected modern service reads protected credentials only for requested export and keeps live settings unchanged`() = runBlocking {
        Fixture().use { f ->
            Files.createDirectories(f.settings.parent)
            Files.writeString(f.settings, backupJson.encodeToString(AppSettings.serializer(), AppSettings()))
            val original = Files.readAllBytes(f.settings)
            var reads = 0
            val store = object : com.example.chatbar.desktop.security.DesktopSecretStore {
                override fun load(key: com.example.chatbar.desktop.security.DesktopCredentialKey): String? {
                    reads++
                    return if (key == com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken) "SYNTHETIC_NAI_SERVICE_SECRET" else null
                }
                override fun save(key: com.example.chatbar.desktop.security.DesktopCredentialKey, secret: String) = error("Export must not write credentials")
                override fun delete(key: com.example.chatbar.desktop.security.DesktopCredentialKey) = error("Export must not delete credentials")
            }
            val focus = DesktopAudioFocus()
            val service = DesktopFullBackupService(f.root, f.gate, DesktopAudioMaintenance({ focus }) {}, secretStore = { store })
            service.exportModern(f.base.resolve("without-secret.cbbackup"), null)
            assertEquals(0, reads)
            val choice = AppBackupSelection(globalOptions = setOf("novelAiCredentials"))
            val output = f.base.resolve("with-secret.cbbackup")
            service.exportModern(output, null, true, choice)
            var token: String? = null
            val result = DesktopFullBackupSource.verify(output, null, object : DesktopFullBackupSource.TentativeSink {
                override fun entity(path: String, original: kotlinx.serialization.json.JsonObject, raw: ByteArray) {}
                override fun preferences(original: kotlinx.serialization.json.JsonObject) {
                    token = (original["novelAiToken"] as kotlinx.serialization.json.JsonPrimitive).content
                }
                override fun media(path: String, input: java.io.InputStream) {}
            })
            assertEquals("SYNTHETIC_NAI_SERVICE_SECRET", token)
            assertEquals(setOf("novelAiCredentials"), result.manifest.scope!!.globalOptions)
            assertTrue(reads > 0); assertContentEquals(original, Files.readAllBytes(f.settings))
        }
    }
    @Test fun `whole backup keeps participation as Desktop extension in legacy and modern envelopes`() = runBlocking {
        for (modern in listOf(false, true)) Fixture().use { f ->
            val person = CharacterInfo.create("Synthetic").copy(desktopEnabled = false)
            val auto = CharacterInfo.create("Automatic")
            val card = CharacterCard.create("Synthetic card").copy(id = "apiKey", characters = listOf(person, auto))
            val session = ChatSession.create(card.id, "Synthetic chat").copy(
                desktopAutoPauseAbsentPeople = true,
                desktopCharacterParticipation = listOf(SessionCharacterParticipation(card.id, setOf(person.id), setOf(auto.id))))
            val cardFile = f.root.resolve("entities/character_cards/${card.id}.json")
            val sessionFile = f.root.resolve("entities/chat_sessions/${session.id}.json")
            Files.createDirectories(cardFile.parent); Files.createDirectories(sessionFile.parent)
            Files.writeString(cardFile, backupJson.encodeToString(CharacterCard.serializer(), card))
            Files.writeString(sessionFile, backupJson.encodeToString(ChatSession.serializer(), session))
            val original = Files.readAllBytes(sessionFile)
            val originalCard = Files.readAllBytes(cardFile)
            val output = f.base.resolve("participation.cbbackup")
            if (modern) {
                f.service.exportModern(output, null)
                DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(f.base.resolve("inspect"))).use { staged ->
                    val value = staged.entities.getValue("entities/chat_sessions/${session.id}.json")
                    assertTrue(DesktopBackupPolicy.hasDesktopParticipation(value))
                    assertEquals(session, backupJson.decodeFromJsonElement(ChatSession.serializer(), value))
                    val cardValue = staged.entities.getValue("entities/character_cards/${card.id}.json")
                    assertTrue(DesktopBackupPolicy.hasDesktopParticipation(cardValue))
                    assertEquals(card, backupJson.decodeFromJsonElement(CharacterCard.serializer(), cardValue))
                }
            } else {
                f.service.export(output, null)
                f.service.inspect(output, null).use { prepared ->
                    assertTrue(prepared.desktopOnly)
                    val value = Files.readString(prepared.files.getValue("entities/chat_sessions/${session.id}.json"))
                    assertEquals(session, backupJson.decodeFromString(ChatSession.serializer(), value))
                    val cardValue = Files.readString(prepared.files.getValue("entities/character_cards/${card.id}.json"))
                    assertEquals(card, backupJson.decodeFromString(CharacterCard.serializer(), cardValue))
                }
            }
            assertContentEquals(original, Files.readAllBytes(sessionFile))
            assertContentEquals(originalCard, Files.readAllBytes(cardFile))
        }
    }

    private class Fixture : AutoCloseable {
        val base = Files.createTempDirectory("whole-app-service-")
        val root = Files.createDirectory(base.resolve("root"))
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val events = mutableListOf<String>()
        var drain: suspend () -> Unit = {}
        var pause: suspend () -> Unit = {}
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {
            events += "drain"; drain()
        }, beforeSnapshot = { events += "flush" }, pauseScheduler = { events += "pause"; pause() },
            resumeScheduler = { events += "resume" })
        val settings = root.resolve("entities/app_settings.json")
        suspend fun seed(): DesktopFullBackupArchive.Prepared {
            Files.createDirectories(settings.parent)
            Files.writeString(settings, backupJson.encodeToString(AppSettings.serializer(), AppSettings(themeMode = ThemeMode.DARK)))
            val path = base.resolve("saved.cbbackup")
            val password = "test-password".toCharArray()
            service.export(path, password)
            assertTrue(password.all { it == '\u0000' })
            val readPassword = "test-password".toCharArray()
            val prepared = service.inspect(path, readPassword)
            assertTrue(readPassword.all { it == '\u0000' })
            Files.writeString(settings, backupJson.encodeToString(AppSettings.serializer(), AppSettings(themeMode = ThemeMode.LIGHT)))
            events.clear()
            return prepared
        }
        fun theme() = backupJson.decodeFromString(AppSettings.serializer(), Files.readString(settings)).themeMode
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            owner.close(); base.toFile().deleteRecursively()
        }
    }

    @Test fun `modern user export preserves code86 fields plain and encrypted without exporting known credentials`() = runBlocking<Unit> {
        for (encrypted in listOf(false, true)) Fixture().use { f ->
            val card = f.root.resolve("entities/character_cards/c.json")
            Files.createDirectories(card.parent)
            val original = """{"id":"c","name":"Synthetic","defaultImageGenerationSettings":{"varietyPlus":false},"createdAt":1,"updatedAt":1}"""
            Files.writeString(card, original)
            Files.createDirectories(f.root.resolve("entities/model_configs"))
            Files.writeString(f.root.resolve("entities/model_configs/m.json"), CensusWireFixtures.model())
            val output = f.base.resolve("modern.cbbackup")
            val password = if (encrypted) "FAKE_MODERN_EXPORT_PASSWORD".toCharArray() else null
            f.service.exportModern(output, password)
            assertTrue(password == null || password.all { it == '\u0000' })
            val readPassword = if (encrypted) "FAKE_MODERN_EXPORT_PASSWORD".toCharArray() else null
            DesktopFullBackupStaging.prepare(output, readPassword, Files.createDirectory(f.base.resolve("inspection"))).use { inspected ->
                assertEquals(2, inspected.source.manifest.formatVersion)
                assertEquals(86, inspected.source.manifest.appVersionCode)
                assertEquals(encrypted, inspected.source.encrypted)
                assertEquals(kotlinx.serialization.json.JsonPrimitive(false), inspected.entities.getValue("entities/character_cards/c.json")
                    .getValue("defaultImageGenerationSettings").let { it as kotlinx.serialization.json.JsonObject }.getValue("varietyPlus"))
                assertTrue(inspected.secrets.metadata.none { it.state == CredentialFieldState.NONEMPTY })
            }
            assertEquals(original, Files.readString(card))
            assertFalse(f.service.restartRequired)
            assertFails { f.service.inspect(output, if (encrypted) "FAKE_MODERN_EXPORT_PASSWORD".toCharArray() else null) }
        }
    }

    @Test fun `real gate seals only after schedule and startup installs before any repository opens`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            Files.writeString(f.root.resolve("desktop-settings.json"), "local settings sentinel")
            f.service.scheduleRestore(prepared)
            f.service.release(prepared)
            assertFalse(Files.exists(prepared.directory))
            assertEquals(listOf("flush", "pause", "drain", "resume"), f.events)
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertTrue(f.service.restartRequired)
            assertFailsWith<DesktopDataOperationRejectedException> { f.gate.withNormalOperation { error("late writer admitted") } }
            f.gate.closeAndDrain(); f.owner.close()
            var business = false
            DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(f.resolved) }, presenter = { error("unexpected refusal") }).run {
                assertTrue(DesktopDataRootOwnership.isHeld(f.root))
                assertEquals(ThemeMode.DARK, f.theme())
                assertEquals("local settings sentinel", Files.readString(f.root.resolve("desktop-settings.json")))
                assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
                business = true
            }
            assertTrue(business); assertFalse(DesktopDataRootOwnership.isHeld(f.root))
            assertTrue(Files.exists(f.base.resolve("saved.cbbackup")))
        }
    }

    @Test fun `unconfirmed audio close refuses scheduling and preserves current data for retry`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            f.drain = { error("native close unconfirmed") }
            assertFailsWith<DesktopAudioDrainException> { f.service.scheduleRestore(prepared) }
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            assertEquals(listOf("flush", "pause", "drain", "resume"), f.events)
            f.drain = {}; f.service.scheduleRestore(prepared)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `cancellation during drain completes close and resumes scheduler without scheduling`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val job = launch { f.service.scheduleRestore(prepared) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
            assertEquals("resume", f.events.last())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            f.drain = {}; f.service.scheduleRestore(prepared)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `scheduler can finish an audio reservation before restore reserves audio`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            val schedulerAudio = DesktopAudioMaintenance({ f.focus }) {}
            f.pause = {
                schedulerAudio.withMaintenance(false, { f.gate.isRestartRequired }) {
                    f.gate.withExclusiveMaintenance { f.events += "scheduled-backup-finished" }
                }
            }
            f.service.scheduleRestore(prepared)
            assertEquals(listOf("flush", "pause", "scheduled-backup-finished", "drain", "resume"), f.events)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `changed inspected bytes cannot acquire audio or schedule restore`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            Files.writeString(prepared.files.values.first(), "changed")
            assertFails { f.service.scheduleRestore(prepared) }
            assertTrue(f.events.isEmpty()); assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
        }
    }

    @Test fun `cancelled request waiting for operation mutex erases its password`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val first = async { f.service.export(f.base.resolve("second.cbbackup"), null) }
            entered.await()
            val secret = "synthetic-secret".toCharArray()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { f.service.inspect(f.base.resolve("never-read"), secret) }
            queued.cancelAndJoin()
            assertTrue(secret.all { it == '\u0000' })
            release.complete(Unit); first.await()
            assertFalse(f.service.restartRequired)
        }
    }

    @Test fun `controller discards replaces and closes inspection without removing original archive or journals`() = runBlocking {
        Fixture().use { f ->
            f.service.release(f.seed())
            val source = f.base.resolve("saved.cbbackup")
            val original = Files.readAllBytes(source)
            val untouched = Files.createDirectories(f.root.resolve("backups/.restore-app.tmp/unknown-evidence"))
                .resolve("keep")
            Files.write(untouched, byteArrayOf(7))
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = source
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
            }
            val controller = DesktopFullBackupController(f.service, picker)
            suspend fun ready() = withTimeout(20000) { controller.state.first { !it.busy }.also { assertNull(it.error) } }
            fun workCount() = Files.list(f.root.resolve("backups/.full-app-work")).use { it.count() }
            assertEquals(0L, workCount()) // encrypted export snapshot and self-check are both gone
            controller.inspect("test-password".toCharArray()); ready()
            assertEquals(1L, workCount())
            controller.inspect("test-password".toCharArray()); ready()
            assertEquals(1L, workCount()) // replacing inspection releases the previous one
            controller.discardInspection(); ready()
            assertEquals(0L, workCount())
            controller.inspect("test-password".toCharArray()); ready()
            controller.leavePanel(); ready()
            assertEquals(0L, workCount())
            controller.inspect("test-password".toCharArray()); ready()
            controller.closeAndDrain()
            assertEquals(0L, workCount())
            assertContentEquals(original, Files.readAllBytes(source))
            assertContentEquals(byteArrayOf(7), Files.readAllBytes(untouched))
        }
    }

    @Test fun `failed and cancelled restore releases mapped plaintext while pending inspection remains retryable`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            f.drain = { error("native close unconfirmed") }
            assertFails { f.service.scheduleRestore(prepared) }
            assertFalse(Files.list(prepared.directory).use { it.anyMatch { path -> path.fileName.toString().startsWith("mapped-") } })
            assertTrue(Files.exists(prepared.files.values.first()))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val job = launch { f.service.scheduleRestore(prepared) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertFalse(Files.list(prepared.directory).use { it.anyMatch { path -> path.fileName.toString().startsWith("mapped-") } })
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
            f.service.release(prepared)
            assertFalse(Files.exists(prepared.directory))
        }
    }

    @Test fun `leaving panel during inspection releases its eventual result without cancelling the operation`() = runBlocking {
        Fixture().use { f ->
            f.service.release(f.seed())
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val source = f.base.resolve("saved.cbbackup")
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path {
                    entered.complete(Unit); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)); return source
                }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
            }
            val controller = DesktopFullBackupController(f.service, picker)
            try {
                controller.inspect("test-password".toCharArray())
                withTimeout(20000) { entered.await() }
                controller.leavePanel(); release.countDown()
                val state = withTimeout(20000) { controller.state.first { !it.busy } }
                assertNull(state.error); assertNull(state.summary)
                assertEquals(0L, Files.list(f.root.resolve("backups/.full-app-work")).use { it.count() })
                assertTrue(Files.exists(source))
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            } finally { release.countDown(); controller.closeAndDrain() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `controller cancellation before dispatch clears busy and password without opening a picker`() = runBlocking {
        Fixture().use { f ->
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
            var picks = 0
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? { picks++; return null }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? { picks++; return null }
            }
            val controller = DesktopFullBackupController(f.service, picker, dispatcher)
            val secret = "synthetic-password".toCharArray()
            controller.inspect(secret); assertTrue(controller.state.value.busy)
            controller.stop(); dispatcher.scheduler.runCurrent()
            assertFalse(controller.state.value.busy)
            assertTrue(secret.all { it == '\u0000' }); assertEquals(0, picks)
            controller.closeAndDrain()
        }
    }
}
