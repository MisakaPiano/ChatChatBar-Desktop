package com.example.chatbar.domain.image

import org.junit.Assert.*
import org.junit.Test

class NovelAiCaretInspectionTest {
    @Test fun wholeTagAtStartMiddleAndEndPreservesSurroundingSyntax() {
        listOf("silver_hair", "{silver_hair}", "[silver_hair]", "(silver_hair)", "1.5::silver_hair::",
            "source#silver_hair", "target#silver_hair", "sky， silver_hair\ncloud").forEach { text ->
            val start = text.indexOf("silver_hair")
            listOf(start, start + 4, start + 11).forEach { caret ->
                val fragment = requireNotNull(NovelAiTagCompletion.inspectedTag(text, caret))
                assertEquals("silver_hair", fragment.query)
                assertEquals(text.replace("silver_hair", "short_hair"), NovelAiTagCompletion.replaceTag(text, fragment, "short_hair").text)
            }
        }
    }
    @Test fun naturalLanguageAndPunctuationDoNotCreateTagReplacements() {
        assertNull(NovelAiTagCompletion.inspectedTag("Text: silver hair, blue sky", 14))
        assertNull(NovelAiTagCompletion.inspectedTag("\"a quiet room\"", 6))
        assertNull(NovelAiTagCompletion.inspectedTag("silver hair", 5, naturalLanguage = true))
        assertNull(NovelAiTagCompletion.inspectedTag("sky,  cloud", 4))
        val text = "source#holding_hands target#looking_back"
        assertEquals("looking_back", NovelAiTagCompletion.inspectedTag(text, text.length)?.query)
    }
}
