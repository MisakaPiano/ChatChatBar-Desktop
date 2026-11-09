package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class DesktopSessionSettingsTab(val label: String) { BASIC("基础"), CONTEXT("Prompt / 上下文"), IMAGES("图片"), VOICE("语音与朗读"), ADVANCED("高级"), RAG("RAG 检索库") }
internal enum class DesktopSessionWorkspaceTab(val label: String) { PARAMETERS("参数与设定"), MEMORY("长期记忆"), RAG("RAG 检索库"), SAVES("存档") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopSessionWorkspaceTabs(selected: DesktopSessionWorkspaceTab, onSelect: (DesktopSessionWorkspaceTab) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DesktopSessionWorkspaceTab.entries.forEach { tab -> BootstrapButton(tab.label, secondary = selected != tab) { onSelect(tab) } }
    }
}

/** The controller draft and immediate writes remain outside tab composition. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopSessionSettingsTabs(selected: DesktopSessionSettingsTab, onSelect: (DesktopSessionSettingsTab) -> Unit) {
    val tabs = DesktopSessionSettingsTab.entries.filterNot { it == DesktopSessionSettingsTab.RAG }
    val focus = remember { tabs.map { FocusRequester() } }
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { index, tab ->
            Box(Modifier.focusRequester(focus[index]).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    val next = when (event.key) { Key.DirectionRight -> (index + 1) % tabs.size; Key.DirectionLeft -> (index + tabs.size - 1) % tabs.size; Key.MoveHome -> 0; Key.MoveEnd -> tabs.lastIndex; else -> -1 }
                    if (next < 0) false else { onSelect(tabs[next]); focus[next].requestFocus(); true }
                }
            }.selectable(tab == selected, role = Role.Tab) { onSelect(tab) }
                .background(if (tab == selected) DesktopBootstrapColors.primary else DesktopBootstrapColors.muted)
                .border(1.dp, DesktopBootstrapColors.border).padding(12.dp)) {
                StatusText(tab.label, if (tab == selected) DesktopBootstrapColors.primaryForeground else DesktopBootstrapColors.foreground)
            }
        }
    }
}
