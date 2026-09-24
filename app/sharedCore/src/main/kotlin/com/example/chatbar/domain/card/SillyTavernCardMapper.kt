package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.WorldBook
import java.util.Base64
import kotlinx.serialization.json.Json

fun interface SillyTavernMappingErrorReporter {
    fun characterBookDecodeFailed(error: Exception)
}

/** JVM-neutral authority for SillyTavern Character → ChatBar Package mapping. */
class SillyTavernCardMapper(
    private val promptPolicy: CharacterTransferPromptPolicy,
    private val errorReporter: SillyTavernMappingErrorReporter = SillyTavernMappingErrorReporter {},
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun toCharacterCardPackage(card: SillyTavernCard): CharacterCardPackage {
        val freeformText = buildString {
            appendLine("【角色名称】")
            appendLine(card.name)
            appendLine()
            appendSection("【人物描述】", card.description)
            appendSection("【性格特点】", card.personality)
            appendSection("【背景场景】", card.scenario)
            appendSection("【对话示例】", card.mesExample)
        }.trim()

        val cleanedGreeting = cleanSillyTavernTags(translatePlaceholders(card.firstMes))
        val cleanedAlternates = card.alternateGreetings
            .map { cleanSillyTavernTags(translatePlaceholders(it)) }
            .filter(String::isNotBlank)
        val (greeting, alternateGreetings) =
            if (cleanedGreeting.isBlank() && cleanedAlternates.isNotEmpty()) {
                cleanedAlternates.first() to cleanedAlternates.drop(1)
            } else {
                cleanedGreeting to cleanedAlternates
            }

        val packageCard = PackagedCharacterCard(
            name = card.name,
            greeting = greeting,
            alternateGreetings = alternateGreetings,
            avatarResourceId = if (card.pngBytes != null) CARD_AVATAR_RESOURCE_ID else null,
            chatBackgroundResourceId = if (card.pngBytes != null) CARD_AVATAR_RESOURCE_ID else null,
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = freeformText,
            defaultImageNegativePrompt = promptPolicy.defaultCharacterNaiNegativePrompt(),
            systemPrompt = card.systemPrompt.translateIfPresent(),
            postHistoryInstructions = card.postHistoryInstructions.translateIfPresent(),
            mesExample = card.mesExample.translateIfPresent(),
            creatorNotes = card.creatorNotes,
            tags = card.tags,
            creator = card.creator,
            characterVersion = card.characterVersion,
            extensions = card.extensions,
            characterBook = null,
        )

        return CharacterCardPackage(
            schemaVersion = SILLY_TAVERN_CHARACTER_PACKAGE_SCHEMA_VERSION,
            card = packageCard,
            documents = emptyList(),
            images = card.pngBytes?.let { pngBytes ->
                mapOf(
                    CARD_AVATAR_RESOURCE_ID to PackagedImage(
                        fileName = "card.png",
                        data = Base64.getEncoder().encodeToString(pngBytes),
                    ),
                )
            }.orEmpty(),
            worldBooks = listOfNotNull(parseCharacterBook(card.characterBook)),
        )
    }

    private fun StringBuilder.appendSection(title: String, value: String) {
        if (value.isBlank()) return
        appendLine(title)
        appendLine(translatePlaceholders(value))
        appendLine()
    }

    private fun parseCharacterBook(raw: String?): WorldBook? {
        if (raw.isNullOrBlank()) return null
        return try {
            WorldBookTransferService(json).decodeCharacterBook(raw, "导入世界书")
        } catch (error: Exception) {
            errorReporter.characterBookDecodeFailed(error)
            null
        }
    }

    private fun String.translateIfPresent(): String =
        takeIf(String::isNotBlank)?.let(::translatePlaceholders).orEmpty()

    private fun translatePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")

    private fun cleanSillyTavernTags(text: String): String =
        text.replace(ST_DATA_BLOCK, "")
            .replace(ST_SELF_CLOSE, "")
            .replace(ST_WRAPPER_TAG, "")
            .replace(ST_HTML_FONT, "")
            .replace(EXCESS_LINE_BREAKS, "\n\n")
            .trim()

    private companion object {
        const val SILLY_TAVERN_CHARACTER_PACKAGE_SCHEMA_VERSION = 5
        const val CARD_AVATAR_RESOURCE_ID = "card-avatar"

        val ST_DATA_BLOCK = Regex(
            """<(?:UpdateVariable|initvar)\b[^>]*>[\s\S]*?</(?:UpdateVariable|initvar)>""",
            RegexOption.IGNORE_CASE,
        )
        val ST_SELF_CLOSE = Regex("""<StatusPlaceHolderImpl\s*/>""", RegexOption.IGNORE_CASE)
        val ST_WRAPPER_TAG = Regex("""</?(?i)(?:scene|content|场景|开场白|开场|内容)[^>]*>""")
        val ST_HTML_FONT = Regex("""<font\b[^>]*>|</font>""", RegexOption.IGNORE_CASE)
        val EXCESS_LINE_BREAKS = Regex("""\n{3,}""")
    }
}
