package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.NovelAiPromptAuthority
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import kotlinx.serialization.json.*
import kotlin.test.*

class ActionOptionsDeclaredFenceCompatibilityTest {
    @Test fun `explicit legacy action fences remain trusted while nested code and quotes stay literal`() {
        val items = "1. Check the map\n2. Ask the guide"
        for (opening in listOf("```行动选项", "```action choices", "```")) {
            val title = if (opening == "```") "【行动选项】\n" else ""
            val source = "$opening\n$title$items\n```"
            assertEquals(2, RoleplayActionOptionsPolicy.blocks(source).single().options.size)
            for (example in listOf("~~~~markdown\n$source\n~~~~", "~~~text\n$source",
                source.lines().joinToString("\n") { "> $it" }, source.lines().joinToString("\n") { "    $it" })) {
                assertTrue(RoleplayActionOptionsPolicy.blocks(example).isEmpty())
                assertEquals(example, RoleplayActionOptionsPolicy.withoutExplicitOptions(example))
            }
            assertTrue(RoleplayActionOptionsPolicy.blocks(source.removeSuffix("\n```")).isEmpty())
        }
        assertTrue(RoleplayActionOptionsPolicy.blocks("```text\n$items\n```").isEmpty())
        assertTrue(RoleplayActionOptionsPolicy.blocks("```action choices\n1. one\n3. skipped\n```").isEmpty())
    }

    @Test fun `declared fenced rules share image speech and button source projections`() {
        for ((opening, closing, heading) in listOf(Triple("```cyoa", "```", ""),
            Triple("~~~cyoa", "~~~~", "下一步"), Triple("```", "```", "下一步"))) {
            val rule = ActionOptionsFormat(opening, closing, heading, ActionOptionLabels.LETTERS)
            assertNull(RoleplayActionOptionsPolicy.validationError(rule))
            val menu = listOfNotNull(opening, heading.takeIf(String::isNotBlank), "A) Check the map", "B) Ask the guide", closing).joinToString("\n")
            val text = "Arrived at the square.\n$menu\nThe gate is open."
            val block = RoleplayActionOptionsPolicy.blocks(text, rule).single()
            assertEquals(listOf("Check the map", "Ask the guide"), block.options.map { it.text })
            val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, text).copy(actionOptionsFormat = rule)
            val image = NovelAiPromptAuthority.novelAiImagePromptAssistantScene(message)
            val speechContext = VoiceAnchorPolicy.promptContextText(text, rule)
            val speech = VoiceGenerationPolicy.generationSegments(text, true, false, rule).joinToString { it.spokenText }
            for (projection in listOf(image, speechContext, speech)) {
                assertTrue("Arrived at the square." in projection)
                assertTrue("The gate is open." in projection)
                assertFalse("Ask the guide" in projection)
            }
            assertTrue(RoleplayActionOptionsPolicy.blocks("`````markdown\n$menu\n`````", rule).isEmpty())
        }
        for (rule in listOf(ActionOptionsFormat("```", "```", ""), ActionOptionsFormat("```a", "~~~", ""),
            ActionOptionsFormat("````a", "```", ""), ActionOptionsFormat("```a", "```end", ""),
            ActionOptionsFormat("> start", "> end", "Title"))) {
            assertNotNull(RoleplayActionOptionsPolicy.validationError(rule))
        }
    }

    @Test fun `rule snapshots survive unbounded candidates edit removal and old wire omission`() {
        val firstRule = ActionOptionsFormat("<first>", "</first>", "First")
        var message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, "first").copy(actionOptionsFormat = firstRule)
        val firstId = MessageAlternativeVersionPolicy.activeVersionId(message)
        for (i in 1..7) message = MessageAlternativeVersionPolicy.append(message, "reply-$i", "version-$i",
            actionOptionsFormat = ActionOptionsFormat("<r$i>", "</r$i>", "Rule $i"))
        assertEquals(8, message.alternatives.size)
        val first = MessageAlternativeVersionPolicy.select(message, 0)
        assertEquals(firstId, MessageAlternativeVersionPolicy.activeVersionId(first))
        assertEquals(firstRule, first.displayActionOptionsFormat)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(first, "edited first")
        val removed = assertNotNull(MessageAlternativeVersionPolicy.remove(edited, "version-3"))
        assertEquals(firstRule, removed.displayActionOptionsFormat)
        assertEquals(7, removed.alternativeActionOptionsFormats.size)
        val json = Json { encodeDefaults = true }
        val roundTrip = json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), removed))
        assertEquals(removed, roundTrip)
        val old = json.parseToJsonElement(json.encodeToString(ChatMessage.serializer(), ChatMessage.create("old", MessageRole.ASSISTANT, "legacy"))).jsonObject
        assertFalse("actionOptionsFormat" in old)
        assertFalse("alternativeActionOptionsFormats" in old)
    }
}
