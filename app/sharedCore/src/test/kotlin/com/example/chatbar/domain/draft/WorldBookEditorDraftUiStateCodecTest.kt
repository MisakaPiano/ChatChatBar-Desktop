package com.example.chatbar.domain.draft

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldBookEditorDraftUiStateCodecTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `envelope preserves modal and raw valid or incomplete numeric text`() {
        val state = WorldBookEditorDraftUiState(
            entryModalState = WorldBookEntryModalState(name = "Unsaved", probability = "37"),
            scanDepthInput = "-", tokenBudgetInput = "120-")
        assertEquals(state, WorldBookEditorDraftUiStateCodec.decode(json,
            WorldBookEditorDraftUiStateCodec.encode(json, state)))
    }

    @Test fun `legacy raw modal remains readable and missing state remains absent`() {
        val modal = WorldBookEntryModalState(name = "Legacy", keys = "key")
        val raw = json.encodeToString(modal)
        val decoded = WorldBookEditorDraftUiStateCodec.decode(json, raw)
        assertEquals(modal, decoded?.entryModalState)
        assertNull(decoded?.scanDepthInput)
        assertNull(decoded?.tokenBudgetInput)
        assertNull(WorldBookEditorDraftUiStateCodec.decode(json, null))
    }

    @Test fun `unknown envelope version fails explicitly`() {
        assertFailsWith<IllegalArgumentException> {
            WorldBookEditorDraftUiStateCodec.decode(json,
                """{"worldBookEditorDraftUiVersion":2,"scanDepthInput":"3"}""")
        }
    }

    @Test fun `production envelope includes marker and markerless unrelated data is rejected`() {
        val raw = WorldBookEditorDraftUiStateCodec.encode(json,
            WorldBookEditorDraftUiState(scanDepthInput = "-", tokenBudgetInput = "120-"))
        kotlin.test.assertTrue(raw.contains("\"worldBookEditorDraftUiVersion\":1"))
        listOf("""{"scanDepthInput":"-"}""", """{"entryModalState":null}""",
            """{"unrelated":"value"}""", "{}").forEach { malformed ->
            assertFailsWith<IllegalArgumentException> { WorldBookEditorDraftUiStateCodec.decode(json, malformed) }
        }
    }
}
