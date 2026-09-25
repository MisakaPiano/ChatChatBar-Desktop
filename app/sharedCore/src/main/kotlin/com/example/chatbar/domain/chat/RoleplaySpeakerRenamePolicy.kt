package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.SpeakerTagRename

/** 只改写持久聊天正文中的角色标记；UI 分段与渲染继续由平台层负责。 */
fun renameRoleplaySpeakerMarkers(
    content: String,
    renames: List<SpeakerTagRename>
): String {
    if (renames.isEmpty() || content.isEmpty()) return content
    return roleplaySpeakerRenamePattern.replace(content) { match ->
        val currentName = match.groupValues[1].trim()
        val rename = renames.firstOrNull { item ->
            item.oldName.trim().equals(currentName, ignoreCase = true)
        } ?: return@replace match.value
        "<n=\"${rename.newName.trim()}\"/>"
    }
}

private val roleplaySpeakerRenamePattern = Regex(
    pattern = "[<＜]\\s*[nｎ]\\s*[=＝]\\s*[\"“”＂]([^\\r\\n\"“”＂]*?)[\"“”＂]\\s*[/／]?\\s*[>＞]",
    option = RegexOption.IGNORE_CASE
)
