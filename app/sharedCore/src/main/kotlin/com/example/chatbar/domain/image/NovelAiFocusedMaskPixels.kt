package com.example.chatbar.domain.image

data class NovelAiFocusedMaskPixels(val requestSelection: BooleanArray, val blendMaskAlpha: ByteArray)

object NovelAiFocusedMaskPolicy {
    fun prepare(sourcePixels: IntArray?, plan: NovelAiFocusedInpaintPlan): NovelAiFocusedMaskPixels {
        require(sourcePixels == null || sourcePixels.size == plan.sourceWidth * plan.sourceHeight)
        val latentWidth = plan.requestSize.width / LATENT_SCALE
        val latentHeight = plan.requestSize.height / LATENT_SCALE
        val inner = plan.innerMaskBounds
        val hasPaint = sourcePixels?.let { pixels ->
            var found = false
            for (y in inner.top until inner.bottom) {
                val sourceY = plan.crop.top + y
                for (x in inner.left until inner.right) {
                    val sourceX = plan.crop.left + x
                    if (isSelected(pixels[sourceY * plan.sourceWidth + sourceX], API_THRESHOLD)) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            found
        } ?: false
        val latentSelection = BooleanArray(latentWidth * latentHeight)
        for (y in 0 until latentHeight) {
            val cropY = ((y + 0.5f) * plan.crop.height / latentHeight).toInt()
                .coerceIn(0, plan.crop.height - 1)
            for (x in 0 until latentWidth) {
                val cropX = ((x + 0.5f) * plan.crop.width / latentWidth).toInt()
                    .coerceIn(0, plan.crop.width - 1)
                if (cropX !in inner.left until inner.right || cropY !in inner.top until inner.bottom) continue
                latentSelection[y * latentWidth + x] = if (!hasPaint) {
                    true
                } else {
                    val sourceX = plan.crop.left + cropX
                    val sourceY = plan.crop.top + cropY
                    isSelected(requireNotNull(sourcePixels)[sourceY * plan.sourceWidth + sourceX], API_THRESHOLD)
                }
            }
        }
        val requestSelection = BooleanArray(plan.requestSize.width * plan.requestSize.height)
        for (y in 0 until plan.requestSize.height) {
            val latentY = (y / LATENT_SCALE).coerceAtMost(latentHeight - 1)
            for (x in 0 until plan.requestSize.width) {
                val latentX = (x / LATENT_SCALE).coerceAtMost(latentWidth - 1)
                requestSelection[y * plan.requestSize.width + x] =
                    latentSelection[latentY * latentWidth + latentX]
            }
        }
        return NovelAiFocusedMaskPixels(
            requestSelection = requestSelection,
            blendMaskAlpha = officialBlendMask(latentSelection, latentWidth, latentHeight)
        )
    }

    private fun officialBlendMask(selection: BooleanArray, width: Int, height: Int): ByteArray {
        val dilated = BooleanArray(selection.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var selected = false
                for (dy in -DILATION_RADIUS..DILATION_RADIUS) {
                    val sampleY = y + dy
                    if (sampleY !in 0 until height) continue
                    for (dx in -DILATION_RADIUS..DILATION_RADIUS) {
                        val sampleX = x + dx
                        if (sampleX in 0 until width && selection[sampleY * width + sampleX]) {
                            selected = true
                            break
                        }
                    }
                    if (selected) break
                }
                dilated[y * width + x] = selected
            }
        }
        val scaledWidth = width * LATENT_SCALE
        val scaledHeight = height * LATENT_SCALE
        var alpha = IntArray(scaledWidth * scaledHeight)
        for (y in 0 until scaledHeight) {
            val latentY = y / LATENT_SCALE
            for (x in 0 until scaledWidth) {
                alpha[y * scaledWidth + x] =
                    if (dilated[latentY * width + x / LATENT_SCALE]) 255 else 0
            }
        }
        repeat(BLUR_ITERATIONS) {
            alpha = boxBlur(alpha, scaledWidth, scaledHeight, BLUR_RADIUS)
        }
        return ByteArray(alpha.size) { index -> alpha[index].coerceIn(0, 255).toByte() }
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(source.size)
        for (y in 0 until height) {
            var sum = 0
            for (x in -radius..radius) {
                sum += source[y * width + x.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / (radius * 2 + 1)
                val removeX = (x - radius).coerceIn(0, width - 1)
                val addX = (x + radius + 1).coerceIn(0, width - 1)
                sum += source[y * width + addX] - source[y * width + removeX]
            }
        }
        val output = IntArray(source.size)
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) {
                sum += horizontal[y.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                output[y * width + x] = sum / (radius * 2 + 1)
                val removeY = (y - radius).coerceIn(0, height - 1)
                val addY = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[addY * width + x] - horizontal[removeY * width + x]
            }
        }
        return output
    }

    private fun isSelected(color: Int, threshold: Int): Boolean {
        val intensity = ((color ushr 16 and 255) + (color ushr 8 and 255) + (color and 255)) / 3
        return (color ushr 24 and 255) > threshold && intensity > threshold
    }

    private const val LATENT_SCALE = 8
    private const val API_THRESHOLD = 155
    private const val LEGACY_THRESHOLD = 128
    private const val DILATION_RADIUS = 4
    private const val BLUR_RADIUS = 20
    private const val BLUR_ITERATIONS = 2
}
