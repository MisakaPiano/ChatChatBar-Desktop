package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*

internal data class DesktopGuidanceImageBinding(
    val key: String, val title: String, val asset: NovelAiStudioAssetRef?, val target: NovelAiImageUseTarget,
    val active: Boolean, val vibeId: String? = null, val encodedOnly: Boolean = false,
)

internal fun desktopGuidanceImageBindings(guidance: NovelAiImageGuidanceDraft, model: NovelAiImageModel): List<DesktopGuidanceImageBinding> = buildList {
    guidance.baseImage?.let { add(DesktopGuidanceImageBinding("base", "基图", it,
        if (guidance.action == NovelAiGenerationAction.INPAINT) NovelAiImageUseTarget.INPAINT else NovelAiImageUseTarget.IMAGE_TO_IMAGE,
        guidance.action != NovelAiGenerationAction.TEXT_TO_IMAGE)) }
    guidance.preciseReference.asset?.let { add(DesktopGuidanceImageBinding("precise", "精确参考", it,
        NovelAiImageUseTarget.PRECISE_REFERENCE, guidance.effectiveReferenceMode(model) == NovelAiReferenceMode.PRECISE)) }
    guidance.vibes.forEachIndexed { index, vibe ->
        add(DesktopGuidanceImageBinding("vibe:${vibe.id}", "氛围参考 ${index + 1}", vibe.asset,
            NovelAiImageUseTarget.VIBE_REFERENCE, guidance.effectiveReferenceMode(model) == NovelAiReferenceMode.VIBE, vibe.id,
            !vibe.encodedVibe.isNullOrBlank()))
    }
}

/** Remove only the captured slot/source. Other uses of the same physical image remain independent. */
internal fun desktopRemoveGuidanceImage(guidance: NovelAiImageGuidanceDraft, binding: DesktopGuidanceImageBinding): NovelAiImageGuidanceDraft = when (binding.key) {
    "base" -> {
        check(guidance.baseImage == binding.asset)
        guidance.copy(baseImage = null, maskImage = null, focusedInpaintRegion = null, action = NovelAiGenerationAction.TEXT_TO_IMAGE)
    }
    "precise" -> {
        check(guidance.preciseReference.asset == binding.asset)
        guidance.copy(preciseReference = guidance.preciseReference.copy(asset = null),
            referenceMode = if (guidance.referenceMode == NovelAiReferenceMode.PRECISE) NovelAiReferenceMode.NONE else guidance.referenceMode)
    }
    else -> {
        val current = guidance.vibes.single { it.id == binding.vibeId }
        check(current.asset == binding.asset)
        val retained = guidance.vibes.filterNot { it.id == current.id }
        guidance.copy(vibes = retained, referenceMode = if (retained.isEmpty() && guidance.referenceMode == NovelAiReferenceMode.VIBE)
            NovelAiReferenceMode.NONE else guidance.referenceMode)
    }
}

@Composable
internal fun DesktopStudioGuidanceImages(guidance: NovelAiImageGuidanceDraft, model: NovelAiImageModel,
    read: (String) -> ByteArray, onPreview: (NovelAiStudioAssetRef) -> Unit,
    onReplace: (DesktopGuidanceImageBinding) -> Unit, onRemove: (DesktopGuidanceImageBinding) -> Unit,
    onSettings: () -> Unit) {
    val bindings = desktopGuidanceImageBindings(guidance, model)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StudioActions {
            val reference = when (guidance.effectiveReferenceMode(model)) {
                NovelAiReferenceMode.PRECISE -> "精确参考"
                NovelAiReferenceMode.VIBE -> "氛围参考"
                NovelAiReferenceMode.NONE -> if (guidance.referenceMode == NovelAiReferenceMode.NONE) "无参考模式" else "参考暂停（当前模型）"
            }
            StatusText("图像引导 · ${guidance.action.displayName} · $reference · ${bindings.size} 项")
            DesktopChatIconAction("图像引导设置", DesktopAppIcons.Settings, targetDp = 28, onClick = onSettings)
        }
        if (bindings.isEmpty()) StatusText("未选择参考图片", DesktopBootstrapColors.mutedForeground)
        bindings.forEach { binding -> key(binding.key) {
            Row(Modifier.fillMaxWidth().background(DesktopBootstrapColors.muted).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val asset = binding.asset
                Box(Modifier.size(44.dp).semantics { contentDescription = "${binding.title}缩略图" }, contentAlignment = Alignment.Center) {
                    if (asset?.isUsable == true) DesktopOwnedImage(asset.path, read, Modifier.fillMaxSize(),
                        displayTargetForSource = { IntSize(96, 96) })
                    else StatusText("参考")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusText("${binding.title} · ${if (binding.active) "当前启用" else "已保留，未使用"}")
                    StatusText(asset?.let { "${it.width} × ${it.height}" }
                        ?: if (binding.encodedOnly) "已编码参考，无原始图片" else "参考图片未就绪", DesktopBootstrapColors.mutedForeground)
                }
                DesktopChatIconAction("查看${binding.title}", DesktopAppIcons.ExpandComposer, enabled = asset?.isUsable == true, targetDp = 28) {
                    asset?.let(onPreview)
                }
                DesktopChatIconAction("更换${binding.title}", DesktopAppIcons.ImageAdd, targetDp = 28) { onReplace(binding) }
                DesktopChatIconAction("移除${binding.title}", DesktopAppIcons.Close, targetDp = 28) { onRemove(binding) }
            }
        } }
    }
}
