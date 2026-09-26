package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.MainChatRequestAssemblyInput
import com.example.chatbar.domain.chat.PromptCachePromptLayers
import java.nio.file.Files
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopMainChatRequestAssemblerIntegrationTest {
    @Test
    fun containerExposesSharedPromptAndLogicalRequestAuthoritiesWithoutWritingRoot() = runTest {
        val parent = Files.createTempDirectory("desktop-main-chat-assembler-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        try {
            assertEquals(emptyList(), Files.list(root).use { it.toList() })
            val layers = PromptCachePromptLayers(
                coreSystemPrompt = "desktop-core",
                stableContextSystemPrompt = "desktop-character",
                dynamicSystemPrompt = "",
                tailSystemPrompt = "desktop-tail",
                stablePrefixCacheable = true,
            )

            val result = container.mainChatRequestAssembler.assemble(
                MainChatRequestAssemblyInput(
                    promptLayers = layers,
                    positionedRequirementsSystemPrompt = "desktop-requirements",
                    formatPromptPosition = FormatPromptPosition.END,
                    currentUserMessage = ChatApiMessage.text("user", "desktop-current"),
                    botName = "Desktop Bot",
                ),
            )

            assertNotNull(container.promptAssembler)
            assertNotNull(result.promptCacheKey)
            assertEquals(1, result.messages.count { it.content.jsonPrimitive.content == "desktop-current" })
            assertEquals("user", result.messages.last().role)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
