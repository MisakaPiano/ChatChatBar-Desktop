package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatPromptPositionTest {
    @Test
    fun startEndAndBothFlagsAndSerializedNamesRemainStable() {
        assertEquals(true to false, FormatPromptPosition.START.includesStart to FormatPromptPosition.START.includesEnd)
        assertEquals(false to true, FormatPromptPosition.END.includesStart to FormatPromptPosition.END.includesEnd)
        assertEquals(true to true, FormatPromptPosition.BOTH.includesStart to FormatPromptPosition.BOTH.includesEnd)
        assertEquals(
            listOf("\"START\"", "\"END\"", "\"BOTH\""),
            FormatPromptPosition.entries.map { Json.encodeToString(FormatPromptPosition.serializer(), it) },
        )
    }
}
