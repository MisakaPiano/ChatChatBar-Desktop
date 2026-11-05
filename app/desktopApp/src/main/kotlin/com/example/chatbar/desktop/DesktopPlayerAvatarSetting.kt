package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopPlayerAvatarSetting(controller: DesktopModelSettingsController, state: DesktopModelSettingsState) {
    if (controller.playerImages == null) return
    val scope = rememberCoroutineScope()
    val bitmap by produceState<ImageBitmap?>(null, state.playerAvatar) {
        value = withContext(Dispatchers.IO) { state.playerAvatar?.let { reference ->
            runCatching { DesktopImageEditing.decode(controller.playerImages.read(reference), longestSide = 128).toComposeImageBitmap() }.getOrNull()
        } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(DesktopBootstrapColors.muted), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it, "玩家头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: StatusText(state.settings?.playerName?.trim()?.take(1)?.ifBlank { "你" } ?: "你")
            }
            BootstrapButton("选择玩家头像", enabled = !state.busy, secondary = true) { scope.launch { controller.choosePlayerAvatar() } }
            DesktopChatIconAction("清除玩家头像", DesktopAppIcons.Delete, enabled = !state.busy && state.playerAvatar != null) {
                scope.launch { controller.clearPlayerAvatar() }
            }
        }
        StatusText("全局默认头像立即保存；会话可以单独覆盖，也可以选择跟随所扮演角色。")
    }
}
