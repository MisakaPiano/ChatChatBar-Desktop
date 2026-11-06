package com.example.chatbar.domain.prompt

/** Shared request-only experimental tool protocol; ordinary Prompt literals stay unchanged. */
object ExperimentalToolPromptAuthority {
    const val EXPERIMENTAL_GAME_CONTENT_TOOL_DESCRIPTION =
        "请使用此工具提交本轮完整回复，将全部回复内容写入 content 参数。"
    const val EXPERIMENTAL_TOOL_USER_TAIL = "\", \"function\": "
}
