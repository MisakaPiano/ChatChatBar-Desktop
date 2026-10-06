package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DesktopPreparedGuidance(
    val guidance: NovelAiPreparedImageGuidance,
    val requestSize: NovelAiImageSize,
    val retainedGuidance: NovelAiImageGuidanceDraft,
    val compose: (ByteArray) -> ByteArray = { it },
)

/** Raster adapter only. Focus, mask, resampling, reference sizes and blend policy are shared. */
internal class DesktopNovelAiGuidance(
    root: Path,
    private val resources: DesktopCharacterResourceStore,
    private val secrets: DesktopSecretStore,
) {
    private val vibes = NovelAiVibeEncodingCore(root.resolve("auxiliary/novelai/vibe-cache").toFile(),
        secureNovelAiClient(), resources::readBytes, safeErrorsOnly = true)

    fun vibeCacheMisses(draft: NovelAiStudioDraft): Int =
        if (draft.imageGuidance.effectiveReferenceMode(draft.selectedModel) != NovelAiReferenceMode.VIBE) 0
        else draft.imageGuidance.vibes.count { it.isUsable && it.encodedVibe.isNullOrBlank() &&
            it.asset?.let { asset -> !vibes.isCached(asset.sha256, draft.selectedModel, it.informationExtracted) } == true }

    suspend fun prepare(draft: NovelAiStudioDraft): DesktopPreparedGuidance = withContext(Dispatchers.IO) {
        val g = draft.imageGuidance
        g.validationError(draft.selectedModel)?.let { error(it) }
        val size = draft.activeSettings.imageSize()
        var requestSize = size
        var image: String? = null
        var mask: String? = null
        var compose: (ByteArray) -> ByteArray = { it }
        if (g.action != NovelAiGenerationAction.TEXT_TO_IMAGE) {
            val base = decode(requireNotNull(g.baseImage))
            if (g.action == NovelAiGenerationAction.IMAGE_TO_IMAGE) {
                image = encode(DesktopImageEditing.crop(base, DesktopImageTransform(), size.width, size.height))
            } else {
                val plan = NovelAiFocusedInpaintPlanner.plan(base.width, base.height,
                    requireNotNull(g.focusedInpaintRegion), g.focusedInpaintMinimumContext)
                val originalMask = g.maskImage?.let(::decode)
                require(originalMask == null || originalMask.width == base.width && originalMask.height == base.height)
                val preparedMask = NovelAiFocusedMaskPolicy.prepare(originalMask?.pixels(), plan)
                val cropped = base.getSubimage(plan.crop.left, plan.crop.top, plan.crop.width, plan.crop.height)
                image = encode(cropped.resize(plan.requestSize.width, plan.requestSize.height))
                mask = encode(raster(plan.requestSize.width, plan.requestSize.height,
                    IntArray(preparedMask.requestSelection.size) { if (preparedMask.requestSelection[it]) -1 else 0xff000000.toInt() }))
                requestSize = plan.requestSize
                compose = { generatedBytes ->
                    val generated = DesktopImageEditing.decode(generatedBytes)
                    require(generated.width == plan.requestSize.width && generated.height == plan.requestSize.height)
                    val scaled = generated.resize(plan.crop.width, plan.crop.height).pixels()
                    val alpha = raster(plan.requestSize.width, plan.requestSize.height,
                        IntArray(preparedMask.blendMaskAlpha.size) { ((preparedMask.blendMaskAlpha[it].toInt() and 255) shl 24) or 0x00ffffff })
                        .resize(plan.crop.width, plan.crop.height).pixels()
                    val output = base.pixels()
                    for (y in 0 until plan.crop.height) for (x in 0 until plan.crop.width) {
                        val src = y * plan.crop.width + x
                        val dest = (y + plan.crop.top) * base.width + x + plan.crop.left
                        output[dest] = NovelAiInpaintPixelPolicy.composeOfficialPixel(output[dest], scaled[src], alpha[src] ushr 24 and 255)
                    }
                    NovelAiInpaintPixelPolicy.preserveTextChunks(DesktopImageEditing.png(raster(base.width, base.height, output)), generatedBytes)
                }
            }
        }
        val mode = g.effectiveReferenceMode(draft.selectedModel)
        val precise = if (mode == NovelAiReferenceMode.PRECISE) {
            val source = decode(requireNotNull(g.preciseReference.asset))
            val target = NovelAiPreciseReferenceWirePolicy.targetSize(source.width, source.height)
            val ratio = minOf(target.width.toDouble() / source.width, target.height.toDouble() / source.height)
            val scaled = source.resize((source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1))
            val output = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
            output.createGraphics().let { graphics -> try {
                graphics.color = Color.BLACK; graphics.fillRect(0, 0, output.width, output.height)
                graphics.drawImage(scaled, (output.width - scaled.width) / 2, (output.height - scaled.height) / 2, null)
            } finally { graphics.dispose() } }
            encode(output)
        } else null
        val preparedVibes = if (mode == NovelAiReferenceMode.VIBE) {
            val strengths = g.effectiveVibeStrengths()
            g.vibes.filter { it.isUsable }.mapIndexed { index, vibe ->
                val encoded = vibe.encodedVibe?.takeIf { it.isNotBlank() } ?: vibes.resolve(
                    requireNotNull(secrets.load(DesktopCredentialKey.NovelAiToken)), requireNotNull(vibe.asset),
                    draft.selectedModel, vibe.informationExtracted)
                NovelAiPreparedVibeReference(encoded, vibe.informationExtracted, strengths[index])
            }
        } else emptyList()
        DesktopPreparedGuidance(NovelAiPreparedImageGuidance(g.action, image, mask,
            g.imageToImageStrength, g.imageToImageNoise, g.inpaintStrength, precise,
            g.preciseReference.type, g.preciseReference.strength, g.preciseReference.fidelity, preparedVibes), requestSize,
            if (mode == NovelAiReferenceMode.VIBE) g.copy(vibes = g.vibes.filter { it.isUsable }.mapIndexed { index, vibe ->
                vibe.copy(encodedVibe = preparedVibes[index].encoding)
            }) else g, compose)
    }

    private fun decode(asset: NovelAiStudioAssetRef) = DesktopImageEditing.decode(resources.readBytes(asset.path))
    private fun encode(image: BufferedImage) = Base64.getEncoder().encodeToString(DesktopImageEditing.png(image))
}

internal fun BufferedImage.pixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)
internal fun raster(width: Int, height: Int, pixels: IntArray): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, width, height, pixels, 0, width) }
internal fun BufferedImage.resize(width: Int, height: Int): BufferedImage =
    raster(width, height, NovelAiPixelResampler.resize(pixels(), this.width, this.height, width, height))
