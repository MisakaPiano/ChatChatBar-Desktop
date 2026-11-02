package com.example.chatbar.domain.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.card.CharacterCardTransferService
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class CommunityService(
    app: ChatBarApp,
    characterRepository: CharacterRepository,
    formatCardRepository: FormatCardRepository,
    worldBookRepository: WorldBookRepository,
    characterTransfers: CharacterCardTransferService,
    formatTransfers: FormatCardTransferService,
    worldBookTransfers: WorldBookTransferService,
    json: Json,
    client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS).build()
) : CommunityClient(
    CommunityConfiguration(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.SUPABASE_REDIRECT_URI),
    AndroidCommunityPackages(characterRepository, formatCardRepository, worldBookRepository, characterTransfers, formatTransfers, worldBookTransfers),
    AndroidCommunitySessionStore(app, json), json, client, { message, error -> Log.w("CommunityService", message, error) }
) {
    suspend fun handleAuthCallback(uri: Uri): CommunitySession = handleAuthCallback(uri.toString())
}

private class AndroidCommunitySessionStore(app: ChatBarApp, private val json: Json) : CommunitySessionStore {
    private val prefs = app.getSharedPreferences("community_session", Context.MODE_PRIVATE)
    override fun load(): CommunitySession? = prefs.getString("session", null)?.let { raw ->
        runCatching { json.decodeFromString(CommunitySession.serializer(), raw) }.getOrNull()
    }
    override fun save(session: CommunitySession) {
        prefs.edit().putString("session", json.encodeToString(CommunitySession.serializer(), session)).apply()
    }
    override fun clear() { prefs.edit().remove("session").apply() }
}

private class AndroidCommunityPackages(
    private val characterRepository: CharacterRepository,
    private val formatCardRepository: FormatCardRepository,
    private val worldBookRepository: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferService,
    private val formatTransfers: FormatCardTransferService,
    private val worldBookTransfers: WorldBookTransferService,
) : CommunityLocalPackages {
    override suspend fun candidates(type: CommunityItemType): List<CommunityUploadCandidate> = withContext(Dispatchers.IO) {
        when (type) {
            CommunityItemType.CHARACTER -> characterRepository.getAll()
                .filterNot { it.isCommunityDownload }
                .map { card ->
                    CommunityUploadCandidate(
                        id = card.id,
                        type = type,
                        title = card.name,
                        subtitle = "${card.characters.size} 人物 / ${card.customDocuments.size} 文档"
                    )
                }

            CommunityItemType.FORMAT -> formatCardRepository.getAll().map { card ->
                CommunityUploadCandidate(
                    id = card.id,
                    type = type,
                    title = card.name,
                    subtitle = if (card.isDefault) "默认格式卡" else ""
                )
            }

            CommunityItemType.WORLD_BOOK -> worldBookRepository.getAll().map { book ->
                CommunityUploadCandidate(
                    id = book.id,
                    type = type,
                    title = book.name,
                    subtitle = "${book.entries.size} 条目"
                )
            }
        }
    }

    override suspend fun export(type: CommunityItemType, localId: String): CommunityLocalPackage = withContext(Dispatchers.IO) {
        val sourceName: String
        val packageText: String
        val preview: CommunityPreviewDraft?
        when (type) {
            CommunityItemType.CHARACTER -> {
                val card = characterRepository.getById(localId) ?: error("角色卡不存在")
                require(!card.isCommunityDownload) { "下载角色卡不能上传，请先复制为本地角色卡" }
                sourceName = card.name
                packageText = characterTransfers.exportJson(localId)
                preview = card.avatar?.let(::previewFromPath)
            }

            CommunityItemType.FORMAT -> {
                val card = formatCardRepository.getById(localId) ?: error("格式卡不存在")
                sourceName = card.name
                packageText = formatTransfers.exportJson(localId)
                preview = null
            }

            CommunityItemType.WORLD_BOOK -> {
                val book = worldBookRepository.getById(localId) ?: error("世界书不存在")
                sourceName = book.name
                packageText = worldBookTransfers.exportJson(localId)
                preview = null
            }
        }
        CommunityLocalPackage(sourceName, packageText, preview)
    }

    private fun previewFromPath(path: String): CommunityPreviewDraft? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_PREVIEW_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = previewSampleSize(width, height)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        val preview = scalePreview(decoded)
        if (preview !== decoded) decoded.recycle()
        val bytes = compressPreview(preview)
        preview.recycle()
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_UPLOAD_BYTES) return null
        return CommunityPreviewDraft(
            fileName = "preview.jpg",
            bytes = bytes,
            contentType = "image/jpeg"
        )
    }

    private fun previewSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > PREVIEW_MAX_DIMENSION * 2 || height / sample > PREVIEW_MAX_DIMENSION * 2) {
            sample *= 2
        }
        return sample
    }

    private fun scalePreview(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= PREVIEW_MAX_DIMENSION) return bitmap
        val scale = PREVIEW_MAX_DIMENSION.toFloat() / maxSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun compressPreview(bitmap: Bitmap): ByteArray {
        var quality = PREVIEW_JPEG_QUALITY
        while (quality >= PREVIEW_MIN_JPEG_QUALITY) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_PREVIEW_UPLOAD_BYTES || quality == PREVIEW_MIN_JPEG_QUALITY) {
                    return bytes
                }
            }
            quality -= 10
        }
        return ByteArray(0)
    }

    companion object {
        private const val MAX_PREVIEW_SOURCE_BYTES = 20L * 1024L * 1024L
        private const val MAX_PREVIEW_UPLOAD_BYTES = 80 * 1024
        private const val PREVIEW_MAX_DIMENSION = 160
        private const val PREVIEW_JPEG_QUALITY = 56
        private const val PREVIEW_MIN_JPEG_QUALITY = 40
    }
}
