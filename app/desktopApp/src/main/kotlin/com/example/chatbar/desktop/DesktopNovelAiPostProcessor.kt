package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient

/** Platform raster/SecretStore adapter around official Enhance/Upscale request and cost authorities. */
internal class DesktopNovelAiPostProcessor(private val secrets: DesktopSecretStore,
    private val client: OkHttpClient = secureNovelAiClient()) {
    suspend fun process(bytes: ByteArray, tab: NovelAiPostProcessTab, source: NovelAiEnhanceSource?,
        options: NovelAiEnhanceOptions, account: NovelAiAccountUsage?, progress: (String) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        try {
            DesktopImageEditing.requireStatic(bytes)
            val raster = DesktopImageEditing.decode(bytes)
            val width = raster.width; val height = raster.height
            val size = if (tab == NovelAiPostProcessTab.ENHANCE) {
                val recipe = requireNotNull(source)
                require(options.scale in NovelAiPostProcessPolicy.scales(width, height, recipe.settings.model))
                require(options.strength.isFinite() && options.strength in 0f..1f && options.noise.isFinite() && options.noise in 0f..1f)
                require(NovelAiPostProcessPolicy.enhanceCost(recipe, width, height, options, account).anlas <= 140)
                NovelAiPostProcessPolicy.requestSize(width, height, options.scale)
            } else { requireNotNull(NovelAiPostProcessPolicy.upscaleCost(width, height)); null }
            val flatten = tab == NovelAiPostProcessTab.ENHANCE && source?.settings?.model == NovelAiImageModel.V4_5_FULL
            val encodedBytes = if (size == null && bytes.size > 8 && bytes[0] == 0x89.toByte()) bytes else {
                val out = java.awt.image.BufferedImage(size?.width ?: width, size?.height ?: height,
                    if (flatten) java.awt.image.BufferedImage.TYPE_INT_RGB else java.awt.image.BufferedImage.TYPE_INT_ARGB)
                out.createGraphics().let { g -> try {
                    if (flatten) { g.color = java.awt.Color.WHITE; g.fillRect(0, 0, out.width, out.height) }
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(raster, 0, 0, out.width, out.height, null)
                } finally { g.dispose() } }
                DesktopImageEditing.png(out)
            }
            currentCoroutineContext().ensureActive()
            val token = secrets.load(DesktopCredentialKey.NovelAiToken)?.takeIf { it.isNotBlank() }
                ?: throw DesktopNovelAiRequestException("请先在 NovelAI 设置中安全保存凭据")
            val encoded = Base64.getEncoder().encodeToString(encodedBytes)
            val events = if (tab == NovelAiPostProcessTab.UPSCALE) NovelAiUpscaleService(client, safeErrorsOnly = true).upscale(token, encoded)
            else {
                val recipe = requireNotNull(source)
                NovelAiImageService(client, safeErrorsOnly = true).generate(token, recipe.prompt, requireNotNull(size),
                    recipe.settings.copy(seed = kotlin.random.Random.nextLong(0, 4_294_967_296L), seedMode = NovelAiSeedMode.FIXED),
                    NovelAiPreparedImageGuidance(action = NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = encoded,
                        imageToImageStrength = options.strength, imageToImageNoise = options.noise), maxRateLimitRetries = 0,
                    enhance = NovelAiEnhanceRequestOptions(options.scale == NovelAiEnhanceScale.MAX, recipe.parameters))
            }
            var result: ByteArray? = null
            events.collect { event -> when (event) {
                is NovelAiImageEvent.Final -> { check(result == null); result = event.image }
                is NovelAiImageEvent.Error -> throw DesktopNovelAiRequestException(event.message)
                is NovelAiImageEvent.Intermediate -> progress("正在增强 · ${(event.progress * 100).toInt()}%")
            } }
            val output = requireNotNull(result)
            require(output.size in 8..DesktopImageEditing.MAX_BYTES && output[0] == 0x89.toByte() && output[1] == 0x50.toByte())
            check(!output.toString(Charsets.ISO_8859_1).contains(token))
            DesktopImageEditing.requireStatic(output)
            val decoded = DesktopImageEditing.decode(output)
            require(decoded.width.toLong() * decoded.height <= NovelAiPostProcessPolicy.MAX_PIXELS * if (tab == NovelAiPostProcessTab.UPSCALE) 4 else 1)
            val expected = if (tab == NovelAiPostProcessTab.UPSCALE) NovelAiImageSize(width * 2, height * 2, "Upscale")
                else size.takeUnless { options.scale == NovelAiEnhanceScale.MAX }
            if (expected != null) require(decoded.width == expected.width && decoded.height == expected.height)
            currentCoroutineContext().ensureActive()
            output
        } catch (cancelled: CancellationException) { throw CancellationException("图片处理已取消") }
        catch (safe: DesktopNovelAiRequestException) { throw safe }
        catch (_: Exception) { throw DesktopNovelAiRequestException("图片处理失败，请检查来源、参数和安全配置；原图保留") }
    }
}
