package com.example.chatbar.desktop

import androidx.compose.runtime.*
import com.example.chatbar.domain.chat.ConnectionProbeResult
import com.example.chatbar.domain.chat.ConnectionProbeStatus

@Composable
internal fun DesktopConnectionTestPanel(controller: DesktopConnectionTestController, unsavedCredential: Boolean) {
    val state by controller.state.collectAsState()
    val t = LocalDesktopUiStrings.current
    StatusText(t(DesktopUiText.TEST_SAVED_CONFIGURATION))
    if (unsavedCredential) StatusText(t(DesktopUiText.SAVE_KEY_FIRST))
    ActionRow {
        BootstrapButton(t(if (state.running) DesktopUiText.TESTING_CONNECTION else DesktopUiText.TEST_CONNECTION),
            enabled = !state.running && !unsavedCredential, variant = DesktopActionVariant.SECONDARY) { controller.start() }
        if (state.running) BootstrapButton(t(DesktopUiText.STOP_CONNECTION_TEST),
            variant = DesktopActionVariant.GHOST) { controller.stop() }
    }
    state.model?.let { StatusText("$it · ${state.endpoint.orEmpty()}") }
    fun result(label: DesktopUiText, value: ConnectionProbeResult): String {
        val status = when (value.status) {
            ConnectionProbeStatus.SUCCESS -> DesktopUiText.PROBE_SUCCESS
            ConnectionProbeStatus.FAILED -> DesktopUiText.PROBE_FAILED
            ConnectionProbeStatus.NOT_CONFIGURED -> DesktopUiText.NOT_CONFIGURED
        }
        return "${t(label)}：${t(status)}" + value.error?.let { " $it" }.orEmpty()
    }
    state.chat?.let { StatusText(result(DesktopUiText.CHAT, it)) }
    state.embedding?.let { StatusText(result(DesktopUiText.EMBEDDING_MODEL, it)) }
    if (state.cancelled) StatusText(t(DesktopUiText.TEST_CANCELLED))
    state.error?.let { StatusText(t.status(it)) }
}
