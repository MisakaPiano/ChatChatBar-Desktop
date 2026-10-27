package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun desktopRecordingProgress(id: String, durationMs: Long, playback: DesktopVoicePlaybackState): Float? = when {
    playback.currentVoiceId != id -> 0f
    !playback.positionKnown || durationMs <= 0 -> null
    else -> (playback.positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
}

/** Width follows measured text where possible; controls retain a usable minimum. No seeking. */
@Composable
internal fun DesktopRecordingRow(id: String, speaker: String, durationMs: Long, playback: DesktopVoicePlaybackState,
    contentWidthDp: Float?, onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DesktopBootstrapColors
    val active = playback.currentVoiceId == id && playback.playing
    val paused = playback.currentVoiceId == id && playback.paused
    val progress = desktopRecordingProgress(id, durationMs, playback)
    Row(modifier.width((contentWidthDp ?: 280f).coerceAtLeast(200f).dp).height(32.dp).testTag("recording-row-$id"),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().background(colors.muted, RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .semantics { contentDescription = "${if (active) "暂停" else if (paused) "继续播放" else "播放"} · $speaker · ${durationMs / 1000}s" }
            .clickable(onClick = onPlay).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(rememberVectorPainter(if (active) DesktopAppIcons.Pause else DesktopAppIcons.Play), null,
                Modifier.size(16.dp), colorFilter = ColorFilter.tint(colors.foreground))
            BasicText("${durationMs / 1000}s", maxLines = 1,
                style = TextStyle(color = colors.foreground, fontSize = 12.sp))
            if (progress != null) Box(Modifier.weight(1f).height(4.dp).background(colors.border)
                .testTag("recording-progress-$id").semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.primary))
            } else BasicText("进度不可用", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.mutedForeground, fontSize = 11.sp))
        }
        DesktopChatIconAction("录音操作 · $speaker", DesktopAppIcons.More, onClick = onMenu)
    }
}
