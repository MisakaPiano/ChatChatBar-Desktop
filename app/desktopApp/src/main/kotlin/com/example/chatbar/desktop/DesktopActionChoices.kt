package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplayActionOptionsPolicy

internal data class DesktopActionChoice(val blockStart: Int, val ordinal: Int, val text: String,
    val label: String = ordinal.toString())

/** Recognition is deliberately stricter than STATUS: unfinished fences and ordinary code stay inert. */
internal fun desktopActionChoices(message: ChatMessage): List<DesktopActionChoice> {
    if (message.role != MessageRole.ASSISTANT) return emptyList()
    return RoleplayActionOptionsPolicy.blocks(message.displayContent, message.displayActionOptionsFormat).flatMap { block ->
        block.options.map { DesktopActionChoice(block.range.first, it.ordinal, it.text, it.label) }
    }
}

internal fun desktopIsActionHeading(text: String): Boolean = text.trim().trim('#', '*', ' ', '[', ']', '【', '】', ':', '：')
    .lowercase() in setOf("行动选项", "可选行动", "行动选择", "action choices", "action options")

@Composable
internal fun DesktopActionChoiceList(choices: List<DesktopActionChoice>, enabled: Boolean,
    onChoice: (DesktopActionChoice) -> Unit) {
    val t = LocalDesktopUiStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusText(t(DesktopUiText.ACTION_CHOICES_HINT))
        choices.forEach { choice ->
            val marker = if (choice.label.toIntOrNull() == null) "${choice.label})" else "${choice.ordinal}."
            BootstrapButton("$marker ${choice.text}", secondary = true, enabled = enabled) { onChoice(choice) }
        }
    }
}
