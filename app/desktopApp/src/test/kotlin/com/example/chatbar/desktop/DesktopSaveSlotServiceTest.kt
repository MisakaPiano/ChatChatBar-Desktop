package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import java.awt.image.BufferedImage
import java.nio.file.*
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopSaveSlotServiceTest {
    @Test fun `player role and local avatar survive packaged export import and restored media custody`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed()
            val role = SessionPlayerRole(f.session.characterCardId, "explicit-person", followName = false)
            f.chats.saveSessionSettingsDraft(f.session, f.session.copy(desktopPlayerRole = role, desktopPlayerAvatar = "images/synthetic.png"))
            val service = f.service()
            val slot = service.create("s", "Player", SaveSlotImagePolicy.ORIGINAL, false)
            assertEquals(role, slot.desktopPlayerRole)
            val output = f.root.resolve("player.cbsave")
            service.export(slot.id, output)
            val imported = service.import(output, "s")
            val current = f.chats.getSession("s")!!
            f.chats.saveSessionSettingsDraft(current, current.copy(desktopPlayerRole = null, desktopPlayerAvatar = null))
            service.restore(imported.id, "s")
            val restored = f.chats.getSession("s")!!
            assertEquals(role, restored.desktopPlayerRole)
            assertNotNull(restored.desktopPlayerAvatar)
            assertContentEquals(Files.readAllBytes(f.root.resolve("images/synthetic.png")),
                Files.readAllBytes(f.root.resolve(restored.desktopPlayerAvatar!!)))
            assertNotEquals("images/synthetic.png", restored.desktopPlayerAvatar)
            val currentSlot = service.create("s", "Without images", SaveSlotImagePolicy.NONE, false)
            assertNull(currentSlot.desktopPlayerAvatar)
            assertEquals(role, currentSlot.desktopPlayerRole)
        }
    }

    @Test fun `participation survives real package export import restore with original card scope`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            val overrides = listOf(SessionCharacterParticipation(f.session.characterCardId, setOf("disabled-person"), setOf("automatic-person")),
                SessionCharacterParticipation("other-card", setOf("other-person")))
            f.chats.saveSessionSettingsDraft(f.session, f.session.copy(desktopCharacterParticipation = overrides, desktopAutoPauseAbsentPeople = true))
            val service = f.service()
            val slot = service.create("s", "Participation", SaveSlotImagePolicy.ORIGINAL, false)
            assertEquals(overrides, slot.desktopCharacterParticipation)
            assertTrue(slot.desktopAutoPauseAbsentPeople)
            val output = f.root.resolve("participation.cbsave")
            service.export(slot.id, output)
            val imported = service.import(output, "s")
            assertEquals(overrides, imported.desktopCharacterParticipation)
            assertTrue(imported.desktopAutoPauseAbsentPeople)
            val current = f.chats.getSession("s")!!
            f.chats.saveSessionSettingsDraft(current, current.copy(desktopCharacterParticipation = emptyList(), desktopAutoPauseAbsentPeople = false))
            service.restore(imported.id, "s")
            assertEquals(overrides, f.chats.getSession("s")!!.desktopCharacterParticipation)
            assertTrue(f.chats.getSession("s")!!.desktopAutoPauseAbsentPeople)
            assertEquals(f.session.characterCardId, f.chats.getSession("s")!!.characterCardId)
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("desktop-slot-service-")
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")))).ownership
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, true)
        val focus = DesktopAudioFocus()
        var snapshot: MemorySnapshot? = null
        var drain: suspend () -> Unit = {}
        val audio = DesktopAudioMaintenance({ focus }) { drain() }
        val session = ChatSession.create("synthetic-card", "Synthetic").copy(id = "s", longTermMemoryEnabled = false,
            playerName = "Test player", imagePromptPreference = "retain preference")
        val message = SyntheticSaveSlotCorpus.message().copy(images = listOf("images/synthetic.png"))
        fun service(journal: DesktopSessionCommitJournal = DesktopSessionCommitJournal(root)) = DesktopSaveSlotService(root, gate,
            audio, object : DesktopSessionReservation { override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T = operation() },
            chats, voices, { snapshot }, { reference ->
                if (Path.of(reference).isAbsolute) DesktopVoiceAudioStore(root).verify(reference) else root.resolve(reference)
            }, journal)
        suspend fun seed() {
            chats.createSession(session); chats.addMessage(message); chats.updateSessionDraft("s", "unsent draft")
            val image = BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB)
            Files.createDirectories(root.resolve("images"))
            try { ImageIO.write(image, "png", root.resolve("images/synthetic.png").toFile()) } finally { image.flush() }
        }
        override fun close() { runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }; owner.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `restore is an atomic graph replacement with safety slot and original resources retained`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val slot = service.create("s", "Before edit", SaveSlotImagePolicy.ORIGINAL, false)
            val sourceImage = Files.readAllBytes(f.root.resolve("images/synthetic.png"))
            f.chats.updateMessage(f.message.copy(content = "later edit", alternatives = emptyList(), alternativeVersionIds = emptyList()))
            service.restore(slot.id, "s")
            val restored = f.chats.getMessages("s").single()
            assertEquals(f.message.id, restored.id)
            assertEquals(f.message.content, restored.content)
            assertEquals(f.message.sourceTurnId, restored.sourceTurnId)
            assertEquals("synthetic-card", f.chats.getSession("s")!!.characterCardId)
            assertEquals("retain preference", f.chats.getSession("s")!!.imagePromptPreference)
            assertEquals(2, service.list("s").size)
            assertNotEquals(f.message.images.single(), restored.images.single())
            assertContentEquals(sourceImage, DesktopCharacterResourceStore(f.root).readBytes(restored.images.single()))
            assertContentEquals(sourceImage, Files.readAllBytes(f.root.resolve("images/synthetic.png")))
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
        }
    }

    @Test fun `legacy foreign imports remap colliding messages even after library envelope rebinding`(): Unit = runBlocking {
        for (schema in 1..7) Fixture().use { f ->
            f.seed(); f.voices.ensureAnchors(f.message)
            val before = DesktopSessionInventory(f.root).read("s")
            val target = f.session.copy(id="foreign",title="Foreign target")
            f.chats.createSession(target)
            val original = f.message.copy(images=emptyList(),generatedImageMetadata=emptyList())
            val slot = SaveSlot(schemaVersion=schema,id="legacy",sessionId="s",name="Synthetic legacy",createdAt=1,messages=listOf(original))
            val file = f.root.resolve("legacy-$schema.cbsave")
            Files.writeString(file,f.storage.json.encodeToString(SaveSlot.serializer(),slot))
            val service = f.service()
            val imported = service.import(file,target.id)
            assertEquals(target.id,imported.sessionId)
            service.restore(imported.id,target.id)
            assertEquals(before,DesktopSessionInventory(f.root).read("s"))
            val copied = f.chats.getMessages(target.id).single()
            assertNotEquals(original.id,copied.id)
            assertEquals(original.currentAlternativeVersionId,copied.currentAlternativeVersionId)
            assertEquals(original.sourceTurnId,copied.sourceTurnId)
        }
    }

    @Test fun `duplicate preserves versions source turns draft and settings without changing original`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val before = DesktopSessionInventory(f.root).read("s")
            val duplicate = f.service().duplicate("s")
            val copied = f.chats.getMessages(duplicate.id).single()
            assertNotEquals("s", duplicate.id); assertNotEquals(f.message.id, copied.id)
            assertEquals(f.message.currentAlternativeVersionId, copied.currentAlternativeVersionId)
            assertEquals(f.message.sourceTurnId, copied.sourceTurnId)
            assertEquals("unsent draft", f.chats.getSessionDraft(duplicate.id))
            assertEquals("retain preference", duplicate.imagePromptPreference)
            assertEquals(before, DesktopSessionInventory(f.root).read("s"))
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
        }
    }

    @Test fun `native close failure prohibits replacement and archive library publication`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val before = DesktopSessionInventory(f.root).read("s")
            f.drain = { error("unconfirmed native close") }
            assertFailsWith<DesktopAudioDrainException> { f.service().create("s", "Blocked", SaveSlotImagePolicy.NONE, false) }
            assertEquals(before, DesktopSessionInventory(f.root).read("s"))
            assertTrue(f.service().list("s").isEmpty())
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
            f.drain = {}
            // A new explicit drain can recover the sealed audio admission.
            f.service().create("s", "Recovered", SaveSlotImagePolicy.NONE, false)
            assertEquals(1, f.service().list("s").size)
        }
    }

    @Test fun `publication failure restores source graph and permits a later explicit retry`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val good = f.service()
            val slot = good.create("s", "Saved", SaveSlotImagePolicy.NONE, false)
            val before = DesktopSessionInventory(f.root).read("s")
            val bad = f.service(DesktopSessionCommitJournal(f.root) { if (it == 2) error("synthetic write failure") })
            assertFailsWith<IllegalStateException> { bad.restore(slot.id, "s") }
            assertEquals(before, DesktopSessionInventory(f.root).read("s"))
            assertEquals(1, good.list("s").size)
            good.restore(slot.id, "s")
            assertEquals(2, good.list("s").size)
        }
    }

    @Test fun `changed source after prepare refuses commit and cancellation cannot leave partial graph`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.drain = { f.chats.updateSessionDraft("s", "late draft") }
            assertFailsWith<IllegalStateException> { f.service().duplicate("s") }
            assertEquals(1, f.chats.getAllSessions().size)
            assertEquals("late draft", f.chats.getSessionDraft("s"))
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.drain = { reached.complete(Unit); release.await() }
            val job = launch { f.service().duplicate("s") }
            reached.await(); job.cancel(); release.complete(Unit); job.join()
            assertEquals(1, f.chats.getAllSessions().size)
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
        }
    }

    @Test fun `legacy import export remains independent and rejects an existing output`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            for (schema in 1..7) {
                val input = SyntheticSaveSlotCorpus.legacy(f.root.resolve("input-$schema.json"),
                    SyntheticSaveSlotCorpus.slot(schema).copy(messages = listOf(SyntheticSaveSlotCorpus.message())))
                val original = Files.readAllBytes(input)
                val slot = service.import(input, "s")
                val output = f.root.resolve("output-$schema.cbsave")
                service.export(slot.id, output)
                assertEquals(SlotInspectStatus.STRUCTURE_VALID, DesktopSaveSlotInspector().inspect(output).status)
                assertFailsWith<IllegalStateException> { service.export(slot.id, output) }
                assertContentEquals(original, Files.readAllBytes(input))
            }
        }
    }

    @Test fun `audio images and current memory survive duplicate under separate owned identities`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed()
            val audio = DesktopVoiceAudioStore(f.root).persist("s", "original", fishPreviewMp3().inputStream())
            val anchor = f.voices.ensureAnchors(f.message).single().anchor
            val voice = GeneratedVoiceMessage("voice", "s", "m", "v", anchorId = anchor.id,
                sourceOrder = anchor.sourceOrder, sourceSegmentKind = anchor.segmentKind,
                sourceSpeakerName = anchor.speakerName.orEmpty(), sourceText = anchor.sourceText, taggedText = anchor.sourceText, characterId = "c", characterName = "Synthetic",
                voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "synthetic", audioPath = audio.path,
                durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1)
            f.voices.restoreForSession("s", listOf(voice), listOf(f.message))
            val node = MemoryNode(id = "legacy-node", sessionId = "s", tier = MemoryTier.LEGACY_REFERENCE,
                overview = "合成记忆", coverageUnits = listOf(MemoryCoverageUnit("legacy:s", "合成记忆正文")), author = MemoryAuthor.MIGRATION)
            f.snapshot = MemorySnapshot(state = MemorySessionSnapshot(legacyReferenceNodeIds = listOf(node.id)), nodes = listOf(node))
            val copy = f.service().duplicate("s")
            val copiedVoice = f.voices.listForSession(copy.id).single()
            assertNotEquals(voice.id, copiedVoice.id); assertNotEquals(audio.path, copiedVoice.audioPath)
            assertEquals(f.chats.getMessages(copy.id).single().id, copiedVoice.messageId)
            assertEquals("v", copiedVoice.messageVersionId)
            assertNotNull(copiedVoice.anchorId)
            assertContentEquals(Files.readAllBytes(Path.of(audio.path)), Files.readAllBytes(DesktopVoiceAudioStore(f.root).verify(copiedVoice.audioPath)))
            val memory = MemoryRepository(f.storage, strictReads = true, dataGate = f.gate).snapshot(copy.id)!!
            assertTrue(memory.nodes.any { it.body.contains("合成记忆正文") })
            assertTrue(memory.nodes.all { it.sessionId == copy.id && it.id != node.id })
            assertTrue(Files.exists(Path.of(audio.path)))
        }
    }

    @Test fun `resource rebinding works while old card exists and preserves messages format and old resources`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed()
            val old = CharacterCard.create("Old").copy(id = "synthetic-card", defaultFormatCardId = "old-default")
            val next = CharacterCard.create("New").copy(id = "new-card", defaultFormatCardId = "must-not-apply")
            f.storage.saveEntity("character_cards", old.id, old, CharacterCard.serializer())
            f.storage.saveEntity("character_cards", next.id, next, CharacterCard.serializer())
            val book = WorldBook.create("New book").copy(id = "book")
            f.storage.saveEntity("world_books", book.id, book, WorldBook.serializer())
            f.chats.updateSession(f.session.copy(formatCardId = "keep-format", timedWorldInfo = mapOf("stale" to TimedEffectState("entry", 4, 5))))
            val messages = f.chats.getMessages("s")
            val rebound = f.service().rebind("s", next.id, listOf(book.id))
            assertEquals("s", rebound.id); assertEquals("new-card", rebound.characterCardId)
            assertEquals("keep-format", rebound.formatCardId); assertTrue(rebound.timedWorldInfo.isEmpty())
            assertEquals(listOf("book"), rebound.extraWorldBookIds)
            assertEquals(messages, f.chats.getMessages("s"))
            assertEquals(1, f.service().list("s").size)
            assertTrue(Files.exists(f.root.resolve("entities/character_cards/synthetic-card.json")))
            val before = DesktopSessionInventory(f.root).read("s")
            assertFailsWith<IllegalStateException> { f.service().rebind("s", "missing", emptyList()) }
            assertEquals(before, DesktopSessionInventory(f.root).read("s"))
        }
    }

    @Test fun `confirmed v8 deletion removes only exclusive package and record retaining live media`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val first = service.create("s", "Delete", SaveSlotImagePolicy.ORIGINAL, false)
            val second = service.create("s", "Keep", SaveSlotImagePolicy.ORIGINAL, false)
            val voice = DesktopVoiceAudioStore(f.root).persist("s", "keep", fishPreviewMp3().inputStream())
            val before = DesktopSessionInventory(f.root).read("s")
            val packagePath = f.root.resolve("save_slot_packages/${first.packageRef!!.fileName}")
            val keptPackage = f.root.resolve("save_slot_packages/${second.packageRef!!.fileName}")
            val keptBytes = Files.readAllBytes(keptPackage)
            service.delete(first.id, "s")
            assertEquals(listOf(second.id), service.list("s").map { it.id })
            assertFalse(Files.exists(packagePath))
            assertFalse(Files.exists(f.root.resolve("entities/save_slots/${first.id}.json")))
            assertContentEquals(keptBytes, Files.readAllBytes(keptPackage))
            assertEquals(before, DesktopSessionInventory(f.root).read("s"))
            assertTrue(Files.exists(f.root.resolve("images/synthetic.png")))
            assertTrue(Files.exists(Path.of(voice.path)))
        }
    }

    @Test fun `shared package remains until its final proven reference is deleted`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val first = service.create("s", "One", SaveSlotImagePolicy.ORIGINAL, false)
            val other = first.copy(id = "other", name = "Shared")
            f.storage.saveEntity("save_slots", other.id, other, SaveSlot.serializer())
            val packagePath = f.root.resolve("save_slot_packages/${first.packageRef!!.fileName}")
            service.delete(first.id, "s")
            assertTrue(Files.exists(packagePath))
            service.delete(other.id, "s")
            assertFalse(Files.exists(packagePath)); assertTrue(service.list("s").isEmpty())
        }
    }

    @Test fun `legacy one through seven deletion keeps original import and independent media`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            for (schema in 1..7) {
                val input = SyntheticSaveSlotCorpus.legacy(f.root.resolve("legacy-$schema.json"), SyntheticSaveSlotCorpus.slot(schema))
                val bytes = Files.readAllBytes(input)
                val imported = service.import(input, "s")
                service.delete(imported.id, "s")
                assertTrue(service.list("s").none { it.id == imported.id })
                assertContentEquals(bytes, Files.readAllBytes(input))
                assertTrue(Files.exists(f.root.resolve("images/synthetic.png")))
            }
        }
    }

    @Test fun `deletion failure rolls back both catalog and package before retry`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val slot = service.create("s", "Keep on failure", SaveSlotImagePolicy.NONE, false)
            val file = f.root.resolve("save_slot_packages/${slot.packageRef!!.fileName}")
            val bytes = Files.readAllBytes(file)
            val failing = f.service(DesktopSessionCommitJournal(f.root) { if (it == 1) error("synthetic deletion failure") })
            assertFails { failing.delete(slot.id, "s") }
            assertEquals(listOf(slot.id), service.list("s").map { it.id })
            assertContentEquals(bytes, Files.readAllBytes(file))
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
            service.delete(slot.id, "s")
            assertTrue(service.list("s").isEmpty())
        }
    }

    @Test fun `foreign bytes during deletion preserve recovery evidence and seal restart`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val slot = service.create("s", "Uncertain", SaveSlotImagePolicy.NONE, false)
            val record = f.root.resolve("entities/save_slots/${slot.id}.json")
            val failing = f.service(DesktopSessionCommitJournal(f.root) { index ->
                if (index == 0) { Files.writeString(record, "foreign sentinel"); error("synthetic uncertain commit") }
            })
            assertFails { failing.delete(slot.id, "s") }
            assertEquals("foreign sentinel", Files.readString(record))
            assertTrue(f.gate.isRestartRequired)
            assertTrue(DesktopSessionCommitJournal(f.root).hasPending())
            assertTrue(Files.exists(f.root.resolve("save_slot_packages/${slot.packageRef!!.fileName}")))
        }
    }

    @Test fun `late package reference and cancelled audio drain cannot authorize deletion`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val slot = service.create("s", "Late reference", SaveSlotImagePolicy.NONE, false)
            val other = slot.copy(id = "late", name = "Late")
            f.drain = { f.storage.saveEntity("save_slots", other.id, other, SaveSlot.serializer()) }
            assertFails { service.delete(slot.id, "s") }
            assertEquals(2, service.list("s").size)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val job = launch { service.delete(slot.id, "s") }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertEquals(2, service.list("s").size)
            assertFalse(DesktopSessionCommitJournal(f.root).hasPending())
        }
    }

    @Test fun `unknown catalog records and wrong session refuse deletion without guessing ownership`(): Unit = runBlocking {
        Fixture().use { f ->
            f.seed(); val service = f.service()
            val slot = service.create("s", "Keep", SaveSlotImagePolicy.NONE, false)
            assertFails { service.delete(slot.id, "wrong-session") }
            Files.writeString(f.root.resolve("entities/save_slots/unknown.json"), "{invalid}")
            assertFails { service.delete(slot.id, "s") }
            assertTrue(Files.exists(f.root.resolve("entities/save_slots/${slot.id}.json")))
            assertTrue(Files.exists(f.root.resolve("save_slot_packages/${slot.packageRef!!.fileName}")))
        }
    }
}
