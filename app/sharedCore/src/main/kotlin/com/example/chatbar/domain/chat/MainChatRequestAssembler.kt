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
)

/** Owns the main-chat logical message order through the transport-neutral boundary. */
class MainChatRequestAssembler {
    fun assemble(input: MainChatRequestAssemblyInput): MainChatRequestAssemblyResult {
        input.currentUserMessage?.let { require(it.role == "user") }

        val stablePrefix = buildStablePrefix(input)
        val promptCacheKey = stablePrefix
            .takeIf { input.promptLayers.stablePrefixCacheable && it.isNotEmpty() }
            ?.let(PromptCacheKeyFactory::cacheKey)
        val messages = stablePrefix.toMutableList()

        ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = null,
            archive = input.archive,
            headAndTimeline = null,
            playerName = input.playerName,
            botName = input.botName,
        ).forEach(messages::add)

        if (input.earlierHistoryMessages.isNotEmpty()) {
            messages += ChatApiMessage.text(
                role = "system",
                content = MainChatPromptAuthority.sectionHeading(
                    MainChatPromptAuthority.SECTION_CHAT_HISTORY,
                ),
            )
            messages += input.earlierHistoryMessages
        }

        ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = input.promptLayers.memoryRagSystemPrompt,
            archive = null,
            headAndTimeline = input.headAndTimeline,
            playerName = input.playerName,
            botName = input.botName,
        ).forEach(messages::add)

        if (input.previousTurnMessages.isNotEmpty()) {
            messages += ChatApiMessage.text(
                role = "system",
                content = MainChatPromptAuthority.sectionHeading(
                    MainChatPromptAuthority.SECTION_PREVIOUS_TURN,
                ),
            )
            messages += input.previousTurnMessages
        }

        messages += ChatApiMessage.text(
            role = "system",
            content = MainChatPromptAuthority.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim(),
        )

        input.currentUserMessage?.let { currentUser ->
            messages += currentUser
            val postUserSystemPrompt = joinPromptParts(
                input.promptLayers.tailSystemPrompt,
                input.positionedRequirementsSystemPrompt
                    .takeIf { input.formatPromptPosition.includesEnd }
                    .orEmpty(),
            )
            if (postUserSystemPrompt.isNotBlank()) {
                messages += ChatApiMessage.text("system", postUserSystemPrompt)
            }
            if (input.strongPromptSystemSuffix.isNotBlank()) {
                messages += ChatApiMessage.text("system", input.strongPromptSystemSuffix)
            }
            messages += ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_POST_USER_ACK_ASSISTANT_PROMPT
                    .trimIndent()
                    .trim(),
            )
            messages += ChatApiMessage.text(
                role = "user",
                content = MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT
                    .trimIndent()
                    .trim(),
            )
        }

        ChatRequestMemoryPolicy.requireArchiveIncluded(messages, input.archive)
        return MainChatRequestAssemblyResult(messages, promptCacheKey)
    }

    private fun buildStablePrefix(input: MainChatRequestAssemblyInput): List<ChatApiMessage> = buildList {
        add(
            ChatApiMessage.text(
                role = "system",
                content = joinPromptParts(
                    input.promptLayers.coreSystemPrompt,
                    MainChatPromptAuthority.CCB_CREATOR_IDENTITY_SYSTEM_PROMPT,
                ),
            ),
        )
        add(
            ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_FIRST_ACK_ASSISTANT_PROMPT.trimIndent().trim(),
            ),
        )
        add(
            ChatApiMessage.text(
                role = "user",
                content = MainChatPromptAuthority.CCB_CREATIVE_CONTRACT_USER_PROMPT.trimIndent().trim(),
            ),
        )
        add(
            ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT
                    .trimIndent()
                    .trim(),
            ),
        )
        input.positionedRequirementsSystemPrompt
            .takeIf { input.formatPromptPosition.includesStart }
            ?.takeIf(String::isNotBlank)
            ?.let { add(ChatApiMessage.text("system", it)) }
        input.promptLayers.stableContextSystemPrompt.takeIf(String::isNotBlank)?.let {
            add(ChatApiMessage.text("system", it))
        }
        input.promptLayers.settingReferenceSystemPrompt.takeIf(String::isNotBlank)?.let {
            add(ChatApiMessage.text("system", it))
        }
        input.promptLayers.supplementarySystemPrompt.takeIf(String::isNotBlank)?.let {
            add(ChatApiMessage.text("system", it))
        }
        input.promptLayers.playerSystemPrompt.takeIf(String::isNotBlank)?.let {
            add(ChatApiMessage.text("system", it))
        }
        add(
            ChatApiMessage.text(
                role = "assistant",
                content = MainChatPromptAuthority.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT
                    .trimIndent()
                    .trim(),
            ),
        )
    }
}

fun joinPromptParts(vararg parts: String): String = parts
    .map { it.trimIndent().trim() }
    .filter(String::isNotBlank)
    .joinToString("\n\n")
