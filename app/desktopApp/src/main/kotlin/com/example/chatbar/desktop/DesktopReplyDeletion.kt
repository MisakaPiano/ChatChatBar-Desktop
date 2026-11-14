package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Secondary destructive route; opening it never deletes a candidate. */
@Composable
internal fun DesktopDeleteAllAlternativesAction(onClick: () -> Unit) {
    val colors = DesktopBootstrapColors
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("delete-all-alternatives")
        .clip(shape).border(1.dp, colors.destructive, shape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText("删除全部备选", style = TextStyle(color = colors.destructive))
        Image(rememberVectorPainter(DesktopAppIcons.Next), contentDescription = null,
            modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(colors.destructive))
    }
}
