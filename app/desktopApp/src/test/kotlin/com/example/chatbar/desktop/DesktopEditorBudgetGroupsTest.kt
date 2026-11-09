package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.budget.*
import kotlin.test.*

class DesktopEditorBudgetGroupsTest {
    @Test fun `hierarchy partitions every original field once and keeps same-name people separate including colon identities`() {
        val target = PromptEstimateTarget("synthetic", 12000)
        val card = CharacterCard("card", "Card", greeting = "Greeting", basicSetting = "Common", createdAt = 1, updatedAt = 1,
            characters = listOf(CharacterInfo("x", "Same", profile = "One"),
                CharacterInfo("x:0:name", "Same", profile = "Another long profile", desktopEnabled = false)))
        val structured = EditorPromptBudgetEstimator.character(card, target)
        val groups = desktopEditorBudgetGroups(card, structured)
        val people = groups.single { it.id == "people" }.children
        assertEquals(2, people.size)
        assertEquals(10, people[0].fields.size)
        assertEquals(10, people[1].fields.size)
        assertNotEquals(people[0].id, people[1].id)
        assertEquals(structured.sections.map { it.id }.sorted(), groups.flatMap { it.fields }.map { it.id }.sorted())
        assertEquals(structured.estimatedTextTokens, groups.sumOf { it.tokens })
        assertTrue(people[1].sections.any { it.note.contains("已停用") })
        val freeform = card.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "Shared freeform body")
        val report = EditorPromptBudgetEstimator.character(freeform, target)
        val freeGroups = desktopEditorBudgetGroups(freeform, report)
        assertEquals(2, freeGroups.single { it.id == "people" }.children[0].fields.size)
        assertEquals(1, freeGroups.flatMap { it.fields }.count { it.id == "freeform" })
        assertEquals(report.estimatedTextTokens, freeGroups.sumOf { it.tokens })
    }

    @Test fun `worldbook and format groups retain activation notes and the original estimate without rounding subtotals again`() {
        val target = PromptEstimateTarget("synthetic")
        val book = WorldBook("book", "Book", entries = listOf(WorldBookEntry("entry", "Same", listOf("key"), "Body"),
            WorldBookEntry("entry:0:name", "Same", listOf("other"), "Other body", enabled = false)))
        val format = FormatCard("format", "Format", "Content", createdAt = 1)
        for ((entity, report) in listOf(book to EditorPromptBudgetEstimator.worldBook(book, target),
            format to EditorPromptBudgetEstimator.format(format, target))) {
            val groups = desktopEditorBudgetGroups(entity, report)
            assertEquals(report.estimatedTextTokens, groups.sumOf { it.tokens })
            assertEquals(report.sections.map { it.id }.sorted(), groups.flatMap { it.fields }.map { it.id }.sorted())
        }
        val entries = desktopEditorBudgetGroups(book, EditorPromptBudgetEstimator.worldBook(book, target)).single { it.id == "entries" }.children
        assertEquals(listOf(4, 4), entries.map { it.fields.size })
        assertTrue(entries[1].fields.any { it.note.contains("已停用") })
    }
}
