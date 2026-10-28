package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The shell owns the top title. Workspace/divider end above this bounded global status band. */
@Composable
internal fun DesktopStudioContentFrame(status: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Box(Modifier.fillMaxWidth().height(28.dp).background(DesktopBootstrapColors.card)
            .testTag("studio-global-status").padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicText(status.ifBlank { "NovelAI Studio" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
        }
    }
}
