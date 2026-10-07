package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.prompt.AiTaskEmptyResponseException
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import kotlinx.coroutines.CancellationException

/** Keeps IME state across recomposition and delayed repository echoes of our own edits. */
internal class DesktopDesignInput(text: String) {
    var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        private set
    private var authority = text
    private val pending = mutableListOf<String>()
    fun edit(next: TextFieldValue, publish: (String) -> Unit) {
        val changed = next.text != value.text
        value = next
        if (changed) { pending.add(next.text); publish(next.text) }
    }
    fun echo(text: String) {
        if (text == authority) return
        authority = text
        val index = pending.indexOf(text)
        if (index >= 0) { repeat(index + 1) { pending.removeAt(0) }; return }
        pending.clear()
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }
}

@Composable
internal fun DesktopDesignField(label: String, text: String, identity: Any = label, onChange: (String) -> Unit) {
    val editor = remember(identity) { DesktopDesignInput(text) }
    LaunchedEffect(text) { editor.echo(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(editor.value, { editor.edit(it, onChange) }, Modifier.fillMaxWidth()
            .heightIn(min = 64.dp).border(1.dp, DesktopBootstrapColors.border)
            .background(DesktopBootstrapColors.input).padding(9.dp).semantics { contentDescription = label },
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
    }
}

/** Fixed allowlisted messages only. Neither throwable messages nor causes leave this boundary. */
internal enum class DesktopDesignFailure(val text: String) {
    MODEL("设计模型未配置或不可用，请在设计设置中选择可用模型"),
    AUTH("设计模型认证失败，请检查对应 Provider 的安全凭据"),
    NETWORK("网络连接未完成，请检查连接后重试"),
    PROVIDER("Provider 拒绝或无法完成请求，请检查服务状态后重试"),
    RESPONSE("设计响应格式错误或未完整完成，请重试"),
    CANCELLED("设计已取消，可保留原需求重试"),
    UNKNOWN("设计未完成，请检查配置或稍后重试；当前 Studio Prompt 保留")
}
internal class DesktopDesignException(val category: DesktopDesignFailure) : RuntimeException(category.text)
internal fun desktopDesignFailure(error: Throwable): DesktopDesignFailure = when (error) {
    is DesktopDesignException -> error.category
    is CancellationException -> DesktopDesignFailure.CANCELLED
    is ModelRequestException -> when {
        error.isAuthenticationFailure -> DesktopDesignFailure.AUTH
        error.httpStatus != null -> DesktopDesignFailure.PROVIDER
        else -> DesktopDesignFailure.NETWORK
    }
    is java.io.IOException -> DesktopDesignFailure.NETWORK
    is AiTaskRefusalException -> DesktopDesignFailure.PROVIDER
    is AiTaskEmptyResponseException, is ModelResponseTruncatedException,
    is kotlinx.serialization.SerializationException -> DesktopDesignFailure.RESPONSE
    // Shared parser errors include raw output; inspect only the fixed prefix, never publish it.
    is IllegalStateException -> if (error.message?.startsWith("对话 AI 返回的生图 Prompt JSON 无法解析") == true ||
        error.message?.startsWith("对话 AI 流式生图 Prompt 返回空内容") == true) DesktopDesignFailure.RESPONSE else DesktopDesignFailure.UNKNOWN
    else -> DesktopDesignFailure.UNKNOWN
}

internal data class DesktopDesignModule(val label: String, val prompt: String)
internal fun desktopDesignModules(reply: NovelAiDesignReply) = listOf(DesktopDesignModule("基础 Prompt", reply.plan.baseCaption)) +
    reply.plan.characterCaptions.mapIndexed { index, caption -> DesktopDesignModule("角色 Prompt ${index + 1}", caption.prompt) }

internal fun desktopCopyText(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
}

@Composable
internal fun DesktopDesignResult(reply: NovelAiDesignReply, onCopy: (String) -> Unit = ::desktopCopyText) {
    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("目标图像模型 · ${reply.targetImageModel.displayName}")
        desktopDesignModules(reply).forEach { module ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusText(module.label)
                StudioAction("复制 ${module.label}", icon = DesktopAppIcons.Copy, style = StudioActionStyle.TERTIARY) { onCopy(module.prompt) }
            }
            SelectionContainer { StatusText(module.prompt) }
        }
    }
}
