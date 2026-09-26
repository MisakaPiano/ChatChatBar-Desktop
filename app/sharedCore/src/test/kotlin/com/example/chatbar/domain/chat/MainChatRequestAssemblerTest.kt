package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainChatRequestAssemblerTest {
    private val assembler = MainChatRequestAssembler()

    @Test
    fun fullRequestKeepsAuthoritativeLogicalOrder() {
        val result = assembler.assemble(
            input(
                position = FormatPromptPosition.BOTH,
                archive = "【${MainChatPromptAuthority.SECTION_MEMORY_ARCHIVE}】\narchive",
                head = "head",
                earlier = listOf(ChatApiMessage.text("assistant", "earlier")),
                previous = listOf(ChatApiMessage.text("assistant", "previous")),
                current = ChatApiMessage.text("user", "current"),
                strong = "strong",
            ),
        )
        val messages = result.messages
        val contents = messages.map { it.content.jsonPrimitive.content }

        assertEquals(23, messages.size)
        assertTrue(contents[0].startsWith("core"))
        assertEquals(MainChatPromptAuthority.CCB_FIRST_ACK_ASSISTANT_PROMPT.trimIndent().trim(), contents[1])
        assertEquals(MainChatPromptAuthority.CCB_CREATIVE_CONTRACT_USER_PROMPT.trimIndent().trim(), contents[2])
        assertEquals(MainChatPromptAuthority.CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT.trimIndent().trim(), contents[3])
        assertEquals("requirements", contents[4])
        assertEquals(listOf("stable", "reference", "supplementary", "player"), contents.subList(5, 9))
        assertEquals(MainChatPromptAuthority.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT.trimIndent().trim(), contents[9])
        assertEquals("【${MainChatPromptAuthority.SECTION_MEMORY_ARCHIVE}】\narchive", contents[10])
        assertTrue(contents[11].contains(MainChatPromptAuthority.SECTION_CHAT_HISTORY))
        assertEquals("earlier", contents[12])
        assertEquals("memory", contents[13])
        assertEquals("head", contents[14])
        assertTrue(contents[15].contains(MainChatPromptAuthority.SECTION_PREVIOUS_TURN))
        assertEquals("previous", contents[16])
        assertEquals(MainChatPromptAuthority.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim(), contents[17])
        assertEquals("current", contents[18])
        assertEquals("tail\n\nrequirements", contents[19])
        assertEquals("strong", contents[20])
        assertEquals(MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim(), contents[21])
        assertEquals(MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT.trimIndent().trim(), contents[22])
        assertEquals("user", messages.last().role)
        assertEquals(1, contents.count { it == "current" })
    }

    @Test
    fun startEndAndBothPlaceTheSameRequirementsAtTheirDeclaredBoundaries() {
        FormatPromptPosition.entries.forEach { position ->
            val contents = assembler.assemble(input(position = position)).messages.map { it.content.jsonPrimitive.content }
            val requirementIndices = contents.indices.filter { "requirements" in contents[it] }

            assertEquals((if (position.includesStart) 1 else 0) + (if (position.includesEnd) 1 else 0), requirementIndices.size)
            if (position.includesStart) assertTrue(requirementIndices.first() < contents.indexOf("stable"))
            if (position.includesEnd) {
                assertTrue(requirementIndices.last() > contents.indexOf("current"))
                assertTrue(requirementIndices.last() < contents.indexOf(MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim()))
            }
        }
    }

    @Test
    fun stablePrefixCacheKeyExcludesHistoryCurrentAndTail() {
        val first = assembler.assemble(input(earlier = listOf(ChatApiMessage.text("user", "history-a"))))
        val changedDynamic = assembler.assemble(
            input(
                earlier = listOf(ChatApiMessage.text("user", "history-b")),
                previous = listOf(ChatApiMessage.text("assistant", "previous-b")),
                current = ChatApiMessage.text("user", "current-b"),
                strong = "strong-b",
                layers = layers(tail = "tail-b"),
            ),
        )
        val changedStable = assembler.assemble(input(layers = layers(reference = "reference-b")))

        assertEquals(first.promptCacheKey, changedDynamic.promptCacheKey)
        assertNotEquals(first.promptCacheKey, changedStable.promptCacheKey)
        assertTrue(first.promptCacheKey.orEmpty().startsWith("chatbar-"))
    }

    @Test
    fun nonCacheableOrEmptyStablePrefixDoesNotPublishKey() {
        val result = assembler.assemble(input(layers = layers(cacheable = false)))

        assertNull(result.promptCacheKey)
    }

    @Test
    fun postHistoryAndEndRequirementsPrecedeStrongAndFinalTail() {
        val messages = assembler.assemble(
            input(position = FormatPromptPosition.END, strong = "strong"),
        ).messages
        val contents = messages.map { it.content.jsonPrimitive.content }
        val current = contents.indexOf("current")
        val post = contents.indexOf("tail\n\nrequirements")
        val strong = contents.indexOf("strong")

        assertTrue(current < post)
        assertTrue(post < strong)
        assertTrue(strong < messages.lastIndex - 1)
        assertEquals("assistant", messages[messages.lastIndex - 1].role)
        assertEquals("user", messages.last().role)
    }

    @Test
    fun absentCurrentUserKeepsContinuationWithoutInventingFinalTail() {
        val messages = assembler.assemble(input(current = null)).messages
        val contents = messages.map { it.content.jsonPrimitive.content }

        assertEquals(MainChatPromptAuthority.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim(), contents.last())
        assertFalse(contents.contains(MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim()))
        assertFalse(contents.contains(MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT.trimIndent().trim()))
    }

    private fun input(
        position: FormatPromptPosition = FormatPromptPosition.END,
        archive: String? = null,
        head: String? = null,
        earlier: List<ChatApiMessage> = emptyList(),
        previous: List<ChatApiMessage> = emptyList(),
        current: ChatApiMessage? = ChatApiMessage.text("user", "current"),
        strong: String = "",
        layers: PromptCachePromptLayers = layers(),
    ) = MainChatRequestAssemblyInput(
        promptLayers = layers,
        positionedRequirementsSystemPrompt = "requirements",
        formatPromptPosition = position,
        archive = archive,
        headAndTimeline = head,
        earlierHistoryMessages = earlier,
        previousTurnMessages = previous,
        currentUserMessage = current,
        strongPromptSystemSuffix = strong,
        playerName = "player-name",
        botName = "bot-name",
    )

    private fun layers(
        tail: String = "tail",
        reference: String = "reference",
        cacheable: Boolean = true,
    ) = PromptCachePromptLayers(
        coreSystemPrompt = "core",
        stableContextSystemPrompt = "stable",
        dynamicSystemPrompt = "dynamic",
        tailSystemPrompt = tail,
        stablePrefixCacheable = cacheable,
        settingReferenceSystemPrompt = reference,
        playerSystemPrompt = "player",
        memoryRagSystemPrompt = "memory",
        supplementarySystemPrompt = "supplementary",
        replyConstraintsSystemPrompt = "reply",
    )
}
