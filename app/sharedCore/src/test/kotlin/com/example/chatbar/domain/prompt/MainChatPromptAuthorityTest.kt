package com.example.chatbar.domain.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainChatPromptAuthorityTest {
    @Test
    fun systemPromptDefaultUsesAuthoritativeMiddle() {
        val prompt = MainChatPromptAuthority.systemPromptTemplate()

        assertTrue(MainChatPromptAuthority.SYSTEM_PROMPT_TEMPLATE.isNotBlank())
        assertTrue(prompt.contains(MainChatPromptAuthority.SYSTEM_PROMPT_REPLACEABLE_CONTENT.trimIndent().trim()))
        assertFalse(prompt.contains("{{original}}"))
    }

    @Test
    fun blankSystemOverrideUsesDefault() {
        assertEquals(
            MainChatPromptAuthority.systemPromptTemplate(),
            MainChatPromptAuthority.systemPromptTemplate("  \n\t"),
        )
    }

    @Test
    fun customSystemOverrideReplacesDefaultMiddle() {
        val prompt = MainChatPromptAuthority.systemPromptTemplate("custom-middle")

        assertTrue(prompt.contains("custom-middle"))
        assertFalse(prompt.contains(MainChatPromptAuthority.SYSTEM_PROMPT_REPLACEABLE_CONTENT.trimIndent().trim()))
    }

    @Test
    fun originalTokenExpandsOnlyDefaultMiddle() {
        val prompt = MainChatPromptAuthority.systemPromptTemplate("before {{original}} after")
        val defaultMiddle = MainChatPromptAuthority.SYSTEM_PROMPT_REPLACEABLE_CONTENT.trimIndent().trim()

        assertTrue(prompt.contains("before $defaultMiddle after"))
        assertFalse(prompt.contains("{{original}}"))
    }

    @Test
    fun postHistoryBuilderReturnsAuthoritativeTemplate() {
        assertEquals(
            MainChatPromptAuthority.POST_HISTORY_INSTRUCTIONS_TEMPLATE,
            MainChatPromptAuthority.postHistoryInstructionsTemplate(),
        )
    }

    @Test
    fun ccbHandshakeAndTailLiteralsAreExposedAndNonBlank() {
        val prompts = listOf(
            MainChatPromptAuthority.CCB_CREATOR_IDENTITY_SYSTEM_PROMPT,
            MainChatPromptAuthority.CCB_FIRST_ACK_ASSISTANT_PROMPT,
            MainChatPromptAuthority.CCB_CREATIVE_CONTRACT_USER_PROMPT,
            MainChatPromptAuthority.CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT,
            MainChatPromptAuthority.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT,
            MainChatPromptAuthority.CCB_CONTINUATION_SYSTEM_PROMPT,
            MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT,
            MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT,
        )

        assertTrue(prompts.all(String::isNotBlank))
    }

    @Test
    fun currentTurnRequirementsWithFormatCardKeepContentBeforeLength() {
        val prompt = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "format-content",
            replyLength = 500,
        )

        assertTrue(prompt.indexOf("format-content") >= 0)
        assertTrue(prompt.indexOf("500字") > prompt.indexOf("format-content"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun currentTurnRequirementsWithoutFormatCardUseLengthOnly() {
        val prompt = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = null,
            replyLength = 300,
        )

        assertTrue(prompt.contains("[300字]"))
        assertFalse(prompt.contains("{{"))
        assertFalse(prompt.contains("null", ignoreCase = true))
    }

    @Test
    fun continuityNoticeIsOptInAndRequiresFormatCard() {
        val included = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "format-content",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true,
        )
        val disabled = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "format-content",
            replyLength = 300,
        )
        val noCard = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = null,
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true,
        )

        assertTrue(included.contains(MainChatPromptAuthority.FORMAT_HISTORY_CONTINUITY_NOTICE))
        assertFalse(disabled.contains(MainChatPromptAuthority.FORMAT_HISTORY_CONTINUITY_NOTICE))
        assertFalse(noCard.contains(MainChatPromptAuthority.FORMAT_HISTORY_CONTINUITY_NOTICE))
    }

    @Test
    fun roleplaySpeakerFormatNormalizesAndDeduplicatesNames() {
        val prompt = MainChatPromptAuthority.roleplaySpeakerFormatSystemPrompt(
            listOf(" Alice ", "alice", "Bob", ""),
        )

        assertTrue(prompt.contains("角色姓名：Alice、Bob"))
        assertFalse(prompt.contains("Alice、alice"))
    }

    @Test
    fun replyLengthLanguageAndTailBuildersPreserveInputs() {
        val tail = MainChatPromptAuthority.replyTailSystemPrompt(
            replyLength = 420,
            roleplaySpeakerFormatEnabled = true,
            characterNames = listOf("Alice"),
        )

        assertTrue(MainChatPromptAuthority.replyLengthConstraint(420).contains("420字"))
        assertTrue(MainChatPromptAuthority.replyLengthTailSystemPrompt(420).contains("420字"))
        assertTrue(MainChatPromptAuthority.replyLanguageConstraint("中文").contains("中文"))
        assertTrue(tail.contains("角色姓名：Alice"))
        assertTrue(tail.contains("420字"))
    }

    @Test
    fun blankContinueBuilderTrimsAuthoritativeLiteral() {
        assertEquals(
            MainChatPromptAuthority.CONTINUE_GENERATION_USER_PROMPT.trimIndent().trim(),
            MainChatPromptAuthority.continueGenerationUserPrompt(),
        )
    }

    @Test
    fun randomNumberSuffixSupportsSingleAndMultipleValues() {
        assertTrue(MainChatPromptAuthority.randomNumberUserToolSuffix(listOf(7)).contains("下一轮使用随机数：7"))
        assertTrue(
            MainChatPromptAuthority.randomNumberUserToolSuffix(listOf(7, 9))
                .contains("下一轮按顺序使用随机数：7；9；"),
        )
    }

    @Test
    fun userToolSuffixBlockPreservesFormatting() {
        assertEquals(
            "message\n{\nfirst\nsecond\n}",
            MainChatPromptAuthority.appendUserToolSuffixBlock("message", listOf("first", "second")),
        )
        assertEquals("message", MainChatPromptAuthority.appendUserToolSuffixBlock("message", emptyList()))
    }

    @Test
    fun sectionHeadingsAndConstantsAreExposed() {
        val sections = listOf(
            MainChatPromptAuthority.SECTION_CHARACTER,
            MainChatPromptAuthority.SECTION_WORLD_BOOK,
            MainChatPromptAuthority.SECTION_REFERENCE,
            MainChatPromptAuthority.SECTION_REPLY,
            MainChatPromptAuthority.SECTION_LONG_TERM_MEMORY,
            MainChatPromptAuthority.SECTION_SUPPLEMENTARY,
            MainChatPromptAuthority.SECTION_PLAYER,
            MainChatPromptAuthority.SECTION_CORE,
            MainChatPromptAuthority.SECTION_POST_HISTORY,
            MainChatPromptAuthority.SECTION_CHAT_HISTORY,
            MainChatPromptAuthority.SECTION_PREVIOUS_TURN,
        )

        assertTrue(sections.all(String::isNotBlank))
        assertEquals("【${MainChatPromptAuthority.SECTION_CHARACTER}】", MainChatPromptAuthority.sectionHeading(MainChatPromptAuthority.SECTION_CHARACTER))
    }

    @Test
    fun ragChatMemoryUsageNoteIsExposed() {
        assertTrue(MainChatPromptAuthority.RAG_CHAT_MEMORY_USAGE_NOTE.isNotBlank())
    }
}
