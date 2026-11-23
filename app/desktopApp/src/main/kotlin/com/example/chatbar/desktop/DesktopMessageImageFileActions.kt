package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopMessageImageFileActions(reference: String, resources: DesktopCharacterResourceStore,
    picker: DesktopFilePicker, clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
    clipboardFiles: () -> DesktopImageClipboardFiles = { desktopClipboardFiles }) {
    var file by remember { mutableStateOf<DesktopMessageImageFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reference, resources) {
        try { file = withContext(Dispatchers.IO) { resources.openMessageImageFile(reference) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status = failure.message ?: "无法读取原文件" }
    }
    fun act(action: suspend () -> String?) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try { status = withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "图片操作失败" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        status?.let { StatusText(it) }
        file?.let { opened ->
            StatusText(opened.originalDescription)
            opened.pngUnavailable?.let { StatusText(it) }
            StudioActions {
                StudioAction("另存原文件", icon = DesktopAppIcons.SaveImage, enabled = !busy) { act {
                    val target = picker.pickNewSaveFile(opened.originalType, opened.suggestedOriginalName, "另存原文件")
                        ?: return@act null
                    currentCoroutineContext().ensureActive()
                    opened.saveOriginal(target)
                    "原文件已保存"
                } }
                StudioAction("复制原文件", icon = DesktopAppIcons.Copy, enabled = !busy) { act {
                    opened.copyOriginal(clipboard(), clipboardFiles())
                    "原文件已复制，可粘贴为文件"
                } }
                opened.pngSize?.let { size ->
                    if (opened.canSavePngCopy) StudioAction("另存 PNG 副本 · $size", enabled = !busy) { act {
                        val target = picker.pickNewSaveFile(DesktopFileType("PNG", listOf("png")), "image-copy.png",
                            "另存 PNG 副本 · $size") ?: return@act null
                        currentCoroutineContext().ensureActive()
                        opened.savePngCopy(target)
                        "已保存 PNG 副本 · $size"
                    } }
                    StudioAction("复制位图 · $size", enabled = !busy) { act {
                        opened.copyBitmap(clipboard())
                        "已复制位图 · $size"
                    } }
                }
            }
        }
    }
}
