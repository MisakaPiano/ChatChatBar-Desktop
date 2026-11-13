package com.example.chatbar.desktop

/** Local lifecycle evidence only; a preparation phase is not a provider request or usage. */
enum class DesktopChatPreparationStage(val label: String) {
    CONFIGURATION("检查对话与模型配置"),
    INPUT("准备输入图片"),
    USER_PERSISTENCE("保存用户消息"),
    HISTORY_AND_MEMORY("读取历史与记忆"),
    KNOWLEDGE_RETRIEVAL("召回知识"),
    REQUEST_ASSEMBLY("组装对话请求"),
    MAIN_REPLY("生成主回复"),
}

internal class DesktopEmbeddingAuthenticationException : IllegalStateException(
    "向量模型缺少可用凭据，请在模型设置中检查向量模型的 API 密钥",
)

internal fun desktopChatPreparationFailure(stage: DesktopChatPreparationStage, failure: Throwable,
    userPersisted: Boolean): String {
    val action = if (failure is DesktopEmbeddingAuthenticationException)
        "请在模型设置中检查向量模型的 API 密钥。"
    else when (stage) {
        DesktopChatPreparationStage.KNOWLEDGE_RETRIEVAL -> "请检查向量模型、网络及索引状态。"
        DesktopChatPreparationStage.CONFIGURATION -> "请检查当前对话和模型配置。"
        DesktopChatPreparationStage.USER_PERSISTENCE -> "消息保存未确认，请核对对话后再操作。"
        else -> "请查看聊天调试日志，核对配置及资料状态后重试。"
    }
    val retained = if (userPersisted) "本条消息已保存；排除问题后可用空输入的继续生成重试，无需重复发送。"
        else "请先核对消息是否已经保存。"
    return "发送准备失败（${stage.label}），主回复尚未发出。$action$retained"
}

internal fun desktopLatestChatFailure(tasks: List<DesktopTaskEntry>, sessionId: String): DesktopTaskEntry? =
    tasks.firstOrNull { it.sessionId == sessionId && it.kind == DesktopTaskKind.REAL_CHAT }
        ?.takeIf { it.status == DesktopTaskStatus.FAILED }
