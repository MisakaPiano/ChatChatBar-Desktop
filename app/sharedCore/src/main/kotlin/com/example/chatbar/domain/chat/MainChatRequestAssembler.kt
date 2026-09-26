package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.prompt.MainChatPromptAuthority

data class MainChatRequestAssemblyInput(
    val promptLayers: PromptCachePromptLayers,
    val positionedRequirementsSystemPrompt: String,
    val formatPromptPosition: FormatPromptPosition,
    val archive: String? = null,
    val headAndTimeline: String? = null,
    val earlierHistoryMessages: List<ChatApiMessage> = emptyList(),
    val previousTurnMessages: List<ChatApiMessage> = emptyList(),
    val currentUserMessage: ChatApiMessage? = null,
    val strongPromptSystemSuffix: String = "",
    val playerName: String? = null,
    val botName: String,
)

data class MainChatRequestAssemblyResult(
    val messages: List<ChatApiMessage>,
    val promptCacheKey: String?,
    val messageTrace: List<MainChatLogicalMessageTrace>,
    val stablePrefixMessages: List<ChatApiMessage>,
    val stablePrefixCacheable: Boolean,
)

enum class MainChatLogicalMessageSource {
    CORE,
    CCB_FIRST_ACK,
    CREATIVE_CONTRACT,
    CONTRACT_CONFIRMATION,
    START_REQUIREMENTS,
    CHARACTER,
    SETTING_REFERENCE,
    SUPPLEMENTARY,
    PLAYER,
    CONTEXT_APPROVAL,
    ARCHIVE,
    CHAT_HISTORY_HEADING,
    EARLIER_HISTORY,
    MEMORY_RAG,
    HEAD_TIMELINE,
    PREVIOUS_TURN_HEADING,
    PREVIOUS_TURN,
    CONTINUATION,
    CURRENT_USER,
    POST_HISTORY_END_REQUIREMENTS,
    STRONG_PROMPT_SUFFIX,
    POST_USER_ACK,
    FINAL_IDENTITY_REMINDER,
}

data class MainChatLogicalMessageTrace(
    val message: ChatApiMessage,
    val source: MainChatLogicalMessageSource,
    val inStableCachePrefix: Boolean,
)

/** Owns the main-chat logical message order through the transport-neutral boundary. */
class MainChatRequestAssembler {
    fun assemble(input: MainChatRequestAssemblyInput): MainChatRequestAssemblyResult {
        input.currentUserMessage?.let { require(it.role == "user") }

        val messageTrace = buildMessageTrace(input)
        val messages = messageTrace.map(MainChatLogicalMessageTrace::message)
        val stablePrefixMessages = messageTrace
            .takeWhile(MainChatLogicalMessageTrace::inStableCachePrefix)
            .map(MainChatLogicalMessageTrace::message)
        val promptCacheKey = stablePrefixMessages
            .takeIf { input.promptLayers.stablePrefixCacheable && it.isNotEmpty() }
            ?.let(PromptCacheKeyFactory::cacheKey)

        ChatRequestMemoryPolicy.requireArchiveIncluded(messages, input.archive)
        return MainChatRequestAssemblyResult(
            messages = messages,
            promptCacheKey = promptCacheKey,
            messageTrace = messageTrace,
            stablePrefixMessages = stablePrefixMessages,
            stablePrefixCacheable = input.promptLayers.stablePrefixCacheable,
        )
    }

