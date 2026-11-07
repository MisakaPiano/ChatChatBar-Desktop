package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.domain.card.PackagedDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopDocumentSuffixBackupTest {
    @Test fun `actual document and draft owners survive modern full and selected export authenticated restore`() = runBlocking<Unit> {
        for (selected in listOf(false, true)) {
            val base = Files.createTempDirectory("document-suffix-backup-")
            val root = Files.createDirectory(base.resolve("source"))
            val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
            val gate = DesktopDataOperationCoordinator()
            val focus = DesktopAudioFocus()
            try {
                val storage = JsonFileStorage(root)
                val resources = DesktopCharacterResourceStore(root)
                val drafts = DesktopCharacterDraftResources(root, resources)
                val originals = linkedMapOf<String, ByteArray>()
                val documents = mutableListOf<DocumentInfo>()
                val draftDocuments = mutableListOf<DocumentInfo>()
                for (suffix in listOf("tmp", "part", "backup")) {
                    val text = "Synthetic document with .$suffix name\n中文正文 $suffix"
                    val durable = resources.materializeDocument(PackagedDocument("notes.$suffix", "txt", text), 1, suffix)
                    val input = Files.writeString(base.resolve("pending.$suffix"), "$text pending")
                    val staged = drafts.stage("edit-session", input, image = false)
                    val typed = drafts.stageText("edit-session", "typed.$suffix", "$text typed")
                    documents += DocumentInfo("doc-$suffix", "notes.$suffix", durable, "txt", 1)
                    draftDocuments += DocumentInfo("pending-$suffix", "pending.$suffix", staged, "txt", 1)
                    draftDocuments += DocumentInfo("typed-$suffix", "typed.$suffix", typed, "txt", 1)
                    for (reference in listOf(durable, staged, typed)) originals[reference] = Files.readAllBytes(root.resolve(reference))
                }
                val card = CharacterCard("c", "Synthetic suffix card", customDocuments = documents, createdAt = 1, updatedAt = 1)
                storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
                val draft = EditorDraft(EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, card.id),
                    EditorDraftType.CHARACTER_CARD, EditorDraftMode.EDIT, card.id, "edit-session",
                    characterPayload = card.copy(customDocuments = draftDocuments),
                    draftAssetPaths = draftDocuments.map { it.filePath }, createdAt = 1, updatedAt = 2)
                EditorDraftRepository(storage).save(draft)
                val unfinished = Files.writeString(Files.createDirectories(root.resolve("images")).resolve(".publication.part"), "unfinished image publication")
                val mapping = DesktopFullBackupMapping(root)
                val proof = mapping.inventory()
                originals.keys.forEach { assertTrue(it in proof, "Missing owned resource: $it") }
                assertFalse("images/.publication.part" in proof)
                val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
                val selection = if (selected) service.catalog().allSelection() else null
                val archive = base.resolve("saved.cbbackup")
                service.exportModern(archive, null, selection = selection)
                assertTrue(service.verifyFile(archive, null).summary.fileCount >= originals.size)
                DesktopFullBackupStaging.prepare(archive, null, Files.createDirectory(base.resolve("incoming"))).use { incoming ->
                    for ((reference, bytes) in originals) assertContentEquals(bytes, Files.readAllBytes(incoming.files.getValue(reference)))
                    assertFalse("images/.publication.part" in incoming.files)
                    val target = Files.createDirectory(base.resolve("target"))
                    DesktopFullBackupMapping(target).restoreMerged(incoming.files, Files.createDirectory(base.resolve("mapped")), BackupPreferences()).use { plan ->
                        DesktopFullBackupGraphValidation.validate(plan.directory)
                        plan.files.forEach { (relative, file) ->
                            val destination = target.resolve(relative)
                            Files.createDirectories(destination.parent)
                            Files.copy(file, destination)
                        }
                        val reopened = JsonFileStorage(target)
                        val restored = assertNotNull(reopened.loadEntity("character_cards", card.id, CharacterCard.serializer()))
                        for ((before, after) in documents.zip(restored.customDocuments)) {
                            assertEquals(before.fileName, after.fileName)
                            assertContentEquals(originals.getValue(before.filePath), Files.readAllBytes(target.resolve(after.filePath)))
                        }
                        val pending = assertNotNull(EditorDraftRepository(reopened).getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
                        assertEquals("edit-session", pending.draftSessionId)
                        val afterDocuments = assertNotNull(pending.characterPayload).customDocuments
                        for ((before, after) in draftDocuments.zip(afterDocuments)) {
                            assertEquals(before.fileName, after.fileName)
                            assertContentEquals(originals.getValue(before.filePath), Files.readAllBytes(Path.of(after.filePath)))
                        }
                        assertEquals(afterDocuments.map { it.filePath }, pending.draftAssetPaths)
                    }
                }
                assertEquals(proof, mapping.inventory())
                for ((relative, bytes) in originals) assertContentEquals(bytes, Files.readAllBytes(root.resolve(relative)))
                assertEquals("unfinished image publication", Files.readString(unfinished))
                assertFalse(service.restartRequired)
            } finally {
                focus.closeAndDrain(); gate.closeAndDrain(); owner.close()
                check(base.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `document suffix allowance still refuses hidden files and hidden draft directories`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("document-suffix-hidden-")
        try {
            val root = Files.createDirectory(base.resolve("root"))
            val mapping = DesktopFullBackupMapping(root)
            val hiddenFile = Files.writeString(Files.createDirectories(root.resolve("documents")).resolve(".private.tmp"), "retained recovery data")
            assertFailsWith<IllegalStateException> { mapping.inventory() }
            assertEquals("retained recovery data", Files.readString(hiddenFile))
            Files.delete(hiddenFile)
            val hiddenDraft = Files.writeString(Files.createDirectories(root.resolve("draft_assets/.recovery")).resolve("pending.part"), "retained draft data")
            assertFailsWith<IllegalStateException> { mapping.inventory() }
            assertEquals("retained draft data", Files.readString(hiddenDraft))
        } finally { check(base.toFile().deleteRecursively()) }
    }
}
