package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.repository.EditorDraftRepository
import kotlinx.serialization.json.*

/** The real editor looks up drafts by a key derived from their final target. */
internal object DesktopMergeEditorDraftBindings {
    fun category(type: String?): String = when (type) {
        "CHARACTER_CARD" -> "character_cards"
        "FORMAT_CARD" -> "format_cards"
        "WORLD_BOOK" -> "world_books"
        else -> error("MERGE_DRAFT_TYPE")
    }
    fun id(type: String?, target: String?): String = EditorDraftRepository.draftId(
        EditorDraftType.valueOf(requireNotNull(type) { "MERGE_DRAFT_TYPE" }), target)

    fun bind(selection: DesktopMergePreview.Selection, identity: DesktopMergeIdentity, active: () -> Unit) {
        for (unit in selection.phone) {
            active()
            if (unit.category != "edit_drafts") continue
            val target = unit.draftTarget?.let { identity.value(category(unit.draftType), it) }
            identity.bind("edit_drafts", requireNotNull(unit.primaryId) { "MERGE_DRAFT_ID" }, id(unit.draftType, target))
        }
    }
}
