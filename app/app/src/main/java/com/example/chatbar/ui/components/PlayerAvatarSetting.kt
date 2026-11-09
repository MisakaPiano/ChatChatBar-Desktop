package com.example.chatbar.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.image.PlayerAvatarStorage
import com.example.chatbar.ui.character.CharacterImageCropDialog
import com.example.chatbar.ui.character.CharacterImageCropTarget
import com.example.chatbar.ui.kit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlayerAvatarSetting(path: String?, inherited: String? = null, onSave: suspend (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pending = it }
    suspend fun save(value: String?) {
        try { onSave(value); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "头像保存失败：${failure.message}" }
        finally { busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (path ?: inherited)?.let { AsyncImage(it, "玩家头像预览", Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop) }
        CbButton("选择玩家头像", { picker.launch(arrayOf("image/*")) }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp), variant = ButtonVariant.Outline)
        if (path != null) CbButton(if (inherited != null) "恢复跟随全局头像" else "清除玩家头像", {
            busy = true; scope.launch { LocalDataMaintenance.access { save(null) } }
        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), variant = ButtonVariant.Ghost)
        CbText("确认裁剪后立即保存；取消裁剪保留原头像。", style = ChatBarTheme.typography.caption)
        error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
    }
    pending?.let { uri -> CharacterImageCropDialog(uri, CharacterImageCropTarget.Avatar,
        onDismiss = { if (!busy) pending = null }, onConfirm = { rect ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        LocalDataMaintenance.access {
                            val avatar = PlayerAvatarStorage.crop(context, uri, rect)
                            save(avatar)
                        }
                        pending = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = "头像读取失败：${failure.message}"; pending = null }
                    finally { busy = false }
                }
            }
        }) }
}
