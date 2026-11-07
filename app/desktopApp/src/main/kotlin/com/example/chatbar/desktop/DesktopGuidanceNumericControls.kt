package com.example.chatbar.desktop

import androidx.compose.runtime.*
import com.example.chatbar.domain.image.*

/** Numeric publication retains the existing guidance transforms and stable per-Vibe identities. */
@Composable
internal fun DesktopGuidanceNumericControls(guidance: NovelAiImageGuidanceDraft,
    change: ((NovelAiImageGuidanceDraft) -> NovelAiImageGuidanceDraft) -> Unit) {
    val scope = LocalDesktopStudioFieldScope.current + ":guidance"
    CompositionLocalProvider(LocalDesktopStudioFieldScope provides scope) {
        StudioNumericField("Minimum Context 32–96", guidance.focusedInpaintMinimumContext.toString()) { text ->
            text.toIntOrNull()?.takeIf { it in 32..96 }?.let { value -> change { it.copy(focusedInpaintMinimumContext = value) } }
        }
        StudioNumericField("图生图 Strength", guidance.imageToImageStrength.toString()) { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change { it.copy(imageToImageStrength = value) } }
        }
        StudioNumericField("Noise", guidance.imageToImageNoise.toString()) { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change { it.copy(imageToImageNoise = value) } }
        }
        StudioNumericField("Inpaint Strength", guidance.inpaintStrength.toString()) { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change { it.copy(inpaintStrength = value) } }
        }
        CompactChoice("精确参考类型", NovelAiPreciseReferenceType.entries, guidance.preciseReference.type, { it.displayName }) { value ->
            change { it.copy(preciseReference = it.preciseReference.copy(type = value)) }
        }
        StudioNumericField("参考 Strength", guidance.preciseReference.strength.toString()) { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(strength = value)) } }
        }
        StudioNumericField("Fidelity", guidance.preciseReference.fidelity.toString()) { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change { it.copy(preciseReference = it.preciseReference.copy(fidelity = value)) } }
        }
        guidance.vibes.forEachIndexed { index, vibe -> key(vibe.id) {
            CompositionLocalProvider(LocalDesktopStudioFieldScope provides "$scope:vibe:${vibe.id}") {
                StatusText("Vibe ${index + 1}")
                StudioNumericField("信息提取", vibe.informationExtracted.toString()) { text ->
                    text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change {
                        it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id && v.informationExtracted != value)
                            v.copy(informationExtracted = value, encodedVibe = null) else v })
                    } }
                }
                StudioNumericField("强度", vibe.strength.toString()) { text ->
                    text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { value -> change {
                        it.copy(vibes = it.vibes.map { v -> if (v.id == vibe.id) v.copy(strength = value) else v })
                    } }
                }
                StudioAction("移除 Vibe") { change { it.copy(vibes = it.vibes.filterNot { v -> v.id == vibe.id }) } }
            }
        } }
    }
}
