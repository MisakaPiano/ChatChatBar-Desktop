package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SillyTavernCardMapperTest {
    @Test
    fun mapsSchemaFiveFreeformPlaceholdersMetadataAndInjectedPrompt() {
        val packageData = mapper().toCharacterCardPackage(
            SillyTavernCard(
                name = "角色",
                description = "{{char}} 与 {{user}}",
                personality = "<BOT> / <USER>",
                scenario = "场景",
                mesExample = "{{char}}: 示例",
                systemPrompt = "系统 {{user}}",
                postHistoryInstructions = "尾部 <BOT>",
                creatorNotes = "注释",
                tags = listOf("tag"),
                creator = "作者",
                characterVersion = "v2",
                extensions = "{\"x\":1}",
            ),
        )

        assertEquals(5, packageData.schemaVersion)
        assertEquals(CharacterEditMode.FREEFORM, packageData.card.editMode)
        assertTrue(packageData.card.freeformCharacterText.contains("【角色名称】\n角色"))
        assertTrue(packageData.card.freeformCharacterText.contains("【人物描述】\n\$botname 与 \$username"))
        assertTrue(packageData.card.freeformCharacterText.contains("【性格特点】\n\$botname / \$username"))
        assertTrue(packageData.card.freeformCharacterText.contains("【背景场景】\n场景"))
        assertTrue(packageData.card.freeformCharacterText.contains("【对话示例】\n\$botname: 示例"))
        assertEquals("系统 \$username", packageData.card.systemPrompt)
        assertEquals("尾部 \$botname", packageData.card.postHistoryInstructions)
        assertEquals("\$botname: 示例", packageData.card.mesExample)
        assertEquals("INJECTED_NEGATIVE", packageData.card.defaultImageNegativePrompt)
        assertEquals("注释", packageData.card.creatorNotes)
        assertEquals(listOf("tag"), packageData.card.tags)
        assertEquals("作者", packageData.card.creator)
        assertEquals("v2", packageData.card.characterVersion)
        assertEquals("{\"x\":1}", packageData.card.extensions)
    }

    @Test
    fun cleansGreetingTagsAndPromotesFirstNonblankAlternate() {
        val packageData = mapper().toCharacterCardPackage(
            SillyTavernCard(
                name = "角色",
                firstMes = "<UpdateVariable>hidden</UpdateVariable><StatusPlaceHolderImpl/><scene><font>  </font></scene>",
                alternateGreetings = listOf(
                    "<content>第一句</content>\n\n\n\n继续",
                    "<开场白>第二句</开场白>",
                ),
            ),
        )

        assertEquals("第一句\n\n继续", packageData.card.greeting)
        assertEquals(listOf("第二句"), packageData.card.alternateGreetings)
    }

    @Test
    fun pngUsesOneResourceForAvatarAndBackgroundAndPreservesBytes() {
        val png = byteArrayOf(1, 2, 3, 4)
        val packageData = mapper().toCharacterCardPackage(SillyTavernCard(name = "PNG", pngBytes = png))

        assertEquals("card-avatar", packageData.card.avatarResourceId)
        assertEquals("card-avatar", packageData.card.chatBackgroundResourceId)
        assertEquals("card.png", packageData.images.getValue("card-avatar").fileName)
        assertContentEquals(
            png,
            Base64.getDecoder().decode(packageData.images.getValue("card-avatar").data),
        )
    }

    @Test
    fun characterBookUsesSharedDecoderAndLeavesEmbeddedFieldNull() {
        val packageData = mapper().toCharacterCardPackage(
            SillyTavernCard(
                name = "角色",
                characterBook = """{"name":"内嵌设定","entries":[{"keys":["城镇"],"content":"内容"}]}""",
            ),
        )

        assertEquals(1, packageData.worldBooks.size)
        assertEquals("内嵌设定", packageData.worldBooks.single().name)
        assertEquals("内容", packageData.worldBooks.single().entries.single().content)
        assertNull(packageData.card.characterBook)
    }

    @Test
    fun malformedCharacterBookIsNonfatalAndReported() {
        val failures = mutableListOf<Exception>()
        val packageData = mapper(failures::add).toCharacterCardPackage(
            SillyTavernCard(name = "角色", characterBook = "not-json"),
        )

        assertTrue(packageData.worldBooks.isEmpty())
        assertNull(packageData.card.characterBook)
        assertEquals(1, failures.size)
    }

    private fun mapper(
        report: (Exception) -> Unit = {},
    ): SillyTavernCardMapper = SillyTavernCardMapper(
        promptPolicy = object : CharacterTransferPromptPolicy {
            override fun defaultCharacterNaiNegativePrompt(): String = "INJECTED_NEGATIVE"

            override fun effectiveCharacterNaiNegativePrompt(value: String): String = value
        },
        errorReporter = SillyTavernMappingErrorReporter(report),
    )
}
