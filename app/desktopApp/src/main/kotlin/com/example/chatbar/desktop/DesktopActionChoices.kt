package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.parseRoleplayTextSegments

internal data class DesktopActionChoice(val blockStart: Int, val ordinal: Int, val text: String)

/** Recognition is deliberately stricter than STATUS: unfinished fences and ordinary code stay inert. */
internal fun desktopActionChoices(message: ChatMessage): List<DesktopActionChoice> {
    if (message.role != MessageRole.ASSISTANT) return emptyList()
    return parseRoleplayTextSegments(message.displayContent).flatMap { segment ->
        if (segment.kind != RoleplaySegmentKind.STATUS) return@flatMap emptyList()
        val raw = segment.rawText.trim().replace("\r\n", "\n")
        val lines = raw.lines()
        if (lines.size < 4) return@flatMap emptyList()
        val fenceHeading = when {
            lines.first() == "---" && lines.last() == "---" -> ""
            lines.first().startsWith("```") && lines.last() == "```" -> {
                val header = lines.first().removePrefix("```").trim()
                if (header.isNotEmpty() && !isActionHeading(header)) return@flatMap emptyList()
                header
            }
            else -> return@flatMap emptyList()
        }
        var body = lines.drop(1).dropLast(1).map(String::trim).filter(String::isNotEmpty)
        if (body.firstOrNull()?.let(::isActionHeading) == true) body = body.drop(1)
        else if (fenceHeading.isEmpty()) return@flatMap emptyList()
        if (body.size !in 2..20) return@flatMap emptyList()
        val choices = body.mapIndexed { index, line ->
            val match = actionNumber.matchEntire(line) ?: return@flatMap emptyList()
            if (match.groupValues[1].toIntOrNull() != index + 1) return@flatMap emptyList()
            val text = match.groupValues[2].trim()
            if (text.isEmpty() || text.contains("```") || markdownLink.containsMatchIn(text) ||
                text.contains('<') || text.contains('>')) return@flatMap emptyList()
            DesktopActionChoice(segment.start, index + 1, text)
        }
        if (choices.map { it.text }.distinct().size != choices.size) emptyList() else choices
    }
}

private val actionNumber = Regex("([1-9][0-9]?)[.、)）][ \\t]*(.+)")
private val markdownLink = Regex("!?\\[[^]]*]\\s*\\(|https?://", RegexOption.IGNORE_CASE)
private fun isActionHeading(text: String): Boolean = text.trim().trim('#', '*', ' ', '[', ']', '【', '】', ':', '：')
    .lowercase() in setOf("行动选项", "可选行动", "行动选择", "action choices", "action options")

@Composable
internal fun DesktopActionChoiceList(choices: List<DesktopActionChoice>, enabled: Boolean,
    onChoice: (DesktopActionChoice) -> Unit) {
    val t = LocalDesktopUiStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusText(t(DesktopUiText.ACTION_CHOICES_HINT))
        choices.forEach { choice ->
            BootstrapButton("${choice.ordinal}. ${choice.text}", secondary = true, enabled = enabled) { onChoice(choice) }
        }
    }
}
