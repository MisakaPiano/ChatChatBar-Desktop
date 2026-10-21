package com.example.chatbar.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopDataSectionHeading(text: String) {
    BasicText(text, Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
}
