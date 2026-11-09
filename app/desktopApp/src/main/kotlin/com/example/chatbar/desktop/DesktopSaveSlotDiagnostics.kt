package com.example.chatbar.desktop

internal enum class DesktopSessionResourceRead(val label: String) {
    SESSION("当前会话"), CHARACTER_CARDS("角色卡候选"), WORLD_BOOKS("世界书候选")
}

internal class DesktopSessionResourceReadException(
    val resource: DesktopSessionResourceRead,
    storageId: String?,
    cause: Exception,
) : IllegalStateException("SESSION_RESOURCE_READ", cause) {
    val storageId = storageId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
}

/** Never include exception messages: serializers may embed original JSON or model credentials. */
internal fun desktopSaveSlotFailureDetails(operation: String, stage: String, error: Throwable): String = buildString {
    appendLine("操作：$operation")
    appendLine("阶段：$stage")
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    chain.filterIsInstance<DesktopSessionResourceReadException>().firstOrNull()?.let { failure ->
        appendLine("资源：${failure.resource.label}")
        failure.storageId?.let { appendLine("存储标识：$it") }
    }
    chain.forEach { failure ->
        appendLine(failure.javaClass.name.take(180))
        failure.stackTrace.asSequence().filter { it.className.startsWith("com.example.chatbar.") }
            .take(6).forEach { appendLine("${it.className}.${it.methodName}:${it.lineNumber}") }
    }
}.trimEnd()
