package com.example.chatbar.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopChatHeader(title: String, summary: String?, navigation: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 480.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                navigation?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(title, Modifier.testTag("chat-header-title"), maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.foreground,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                    summary?.let {
                        BasicText(it, Modifier.testTag("chat-header-model"), maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 13.sp))
                    }
                }
                if (!narrow) actions()
            }
            if (narrow) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
        }
    }
}
