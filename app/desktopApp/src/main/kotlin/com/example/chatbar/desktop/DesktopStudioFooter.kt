package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiPromptTokenUsage

/** Presentation only: the caller owns token calculation, undo state and generation permissions. */
@Composable
internal fun DesktopStudioFooter(
    usage: NovelAiPromptTokenUsage?, undoEnabled: Boolean, redoEnabled: Boolean,
    generateLabel: String, generateEnabled: Boolean,
    onUndo: () -> Unit, onRedo: () -> Unit, onGenerate: () -> Unit,
) {
    @Composable fun tokens(modifier: Modifier) {
        Column(modifier.semantics { contentDescription = "Studio Tokens" }) {
            usage?.let {
                StudioTokenBar("正向 Tokens", it.positive, it.limit)
                StudioTokenBar("负向 Tokens", it.negative, it.limit)
            }
        }
    }
    @Composable fun undoRedo() {
        DesktopIconAction("撤销上次载入/重置", DesktopAppIcons.Undo, enabled = undoEnabled, onClick = onUndo)
        DesktopIconAction("重做", DesktopAppIcons.Redo, enabled = redoEnabled, onClick = onRedo)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        if (availableWidth >= 380.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                tokens(Modifier.width(minOf(availableWidth * .42f, 220.dp)))
                undoRedo()
                Spacer(Modifier.weight(1f))
                Box(Modifier.widthIn(max = availableWidth * .38f)) {
                    BootstrapButton(generateLabel, enabled = generateEnabled, onClick = onGenerate)
                }
            }
        } else {
            // At the minimum editor/window width, retain readable token counts and reachable actions.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                tokens(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    undoRedo()
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.widthIn(max = availableWidth - 100.dp)) {
                        BootstrapButton(generateLabel, enabled = generateEnabled, onClick = onGenerate)
                    }
                }
            }
        }
    }
}
