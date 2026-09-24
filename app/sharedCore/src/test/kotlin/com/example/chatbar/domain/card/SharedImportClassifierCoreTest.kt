package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedImportClassifierCoreTest {
    private val json = Json { encodeDefaults = true }
    private val classifier = SharedImportClassifierCore(
        sillyTavernMapper = SillyTavernCardMapper(TestPromptPolicy),
        modelTemplateDecoder = SharedImportModelTemplateDecoder<String> { raw ->
            require(raw.contains("\"displayName\""))
            "decoded-model"
        },
    )

    @Test
    fun classifiesSharedChatBarPackagesAndTypedModelExtension() {
        val character = json.encodeToString(
            CharacterCardPackage.serializer(),
            CharacterCardPackage(card = PackagedCharacterCard(name = "林雾")),
        )
        val format = json.encodeToString(
            FormatCardPackage.serializer(),
            FormatCardPackage(name = "格式", content = "内容"),
        )
        val worldBook = """{"schemaVersion":1,"book":{"id":"b1","name":"设定集","entries":[]}}"""
        val model = """{"displayName":"模型","modelName":"model"}"""

        assertEquals(SharedImportKind.CHARACTER, inspectText(character).kind)
        assertEquals(SharedImportKind.FORMAT, inspectText(format).kind)
        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(worldBook).kind)
        val modelResult = inspectText(model)
        assertIs<SharedImportCoreInspection.ModelTemplate<String>>(modelResult)
        assertEquals("decoded-model", modelResult.packageData)
    }

    @Test
    fun classifiesSillyTavernV1AndV2Characters() {
        val v1 = """{"name":"V1","description":"角色描述","first_mes":"你好"}"""
        val v2 = """{"spec":"chara_card_v2","data":{"name":"V2","description":"角色描述","first_mes":"你好"}}"""

        assertEquals(SharedImportKind.CHARACTER, inspectText(v1).kind)
        assertEquals(SharedImportKind.CHARACTER, inspectText(v2).kind)
    }

    @Test
    fun classifiesSillyTavernWorldInfoObjectAndArrayEntries() {
        val objectEntries = """{"name":"世界","entries":{"0":{"key":["城镇"],"content":"内容"}}}"""
        val arrayEntries = """{"entries":[{"keys":["城镇"],"content":"内容"}]}"""

        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(objectEntries).kind)
        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(arrayEntries, "array-world.json").kind)
    }

    @Test
    fun classifiesChatBarAndSillyTavernPngBeforePlainImage() {
        val basePng = onePixelPng()
        val characterJson = json.encodeToString(
            CharacterCardPackage.serializer(),
            CharacterCardPackage(card = PackagedCharacterCard(name = "ChatBar")),
        )
        val chatBarPng = PngTextChunks.insertTextChunk(
            basePng,
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            Base64.getEncoder().encodeToString(characterJson.toByteArray()),
        )
        val sillyTavernJson = """{"spec":"chara_card_v2","data":{"name":"ST","description":"描述"}}"""
        val sillyTavernPng = PngTextChunks.insertTextChunk(
            basePng,
            "Chara",
            Base64.getEncoder().encodeToString(sillyTavernJson.toByteArray()),
        )
        val imageInfo = SharedImportImageInfo("image/png", 1, 1, animatedGif = false)

        assertEquals(SharedImportKind.CHARACTER, classifier.inspect(chatBarPng, imageInfo = imageInfo).kind)
        assertEquals(SharedImportKind.CHARACTER, classifier.inspect(sillyTavernPng, imageInfo = imageInfo).kind)
        assertEquals(SharedImportKind.IMAGE, classifier.inspect(basePng, imageInfo = imageInfo).kind)
    }

    @Test
    fun ordinaryNovelAiAndGifMetadataRemainImageActions() {
        val png = onePixelPng()
        val pngInfo = SharedImportImageInfo("image/png", 832, 1216, animatedGif = false)
        val gifInfo = SharedImportImageInfo("image/gif", 320, 320, animatedGif = true)

        assertEquals(SharedImportKind.IMAGE, classifier.inspect(png, "novelai.png", pngInfo).kind)
        val gif = classifier.inspect("GIF89a-data".toByteArray(), "animated.gif", gifInfo)
        assertTrue(gif is SharedImportCoreInspection.Image && gif.info.animatedGif)
    }

    @Test
    fun bomAmbiguityInvalidPayloadAndForeignTextKeepExistingClassification() {
        val format = "\uFEFF" + json.encodeToString(
            FormatCardPackage.serializer(),
            FormatCardPackage(name = "格式", content = "内容"),
        )
        val ambiguous = """{"card":{},"book":{},"name":"冲突","content":"内容"}"""
        val invalidFormat = """{"name":"空格式","content":""}"""

        assertEquals(SharedImportKind.FORMAT, inspectText(format).kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText(ambiguous).kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText(invalidFormat).kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText("普通分享文本").kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText("{}").kind)
    }

    @Test
    fun manualTargetUsesStrictDecoder() {
        val format = """{"schemaVersion":2,"name":"格式","content":"内容"}""".toByteArray()
        assertEquals(SharedImportKind.FORMAT, classifier.decodeAs(format, SharedImportKind.FORMAT).kind)
        assertFails { classifier.decodeAs(format, SharedImportKind.CHARACTER) }
    }

    private fun inspectText(
        text: String,
        displayName: String = "shared.json",
    ): SharedImportCoreInspection<String> =
        classifier.inspect(text.toByteArray(Charsets.UTF_8), displayName)

    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )

    private object TestPromptPolicy : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt(): String = "NEGATIVE"

        override fun effectiveCharacterNaiNegativePrompt(value: String): String = value
    }
}
