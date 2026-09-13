package com.example.chatbar.domain.card

import android.util.Log
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.Json
import java.util.Base64

object SillyTavernCardMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ST_DATA_BLOCK = Regex("""<(?:UpdateVariable|initvar)\b[^>]*>[\s\S]*?</(?:UpdateVariable|initvar)>""", RegexOption.IGNORE_CASE)
    private val ST_SELF_CLOSE = Regex("""<StatusPlaceHolderImpl\s*/>""", RegexOption.IGNORE_CASE)
    private val ST_WRAPPER_TAG = Regex("""</?(?i)(?:scene|content|场景|开场白|开场|内容)[^>]*>""")
    private val ST_HTML_FONT = Regex("""<font\b[^>]*>|</font>""", RegexOption.IGNORE_CASE)

    fun toCharacterCardPackage(st: SillyTavernCard): CharacterCardPackage {
        val freeformText = buildString {
            appendLine("【角色名称】")
            appendLine(st.name)
            appendLine()
            if (st.description.isNotBlank()) {
                appendLine("【人物描述】")
                appendLine(translatePlaceholders(st.description))
                appendLine()
            }
            if (st.personality.isNotBlank()) {
                appendLine("【性格特点】")
                appendLine(translatePlaceholders(st.personality))
                appendLine()
            }
            if (st.scenario.isNotBlank()) {
                appendLine("【背景场景】")
                appendLine(translatePlaceholders(st.scenario))
                appendLine()
            }
            if (st.mesExample.isNotBlank()) {
                appendLine("【对话示例】")
                appendLine(translatePlaceholders(st.mesExample))
                appendLine()
            }
        }.trim()

        val mesExample = if (st.mesExample.isNotBlank()) translatePlaceholders(st.mesExample) else ""
        val systemPrompt = st.systemPrompt.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""
        val postHistory = st.postHistoryInstructions.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""

        val cleanedGreeting = cleanSTTags(translatePlaceholders(st.firstMes))
        val cleanedAlternates = st.alternateGreetings.map { cleanSTTags(translatePlaceholders(it)) }
            .filter { it.isNotBlank() }

        val (finalGreeting, finalAlternates) = if (cleanedGreeting.isBlank() && cleanedAlternates.isNotEmpty()) {
            cleanedAlternates.first() to cleanedAlternates.drop(1)
        } else {
            cleanedGreeting to cleanedAlternates
        }

        val characterBook = parseCharacterBook(st.characterBook)
        val card = PackagedCharacterCard(
            name = st.name,
            greeting = finalGreeting,
            alternateGreetings = finalAlternates,
            avatarResourceId = if (st.pngBytes != null) "card-avatar" else null,
            chatBackgroundResourceId = if (st.pngBytes != null) "card-avatar" else null,
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = freeformText,
            defaultImageNegativePrompt = PromptTemplates.defaultCharacterNaiNegativePrompt(),
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistory,
            mesExample = mesExample,
            creatorNotes = st.creatorNotes,
            tags = st.tags,
            creator = st.creator,
            characterVersion = st.characterVersion,
            extensions = st.extensions,
            characterBook = null
        )

        return CharacterCardPackage(
            schemaVersion = 5,
            card = card,
            documents = emptyList(),
            images = if (st.pngBytes != null) {
                mapOf("card-avatar" to PackagedImage(
                    fileName = "card.png",
                    data = Base64.getEncoder().encodeToString(st.pngBytes)
                ))
            } else emptyMap(),
            worldBooks = listOfNotNull(characterBook)
        )
    }

    private fun parseCharacterBook(raw: String?): WorldBook? {
        if (raw.isNullOrBlank()) return null
        return try {
            WorldBookTransferService(json).decodeCharacterBook(raw, "导入世界书")
        } catch (e: Exception) {
            Log.e("ChatBar", "解析角色卡内嵌世界书失败: ${e.message}", e)
            null
        }
    }

    private fun translatePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")

    private fun cleanSTTags(text: String): String =
        text.replace(ST_DATA_BLOCK, "")
            .replace(ST_SELF_CLOSE, "")
            .replace(ST_WRAPPER_TAG, "")
            .replace(ST_HTML_FONT, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
