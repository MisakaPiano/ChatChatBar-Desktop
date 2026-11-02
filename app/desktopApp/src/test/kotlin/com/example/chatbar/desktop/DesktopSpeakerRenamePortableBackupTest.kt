package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.backup.legacy.Official147SpeakerTagHistoryService
import com.example.chatbar.desktop.backup.legacy.entity.SpeakerTagRenameTask as Official147Task
import com.example.chatbar.domain.chat.SpeakerTagHistoryService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopSpeakerRenamePortableBackupTest {
    private class Fixture : AutoCloseable {
        val base = Files.createTempDirectory("speaker-portable-")
        val root = Files.createDirectory(base.resolve("data"))
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val storage = JsonFileStorage(root)
        val cards = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
        suspend fun seed(nextB: String = "C", partial: Boolean = true, nextA: String = "B") {
            val renames = listOf(SpeakerTagRename("a", "A", nextA), SpeakerTagRename("b", "B", nextB))
            val owner = SpeakerTagHistoryService(cards, chats)
            cards.save(CharacterCard("card", "Synthetic", characters = listOf(CharacterInfo("a", nextA), CharacterInfo("b", nextB)),
                pendingSpeakerRenameTasks = listOf(owner.createTask("card", 2, renames)!!), createdAt = 1, updatedAt = 2))
            chats.createSession(ChatSession("s", "card", "Synthetic", createdAt = 1, updatedAt = 1))
            chats.addMessage(ChatMessage("first", "s", MessageRole.ASSISTANT, "<n=\"A\"/>first", createdAt = 1, updatedAt = 1))
            chats.addMessage(ChatMessage("second", "s", MessageRole.ASSISTANT, "<n=\"B\"/>second", createdAt = 2, updatedAt = 2))
            if (partial) {
                val task = cards.getById("card")!!.pendingSpeakerRenameTasks.single()
                val failing = SpeakerTagHistoryService(cards, chats, rewrite = { work, persist ->
                    chats.rewriteSpeakerTagsForTaskObserved(work, persist) { throw IOException("synthetic after first write") }
                })
                assertFailsWith<IOException> { failing.execute("card", task.id) }
                assertEquals("<n=\"$nextA\"/>first", chats.getMessage("first", "s")!!.content)
            }
        }
        fun sourceBytes() = Files.walk(root.resolve("entities")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
        }
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            owner.close()
            base.toFile().deleteRecursively()
        }
    }

    private fun entries(path: Path): Map<String, ByteArray> = Files.newInputStream(path).use { input ->
        Official147FixtureCodec.decrypting(input, null).use { clear -> ZipInputStream(clear).use { zip -> buildMap {
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name.removePrefix("files/"), zip.readNBytes(1024 * 1024)); zip.closeEntry() }
        } } }
    }

    private suspend fun legacyReplay(base: Path, files: Map<String, ByteArray>): ChatRepository {
        val root = Files.createDirectory(base.resolve("official147-replay"))
        for ((name, bytes) in files.filterKeys { it.startsWith("entities/") }) {
            val target = root.resolve(name)
            Files.createDirectories(target.parent); Files.write(target, bytes)
        }
        val raw = backupJson.parseToJsonElement(files.getValue("entities/character_cards/card.json").decodeToString()).jsonObject
        val decoder = Json { ignoreUnknownKeys = true }
        val oldTasks = decoder.decodeFromJsonElement(ListSerializer(Official147Task.serializer()), raw.getValue("pendingSpeakerRenameTasks"))
        val oldRoundtrip = decoder.encodeToJsonElement(ListSerializer(Official147Task.serializer()), oldTasks).jsonArray
        assertTrue(oldRoundtrip.all { "rewritePlan" !in it.jsonObject && "rewriteProtocolVersion" !in it.jsonObject })
        val card = backupJson.decodeFromJsonElement(CharacterCard.serializer(), raw)
        val storage = JsonFileStorage(root)
        val cards = CharacterRepository(storage)
        cards.save(card.copy(pendingSpeakerRenameTasks = oldTasks.map { task ->
            SpeakerTagRenameTask(task.id, task.characterCardId, task.expectedCardUpdatedAt,
                task.renames.map { SpeakerTagRename(it.characterId, it.oldName, it.newName) }, task.createdAt, task.lastError)
        }))
        val chats = ChatRepository(storage)
        Official147SpeakerTagHistoryService(cards, chats).resumePending()
        return chats
    }

    @Test fun `selected prepared chain or swap refuses portable export while frozen147 actual replay proves the counterexample`() = runBlocking {
        for (modern in listOf(false, true)) for (nextB in listOf("C", "A")) Fixture().use { f ->
            f.seed(nextB)
            val before = f.sourceBytes()
            val output = f.base.resolve("refused.cbbackup")
            assertFailsWith<DesktopPendingSpeakerRenameExport> {
                if (modern) f.service.exportModern(output, null) else f.service.export(output, null)
            }
            assertFalse(Files.exists(output)); assertEquals(before, f.sourceBytes())
            // The internal source-preserving route is intentionally distinct from portable user export.
            val work = Files.createDirectory(f.base.resolve("internal-snapshot"))
            DesktopFullBackupMapping(f.root).export(work, if (modern) 86 else 84, forMerge = true).use { snapshot ->
                val unsafeForLegacy = f.base.resolve("internal-counterexample.cbbackup")
                if (modern) DesktopFullBackupWriter.export(snapshot.files, unsafeForLegacy, null, snapshot.preferences)
                else DesktopFullBackupArchive(work).export(snapshot.files, unsafeForLegacy, f.root.toString(), null, snapshot.preferences)
                val files = entries(unsafeForLegacy)
                val retained = backupJson.parseToJsonElement(files.getValue("entities/character_cards/card.json").decodeToString()).jsonObject
                assertTrue("rewritePlan" in retained.getValue("pendingSpeakerRenameTasks").jsonArray.single().jsonObject)
                val old = legacyReplay(f.base, files)
                assertEquals("<n=\"$nextB\"/>first", old.getMessage("first", "s")!!.content)
            }
            assertEquals(before, f.sourceBytes())
            SpeakerTagHistoryService(f.cards, f.chats).resumePending()
            assertEquals("<n=\"B\"/>first", f.chats.getMessage("first", "s")!!.content)
            assertTrue(f.cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
            if (modern) f.service.exportModern(f.base.resolve("recovered.cbbackup"), null)
            else f.service.export(f.base.resolve("recovered.cbbackup"), null)
            assertTrue(Files.isRegularFile(f.base.resolve("recovered.cbbackup")))
        }
    }

    @Test fun `unaffected selected cards export while the excluded prepared task and history remain untouched`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.cards.save(CharacterCard("other", "Other synthetic", createdAt = 1, updatedAt = 1))
            val before = f.sourceBytes()
            val output = f.base.resolve("other.cbbackup")
            f.service.exportModern(output, null, selection = AppBackupSelection(characterIds = setOf("other")))
            assertTrue(Files.exists(output))
            val files = entries(output)
            assertTrue("entities/character_cards/other.json" in files)
            assertFalse("entities/character_cards/card.json" in files)
            val workspace = Files.createDirectory(f.base.resolve("legacy-selected-work"))
            val selected = AppBackupSelection(characterIds = setOf("other"))
            DesktopFullBackupMapping(f.root).export(workspace, 84, selection = selected).use { snapshot ->
                val legacy = f.base.resolve("legacy-other.cbbackup")
                DesktopFullBackupArchive(workspace).export(snapshot.files, legacy, f.root.toString(), null, snapshot.preferences)
                assertTrue("entities/character_cards/other.json" in entries(legacy))
                assertFalse("entities/character_cards/card.json" in entries(legacy))
            }
            assertEquals(before, f.sourceBytes())
        }
    }

    @Test fun `a missing plan cannot prove legacy replay safe for an overlapping task`() = runBlocking {
        for (modern in listOf(false, true)) Fixture().use { f ->
            f.seed(partial = false)
            val before = f.sourceBytes()
            val output = f.base.resolve("not-started.cbbackup")
            assertFailsWith<DesktopPendingSpeakerRenameExport> {
                if (modern) f.service.exportModern(output, null) else f.service.export(output, null)
            }
            assertFalse(Files.exists(output)); assertEquals(before, f.sourceBytes())
        }
    }

    @Test fun `nonoverlapping task cannot let an old reader overwrite a later manual source edit`() = runBlocking {
        for (modern in listOf(false, true)) Fixture().use { f ->
            f.seed(nextA = "D")
            f.chats.updateMessage(f.chats.getMessage("first", "s")!!.copy(content = "<n=\"A\"/>later manual edit"))
            val before = f.sourceBytes()
            val output = f.base.resolve("changed-source-refused.cbbackup")
            assertFailsWith<DesktopPendingSpeakerRenameExport> {
                if (modern) f.service.exportModern(output, null) else f.service.export(output, null)
            }
            assertFalse(Files.exists(output)); assertEquals(before, f.sourceBytes())
            val work = Files.createDirectory(f.base.resolve("changed-source-internal"))
            DesktopFullBackupMapping(f.root).export(work, if (modern) 86 else 84, forMerge = true).use { snapshot ->
                val internal = f.base.resolve("changed-source-counterexample.cbbackup")
                if (modern) DesktopFullBackupWriter.export(snapshot.files, internal, null, snapshot.preferences)
                else DesktopFullBackupArchive(work).export(snapshot.files, internal, f.root.toString(), null, snapshot.preferences)
                val old = legacyReplay(f.base, entries(internal))
                assertEquals("<n=\"D\"/>later manual edit", old.getMessage("first", "s")!!.content)
            }
            assertEquals(before, f.sourceBytes())
            val task = f.cards.getById("card")!!.pendingSpeakerRenameTasks.single()
            assertFailsWith<IllegalStateException> { SpeakerTagHistoryService(f.cards, f.chats).execute("card", task.id) }
            assertEquals("<n=\"A\"/>later manual edit", f.chats.getMessage("first", "s")!!.content)
            assertNotNull(f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
        }
    }

    @Test fun `empty prepared plan and later current-name message cannot authorize an old-reader replay`() = runBlocking {
        for (modern in listOf(false, true)) Fixture().use { f ->
            f.seed(partial = false)
            for (id in listOf("first", "second")) {
                f.chats.updateMessage(f.chats.getMessage(id, "s")!!.copy(content = "No speaker marker"))
            }
            val task = f.cards.getById("card")!!.pendingSpeakerRenameTasks.single()
            val failingClear = SpeakerTagHistoryService(f.cards, f.chats, updateTask = { cardId, taskId, transform ->
                if (transform(f.cards.getById(cardId)!!.pendingSpeakerRenameTasks.single()) == null)
                    throw IOException("synthetic clear failure")
                f.cards.mutatePendingSpeakerTask(cardId, taskId, transform)
            })
            assertFailsWith<IOException> { failingClear.execute("card", task.id) }
            assertEquals(emptyList(), f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
            f.chats.addMessage(ChatMessage("later", "s", MessageRole.ASSISTANT, "<n=\"B\"/>new current person", createdAt = 3, updatedAt = 3))
            val before = f.sourceBytes()
            val output = f.base.resolve("empty-plan-refused.cbbackup")
            assertFailsWith<DesktopPendingSpeakerRenameExport> {
                if (modern) f.service.exportModern(output, null) else f.service.export(output, null)
            }
            assertFalse(Files.exists(output)); assertEquals(before, f.sourceBytes())
            val work = Files.createDirectory(f.base.resolve("empty-plan-internal"))
            DesktopFullBackupMapping(f.root).export(work, if (modern) 86 else 84, forMerge = true).use { snapshot ->
                val internal = f.base.resolve("empty-plan-counterexample.cbbackup")
                if (modern) DesktopFullBackupWriter.export(snapshot.files, internal, null, snapshot.preferences)
                else DesktopFullBackupArchive(work).export(snapshot.files, internal, f.root.toString(), null, snapshot.preferences)
                val old = legacyReplay(f.base, entries(internal))
                assertEquals("<n=\"C\"/>new current person", old.getMessage("later", "s")!!.content)
            }
            assertEquals(before, f.sourceBytes())
            SpeakerTagHistoryService(f.cards, f.chats).resumePending()
            assertEquals("<n=\"B\"/>new current person", f.chats.getMessage("later", "s")!!.content)
            assertTrue(f.cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
        }
    }
}
