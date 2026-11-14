package com.example.chatbar.ui.components

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.chatbar.domain.image.OwnedImageCropStorage
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.ui.character.CharacterImageCropDialog
import com.example.chatbar.ui.character.CharacterImageCropTarget
import com.example.chatbar.ui.kit.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BackgroundCropButton(path: String, enabled: Boolean, onAdded: suspend (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(path) { mutableStateOf(false) }
    var busy by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    CbButton(if (busy) "保存副本…" else "裁剪副本", { open = true }, enabled = enabled && !busy,
        variant = ButtonVariant.Ghost)
    error?.let { CbText(it, style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.destructive) }
    if (open) CharacterImageCropDialog(Uri.fromFile(File(path)), CharacterImageCropTarget.Background,
        onDismiss = { if (!busy) open = false }, onConfirm = { rect ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        LocalDataMaintenance.access {
                            val created = OwnedImageCropStorage.crop(context, Uri.fromFile(File(path)), rect, 1080, 1920, "background-crops")
                            onAdded(created)
                        }
                        open = false; error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = "副本保存失败：${failure.message}"; open = false }
                    finally { busy = false }
                }
            }
        })
}
