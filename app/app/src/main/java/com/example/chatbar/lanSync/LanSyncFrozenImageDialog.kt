package com.example.chatbar.lanSync

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.chatbar.domain.lanSync.LanSyncImagePreview
import com.example.chatbar.ui.kit.*

/** Only a validated immutable cache image can enter this read-only surface. */
@Composable
internal fun LanSyncFrozenImageDialog(preview: LanSyncImagePreview, close: () -> Unit) {
    val context = LocalContext.current
    var scale by remember(preview.id) { mutableFloatStateOf(1f) }
    var offset by remember(preview.id) { mutableStateOf(Offset.Zero) }
    var failed by remember(preview.id) { mutableStateOf(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val request = remember(preview.id, context) {
        val metrics = context.resources.displayMetrics
        ImageRequest.Builder(context).data(preview.path.toFile()).size(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED).build()
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background).systemBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText("冻结内容中的图片 · 双指缩放与移动")
            if (failed) CbText("图片未能显示，原资料和选择保留", color = ChatBarTheme.colors.warning)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AsyncImage(request, "冻结图片", Modifier.fillMaxSize().transformable(transform)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                    contentScale = ContentScale.Fit, onError = { failed = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("重置视图", { scale = 1f; offset = Offset.Zero }, variant = ButtonVariant.Secondary)
                CbButton("关闭预览", close)
            }
        }
    }
}
