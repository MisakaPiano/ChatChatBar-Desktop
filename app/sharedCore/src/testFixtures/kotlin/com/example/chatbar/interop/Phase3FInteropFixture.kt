package com.example.chatbar.interop

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.PackagedCharacter
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.image.NovelAiImageModel
import java.security.MessageDigest
import java.util.Base64

/** One canonical semantic fixture authority shared by Desktop JVM and Android instrumented tests. */
object Phase3FInteropFixture {
    const val SOURCE_COMMIT = "88bf0b154b6472085acface0879d50fdc607c826"
    const val STRUCTURED_NAME = "Phase3F Structured 双角色"
    const val FREEFORM_NAME = "Phase3F Freeform 原生"
    const val FORMAT_NAME = "Phase3F Standalone Format"
    const val WORLD_BOOK_NAME = "Phase3F Standalone WorldBook"

    private val imageBytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )

    private val fishBinding = FishAudioVoiceBinding(
        referenceId = "fish-reference-世界",
        title = "Phase3F Voice",
        authorId = "author-42",
        authorName = "测试作者",
        coverImage = "https://example.invalid/cover.png",
        sampleAudio = "https://example.invalid/sample.mp3",
        sampleText = "你好，跨平台语音绑定。",
        visibility = "public",
        languages = listOf("zh", "ja"),
        tags = listOf("calm", "narrator"),
    )

    val defaultFormat = FormatCardPackage(
        exportedAt = 1_700_000_000_100L,
        name = "Phase3F Default Format",
        content = "{{user}} -> {{char}}",
        userTools = listOf(
            FormatCardUserToolConfig(
                type = FormatCardUserToolType.RANDOM_NUMBER,
                minimum = "7",
                maximum = "19",
            ),
            FormatCardUserToolConfig(
                type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
                text = "保持角色语言风格。",
            ),
        ),
    )

    val embeddedWorldBook = WorldBook(
        id = "fixture-book-id",
        name = "Phase3F Embedded Lore",
        description = "跨平台世界书描述",
        scanDepth = 7,
        tokenBudget = 777,
        recursiveScanning = true,
        caseSensitive = true,
        matchWholeWords = true,
        createdAt = 1_700_000_000_200L,
        updatedAt = 1_700_000_000_300L,
        entries = listOf(
            WorldBookEntry(
                id = "fixture-entry-a",
                name = "入口甲",
                keys = listOf("Alpha", "/beta/i"),
                content = "第一条世界书内容。",
                enabled = true,
                insertionOrder = 12,
                priority = 8,
                constant = false,
                position = WorldBookPosition.BEFORE_CHAR,
                caseSensitive = true,
                matchWholeWords = false,
                selective = true,
                secondaryKeys = listOf("secondary-a", "secondary-b"),
                selectiveLogic = WorldBookSelectiveLogic.AND_ALL.value,
                comment = "entry-a-comment",
                scanDepth = 4,
                role = "system",
                ignoreBudget = true,
                excludeRecursion = true,
                preventRecursion = false,
                delayUntilRecursion = true,
                recursionLevel = 2,
                originalPosition = "before_char",
                matchCharacterDescription = true,
                matchCharacterPersonality = true,
                matchScenario = true,
                matchCreatorNotes = true,
                matchPersonaDescription = true,
                probability = 83,
                group = "group-a",
                groupWeight = 61,
                sticky = 3,
                cooldown = 2,
                delay = 1,
                useRegex = true,
                outletName = "outlet-a",
                characterFilter = listOf("Alice", "Bob"),
                characterFilterExclude = true,
                extensions = "{\"fixture\":\"entry-a\"}",
            ),
            WorldBookEntry(
                id = "fixture-entry-b",
                name = "入口乙",
                keys = listOf("gamma"),
                content = "第二条内容，保持顺序。",
                insertionOrder = 34,
                constant = true,
                position = WorldBookPosition.OUTLET,
                probability = 100,
                outletName = "outlet-b",
                extensions = "{\"fixture\":\"entry-b\"}",
            ),
        ),
    )

    val structuredCharacter = CharacterCardPackage(
        exportedAt = 1_700_000_001_000L,
        card = PackagedCharacterCard(
            name = STRUCTURED_NAME,
            botName = "结构化助手",
            avatarResourceId = "avatar",
            chatBackgroundResourceId = "chat-background",
            editMode = CharacterEditMode.STRUCTURED,
            characters = listOf(
                PackagedCharacter(
                    name = "Alice",
                    profile = "负责探索的第一角色。",
                    appearance = "银发蓝眼",
                    appearanceImageResourceId = "character-0-appearance",
                    clothing = "深蓝外套",
                    abilities = "导航与推理",
                    habits = "记录星图",
                    background = "来自远航舰队",
                    relationships = "信任 Bob",
                    speakingStyle = "简洁、冷静",
                    imagePrompt = "1girl, silver hair, blue eyes",
                    fishAudioVoice = fishBinding,
                ),
                PackagedCharacter(
                    name = "Bob",
                    profile = "负责维护的第二角色。",
                    appearance = "黑发金眼",
                    clothing = "工程制服",
                    abilities = "机械维修",
                    habits = "哼唱旧歌",
                    background = "空间站工程师",
                    relationships = "保护 Alice",
                    speakingStyle = "温和、详细",
                    imagePrompt = "1boy, black hair, golden eyes",
                ),
            ),
            greeting = "欢迎来到 Phase 3F。",
            alternateGreetings = listOf("备用问候一", "备用问候二"),
            basicSetting = "星际探索背景。",
            defaultImagePrompt = "masterpiece, space opera",
            defaultImageNegativePrompt = "low quality, watermark",
            defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
            systemPrompt = "结构化系统提示字段",
            postHistoryInstructions = "结构化后历史指令",
            mesExample = "<START>\n{{user}}: 示例\n{{char}}: 回答",
            creatorNotes = "Phase 3F creator notes",
            tags = listOf("interop", "structured", "多角色"),
            creator = "CCB Desktop Project",
            characterVersion = "3F-1",
            extensions = "{\"phase3f\":true,\"mode\":\"structured\"}",
        ),
        documents = listOf(
            PackagedDocument(
                fileName = "航行记录.txt",
                fileType = "text/plain",
                content = "UTF-8 文档：星海、かな、emoji 🚀。\n第二行。",
            ),
        ),
        images = linkedMapOf(
            "avatar" to image("avatar.png"),
            "chat-background" to image("background.png"),
            "character-0-appearance" to image("alice.png"),
        ),
        worldBooks = listOf(embeddedWorldBook),
        defaultFormatCard = defaultFormat,
    )

    val freeformCharacter = CharacterCardPackage(
        exportedAt = 1_700_000_002_000L,
        card = PackagedCharacterCard(
            name = FREEFORM_NAME,
            botName = "自由文本助手",
            avatarResourceId = "avatar",
            editMode = CharacterEditMode.FREEFORM,
            characters = listOf(
                PackagedCharacter(
                    name = "Freeform Voice Anchor",
                    profile = "原生 FREEFORM 中保留的 packaged character。",
                    appearance = "青色长发",
                    appearanceImageResourceId = "character-0-appearance",
                    imagePrompt = "1girl, cyan hair",
                    fishAudioVoice = fishBinding.copy(referenceId = "fish-freeform-reference"),
                ),
            ),
            greeting = "自由文本问候。",
            alternateGreetings = listOf("自由备用一", "自由备用二"),
            basicSetting = "自由文本基础设定。",
            freeformCharacterText = "这是原生 ChatBar FREEFORM 角色文本，不来自 SillyTavern。",
            defaultImagePrompt = "freeform prompt",
            defaultImageNegativePrompt = "freeform negative",
            systemPrompt = "FREEFORM system field",
            postHistoryInstructions = "FREEFORM post-history field",
            mesExample = "FREEFORM example",
            creatorNotes = "FREEFORM notes",
            tags = listOf("interop", "freeform"),
            creator = "CCB Desktop Project",
            characterVersion = "3F-freeform-1",
            extensions = "{\"phase3f\":true,\"mode\":\"freeform\"}",
        ),
        documents = listOf(
            PackagedDocument("自由文档.md", "text/markdown", "# 自由文档\n保留 UTF-8：你好，世界。"),
        ),
        images = linkedMapOf(
            "avatar" to image("freeform-avatar.png"),
            "character-0-appearance" to image("freeform-character.png"),
        ),
    )

    val standaloneFormat = FormatCardPackage(
        exportedAt = 1_700_000_003_000L,
        name = FORMAT_NAME,
        content = "Standalone {{user}} / {{char}}",
        userTools = defaultFormat.userTools,
        sourcePresetKey = "format-preset",
        sourcePresetVersion = 9,
    )

    val standaloneWorldBook = WorldBookPackage(
        exportedAt = 1_700_000_004_000L,
        book = embeddedWorldBook.copy(
            id = "standalone-book-id",
            name = WORLD_BOOK_NAME,
        ),
    )

    fun normalizeCharacter(value: CharacterCardPackage): CharacterCardPackage = value.copy(
        exportedAt = 0L,
        images = value.images.mapValues { (_, image) -> image.copy(fileName = "<platform-local>") },
        worldBooks = value.worldBooks.map(::normalizeWorldBook),
        defaultFormatCard = value.defaultFormatCard?.let(::normalizeFormat),
    )

    fun normalizeFormat(value: FormatCardPackage): FormatCardPackage = value.copy(exportedAt = 0L)

    fun normalizeWorldBook(value: WorldBookPackage): WorldBookPackage = value.copy(
        exportedAt = 0L,
        book = normalizeWorldBook(value.book),
    )

    fun normalizeWorldBook(value: WorldBook): WorldBook = value.copy(
        id = "<local>",
        createdAt = 0L,
        updatedAt = 0L,
        entries = value.entries.map { it.copy(id = "<local>") },
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun imageRoleHashes(value: CharacterCardPackage): Map<String, String> = buildMap {
        value.card.avatarResourceId?.let { put("avatar", imageHash(value, it)) }
        value.card.chatBackgroundResourceId?.let { put("chat-background", imageHash(value, it)) }
        value.card.characters.forEachIndexed { index, character ->
            character.appearanceImageResourceId?.let { put("character-$index-appearance", imageHash(value, it)) }
        }
    }

    private fun imageHash(value: CharacterCardPackage, id: String): String =
        sha256(Base64.getMimeDecoder().decode(value.images.getValue(id).data))

    private fun image(fileName: String): PackagedImage = PackagedImage(
        fileName = fileName,
        data = Base64.getEncoder().encodeToString(imageBytes),
    )
}