    private fun buildMessageTrace(input: MainChatRequestAssemblyInput): List<MainChatLogicalMessageTrace> =
        buildList {
        addTrace(
            source = MainChatLogicalMessageSource.CORE,
            stable = true,
            message = ChatApiMessage.text(
                role = "system",
                content = joinPromptParts(
                    input.promptLayers.coreSystemPrompt,
                    MainChatPromptAuthority.CCB_CREATOR_IDENTITY_SYSTEM_PROMPT,
                ),
            ),
        )
        addTrace(
            source = MainChatLogicalMessageSource.CCB_FIRST_ACK,
            stable = true,
            message = ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_FIRST_ACK_ASSISTANT_PROMPT.trimIndent().trim(),
            ),
        )
        addTrace(
            source = MainChatLogicalMessageSource.CREATIVE_CONTRACT,
            stable = true,
            message = ChatApiMessage.text(
                role = "user",
                content = MainChatPromptAuthority.CCB_CREATIVE_CONTRACT_USER_PROMPT.trimIndent().trim(),
            ),
        )
        addTrace(
            source = MainChatLogicalMessageSource.CONTRACT_CONFIRMATION,
            stable = true,
            message = ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT
                    .trimIndent()
                    .trim(),
            ),
        )
        input.positionedRequirementsSystemPrompt
            .takeIf { input.formatPromptPosition.includesStart }
            ?.takeIf(String::isNotBlank)
            ?.let {
                addTrace(
                    MainChatLogicalMessageSource.START_REQUIREMENTS,
                    ChatApiMessage.text("system", it),
                    stable = true,
                )
            }
        input.promptLayers.stableContextSystemPrompt.takeIf(String::isNotBlank)?.let {
            addTrace(
                MainChatLogicalMessageSource.CHARACTER,
                ChatApiMessage.text("system", it),
                stable = true,
            )
        }
        input.promptLayers.settingReferenceSystemPrompt.takeIf(String::isNotBlank)?.let {
            addTrace(
                MainChatLogicalMessageSource.SETTING_REFERENCE,
                ChatApiMessage.text("system", it),
                stable = true,
            )
        }
        input.promptLayers.supplementarySystemPrompt.takeIf(String::isNotBlank)?.let {
            addTrace(
                MainChatLogicalMessageSource.SUPPLEMENTARY,
                ChatApiMessage.text("system", it),
                stable = true,
            )
        }
        input.promptLayers.playerSystemPrompt.takeIf(String::isNotBlank)?.let {
            addTrace(
                MainChatLogicalMessageSource.PLAYER,
                ChatApiMessage.text("system", it),
                stable = true,
            )
        }
        addTrace(
            source = MainChatLogicalMessageSource.CONTEXT_APPROVAL,
            stable = true,
            message = ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT
                    .trimIndent()
                    .trim(),
            ),
        )

        ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = null,
            archive = input.archive,
            headAndTimeline = null,
            playerName = input.playerName,
            botName = input.botName,
        ).forEach { addTrace(MainChatLogicalMessageSource.ARCHIVE, it) }

        if (input.earlierHistoryMessages.isNotEmpty()) {
            addTrace(
                MainChatLogicalMessageSource.CHAT_HISTORY_HEADING,
                ChatApiMessage.text(
                    role = "system",
                    content = MainChatPromptAuthority.sectionHeading(
                        MainChatPromptAuthority.SECTION_CHAT_HISTORY,
                    ),
                ),
            )
            input.earlierHistoryMessages.forEach {
                addTrace(MainChatLogicalMessageSource.EARLIER_HISTORY, it)
            }
        }

        ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = input.promptLayers.memoryRagSystemPrompt,
            archive = null,
            headAndTimeline = null,
            playerName = input.playerName,
            botName = input.botName,
        ).forEach { addTrace(MainChatLogicalMessageSource.MEMORY_RAG, it) }
        ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = null,
            archive = null,
            headAndTimeline = input.headAndTimeline,
            playerName = input.playerName,
            botName = input.botName,
        ).forEach { addTrace(MainChatLogicalMessageSource.HEAD_TIMELINE, it) }

        if (input.previousTurnMessages.isNotEmpty()) {
            addTrace(
                MainChatLogicalMessageSource.PREVIOUS_TURN_HEADING,
                ChatApiMessage.text(
                    role = "system",
                    content = MainChatPromptAuthority.sectionHeading(
                        MainChatPromptAuthority.SECTION_PREVIOUS_TURN,
                    ),
                ),
            )
            input.previousTurnMessages.forEach {
                addTrace(MainChatLogicalMessageSource.PREVIOUS_TURN, it)
            }
        }

        addTrace(
            MainChatLogicalMessageSource.CONTINUATION,
            ChatApiMessage.text(
                role = "system",
                content = MainChatPromptAuthority.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim(),
            ),
        )

        input.currentUserMessage?.let { currentUser ->
            addTrace(MainChatLogicalMessageSource.CURRENT_USER, currentUser)
            val postUserSystemPrompt = joinPromptParts(
                input.promptLayers.tailSystemPrompt,
                input.positionedRequirementsSystemPrompt
                    .takeIf { input.formatPromptPosition.includesEnd }
                    .orEmpty(),
            )
            if (postUserSystemPrompt.isNotBlank()) {
                addTrace(
                    MainChatLogicalMessageSource.POST_HISTORY_END_REQUIREMENTS,
                    ChatApiMessage.text("system", postUserSystemPrompt),
                )
            }
            if (input.strongPromptSystemSuffix.isNotBlank()) {
                addTrace(
                    MainChatLogicalMessageSource.STRONG_PROMPT_SUFFIX,
                    ChatApiMessage.text("system", input.strongPromptSystemSuffix),
                )
            }
            addTrace(
                MainChatLogicalMessageSource.POST_USER_ACK,
                ChatApiMessage.text(
                    role = "assistant",
                    content = MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT
                        .trimIndent()
                        .trim(),
                ),
            )
            addTrace(
                MainChatLogicalMessageSource.FINAL_IDENTITY_REMINDER,
                ChatApiMessage.text(
                    role = "user",
                    content = MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT
                        .trimIndent()
                        .trim(),
                ),
            )
        }
    }

    private fun MutableList<MainChatLogicalMessageTrace>.addTrace(
        source: MainChatLogicalMessageSource,
        message: ChatApiMessage,
        stable: Boolean = false,
    ) {
        add(MainChatLogicalMessageTrace(message, source, stable))
    }
}

fun joinPromptParts(vararg parts: String): String = parts
    .map { it.trimIndent().trim() }
    .filter(String::isNotBlank)
    .joinToString("\n\n")
