package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.EditorDraftRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeEditorDraftTest {
    private fun card(id: String, name: String) = CharacterCard(id, name, greeting = "Synthetic $id", createdAt = 1, updatedAt = 1)
    private fun encoded(value: CharacterCard) = "entities/character_cards/${value.id}.json" to backupJson.encodeToJsonElement(CharacterCard.serializer(), value).jsonObject
    private fun draft(target: String?, text: String) = EditorDraft(EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, target),
        EditorDraftType.CHARACTER_CARD, if (target == null) EditorDraftMode.CREATE else EditorDraftMode.EDIT, target,
        "synthetic-edit-session", baseHash = "original-base-proof", characterPayload = card(target ?: "new-payload", text),
        draftAssetPaths = listOf("chatbar-backup-file:draft_assets/pending.png"),
        pendingDeletedAssets = listOf("chatbar-backup-file:draft_assets/pending.png"), openModalState = "original-modal", createdAt = 1, updatedAt = 2)
    private fun encoded(value: EditorDraft) = "entities/edit_drafts/${value.id}.json" to backupJson.encodeToJsonElement(EditorDraft.serializer(), value).jsonObject
    private suspend fun inspect(plan: DesktopMergeDestinationPlan, action: suspend (EditorDraftRepository) -> Unit) {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-editor-draft").toAbsolutePath()), "case-")
        try {
            for ((relative, value) in plan.entities) {
                val path = root.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, value.toString())
            }
            action(EditorDraftRepository(JsonFileStorage(root)))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `actual draft lookup follows adopted and copied targets without hiding competing pending edits`() = runBlocking<Unit> {
        val pcCard = card("a", "Shared"); val phoneCard = card("b", "SHARED")
        val incomingDraft = draft("b", "Pending phone edits b must remain text")
        val pc = mapOf(encoded(pcCard)); val phone = mapOf(encoded(phoneCard), encoded(incomingDraft))
        val originalPc = pc.toString(); val originalPhone = phone.toString()
        val media = mapOf("draft_assets/pending.png" to "a".repeat(64))
        for (choice in listOf(DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.SKIP, DesktopMergePreview.Choice.BOTH)) {
            val preview = DesktopMergePreview.create(pc, phone, emptyMap(), media)
            val selected = preview.select(mapOf(preview.conflicts.single().key to choice))
            DesktopMergeDestinationPlan.create(selected, emptyMap(), media).use { plan -> inspect(plan) { repository ->
                val target = if (choice == DesktopMergePreview.Choice.BOTH) "b" else "a"
                val loaded = assertNotNull(repository.getForTarget(EditorDraftType.CHARACTER_CARD, target))
                assertEquals(EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, target), loaded.id)
                assertEquals(target, loaded.targetId); assertEquals(target, loaded.characterPayload!!.id)
                assertEquals(incomingDraft.characterPayload!!.name, loaded.characterPayload!!.name)
                assertEquals(incomingDraft.baseHash, loaded.baseHash); assertEquals(incomingDraft.draftSessionId, loaded.draftSessionId)
                assertEquals(incomingDraft.openModalState, loaded.openModalState)
                assertEquals(loaded.draftAssetPaths, loaded.pendingDeletedAssets)
                assertTrue(loaded.draftAssetPaths.single().startsWith("chatbar-backup-file:draft_assets/import-"))
            } }
        }
        val pcDraft = draft("a", "Pending computer edits")
        val competingPc = pc + encoded(pcDraft)
        val preview = DesktopMergePreview.create(competingPc, phone, media, media)
        val ownerKey = preview.conflicts.single().key
        var choices = preview.choose(emptyMap(), ownerKey, DesktopMergePreview.Choice.TAKE_PHONE)
        val dependent = preview.effectiveConflicts(choices).single { it.category == "edit_drafts" }
        assertFalse(DesktopMergePreview.Choice.BOTH in dependent.choices)
        for (draftChoice in listOf(DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.SKIP, DesktopMergePreview.Choice.TAKE_PHONE)) {
            val chosen = preview.choose(choices, dependent.key, draftChoice)
            DesktopMergeDestinationPlan.create(preview.select(chosen), media, media).use { plan -> inspect(plan) { repository ->
                val loaded = assertNotNull(repository.getForTarget(EditorDraftType.CHARACTER_CARD, "a"))
                assertEquals(if (draftChoice == DesktopMergePreview.Choice.TAKE_PHONE) incomingDraft.characterPayload!!.name else pcDraft.characterPayload!!.name,
                    loaded.characterPayload!!.name)
            } }
        }
        choices = preview.choose(preview.choose(choices, dependent.key, DesktopMergePreview.Choice.TAKE_PHONE), ownerKey, DesktopMergePreview.Choice.BOTH)
        assertEquals(setOf(ownerKey), choices.keys)
        DesktopMergeDestinationPlan.create(preview.select(choices), media, media).use { plan -> inspect(plan) { repository ->
            assertEquals(pcDraft.characterPayload!!.name, repository.getForTarget(EditorDraftType.CHARACTER_CARD, "a")!!.characterPayload!!.name)
            assertEquals(incomingDraft.characterPayload!!.name, repository.getForTarget(EditorDraftType.CHARACTER_CARD, "b")!!.characterPayload!!.name)
        } }
        // Identical draft bytes still need a second lookup when their exact-ID owner is copied.
        val sameDraft = draft("a", "Identical pending edit")
        val samePc = mapOf(encoded(pcCard), encoded(sameDraft))
        val samePhone = mapOf(encoded(pcCard.copy(greeting = "Different imported greeting")), encoded(sameDraft))
        val samePreview = DesktopMergePreview.create(samePc, samePhone, media, media)
        DesktopMergeDestinationPlan.create(samePreview.select(mapOf(samePreview.conflicts.single().key to DesktopMergePreview.Choice.BOTH)), media, media).use { plan -> inspect(plan) { repository ->
            val copied = plan.identity.value("character_cards", "a")
            assertNotEquals("a", copied)
            assertNotNull(repository.getForTarget(EditorDraftType.CHARACTER_CARD, "a"))
            assertNotNull(repository.getForTarget(EditorDraftType.CHARACTER_CARD, copied))
        } }
        val fresh = draft(null, "Brand new pending edit")
        val freshPreview = DesktopMergePreview.create(emptyMap(), mapOf(encoded(fresh)), emptyMap(), media)
        DesktopMergeDestinationPlan.create(freshPreview.select(emptyMap()), emptyMap(), media).use { plan -> inspect(plan) { repository ->
            assertEquals(fresh.characterPayload!!.name, repository.getLatestNew(EditorDraftType.CHARACTER_CARD)!!.characterPayload!!.name)
            assertNotNull(repository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
        } }
        assertEquals(originalPc, pc.toString()); assertEquals(originalPhone, phone.toString())
    }
}
